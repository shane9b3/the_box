#!/usr/bin/env python3
"""TCP fault proxy for the SMB save-location tests (design doc 16.3). Pure stdlib.

    python3 tools/smbproxy/smbproxy.py --listen 4446 --upstream 127.0.0.1:4445 --control 4447

Emulator -> 10.0.2.2:4446 -> this proxy -> 127.0.0.1:4445 (Samba). Faults are switched at runtime over HTTP
on 127.0.0.1:<control> (GET or POST /control, GET /status). See README.md. Payload bytes are never logged.
"""
import argparse
import json
import queue
import random
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHUNK = 16384
MODES = ("pass", "delay", "throttle", "stall", "blackhole", "drop", "refuse", "sequence")
# Per-mode short aliases (so `mode=delay&ms=200` works next to the canonical names).
ALIASES = {"delay": {"ms": "delay_ms", "jitter": "jitter_ms"}, "throttle": {"bps": "rate_bps"},
           "stall": {"ms": "stall_ms"}}
INT_FIELDS = ("delay_ms", "jitter_ms", "rate_bps", "stall_ms", "close_after_bytes", "delay_first_ms")
BOOL_FIELDS = ("blackhole", "refuse", "drop")


def _bool(v):
    return v if isinstance(v, bool) else str(v).lower() in ("1", "true", "yes", "on")


def _rst(sock):
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    except OSError:
        pass


class Conn:
    def __init__(self, cid, client, upstream):
        self.id, self.client, self.upstream = cid, client, upstream
        self.closed = threading.Event()
        self.bytes = 0
        self.writers_left = 2
        self.lock = threading.Lock()

    def close(self, rst=False):
        if self.closed.is_set():
            return
        self.closed.set()
        for s in (self.client, self.upstream):
            if s is None:
                continue
            if rst:
                _rst(s)
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


