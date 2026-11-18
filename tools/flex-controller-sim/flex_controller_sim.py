"""
Aegis Flex controller simulator: a stand-in for a partner's controller, e.g. Outdoor Vend's InHand
EC3320, so a terminal can be tested end to end without the machine. Python 3, standard library only.

    python flex_controller_sim.py [--port 8080] [--token T] [--v1] [--auto] [--delay 3] [--pickup 60]

Then on the terminal's technician panel: 设置控制器地址 = <this PC's IP>:<port>.

Default: API v1.1 (docs/partner/flex_controller_api_v1.1.md). Each dispense is accepted at once and
goes ACCEPTED -> OPENING (2 s) -> OPEN (--pickup seconds, the terminal counts down) -> CLOSING ->
COMPLETED. You play the customer in a browser at http://localhost:<port>/: "took N" ends the pickup
early with N taken; let the window run out and the pickup ends with TIMEOUT, then pick how many were
taken. FAIL = the shutter never opened (nothing taken, the money goes back). --auto takes everything
after --delay seconds by itself.

--v1: the old single answer (docs/partner/flex_controller_api.md): the POST is held until you click
DISPENSED / FAILED.
"""
import argparse
import html
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

state = {"ready": True, "sold_out": [], "stock": 40}  # stock: bags left, the same for every slot
orders = {}  # order_id -> {"req", "at", ...}; also the idempotency record
lock = threading.Lock()

OPENING_S = 2.0
CLOSING_S = 3.0

PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<meta http-equiv="refresh" content="1"><title>EC3320 (simulated)</title>
<style>body{{font:16px system-ui;background:#111;color:#eee;margin:20px}} h1{{color:#ffb800;font-size:20px}}
.card{{background:#1d1d21;border-radius:10px;padding:12px;margin:10px 0}} .wait{{border:2px solid #ffb800}}
button{{font:bold 16px system-ui;padding:10px 18px;border:0;border-radius:8px;margin:4px 8px 0 0;cursor:pointer}}
.ok{{background:#10b981;color:#000}} .bad{{background:#ef4444;color:#fff}} .gray{{background:#444;color:#fff}}
.muted{{color:#94a3b8;font-size:13px}} .st{{color:#ffb800;font-weight:bold}}</style></head><body>
<h1>EC3320 controller (simulated) &middot; port {port} &middot; API {api}</h1>
<form method="post" action="/console/ready"><b>Status:</b> {ready}
<button class="gray">switch to {other}</button></form>
<form method="post" action="/console/sold_out" style="margin-top:8px"><b>Sold out slots:</b>
<input name="slots" value="{sold_out}" placeholder="e.g. 3 or 1,3" style="font:16px system-ui;width:120px">
<button class="gray">set</button> <span class="muted">empty = all in stock</span></form>
<form method="post" action="/console/stock" style="margin-top:8px"><b>Bags in stock:</b>
<input name="stock" value="{stock}" style="font:16px system-ui;width:80px"> <button class="gray">set</button></form>
<h3>Under way</h3>{waiting}
<h3>Done</h3>{done}
<p class="muted">Terminal setting: this PC's IP:{port}. {mode}</p></body></html>"""


def now_ms():
    return int(time.time() * 1000)


def view(o):
    """The order as GET /flex/v1/orders/{id} answers it (v1.1)."""
    r = o["req"]
    out = {"order_id": r["order_id"], "state": o["state"], "quantity": r.get("quantity")}
    if o["state"] == "OPEN":
        out["pickup_remaining_ms"] = max(0, o["pickup_ends"] - now_ms())
    if o.get("pickup_end"):
        out["pickup_end"] = o["pickup_end"]
    if o["state"] == "COMPLETED":
        out.update(taken_quantity=o["taken"], start_weight_g=o["w0"], end_weight_g=o["w0"] - 8000 * o["taken"])
    if o.get("reason"):
        out["reason"] = o["reason"]
    return out


def advance(oid):
    """Moves one v1.1 order along by the clock (called under the lock)."""
    o = orders[oid]
    r = o["req"]
    t = now_ms()
    if o["state"] in ("COMPLETED", "FAILED"):
        return
    if t > r.get("complete_by_ms", 0):
        # A real controller would still report what was taken; the terminal has given up by now.
        o.update(state="FAILED", reason="complete_by_ms passed")
        return
    # Each stage at a fixed time after the order came in, however seldom it is asked about.
    opening_at = o["at_ms"] + 500
    open_at = opening_at + int(OPENING_S * 1000)
    if o["state"] == "ACCEPTED" and t >= opening_at:
        if opening_at > r.get("deadline_ms", 0):
            o.update(state="FAILED", reason="deadline passed before opening")
            return
        o["state"] = "OPENING"
    if o["state"] == "OPENING" and t >= open_at:
        o.update(state="OPEN", pickup_ends=open_at + ARGS.pickup * 1000)
    if o["state"] == "OPEN":
        if ARGS.auto and t >= open_at + ARGS.delay * 1000:
            o["taken"] = r.get("quantity", 1)
            o.update(state="CLOSING", pickup_end="TAKEN", closing_at=open_at + int(ARGS.delay * 1000))
        elif t >= o["pickup_ends"]:
            o.update(state="CLOSING", pickup_end="TIMEOUT", closing_at=o["pickup_ends"])
    if o["state"] == "CLOSING" and o.get("taken") is not None and t >= o["closing_at"] + CLOSING_S * 1000:
        o["state"] = "COMPLETED"
        state["stock"] = max(0, state["stock"] - o["taken"])
        print(f"   {oid}: COMPLETED, {o['taken']} of {r.get('quantity')} taken ({o.get('pickup_end')})")


def render():
    with lock:
        for oid in list(orders):
            if not ARGS.v1:
                advance(oid)
        items = sorted(orders.items(), key=lambda kv: -kv[1]["at_ms"])
    waiting, done = [], []
    for oid, o in items:
        r = o["req"]
        qty = int(r.get("quantity") or 1)
        what = (f"<b>{html.escape(str(qty))} &times; {html.escape(str(r.get('name')))}</b> &middot; "
                f"{r.get('amount_cents', 0) / 100:.2f} {html.escape(str(r.get('currency')))} &middot; "
                f"{html.escape(str(r.get('payment_method')))} {html.escape(str(r.get('payment_flow', '')))}"
                f"<br><span class='muted'>{html.escape(oid)} &middot; terminal {html.escape(str(r.get('terminal_sn')))}"
                f"{' &middot; charge by quantity ' + html.escape(json.dumps(r['charge_by_quantity'])) if r.get('charge_by_quantity') else ''}</span>")
        hidden = f"<input type='hidden' name='order_id' value='{html.escape(oid)}'>"
        if ARGS.v1:
            if o.get("answer") is None:
                left = int(r.get("deadline_ms", 0) / 1000 - time.time())
                waiting.append(
                    f"<div class='card wait'>{what}<br><span class='muted'>{max(left, 0)} s left</span>"
                    f"<form method='post' action='/console/answer'>{hidden}"
                    f"<button class='ok' name='result' value='DISPENSED'>DISPENSED</button>"
                    f"<button class='bad' name='result' value='FAILED'>FAILED</button></form></div>")
            else:
                res = o["answer"]["result"]
                done.append(f"<div class='card'>{what}<br><b style='color:{'#10b981' if res == 'DISPENSED' else '#ef4444'}'>{res}</b></div>")
            continue
        st = o["state"]
        if st in ("COMPLETED", "FAILED"):
            res = (f"COMPLETED &middot; took {o['taken']} of {qty} ({o.get('pickup_end')})" if st == "COMPLETED"
                   else f"FAILED &middot; {html.escape(o.get('reason', ''))}")
            done.append(f"<div class='card'>{what}<br><b style='color:{'#10b981' if st == 'COMPLETED' else '#ef4444'}'>{res}</b></div>")
            continue
        info = st
        buttons = ""
        if st in ("ACCEPTED", "OPENING"):
            buttons = f"<button class='bad' name='fail' value='1'>FAIL (shutter jammed)</button>"
        if st == "OPEN":
            info += f" &middot; {max(0, o['pickup_ends'] - now_ms()) // 1000} s left of the pickup window"
        if st == "OPEN" or (st == "CLOSING" and o.get("taken") is None):
            if st == "CLOSING":
                info += f" ({o.get('pickup_end')}) &middot; how many were taken?"
            buttons = "".join(f"<button class='{'ok' if n == qty else 'gray'}' name='taken' value='{n}'>took {n}</button>"
                              for n in range(0, qty + 2))
        elif st == "CLOSING":
            info += f" ({o.get('pickup_end')}) &middot; weighing&hellip;"
        waiting.append(f"<div class='card wait'>{what}<br><span class='st'>{info}</span>"
                       f"<form method='post' action='/console/act'>{hidden}{buttons}</form></div>")
    return PAGE.format(
        port=ARGS.port, api="1" if ARGS.v1 else "1.1",
        ready="<b style='color:#10b981'>READY</b>" if state["ready"] else "<b style='color:#ef4444'>NOT READY</b>",
        other="NOT READY" if state["ready"] else "READY", sold_out=html.escape(",".join(state["sold_out"])),
        stock=state["stock"],
        waiting="".join(waiting) or "<p class='muted'>none</p>", done="".join(done[:20]) or "<p class='muted'>none</p>",
        mode=(f"Auto: takes everything after {ARGS.delay}s." if ARGS.auto else "Manual: you play the customer.")
        + ("" if ARGS.v1 else f" Pickup window {ARGS.pickup}s."),
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
        if not path.startswith("/flex/v1/"):
            return self._send(404, {"error": "not found"})
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        if path == "/flex/v1/status":
            body = {"ready": state["ready"], "message": "simulator" if state["ready"] else "simulator: not ready",
                    "sold_out": state["sold_out"]}
            if not ARGS.v1:
                body.update(api_version="1.1", stock={s: state["stock"] for s in ("1", "2", "3")})
            return self._send(200, body)
        if path.startswith("/flex/v1/orders/") and not ARGS.v1:
            oid = path[len("/flex/v1/orders/"):]
            with lock:
                if oid not in orders:
                    return self._send(404, {"error": "unknown order"})
                advance(oid)
                return self._send(200, view(orders[oid]))
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)) or 0)
        form = {k: v[0] for k, v in parse_qs(body.decode("utf-8", "replace")).items()} if path.startswith("/console/") else {}
        if path == "/console/ready":
            state["ready"] = not state["ready"]
            print("status:", "READY" if state["ready"] else "NOT READY")
            return self._redirect()
        if path == "/console/sold_out":
            state["sold_out"] = [x.strip() for x in form.get("slots", "").split(",") if x.strip()]
            print("sold out:", state["sold_out"] or "none")
            return self._redirect()
        if path == "/console/stock":
            state["stock"] = max(0, int(form.get("stock", "0") or 0))
            return self._redirect()
        if path == "/console/answer":  # v1
            with lock:
                o = orders.get(form.get("order_id"))
                if o and o.get("answer") is None:
                    res = form.get("result")
                    o["answer"] = {"order_id": form["order_id"], "result": res,
                                   **({} if res == "DISPENSED" else {"reason": "failed (simulated)"})}
                    o["event"].set()
            return self._redirect()
        if path == "/console/act":  # v1.1
            with lock:
                o = orders.get(form.get("order_id"))
                if o:
                    advance(form["order_id"])
                if o and o["state"] in ("ACCEPTED", "OPENING") and form.get("fail"):
                    o.update(state="FAILED", reason="shutter did not open (simulated)")
                elif o and form.get("taken") is not None and (
                        o["state"] == "OPEN" or (o["state"] == "CLOSING" and o.get("taken") is None)):
                    o["taken"] = int(form["taken"])
                    if o["state"] == "OPEN":
                        o.update(state="CLOSING", pickup_end="TAKEN", closing_at=now_ms())
                    else:
                        o["closing_at"] = now_ms()
            return self._redirect()
        if path != "/flex/v1/dispense":
            return self._send(404, {"error": "not found"})
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        req = json.loads(body or b"{}")
        oid = req.get("order_id")
        print(f"\n>> DISPENSE {oid}: {req.get('quantity')} x {req.get('name')} "
              f"({req.get('amount_cents')} {req.get('currency')}, {req.get('payment_method')} {req.get('payment_flow')})")
        if ARGS.v1:
            return self._dispense_v1(req, oid)
        with lock:
            o = orders.get(oid)
            new = o is None
            if new:
                o = orders[oid] = {"req": req, "at_ms": now_ms(), "state": "ACCEPTED", "w0": 500000, "taken": None}
                if req.get("deadline_ms", 0) < now_ms():
                    o.update(state="FAILED", reason="deadline passed")
            else:
                print("   already known: its current state again (idempotent)")
                advance(oid)
            answer = view(o)
        # 202 = taken on now; an order seen before (or refused) gets its state with 200.
        self._send(202 if new and answer["state"] != "FAILED" else 200, answer)

    def _dispense_v1(self, req, oid):
        with lock:
            o = orders.get(oid)
            if o is not None and o.get("answer") is not None:
                return self._send(200, o["answer"])
            if o is None:
                o = orders[oid] = {"req": req, "answer": None, "event": threading.Event(), "at_ms": now_ms()}
        deadline = req.get("deadline_ms", 0) / 1000
        if deadline < time.time():
            o["answer"] = {"order_id": oid, "result": "FAILED", "reason": "deadline passed"}
        elif ARGS.auto:
            time.sleep(ARGS.delay)
            o["answer"] = {"order_id": oid, "result": "DISPENSED"}
        elif not o["event"].wait(max(0.0, deadline - time.time())):
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
    ap.add_argument("--v1", action="store_true", help="the old single-answer API")
    ap.add_argument("--auto", action="store_true", help="the customer takes everything by itself")
    ap.add_argument("--delay", type=float, default=3.0, help="--auto: seconds the customer takes")
    ap.add_argument("--pickup", type=int, default=60, help="v1.1: pickup window in seconds")
    ARGS = ap.parse_args()
    print(f"Aegis Flex controller simulator (API {'1' if ARGS.v1 else '1.1'}) on port {ARGS.port}; "
          f"console: http://localhost:{ARGS.port}/")
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), Handler).serve_forever()
