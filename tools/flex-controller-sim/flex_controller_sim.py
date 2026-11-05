"""
Aegis Flex controller simulator (docs/flex_controller_api.md): a stand-in for a partner's
controller, e.g. Outdoor Vend's InHand EC3320, so a terminal can be tested end to end without the
machine. Python 3, standard library only.

    python flex_controller_sim.py [--port 8080] [--token T] [--delay 3] [--fail] [--not-ready]

Then on the terminal's technician panel: 设置控制器地址 = <this PC's IP>:<port>.
While it runs, type in its window:
    f + Enter  next dispense FAILS (the terminal voids the payment)
    s + Enter  next dispense never answers (the terminal times out and voids)
    r + Enter  toggle ready / not ready (the terminal stops / resumes selling)
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"ready": True, "fail_next": False, "silent_next": False}
outcomes = {}  # order_id -> answer, for idempotency
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _auth_ok(self):
        if not ARGS.token:
            return True
        return self.headers.get("Authorization") == f"Bearer {ARGS.token}"

    def _send(self, code, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        if self.path != "/flex/v1/status":
            return self._send(404, {"error": "not found"})
        self._send(200, {"ready": state["ready"], "message": "simulator" if state["ready"] else "simulator: not ready"})

    def do_POST(self):
        if not self._auth_ok():
            return self._send(401, {"error": "unauthorized"})
        if self.path != "/flex/v1/dispense":
            return self._send(404, {"error": "not found"})
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        oid = req.get("order_id")
        print(f"\n>> DISPENSE {oid}: {req.get('quantity')} x {req.get('name')} "
              f"({req.get('amount_cents')} {req.get('currency')}, {req.get('payment_method')})")
        with lock:
            if oid in outcomes:
                print("   already handled: same answer again (idempotent)")
                return self._send(200, outcomes[oid])
            fail, silent = state["fail_next"], state["silent_next"]
            state["fail_next"] = state["silent_next"] = False
        if silent:
            print("   staying silent: the terminal will time out and void")
            time.sleep(max(0, req.get("deadline_ms", 0) / 1000 - time.time()) + 15)
            return
        if req.get("deadline_ms", 0) / 1000 < time.time():
            answer = {"order_id": oid, "result": "FAILED", "reason": "deadline passed"}
        elif fail or ARGS.fail:
            time.sleep(1)
            answer = {"order_id": oid, "result": "FAILED", "reason": "simulated failure"}
        else:
            print(f"   opening the door, customer takes it ({ARGS.delay}s)…")
            time.sleep(ARGS.delay)
            answer = {"order_id": oid, "result": "DISPENSED"}
        with lock:
            outcomes[oid] = answer
        print(f"   -> {answer['result']}")
        self._send(200, answer)

    def log_message(self, fmt, *args):
        pass


def keys():
    while True:
        k = input().strip().lower()
        if k == "f":
            state["fail_next"] = True
            print("next dispense will FAIL")
        elif k == "s":
            state["silent_next"] = True
            print("next dispense will get NO answer")
        elif k == "r":
            state["ready"] = not state["ready"]
            print("ready" if state["ready"] else "NOT ready")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--token", default="")
    ap.add_argument("--delay", type=float, default=3.0, help="seconds the hand-over takes")
    ap.add_argument("--fail", action="store_true", help="every dispense fails")
    ap.add_argument("--not-ready", action="store_true")
    ARGS = ap.parse_args()
    state["ready"] = not ARGS.not_ready
    threading.Thread(target=keys, daemon=True).start()
    print(f"Aegis Flex controller simulator on port {ARGS.port} (f = fail next, s = silent next, r = ready toggle)")
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), Handler).serve_forever()
