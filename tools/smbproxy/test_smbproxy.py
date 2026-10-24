"""Self-tests for smbproxy. Run from the repo root:

    python3 -m unittest tools/smbproxy/test_smbproxy.py
"""
import json
import os
import socket
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smbproxy  # noqa: E402


class EchoServer:
    def __init__(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(128)
        self.port = self.sock.getsockname()[1]
        self.accepted = 0
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            self.accepted += 1
            threading.Thread(target=self._echo, args=(c,), daemon=True).start()

    @staticmethod
    def _echo(c):
        try:
            while True:
                d = c.recv(65536)
                if not d:
                    break
                c.sendall(d)
        except OSError:
            pass
        finally:
            c.close()

    def stop(self):
        self.sock.close()


def recv_exact(s, n, timeout=5.0):
    s.settimeout(timeout)
    buf = b""
    while len(buf) < n:
        d = s.recv(n - len(buf))
        if not d:
            break
        buf += d
    return buf


class ProxyCase(unittest.TestCase):
    def setUp(self):
        self.echo = EchoServer()
        self.logs = []
        self.proxy = smbproxy.Proxy(("127.0.0.1", self.echo.port), log=self.logs.append)
        self.proxy.start()
        self.socks = []

    def tearDown(self):
        for s in self.socks:
            s.close()
        self.proxy.stop()
        self.echo.stop()

    def connect(self):
        s = socket.create_connection(("127.0.0.1", self.proxy.listen_port), timeout=5)
        self.socks.append(s)
        return s

    def ctl(self, query="", body=None):
        url = f"http://127.0.0.1:{self.proxy.control_port}/control?{query}"
        req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())

    def roundtrip(self, s, data=b"hello"):
        t = time.monotonic()
        s.sendall(data)
        got = recv_exact(s, len(data))
        return got, time.monotonic() - t

    def assert_no_data(self, s, wait=0.3):
        """Open and silent: recv times out (not EOF, not reset)."""
        s.settimeout(wait)
        with self.assertRaises(socket.timeout):
            s.recv(1)

    def assert_dead(self, s):
        s.settimeout(3)
        try:
            self.assertEqual(s.recv(1), b"")
        except (ConnectionResetError, BrokenPipeError):
            pass

    # ---- modes
    def test_pass(self):
        s = self.connect()
        got, _ = self.roundtrip(s, b"x" * 100000)
        self.assertEqual(got, b"x" * 100000)
        self.assertEqual(self.ctl("mode=pass")["mode"], "pass")

    def test_delay_fixed(self):
        s = self.connect()
        self.ctl("mode=delay&ms=150")
        got, dt = self.roundtrip(s)
        self.assertEqual(got, b"hello")
        self.assertGreaterEqual(dt, 0.28)  # 150 ms each way
        self.assertLess(dt, 1.5)

    def test_delay_jitter_keeps_order(self):
        s = self.connect()
        self.ctl("mode=delay&ms=20&jitter=60")
        payload = bytes(range(256)) * 8
        for i in range(0, len(payload), 256):
            s.sendall(payload[i:i + 256])
        self.assertEqual(recv_exact(s, len(payload)), payload)

    def test_delay_is_latency_not_serial(self):
        s = self.connect()
        self.ctl("mode=delay&ms=100")
        t = time.monotonic()
        for _ in range(10):
            s.sendall(b"abcd")
            time.sleep(0.01)
        self.assertEqual(recv_exact(s, 40), b"abcd" * 10)
        self.assertLess(time.monotonic() - t, 0.9)  # serial sleeping would take >= 2 s

    def test_throttle(self):
        s = self.connect()
        self.ctl("mode=throttle&bps=20000")
        got, dt = self.roundtrip(s, b"y" * 10000)
        self.assertEqual(len(got), 10000)
        self.assertGreaterEqual(dt, 0.45)  # 0.5 s per direction, pipelined
        self.assertLess(dt, 3.0)

    def test_stall_then_resume(self):
        s = self.connect()
        self.assertEqual(self.roundtrip(s)[0], b"hello")
        self.ctl("mode=stall&ms=400")
        got, dt = self.roundtrip(s, b"after-stall")
        self.assertEqual(got, b"after-stall")  # nothing lost
        self.assertGreaterEqual(dt, 0.3)
        got, dt = self.roundtrip(s)
        self.assertLess(dt, 0.3)  # resumed

    def test_blackhole_new_connection_stays_open(self):
        self.ctl("mode=blackhole")
        s = self.connect()
        s.sendall(b"anyone there")
        self.assert_no_data(s)
        self.assertEqual(self.echo.accepted, 0)  # never reached upstream
        self.assertEqual(self.proxy.status()["connections"], 1)
        self.ctl("mode=drop")
        self.assert_dead(s)

    def test_blackhole_existing_connection_holds_then_resumes(self):
        s = self.connect()
        self.assertEqual(self.roundtrip(s)[0], b"hello")
        self.ctl("mode=blackhole")
        s.sendall(b"held")
        self.assert_no_data(s)
        self.ctl("mode=pass")
        self.assertEqual(recv_exact(s, 4), b"held")

    def test_drop_resets_all_and_is_one_shot(self):
        a, b = self.connect(), self.connect()
        self.assertEqual(self.roundtrip(a)[0], b"hello")
        self.assertEqual(self.roundtrip(b)[0], b"hello")
        self.ctl("mode=drop")
        self.assert_dead(a)
        self.assert_dead(b)
        c = self.connect()  # accepts again
        self.assertEqual(self.roundtrip(c)[0], b"hello")
        self.assertEqual(self.proxy.status()["mode"], "drop")

    def test_refuse_new_but_existing_continue(self):
        a = self.connect()
        self.assertEqual(self.roundtrip(a)[0], b"hello")
        before = self.echo.accepted
        self.ctl("mode=refuse")
        b = self.connect()
        self.assert_dead(b)
        self.assertEqual(self.echo.accepted, before)
        self.assertEqual(self.roundtrip(a)[0], b"hello")
        self.ctl("mode=pass")
        c = self.connect()
        self.assertEqual(self.roundtrip(c)[0], b"hello")

    def test_sequence(self):
        s = self.connect()
        self.assertEqual(self.roundtrip(s)[0], b"hello")
        r = self.ctl(body={"mode": "sequence", "steps": [
            {"mode": "blackhole", "hold_ms": 400}, {"mode": "delay", "ms": 50, "hold_ms": 200}, {"mode": "pass"}]})
        self.assertIn(r["mode"], ("sequence", "blackhole"))  # step 1 may already be applied
        s.sendall(b"seq")
        self.assert_no_data(s, 0.2)  # blackholed
        self.assertEqual(recv_exact(s, 3), b"seq")  # released when step 2 begins
        deadline = time.monotonic() + 3
        while self.proxy.status()["mode"] != "pass" and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(self.proxy.status()["mode"], "pass")
        self.assertEqual(self.proxy.status()["delay_ms"], 0)

    def test_control_call_cancels_sequence(self):
        self.ctl(body={"mode": "sequence", "steps": [{"mode": "blackhole", "hold_ms": 300}, {"mode": "refuse"}]})
        self.ctl("mode=pass")
        time.sleep(0.5)
        st = self.proxy.status()
        self.assertEqual((st["mode"], st["blackhole"], st["refuse"]), ("pass", False, False))

    def test_close_after_bytes(self):
        s = self.connect()
        self.ctl("close_after_bytes=10")
        s.sendall(b"z" * 100)
        got = b""
        s.settimeout(3)
        try:
            while True:
                d = s.recv(100)
                if not d:
                    break
                got += d
        except ConnectionResetError:
            pass
        self.assertLess(len(got), 100)

    def test_delay_first_ms_only_first_response(self):
        s = self.connect()
        self.ctl("delay_first_ms=300")
        got, dt = self.roundtrip(s)
        self.assertEqual(got, b"hello")
        self.assertGreaterEqual(dt, 0.25)
        got, dt = self.roundtrip(s)
        self.assertLess(dt, 0.25)

    def test_combined_params_without_mode(self):
        s = self.connect()
        st = self.ctl("rate_bps=1000000&stall_ms=300")
        self.assertEqual(st["mode"], "custom")
        got, dt = self.roundtrip(s)
        self.assertEqual(got, b"hello")
        self.assertGreaterEqual(dt, 0.2)

    # ---- plumbing
    def test_many_concurrent_connections(self):
        results, errs = [], []

        def worker(i):
            try:
                s = self.connect()
                data = bytes([i % 256]) * 5000
                s.sendall(data)
                results.append(recv_exact(s, len(data), 10) == data)
            except Exception as e:  # noqa: BLE001
                errs.append(e)

        ts = [threading.Thread(target=worker, args=(i,)) for i in range(60)]
        for t in ts:
            t.start()
        for t in ts:
            t.join(15)
        self.assertEqual(errs, [])
        self.assertEqual(results.count(True), 60)
        self.assertEqual(self.proxy.status()["total_connections"], 60)

    def test_client_close_releases_connection(self):
        s = self.connect()
        self.roundtrip(s)
        s.close()
        deadline = time.monotonic() + 3
        while self.proxy.status()["connections"] and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual(self.proxy.status()["connections"], 0)

    def test_control_errors_and_status(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.ctl("mode=bogus")
        self.assertEqual(e.exception.code, 400)
        with self.assertRaises(urllib.error.HTTPError):
            self.ctl("mode=throttle")  # missing bps
        with self.assertRaises(urllib.error.HTTPError):
            self.ctl("delay_ms=abc")
        with urllib.request.urlopen(f"http://127.0.0.1:{self.proxy.control_port}/status") as r:
            st = json.loads(r.read())
        self.assertEqual(st["mode"], "pass")
        self.assertIn("total_bytes", st)

    def test_failed_control_keeps_state(self):
        self.ctl("mode=delay&ms=77")
        with self.assertRaises(urllib.error.HTTPError):
            self.ctl("mode=bogus")
        self.assertEqual(self.proxy.status()["delay_ms"], 77)

    def test_log_records_modes_never_payload(self):
        s = self.connect()
        marker = b"SECRET-PAYLOAD-MARKER"
        self.ctl("mode=delay&ms=5")
        self.roundtrip(s, marker)
        self.ctl("mode=drop")
        joined = "\n".join(self.logs)
        self.assertIn("mode delay", joined)
        self.assertIn("mode drop", joined)
        self.assertNotIn(marker.decode(), joined)


if __name__ == "__main__":
    unittest.main()