class Proxy:
    def __init__(self, upstream, listen=("127.0.0.1", 0), control=("127.0.0.1", 0), log=None):
        self.upstream = upstream
        self.log = log or (lambda m: print(time.strftime("%H:%M:%S ") + m, file=sys.stderr, flush=True))
        self.state_lock = threading.Lock()
        self.conns = {}
        self.next_id = 1
        self.total_conns = 0
        self.total_bytes = 0
        self.mode = "pass"
        self.s = self._defaults()
        self.seq_cancel = threading.Event()
        self.stopping = threading.Event()
        self.lsock = socket.socket()
        self.lsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.lsock.bind(listen)
        self.lsock.listen(256)
        self.listen_port = self.lsock.getsockname()[1]
        proxy = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _go(self):
                u = urlparse(self.path)
                params = {k: v[-1] for k, v in parse_qs(u.query).items()}
                n = int(self.headers.get("Content-Length") or 0)
                if n:
                    try:
                        body = json.loads(self.rfile.read(n))
                        if not isinstance(body, dict):
                            raise ValueError
                        params.update(body)
                    except ValueError:
                        return self._send(400, {"error": "body must be a JSON object"})
                if u.path == "/status":
                    return self._send(200, proxy.status())
                if u.path == "/control":
                    try:
                        proxy.control(params)
                    except (ValueError, TypeError) as e:
                        return self._send(400, {"error": str(e)})
                    return self._send(200, proxy.status())
                self._send(404, {"error": "use /control or /status"})

            do_GET = do_POST = _go

            def _send(self, code, obj):
                b = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

        self.httpd = ThreadingHTTPServer(control, H)
        self.httpd.daemon_threads = True
        self.control_port = self.httpd.server_address[1]

    @staticmethod
    def _defaults():
        return {"delay_ms": 0, "jitter_ms": 0, "rate_bps": 0, "stall_until": 0.0, "blackhole": False,
                "refuse": False, "close_after_bytes": 0, "delay_first_ms": 0}

    # ---- lifecycle
    def start(self):
        threading.Thread(target=self._accept_loop, daemon=True).start()
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.log(f"listening {self.listen_port} -> {self.upstream[0]}:{self.upstream[1]}; control {self.control_port}")

    def stop(self):
        self.stopping.set()
        self.seq_cancel.set()
        self.httpd.shutdown()
        self.httpd.server_close()
        try:
            self.lsock.close()
        except OSError:
            pass
        self.drop_all()

    # ---- control
    def status(self):
        with self.state_lock:
            s = dict(self.s)
            return {"mode": self.mode, "delay_ms": s["delay_ms"], "jitter_ms": s["jitter_ms"],
                    "rate_bps": s["rate_bps"], "blackhole": s["blackhole"], "refuse": s["refuse"],
                    "close_after_bytes": s["close_after_bytes"], "delay_first_ms": s["delay_first_ms"],
                    "stall_remaining_ms": max(0, int((s["stall_until"] - time.monotonic()) * 1000)),
                    "connections": len(self.conns), "total_connections": self.total_conns,
                    "total_bytes": self.total_bytes}

    def control(self, params):
        """Replace the whole fault state from params. Raises ValueError/TypeError on bad input."""
        params = dict(params)
        mode = str(params.pop("mode", "custom"))
        if mode != "custom" and mode not in MODES:
            raise ValueError(f"unknown mode {mode!r}; one of {', '.join(MODES)} or omit mode")
        if mode == "sequence":
            steps = params.get("steps")
            if isinstance(steps, str):
                steps = json.loads(steps)
            if not isinstance(steps, list) or not steps or not all(isinstance(x, dict) for x in steps):
                raise ValueError("sequence needs steps: a non-empty list of control objects with optional hold_ms")
            for st in steps:  # validate up front
                self._parse(dict(st))
            self.seq_cancel.set()
            cancel = self.seq_cancel = threading.Event()
            with self.state_lock:
                self.mode = "sequence"
            threading.Thread(target=self._run_sequence, args=(steps, cancel), daemon=True).start()
            return
        self._parse(dict(params))  # validate before cancelling anything
        self.seq_cancel.set()  # any other control call cancels a running sequence
        self._apply(dict(params, mode=mode))

    def _parse(self, params):
        mode = str(params.pop("mode", "custom"))
        if mode != "custom" and (mode not in MODES or mode == "sequence"):
            raise ValueError(f"bad mode {mode!r}")
        for short, full in ALIASES.get(mode, {}).items():
            if short in params:
                params.setdefault(full, params.pop(short))
        params.pop("hold_ms", None)
        new = self._defaults()
        new["stall_ms"] = 0
        new["drop"] = False
        for k in INT_FIELDS:
            if k in params:
                v = int(params[k])
                if v < 0:
                    raise ValueError(f"{k} must be >= 0")
                new[k] = v
        for k in BOOL_FIELDS:
            if k in params:
                new[k] = _bool(params[k])
        if mode in ("blackhole", "refuse", "drop"):
            new[mode] = True
        if mode == "delay" and not new["delay_ms"] and not new["jitter_ms"]:
            raise ValueError("delay needs ms (delay_ms) and/or jitter (jitter_ms)")
        if mode == "throttle" and not new["rate_bps"]:
            raise ValueError("throttle needs bps (rate_bps) > 0")
        if mode == "stall" and not new["stall_ms"]:
            raise ValueError("stall needs ms (stall_ms) > 0")
        return mode, new

    def _apply(self, params):
        mode, new = self._parse(dict(params))
        drop = new.pop("drop")
        stall = new.pop("stall_ms")
        new["stall_until"] = time.monotonic() + stall / 1000.0 if stall else 0.0
        with self.state_lock:
            self.s = new
            self.mode = mode
        desc = {k: v for k, v in new.items() if v and k != "stall_until"}
        if stall:
            desc["stall_ms"] = stall
        if drop:
            desc["drop"] = True
        self.log(f"mode {mode} {json.dumps(desc, sort_keys=True)}")
        if drop:
            self.log(f"dropped {self.drop_all()} connection(s)")

    def _run_sequence(self, steps, cancel):
        for i, st in enumerate(steps):
            if cancel.is_set() or self.stopping.is_set():
                return
            self.log(f"sequence step {i + 1}/{len(steps)}")
            self._apply(st)
            if i < len(steps) - 1 and cancel.wait(int(st.get("hold_ms", 0)) / 1000.0):
                return

    def drop_all(self):
        with self.state_lock:
            cs = list(self.conns.values())
            self.conns.clear()
        for c in cs:
            c.close(rst=True)
        return len(cs)

    def _snap(self):
        with self.state_lock:
            return self.s

    # ---- data path
    def _accept_loop(self):
        while not self.stopping.is_set():
            try:
                cs, _ = self.lsock.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(cs,), daemon=True).start()

    def _handle(self, cs):
        s = self._snap()
        if s["refuse"]:
            _rst(cs)
            cs.close()
            self.log("refused a new connection")
            return
        cs.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        up = None
        if not s["blackhole"]:
            try:
                up = socket.create_connection(self.upstream, timeout=10)
                up.settimeout(None)
                up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError as e:
                self.log(f"upstream connect failed: {e.__class__.__name__}")
                cs.close()
                return
        with self.state_lock:
            cid = self.next_id
            self.next_id += 1
            self.total_conns += 1
            c = Conn(cid, cs, up)
            self.conns[cid] = c
        if up is None:
            self.log(f"conn {cid} blackholed (accepted, never forwarded)")
            return  # socket stays open until drop or stop
        for src, dst, first_delay in ((cs, up, False), (up, cs, True)):
            q = queue.Queue(maxsize=32)
            threading.Thread(target=self._reader, args=(c, src, q, first_delay), daemon=True).start()
            threading.Thread(target=self._writer, args=(c, dst, q), daemon=True).start()

    def _reader(self, c, src, q, first_delay):
        last = 0.0
        first = first_delay
        try:
            while not c.closed.is_set():
                data = src.recv(CHUNK)
                if not data:
                    break
                s = self._snap()
                d = s["delay_ms"] + (random.uniform(0, s["jitter_ms"]) if s["jitter_ms"] else 0)
                if first:
                    d += s["delay_first_ms"]
                    first = False
                last = max(last, time.monotonic() + d / 1000.0)
                while not c.closed.is_set():
                    try:
                        q.put((last, data), timeout=0.1)
                        break
                    except queue.Full:
                        pass
        except OSError:
            pass
        while not c.closed.is_set():  # end-of-stream marker
            try:
                q.put((0.0, b""), timeout=0.1)
                return
            except queue.Full:
                pass

    def _gate(self, c, until=0.0):
        """Block while blackholed, stalled, or before `until`. False if the connection closed."""
        while not c.closed.is_set():
            s = self._snap()
            now = time.monotonic()
            if s["blackhole"] or now < s["stall_until"] or now < until:
                time.sleep(0.005)
            else:
                return True
        return False

    def _writer(self, c, dst, q):
        try:
            self._write_loop(c, dst, q)
        except OSError:
            c.close()
        finally:
            with c.lock:
                c.writers_left -= 1
                done = c.writers_left == 0
            if done:  # both directions ended
                c.close()
            if c.closed.is_set():
                with self.state_lock:
                    self.conns.pop(c.id, None)

    def _write_loop(self, c, dst, q):
        while True:
            try:
                release, data = q.get(timeout=0.1)
            except queue.Empty:
                if c.closed.is_set():
                    return
                continue
            if not data:
                try:
                    dst.shutdown(socket.SHUT_WR)  # half-close, as the peer did
                except OSError:
                    pass
                return
            pos = 0
            while pos < len(data):
                if not self._gate(c, release):
                    return
                s = self._snap()
                rate, limit = s["rate_bps"], s["close_after_bytes"]
                n = min(len(data) - pos, max(1, rate // 20)) if rate else len(data) - pos
                if limit:
                    with c.lock:
                        n = min(n, max(0, limit - c.bytes))
                if n:
                    dst.sendall(data[pos:pos + n])
                    pos += n
                    with c.lock:
                        c.bytes += n
                    with self.state_lock:
                        self.total_bytes += n
                if limit and c.bytes >= limit:
                    self.log(f"conn {c.id} closed after {c.bytes} bytes")
                    c.close()
                    return
                if rate:
                    time.sleep(n / rate)


def _hostport(v, default_host):
    if ":" not in v:
        return (default_host, int(v))
    h, _, p = v.rpartition(":")
    return (h or default_host, int(p))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--listen", default="4446", help="[host:]port the device connects to (default 0.0.0.0:4446)")
    ap.add_argument("--upstream", default="127.0.0.1:4445", help="host:port of the SMB server")
    ap.add_argument("--control", default="4447", help="[host:]port for the HTTP control (default 127.0.0.1:4447)")
    a = ap.parse_args(argv)
    p = Proxy(_hostport(a.upstream, "127.0.0.1"), _hostport(a.listen, "0.0.0.0"), _hostport(a.control, "127.0.0.1"))
    p.start()
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        p.stop()


if __name__ == "__main__":
    main()
