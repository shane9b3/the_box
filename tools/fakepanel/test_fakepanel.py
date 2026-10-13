"""Self-tests for fakepanel. Run from the repo root:

    python3 -I -m unittest tools/fakepanel/test_fakepanel.py
"""
import datetime as dt
import gzip
import http.client
import json
import os
import sys
import time
import unittest
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fakepanel  # noqa: E402


class PanelCase(unittest.TestCase):
    """Starts a fresh server per test class on ephemeral ports (null-packet media: fast, no ffmpeg)."""
    overrides = {}

    @classmethod
    def setUpClass(cls):
        import copy
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"timezone": "Asia/Tokyo", "pace": False})
        cfg.update(cls.overrides)
        cls.panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        cls.main, cls.lb = fakepanel.start_servers(cls.panel, "127.0.0.1", 0)
        cls.port = cls.panel.main_port

    @classmethod
    def tearDownClass(cls):
        fakepanel.stop_servers(cls.panel, cls.main, cls.lb)

    def setUp(self):
        self.panel.reset()
        self.panel.update_config({k: v for k, v in self.overrides.items()})

    def get(self, path, headers=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def api(self, action="", extra=""):
        a = f"&action={action}" if action else ""
        st, _, body = self.get(f"/player_api.php?username=test&password=test{a}{extra}")
        return json.loads(body)

    def admin(self, patch):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/admin/config", json.dumps(patch).encode(), method="POST")
        urllib.request.urlopen(req, timeout=5).read()

    def read_stream(self, path, n=1880, headers=None):
        """Open a stream, read n bytes, close. Returns (status, first bytes)."""
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", path, headers=headers or {})
        r = c.getresponse()
        data = r.read(n) if r.status == 200 else r.read()
        c.close()
        return r.status, data


class TestApi(PanelCase):
    def test_login_json(self):
        j = self.api()
        self.assertEqual(j["user_info"]["auth"], 1)
        self.assertEqual(j["user_info"]["status"], "Active")
        self.assertEqual(j["server_info"]["timezone"], "Asia/Tokyo")
        # time_now reflects the server zone, timestamp_now is true UTC epoch
        tn = dt.datetime.strptime(j["server_info"]["time_now"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=ZoneInfo("Asia/Tokyo"))
        self.assertLess(abs(tn.timestamp() - j["server_info"]["timestamp_now"]), 3)
        self.assertIsInstance(self.api("get_live_streams")[0]["stream_id"], int)
        self.assertEqual(len(self.api("get_live_streams", "&category_id=1")), 4)

    def test_bad_login_modes(self):
        st, _, body = self.get("/player_api.php?username=x&password=y")
        self.assertEqual((st, json.loads(body)["user_info"]["auth"]), (200, 0))
        for mode, status, body_ in [("empty", 200, b""), ("array", 200, b"[]"), ("401", 401, b""), ("513", 513, b"")]:
            self.admin({"auth_mode": mode})
            st, _, body = self.get("/player_api.php?username=test&password=test")
            self.assertEqual((st, body), (status, body_), mode)

    def test_quirks(self):
        self.admin({"quirks": True})
        s = self.api("get_live_streams")
        self.assertEqual(s[0]["tv_archive"], "1")
        self.assertIsInstance(s[0]["stream_id"], str)
        self.assertIsNone([x for x in s if x["stream_id"] == "5"][0]["category_id"])
        self.assertIsInstance(self.api()["user_info"]["exp_date"], str)
        self.assertEqual(self.api("get_vod_info", "&vod_id=102")["info"], [])
        self.assertIsInstance(self.api("get_vod_info", "&vod_id=103")["info"]["backdrop_path"], str)

    def test_series_episodes_forms(self):
        self.assertIsInstance(self.api("get_series_info", "&series_id=201")["episodes"], dict)
        self.admin({"series_episodes_array": True})
        self.assertIsInstance(self.api("get_series_info", "&series_id=201")["episodes"], list)

    def test_epg_base64_and_plain(self):
        import base64
        e = self.api("get_simple_data_table", "&stream_id=1")["epg_listings"]
        self.assertGreater(len(e), 200)
        self.assertTrue(base64.b64decode(e[0]["title"]).decode())
        self.assertIn(1, {x["has_archive"] for x in e})
        self.admin({"epg_plain": True})
        self.assertIn("(", self.api("get_short_epg", "&stream_id=1&limit=2")["epg_listings"][0]["title"])


class TestM3uXmltv(PanelCase):
    def test_m3u_parses(self):
        st, _, body = self.get("/get.php?username=test&password=test&type=m3u_plus&output=ts")
        lines = body.decode().splitlines()
        self.assertEqual(lines[0].split()[0], "#EXTM3U")
        infs = [l for l in lines if l.startswith("#EXTINF")]
        urls = [l for l in lines if l.startswith("http")]
        self.assertEqual(len(infs), 12)
        self.assertEqual(len(urls), 12)
        self.assertIn('tvg-id="ch1.test"', infs[0])
        self.assertIn('catchup="xc"', infs[0])
        self.assertTrue(any("#EXTVLCOPT:http-user-agent" in l for l in lines))
        self.assertTrue(any("|User-Agent=" in l for l in urls))

    def test_xmltv_programmes(self):
        self.admin({"epg_offset": "+0100"})
        st, _, body = self.get("/xmltv.php?username=test&password=test")
        txt = body.decode()
        self.assertEqual(txt.count("<channel "), 12)
        self.assertGreater(txt.count("<programme "), 12 * 280)
        self.assertIn(" +0100\"", txt)

    def test_xmltv_gzip(self):
        self.admin({"epg_gzip": True})
        _, _, body = self.get("/xmltv.php?username=test&password=test")
        self.assertIn(b"<tv ", gzip.decompress(body))


class TestStreams(PanelCase):
    def test_live_ts_sync_bytes(self):
        st, data = self.read_stream("/live/test/test/1.ts", 1880)
        self.assertEqual(st, 200)
        self.assertEqual(len(data), 1880)
        self.assertTrue(all(data[i] == 0x47 for i in range(0, 1880, 188)))

    def test_hls_playlist_and_segment(self):
        st, _, body = self.get("/live/test/test/1.m3u8")
        self.assertEqual(st, 200)
        seg = [l for l in body.decode().splitlines() if l.endswith(".ts")][-1]
        st, _, data = self.get("/live/test/test/" + seg)
        self.assertEqual((st, data[0]), (200, 0x47))

    def test_m3u8_405(self):
        self.admin({"faults": {"405_on_m3u8": True}})
        self.assertEqual(self.get("/live/test/test/1.m3u8")[0], 405)
        self.assertEqual(self.read_stream("/live/test/test/1.ts")[0], 200)

    def test_vod_finite_with_range(self):
        st, h, body = self.get("/movie/test/test/101.mp4", {"Range": "bytes=0-187"})
        self.assertEqual((st, len(body)), (206, 188))

    def test_max_connections_linger(self):
        self.admin({"max_connections": 1, "faults": {"linger": 3, "linger_status": 458}})
        self.assertEqual(self.read_stream("/live/test/test/1.ts")[0], 200)
        time.sleep(0.4)  # server notices the close; the slot lingers for 3 s
        self.assertEqual(self.read_stream("/live/test/test/2.ts")[0], 458)
        self.assertEqual(self.panel.stats["rejections"]["max_connections"], 1)
        self.admin({"faults": {"linger": 0}})
        deadline = time.time() + 3
        while self.read_stream("/live/test/test/2.ts")[0] != 200 and time.time() < deadline:
            time.sleep(0.2)
        self.assertEqual(self.read_stream("/live/test/test/2.ts")[0], 200)

    def test_range_rejects(self):
        self.admin({"faults": {"range_rejects": True}})
        self.assertEqual(self.read_stream("/live/test/test/1.ts", headers={"Range": "bytes=0-"})[0], 416)
        self.assertEqual(self.panel.stats["range_requests_seen"], 1)
        self.assertEqual(self.read_stream("/live/test/test/1.ts")[0], 200)

    def test_ua_whitelist_and_splash_and_content_type(self):
        self.admin({"faults": {"ua_whitelist": ["TiviMate"]}})
        self.assertEqual(self.read_stream("/live/test/test/1.ts", headers={"User-Agent": "okhttp/4"})[0], 403)
        self.assertEqual(self.read_stream("/live/test/test/1.ts", headers={"User-Agent": "TiviMate/4.7"})[0], 200)
        self.admin({"faults": {"ua_whitelist": None, "html_splash": True}})
        st, h, body = self.get("/live/test/test/1.ts")
        self.assertEqual((st, h["Content-Type"]), (200, "text/html"))

    def test_redirect_token_expiry(self):
        self.admin({"faults": {"redirect": True, "token_ttl": 1}})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        c.request("GET", "/movie/test/test/101.mp4")
        r = c.getresponse()
        self.assertEqual(r.status, 302)
        loc = r.getheader("Location")
        self.assertIn(f":{self.panel.lb_port}/", loc)
        path = loc.split(str(self.panel.lb_port), 1)[1]
        c2 = http.client.HTTPConnection("127.0.0.1", self.panel.lb_port, timeout=5)
        c2.request("GET", path)
        self.assertEqual(c2.getresponse().status, 200)
        time.sleep(1.2)
        c3 = http.client.HTTPConnection("127.0.0.1", self.panel.lb_port, timeout=5)
        c3.request("GET", path)
        self.assertEqual(c3.getresponse().status, 403)


class TestTimeshift(PanelCase):
    def fmt(self, ts, zone):
        return dt.datetime.fromtimestamp(ts, zone).strftime("%Y-%m-%d:%H-%M")

    def test_strict_tz(self):
        slot = int(time.time() - 7200) // 1800 * 1800  # programme that started ~2 h ago
        self.admin({"strict_timeshift_tz": True, "timeshift_expect": [slot]})
        server = self.fmt(slot, ZoneInfo("Asia/Tokyo"))
        utc = self.fmt(slot, dt.timezone.utc)
        self.assertNotEqual(server, utc)
        for path in (f"/timeshift/test/test/60/{server}/1.ts",
                     f"/streaming/timeshift.php?username=test&password=test&stream=1&start={server}&duration=60"):
            st, h, body = self.get(path)
            self.assertEqual(st, 200, path)
            self.assertEqual(body[0], 0x47)
            self.assertIn("Content-Length", h)
        for path in (f"/timeshift/test/test/60/{utc}/1.ts",
                     f"/streaming/timeshift.php?username=test&password=test&stream=1&start={utc}&duration=60"):
            self.assertEqual(self.get(path)[0], 404, path)
        self.assertEqual(self.panel.stats["timeshift_tz_warnings"], 2)

    def test_non_strict_warns_but_serves(self):
        slot = int(time.time() - 7200) // 1800 * 1800
        self.admin({"timeshift_expect": [slot]})
        utc = self.fmt(slot, dt.timezone.utc)
        self.assertEqual(self.get(f"/timeshift/test/test/60/{utc}/1.ts")[0], 200)
        self.assertEqual(self.panel.stats["timeshift_tz_warnings"], 1)

    def test_flussonic_and_shift_archive_use_unix_utc(self):
        slot = int(time.time() - 7200) // 1800 * 1800
        self.admin({"strict_timeshift_tz": True, "timeshift_expect": [slot]})
        st, _, body = self.get(f"/live/test/test/4/archive-{slot}-1800.m3u8")
        self.assertEqual(st, 200)
        seg = [l for l in body.decode().splitlines() if l.endswith(".ts")][0]
        self.assertEqual(seg, "/vodseg/4_0.ts")
        self.assertEqual(self.get(seg)[0], 200)
        self.assertEqual(self.get(f"/live/test/test/3.ts?utc={slot}&lutc={int(time.time())}")[0], 200)
        self.assertEqual(self.get(f"/live/test/test/3.ts?utc={slot + 3600}&lutc={int(time.time())}")[0], 404)


class TestHlsEdgeCases(PanelCase):
    def test_m3u8_only_panel_serves_segments(self):
        self.admin({"allowed_output_formats": ["m3u8"]})
        self.assertEqual(self.read_stream("/live/test/test/1.ts")[0], 405)
        st, _, body = self.get("/live/test/test/1.m3u8")
        self.assertEqual(st, 200)
        seg = [l for l in body.decode().splitlines() if l.endswith(".ts")][-1]
        self.assertEqual(self.get("/live/test/test/" + seg)[0], 200)

    def test_redirected_hls_segments_carry_token(self):
        self.admin({"faults": {"redirect": True}})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        c.request("GET", "/live/test/test/1.m3u8")
        r = c.getresponse()
        self.assertEqual(r.status, 302)
        lb_path = r.getheader("Location").split(str(self.panel.lb_port), 1)[1]
        c2 = http.client.HTTPConnection("127.0.0.1", self.panel.lb_port, timeout=5)
        c2.request("GET", lb_path)
        r2 = c2.getresponse()
        self.assertEqual(r2.status, 200)
        seg = [l for l in r2.read().decode().splitlines() if ".ts?token=" in l][-1]
        c3 = http.client.HTTPConnection("127.0.0.1", self.panel.lb_port, timeout=5)
        c3.request("GET", "/live/test/test/" + seg)
        self.assertEqual(c3.getresponse().status, 200)


# ------------------------------------------------------------------ synthetic TS + independent decoders

VPID, APID = 0x100, 0x101
FRAME = 3600            # 40 ms
FRAMES = 40             # clip = 40 frames = 1.6 s = 144000 ticks
MASK33 = (1 << 33) - 1


def enc_ts(prefix, v):
    bits = (prefix << 36) | (((v >> 30) & 7) << 33) | (1 << 32) | (((v >> 15) & 0x7FFF) << 17) | (1 << 16) | ((v & 0x7FFF) << 1) | 1
    return bits.to_bytes(5, "big")


def dec_ts(b):
    bits = int.from_bytes(b, "big")
    assert bits & 1 and (bits >> 16) & 1 and (bits >> 32) & 1, "marker bits"
    return (((bits >> 33) & 7) << 30) | (((bits >> 17) & 0x7FFF) << 15) | ((bits >> 1) & 0x7FFF)


def make_pkt(pid, cc, payload, pusi=False, af=b"", afc=None):
    afc = afc if afc is not None else (3 if af else 1)
    head = bytes([0x47, (0x40 if pusi else 0) | (pid >> 8), pid & 0xFF, (afc << 4) | cc])
    body = (bytes([len(af)]) + af) if afc & 2 else b""
    pad = 188 - 4 - len(body) - len(payload)
    assert pad >= 0
    if afc & 2 and pad:  # stuff the adaptation field
        body = bytes([len(af) + pad]) + af + b"\xff" * pad
        pad = 0
    return head + body + payload + b"\xaa" * pad


def synth_clip(base=90000):
    """Program-table-free TS: video PID (PES with PTS+DTS, PCR in AF, AF-only packets), audio PID (PTS only)."""
    out, cc = [], {VPID: 0, APID: 0}

    def nxt(pid, has_payload=True):
        c = cc[pid]
        if has_payload:
            cc[pid] = (c + 1) & 15
        return c if has_payload else (c - 1) & 15

    for i in range(FRAMES):
        pts = (base + i * FRAME) & MASK33
        dts = (pts - FRAME) & MASK33
        pcr = (pts - 2 * FRAME) & MASK33
        pcr_field = ((pcr << 15) | (0x3F << 9) | 123).to_bytes(6, "big")  # base | reserved | 27 MHz extension 123
        pes = b"\x00\x00\x01\xe0\x00\x00\x80\xc0\x0a" + enc_ts(3, pts) + enc_ts(1, dts)
        out.append(make_pkt(VPID, nxt(VPID), pes, True, b"\x10" + pcr_field))
        for _ in range(3):
            out.append(make_pkt(VPID, nxt(VPID), b"\x11" * 184))
        out.append(make_pkt(VPID, nxt(VPID, False), b"", afc=2, af=b"\x00"))
        for j in range(2):
            apts = (base + i * FRAME + j * FRAME // 2) & MASK33
            apes = b"\x00\x00\x01\xc0\x00\x00\x80\x80\x05" + enc_ts(2, apts)
            out.append(make_pkt(APID, nxt(APID), apes, True))
            out.append(make_pkt(APID, nxt(APID), b"\x22" * 184))
    return b"".join(out)


def scan(data):
    """Independent TS walk: returns pcrs [(base, ext)], per-PID pts and dts lists, per-PID [(cc, has_payload)]."""
    pcrs, pts, dts, ccs = [], {VPID: [], APID: []}, {VPID: [], APID: []}, {}
    assert len(data) % 188 == 0
    for o in range(0, len(data), 188):
        p = data[o:o + 188]
        assert p[0] == 0x47
        pid = ((p[1] & 0x1F) << 8) | p[2]
        afc, cc = (p[3] >> 4) & 3, p[3] & 15
        ccs.setdefault(pid, []).append((cc, bool(afc & 1)))
        i = 4
        if afc & 2:
            if p[5] & 0x10:
                v = int.from_bytes(p[6:12], "big")
                pcrs.append((v >> 15, v & 0x1FF))
            i = 5 + p[4]
        if afc & 1 and p[1] & 0x40 and p[i:i + 3] == b"\x00\x00\x01":
            fl = p[i + 7] >> 6
            pts[pid].append(dec_ts(p[i + 9:i + 14]))
            if fl == 3:
                dts[pid].append(dec_ts(p[i + 14:i + 19]))
    return pcrs, pts, dts, ccs


def read_until_close(host, port, path, timeout=15):
    c = http.client.HTTPConnection(host, port, timeout=timeout)
    c.request("GET", path)
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, data[:len(data) // 188 * 188]


class SynthCase(PanelCase):
    base = 90000
    overrides = {"pace": True}

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.saved = dict(cls.panel.clips)
        cls.src = synth_clip(cls.base)
        # nominal duration 1.0 s => about one loop per second of paced output
        clip = fakepanel.Clip(cls.src, 1.0, [(cls.src, 1.0)])
        for k in cls.panel.clips:
            cls.panel.clips[k] = clip
        cls.clip = clip

    @classmethod
    def tearDownClass(cls):
        cls.panel.clips.update(cls.saved)
        super().tearDownClass()

    def fetch(self, faults, path="/live/test/test/1.ts"):
        self.admin({"faults": faults})
        st, data = read_until_close("127.0.0.1", self.port, path)
        self.assertEqual(st, 200)
        return data


class TestContinuousTimestamps(SynthCase):
    def test_plan_measures_clip_duration(self):
        self.assertTrue(self.clip.plan.active)
        self.assertEqual(self.clip.plan.dur90k, FRAMES * FRAME)

    def check_two_loops(self, data):
        self.assertGreater(len(data), 2 * len(self.src))
        pcrs, pts, dts, ccs = scan(data)
        dur = FRAMES * FRAME
        # every consecutive pair is exactly one step apart (mod 2^33), including at both loop boundaries
        for a, b in zip(pcrs, pcrs[1:]):
            self.assertEqual((b[0] - a[0]) & MASK33, FRAME)
            self.assertEqual(a[1], 123)  # 27 MHz extension untouched
        for series, step in ((pts[VPID], FRAME), (dts[VPID], FRAME), (pts[APID], FRAME // 2)):
            for a, b in zip(series, series[1:]):
                self.assertEqual((b - a) & MASK33, step)
        # loop 2 is exactly loop 1 + clip_duration
        self.assertEqual((pts[VPID][FRAMES] - pts[VPID][0]) & MASK33, dur)
        self.assertEqual((dts[VPID][FRAMES] - dts[VPID][0]) & MASK33, dur)
        self.assertEqual((pcrs[FRAMES][0] - pcrs[0][0]) & MASK33, dur)
        return ccs

    def test_monotonic_and_exact_across_loops(self):
        self.check_two_loops(self.fetch({"close_every": 1.5}))

    def test_continuity_counters(self):
        ccs = self.check_two_loops(self.fetch({"close_every": 1.5}))
        for pid, lst in ccs.items():
            exp = lst[0][0]
            for cc, payload in lst:
                if payload:
                    self.assertEqual(cc, exp, f"pid {pid:#x}")
                    exp = (exp + 1) & 15
                else:  # AF-only: repeats the previous value
                    self.assertEqual(cc, (exp - 1) & 15, f"pid {pid:#x}")

    def test_first_loop_is_byte_identical(self):
        data = self.fetch({"close_every": 1.2})
        self.assertEqual(data[:len(self.src)], self.src)

    def test_rewriter_speed(self):
        t = time.monotonic()
        st = fakepanel.TsState(0, 123456)
        for pos in range(0, len(self.src), 188 * 28):
            st.emit(self.clip.plan, self.src, pos, min(pos + 188 * 28, len(self.src)))
        self.assertLess(time.monotonic() - t, 1.0)


class TestTimestampWrap(SynthCase):
    base = (1 << 33) - 3 * FRAME  # PTS/PCR wrap inside the first loop

    def test_wrap_mod_2_33(self):
        data = self.fetch({"close_every": 1.5})
        pcrs, pts, dts, _ = scan(data)
        self.assertTrue(any(b[0] < a[0] for a, b in zip(pcrs, pcrs[1:])), "expected a 33-bit wrap")
        for a, b in zip(pcrs, pcrs[1:]):
            self.assertEqual((b[0] - a[0]) & MASK33, FRAME)
        for a, b in zip(pts[VPID], pts[VPID][1:]):
            self.assertEqual((b - a) & MASK33, FRAME)


class TestReconnectModes(SynthCase):
    def two_connections(self, mode):
        f = {"close_every": 0.3, "reconnect_mode": mode}
        d1 = self.fetch(f)
        d2 = self.fetch(f)
        return scan(d1)[0], scan(d2)[0]

    def test_continue_is_monotonic(self):
        p1, p2 = self.two_connections("continue")
        self.assertEqual((p2[0][0] - p1[-1][0]) & MASK33, FRAME)

    def test_reset_goes_back_to_clip_start(self):
        p1, p2 = self.two_connections("reset")
        first = scan(self.src)[0][0][0]
        self.assertEqual(p2[0][0], first)
        self.assertGreater(p1[-1][0], first)  # the first connection ran past the start => a backwards jump

    def test_reset_clears_offset_after_loops(self):
        # first connection loops (offset != 0); the second one must still restart at the clip's own PCR
        self.fetch({"close_every": 1.3, "reconnect_mode": "reset"})
        p2 = scan(self.fetch({"close_every": 0.2}))[0]
        self.assertEqual(p2[0][0], scan(self.src)[0][0][0])

    def test_jump_is_plus_five_seconds(self):
        p1, p2 = self.two_connections("jump")
        self.assertEqual((p2[0][0] - p1[-1][0]) & MASK33, FRAME + 5 * 90000)

    def test_jump_keeps_cc_continuous(self):
        f = {"close_every": 0.3, "reconnect_mode": "jump"}
        d1, d2 = self.fetch(f), self.fetch(f)
        c1, c2 = scan(d1)[3], scan(d2)[3]
        for pid in (VPID, APID):
            exp = None
            for cc, payload in c1[pid] + c2[pid]:  # one unbroken sequence across the reconnect
                if exp is not None:
                    self.assertEqual(cc, exp if payload else (exp - 1) & 15, f"pid {pid:#x}")
                exp = (cc + 1) & 15


class TestStallAfter(SynthCase):
    def test_stall_after_pauses_output(self):
        self.admin({"faults": {"stall_after": [0.6, 1.2]}})
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", "/live/test/test/1.ts")
        r = c.getresponse()
        stamps, chunks = [], []
        t0 = time.monotonic()
        while time.monotonic() - t0 < 3.4:
            d = r.read1(65536)
            if not d:
                break
            stamps.append(time.monotonic() - t0)
            chunks.append(d)
        c.close()
        gap, at = max((b - a, a) for a, b in zip(stamps, stamps[1:]))
        self.assertGreater(gap, 1.0)
        self.assertLess(gap, 1.8)
        self.assertGreater(at, 0.4)
        self.assertLess(at, 1.1)
        self.assertGreater(stamps[-1], 1.8 + 0.3, "output resumed after the stall")
        data = b"".join(chunks)
        pcrs = scan(data[:len(data) // 188 * 188])[0]
        for a, b in zip(pcrs, pcrs[1:]):
            self.assertEqual((b[0] - a[0]) & MASK33, FRAME)

    def test_plain_stall_still_delays_first_byte(self):
        self.admin({"faults": {"stall": 0.5}})
        t = time.monotonic()
        st, _ = self.read_stream("/live/test/test/1.ts", 188)
        self.assertEqual(st, 200)
        self.assertGreaterEqual(time.monotonic() - t, 0.45)

    def test_stall_after_is_documented(self):
        st, _, body = self.get("/admin/faults")
        self.assertIn("stall_after", json.loads(body)["documented_fault_keys"])


class TestStatsAdditions(PanelCase):
    def test_request_log_and_max_open_seen(self):
        self.read_stream("/live/test/test/1.ts", 188, headers={"User-Agent": "UnitTest/1.0"})
        self.get("/live/test/test/1.m3u8?token=abc", headers={"User-Agent": "Other/2"})
        st, _, body = self.get("/admin/stats")
        s = json.loads(body)
        self.assertIsInstance(s["requests"], int)  # existing field kept
        log = s["request_log"]
        self.assertEqual(log[0]["ua"], "UnitTest/1.0")
        self.assertIs(log[0]["token_present"], False)
        self.assertEqual(log[1]["ua"], "Other/2")
        self.assertIs(log[1]["token_present"], True)
        self.assertGreaterEqual(s["connections"]["max_open_seen"], 1)
        for k in ("active", "open", "lingering"):
            self.assertIn(k, s["connections"])

    def test_max_open_seen_tracks_simultaneous_and_resets(self):
        conns = []
        for _ in range(2):
            c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
            c.request("GET", "/live/test/test/2.ts")
            r = c.getresponse()
            r.read(188)
            conns.append((c, r))
        s = json.loads(self.get("/admin/stats")[2])
        self.assertEqual(s["connections"]["max_open_seen"], 2)
        for c, r in conns:
            r.close()
            c.close()
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/admin/reset", b"{}", method="POST")
        urllib.request.urlopen(req, timeout=5).read()
        s = json.loads(self.get("/admin/stats")[2])
        self.assertEqual(s["connections"]["max_open_seen"], 0)


if __name__ == "__main__":
    unittest.main()
