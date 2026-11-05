"""
Aegis Flex controller simulator (docs/flex_controller_api.md): a stand-in for a partner's
controller, e.g. Outdoor Vend's InHand EC3320, so a terminal can be tested end to end without the
machine. Python 3, standard library only.

    python flex_controller_sim.py [--port 8080] [--token T] [--auto] [--delay 3]

Then on the terminal's technician panel: 设置控制器地址 = <this PC's IP>:<port>.

You play the controller from a browser: open http://localhost:<port>/ -- each dispense request
from the terminal waits there for DISPENSED or FAILED (or for nothing: the terminal times out and
voids), and READY / NOT READY switches what /flex/v1/status says. --auto answers DISPENSED by itself
after --delay seconds instead (the console still shows everything).
"""
import argparse
import html
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

state = {"ready": True}
orders = {}  # order_id -> {"req", "answer", "event", "at"}; also the idempotency record
lock = threading.Lock()

PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<meta http-equiv="refresh" content="1"><title>EC3320 (simulated)</title>
<style>body{{font:16px system-ui;background:#111;color:#eee;margin:20px}} h1{{color:#ffb800;font-size:20px}}
.card{{background:#1d1d21;border-radius:10px;padding:12px;margin:10px 0}} .wait{{border:2px solid #ffb800}}
button{{font:bold 16px system-ui;padding:10px 18px;border:0;border-radius:8px;margin-right:8px;cursor:pointer}}
.ok{{background:#10b981;color:#000}} .bad{{background:#ef4444;color:#fff}} .gray{{background:#444;color:#fff}}
.muted{{color:#94a3b8;font-size:13px}}</style></head><body>
<h1>EC3320 controller (simulated) &middot; port {port}</h1>
<form method="post" action="/console/ready"><b>Status:</b> {ready}
<button class="gray">switch to {other}</button></form>
<h3>Waiting for you</h3>{waiting}
<h3>Done</h3>{done}
<p class="muted">Terminal setting: this PC's IP:{port}. {mode}</p></body></html>"""


def render():
    with lock:
        items = sorted(orders.items(), key=lambda kv: -kv[1]["at"])
    waiting, done = [], []
    for oid, o in items:
        r = o["req"]
        what = (f"<b>{html.escape(str(r.get('quantity')))} &times; {html.escape(str(r.get('name')))}</b> &middot; "
                f"{r.get('amount_cents', 0) / 100:.2f} {html.escape(str(r.get('currency')))} &middot; "
                f"{html.escape(str(r.get('payment_method')))}<br><span class='muted'>{html.escape(oid)} &middot; "
                f"terminal {html.escape(str(r.get('terminal_sn')))}</span>")
        if o["answer"] is None:
            left = int(r.get("deadline_ms", 0) / 1000 - time.time())
            waiting.append(
                f"<div class='card wait'>{what}<br><span class='muted'>{max(left, 0)} s left before the terminal gives the money back</span>"
                f"<form method='post' action='/console/answer' style='margin-top:8px'>"
                f"<input type='hidden' name='order_id' value='{html.escape(oid)}'>"
                f"<button class='ok' name='result' value='DISPENSED'>DISPENSED</button>"
                f"<button class='bad' name='result' value='FAILED'>FAILED</button></form></div>")
        else:
            res = o["answer"]["result"]
            done.append(f"<div class='card'>{what}<br><b style='color:{'#10b981' if res == 'DISPENSED' else '#ef4444'}'>{res}</b>"
                        f" <span class='muted'>{html.escape(o['answer'].get('reason', ''))}</span></div>")
    return PAGE.format(
        port=ARGS.port, ready="<b style='color:#10b981'>READY</b>" if state["ready"] else "<b style='color:#ef4444'>NOT READY</b>",
        other="NOT READY" if state["ready"] else "READY",
        waiting="".join(waiting) or "<p class='muted'>none</p>", done="".join(done[:20]) or "<p class='muted'>none</p>",
        mode=f"Auto: answers DISPENSED after {ARGS.delay}s." if ARGS.auto else "Manual: you answer each order.",
    )


class Handler(BaseHTTPRequestHandler):
    def _auth_ok(self):
        return not ARGS.token or self.headers.get("Authorization") == f"Bearer {ARGS.token}"

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _redirect(self):
        self.send_response(303)
        self.send_header("Location", "/")
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            return self._send(200, render(), "text/html; charset=utf-8")
        if path != "/flex/v1/status":
            return self._send(404, {"error": "not found"})
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        self._send(200, {"ready": state["ready"], "message": "simulator" if state["ready"] else "simulator: not ready"})

    def do_POST(self):
        path = urlparse(self.path).path
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)) or 0)
        if path == "/console/ready":
            state["ready"] = not state["ready"]
            print("status:", "READY" if state["ready"] else "NOT READY")
            return self._redirect()
        if path == "/console/answer":
            form = {k: v[0] for k, v in parse_qs(body.decode("utf-8")).items()}
            with lock:
                o = orders.get(form.get("order_id"))
                if o and o["answer"] is None:
                    res = form.get("result")
                    o["answer"] = {"order_id": form["order_id"], "result": res,
                                   **({} if res == "DISPENSED" else {"reason": "failed (simulated)"})}
                    o["event"].set()
            return self._redirect()
        if path != "/flex/v1/dispense":
            return self._send(404, {"error": "not found"})
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        req = json.loads(body or b"{}")
        oid = req.get("order_id")
        print(f"\n>> DISPENSE {oid}: {req.get('quantity')} x {req.get('name')} "
              f"({req.get('amount_cents')} {req.get('currency')}, {req.get('payment_method')})")
        with lock:
            o = orders.get(oid)
            if o is not None and o["answer"] is not None:
                print("   already handled: same answer again (idempotent)")
                return self._send(200, o["answer"])
            if o is None:
                o = orders[oid] = {"req": req, "answer": None, "event": threading.Event(), "at": time.time()}
        deadline = req.get("deadline_ms", 0) / 1000
        if deadline < time.time():
            o["answer"] = {"order_id": oid, "result": "FAILED", "reason": "deadline passed"}
        elif ARGS.auto:
            time.sleep(ARGS.delay)
            o["answer"] = {"order_id": oid, "result": "DISPENSED"}
        elif not o["event"].wait(max(0.0, deadline - time.time())):
            # Nobody answered: a hand-over must not start after the deadline.
            with lock:
                o["answer"] = {"order_id": oid, "result": "FAILED", "reason": "not answered by the deadline"}
        print(f"   -> {o['answer']['result']}")
        self._send(200, o["answer"])

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--token", default="")
    ap.add_argument("--auto", action="store_true", help="answer DISPENSED by itself")
    ap.add_argument("--delay", type=float, default=3.0, help="--auto: seconds the hand-over takes")
    ARGS = ap.parse_args()
    print(f"Aegis Flex controller simulator on port {ARGS.port}; console: http://localhost:{ARGS.port}/")
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), Handler).serve_forever()
