"""Self-tests for fakepanel. Run from the repo root:

    python3 -I -m unittest tools/fakepanel/test_fakepanel.py
"""
import datetime as dt
import gzip
import http.client
import json
import os
import shutil
import socket
import sys
import tempfile
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
        self.assertEqual(len(infs), 13)
        self.assertEqual(len(urls), 13)
        radio = [l for l in infs if "Radio Test" in l][0]
        self.assertIn('radio="true"', radio)
        self.assertIn('group-title="Radio"', radio)
        self.assertIn('tvg-id="ch1.test"', infs[0])
        self.assertIn('catchup="xc"', infs[0])
        self.assertTrue(any("#EXTVLCOPT:http-user-agent" in l for l in lines))
        self.assertTrue(any("|User-Agent=" in l for l in urls))

    def test_xmltv_programmes(self):
        self.admin({"epg_offset": "+0100"})
        st, _, body = self.get("/xmltv.php?username=test&password=test")
        txt = body.decode()
        self.assertEqual(txt.count("<channel "), 13)
        self.assertGreater(txt.count("<programme "), 13 * 280)
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


def raw_get(port, path):
    """Plain socket GET (no HTTP client buffering); returns the connected socket."""
    sk = socket.create_connection(("127.0.0.1", port), timeout=5)
    sk.sendall(f"GET {path} HTTP/1.0\r\nHost: x\r\n\r\n".encode())
    return sk


def stats(port):
    return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/admin/stats", timeout=5).read())


def wait_for(pred, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if pred():
            return True
        time.sleep(0.02)
    return pred()


class TestClientEof(SynthCase):
    def tearDown(self):  # let the server notice this test's own close before the next test resets the stats
        wait_for(lambda: stats(self.port)["connections"]["open"] == 0, 2.0)

    def test_eof_during_stall_after_hold_frees_slot(self):
        self.admin({"faults": {"stall_after": [0.3, 5]}})
        sk = raw_get(self.port, "/live/test/test/1.ts")
        got = b""
        while len(got) < 2000:
            got += sk.recv(65536)
        time.sleep(0.6)  # now inside the 5 s hold
        self.assertEqual(stats(self.port)["connections"]["open"], 1)
        t = time.monotonic()
        sk.close()
        self.assertTrue(wait_for(lambda: stats(self.port)["connections"]["active"] == 0, 0.5),
                        "slot not released within 0.5 s of the close")
        self.assertLess(time.monotonic() - t, 0.5)
        self.assertTrue(wait_for(lambda: stats(self.port)["client_eof"] == 1, 0.5))
        self.assertTrue(wait_for(lambda: stats(self.port)["request_log"][-1]["end_reason"] == "client_eof", 0.5))

    def test_eof_during_plain_stall_before_first_byte(self):
        self.admin({"faults": {"stall": 4}})
        sk = raw_get(self.port, "/live/test/test/1.ts")
        time.sleep(0.3)
        sk.close()
        self.assertTrue(wait_for(lambda: stats(self.port)["client_eof"] == 1, 0.5))
        self.assertEqual(stats(self.port)["connections"]["active"], 0)

    def test_silent_connected_client_is_not_eof(self):
        sk = raw_get(self.port, "/live/test/test/1.ts")
        sk.recv(188)
        time.sleep(2.0)  # connected, not reading: the stream must be kept, not dropped
        s = stats(self.port)
        self.assertEqual(s["client_eof"], 0)
        self.assertEqual(s["connections"]["open"], 1)
        self.assertIsNone(s["request_log"][-1]["end_reason"])
        sk.close()
        self.assertTrue(wait_for(lambda: stats(self.port)["connections"]["open"] == 0, 1.0))

    def test_silent_client_during_stall_after_hold_is_not_eof(self):
        self.admin({"faults": {"stall_after": [0.2, 4]}})
        sk = raw_get(self.port, "/live/test/test/1.ts")
        time.sleep(2.0)  # never reads at all, sends nothing
        s = stats(self.port)
        self.assertEqual((s["client_eof"], s["connections"]["open"]), (0, 1))
        sk.close()

    def test_linger_still_applies_after_eof(self):
        self.admin({"max_connections": 1, "faults": {"linger": 1.5, "linger_status": 458, "stall_after": [0.2, 5]}})
        sk = raw_get(self.port, "/live/test/test/1.ts")
        sk.recv(188)
        time.sleep(0.5)
        sk.close()
        self.assertTrue(wait_for(lambda: stats(self.port)["client_eof"] == 1, 0.5))
        c = stats(self.port)["connections"]
        self.assertEqual((c["open"], c["lingering"]), (0, 1))
        self.assertEqual(self.read_stream("/live/test/test/2.ts")[0], 458)  # slot still occupied
        self.assertTrue(wait_for(lambda: stats(self.port)["connections"]["active"] == 0, 2.0))
        self.admin({"faults": {"linger": 0, "stall_after": None}})
        self.assertEqual(self.read_stream("/live/test/test/2.ts")[0], 200)

    def test_end_reasons(self):
        self.admin({"faults": {"close_every": 0.3}})
        read_until_close("127.0.0.1", self.port, "/live/test/test/1.ts")
        self.admin({"faults": {"close_every": 0}})
        self.get("/live/test/test/999.ts")
        log = stats(self.port)["request_log"]
        self.assertEqual([e["end_reason"] for e in log], ["forced_close", "server_close"])
        self.assertEqual(stats(self.port)["client_eof"], 0)


def parse_psi(data):
    """Return {pid: stream_type} of the first program by walking PAT then PMT (single-section tables)."""
    pkts = [data[o:o + 188] for o in range(0, len(data) // 188 * 188, 188)]

    def section(pid):
        for p in pkts:
            if (((p[1] & 0x1F) << 8) | p[2]) == pid and p[1] & 0x40 and p[3] & 0x10:
                i = 5 + p[4]  # header + pointer_field
                return p[i:]
        return None

    sec = section(0)
    assert sec and sec[0] == 0x00, "no PAT"
    pmt_pid = ((sec[10] & 0x1F) << 8) | sec[11]  # first program entry
    sec = section(pmt_pid)
    assert sec and sec[0] == 0x02, "no PMT"
    end = 3 + (((sec[1] & 0x0F) << 8) | sec[2]) - 4
    i = 12 + (((sec[10] & 0x0F) << 8) | sec[11])
    out = {}
    while i + 5 <= end:
        out[((sec[i + 1] & 0x1F) << 8) | sec[i + 2]] = sec[i]
        i += 5 + (((sec[i + 3] & 0x0F) << 8) | sec[i + 4])
    return out


def pid_pts(data, pid):
    """PTS list of one PID (independent of scan(), which only knows VPID/APID)."""
    out = []
    for o in range(0, len(data), 188):
        p = data[o:o + 188]
        if (((p[1] & 0x1F) << 8) | p[2]) != pid or not (p[1] & 0x40):
            continue
        i = 5 + p[4] if (p[3] >> 4) & 2 else 4
        if p[i:i + 3] == b"\x00\x00\x01" and p[i + 7] >> 6 in (2, 3):
            out.append(dec_ts(p[i + 9:i + 14]))
    return out


def synth_audio_clip(base=90000):
    """Audio-only TS: just APID, PES with PTS every FRAME//2, PCR in the AF of each PES start packet."""
    out, cc = [], 0
    for i in range(FRAMES * 2):
        pts = (base + i * (FRAME // 2)) & MASK33
        pcr = (pts - FRAME // 4) & MASK33
        pcr_field = ((pcr << 15) | (0x3F << 9)).to_bytes(6, "big")
        pes = b"\x00\x00\x01\xc0\x00\x00\x80\x80\x05" + enc_ts(2, pts)
        out.append(make_pkt(APID, cc, pes, True, b"\x10" + pcr_field))
        cc = (cc + 1) & 15
        out.append(make_pkt(APID, cc, b"\x22" * 184))
        cc = (cc + 1) & 15
    return b"".join(out)


class TestAudioOnlyContinuity(PanelCase):
    overrides = {"pace": True}

    def test_audio_only_synth_clip_is_continuous_across_loops(self):
        src = synth_audio_clip()
        clip = fakepanel.Clip(src, 1.0, [(src, 1.0)])
        self.assertTrue(clip.plan.active)
        self.assertEqual(clip.plan.dur90k, FRAMES * FRAME)  # from the audio PTS span (+ one step)
        saved = dict(self.panel.clips)
        try:
            for k in self.panel.clips:
                self.panel.clips[k] = clip
            self.admin({"faults": {"close_every": 1.5}})
            st, data = read_until_close("127.0.0.1", self.port, "/live/test/test/13.ts")
        finally:
            self.panel.clips.update(saved)
        self.assertEqual(st, 200)
        pcrs, pts, dts, ccs = scan(data)
        self.assertEqual(pts[VPID], [])
        self.assertGreater(len(pts[APID]), 2 * FRAMES * 2)
        for a, b in zip(pts[APID], pts[APID][1:]):
            self.assertEqual((b - a) & MASK33, FRAME // 2)
        for a, b in zip(pcrs, pcrs[1:]):
            self.assertEqual((b[0] - a[0]) & MASK33, FRAME // 2)
        exp = None
        for cc, payload in ccs[APID]:
            if exp is not None:
                self.assertEqual(cc, exp)
            exp = (cc + 1) & 15


class TestRadioChannel(unittest.TestCase):
    """Channel 13 with the null fallback, and with real ffmpeg media (skipped without ffmpeg)."""

    def make(self, **kw):
        import copy
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg["pace"] = False
        panel = fakepanel.Panel(cfg, quiet=True, **kw)
        main, lb = fakepanel.start_servers(panel, "127.0.0.1", 0)
        self.addCleanup(fakepanel.stop_servers, panel, main, lb)
        return panel

    def test_fallback_serves_ch13(self):
        panel = self.make(use_ffmpeg=False)
        c = http.client.HTTPConnection("127.0.0.1", panel.main_port, timeout=5)
        c.request("GET", "/live/test/test/13.ts")
        r = c.getresponse()
        self.assertEqual(r.status, 200)
        d = r.read(1880)
        c.close()
        self.assertEqual(len(d) % 188, 0)
        self.assertTrue(all(d[o] == 0x47 for o in range(0, len(d), 188)))
        c = http.client.HTTPConnection("127.0.0.1", panel.main_port, timeout=5)
        c.request("GET", "/player_api.php?username=test&password=test&action=get_live_streams&category_id=4")
        self.assertEqual([x["name"] for x in json.loads(c.getresponse().read())], ["Radio Test"])
        c.close()

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not on PATH")
    def test_real_aac_only_clip(self):
        tmp = tempfile.mkdtemp(prefix="fakepanel-media-")
        self.addCleanup(shutil.rmtree, tmp, True)
        panel = self.make(media_dir=tmp, use_ffmpeg=True)
        self.assertTrue(os.path.exists(os.path.join(tmp, "aac_only_25.ts")))
        clip = panel.clips["radio"]
        self.assertIsNot(clip, panel.clips["std"])
        self.assertTrue(clip.plan.active)
        self.assertGreater(clip.plan.dur90k, 9 * 90000)
        self.assertLess(clip.plan.dur90k, 11 * 90000)
        c = http.client.HTTPConnection("127.0.0.1", panel.main_port, timeout=10)
        c.request("GET", "/live/test/test/13.ts")
        r = c.getresponse()
        self.assertEqual(r.status, 200)
        data = b""
        while len(data) < len(clip.data) * 2.5:  # unpaced: about 2.5 loops, quickly
            d = r.read(65536)
            if not d:
                break
            data += d
        c.close()
        data = data[:len(data) // 188 * 188]
        self.assertTrue(all(data[o] == 0x47 for o in range(0, len(data), 188)))
        streams = parse_psi(data)
        self.assertEqual(list(streams.values()), [0x0F], f"expected a single ADTS-AAC stream, got {streams}")
        ser = pid_pts(data, next(iter(streams)))
        self.assertGreater(len(ser), 60)  # ~32 PES per 10 s loop
        steps = [(b - a) & MASK33 for a, b in zip(ser, ser[1:])]
        self.assertGreater(min(steps), 0)
        # no jump bigger than ordinary PES spacing at either loop boundary (ffmpeg packs several AAC frames per PES)
        self.assertLess(max(steps), 34000,  # 17 AAC frames, the largest ffmpeg PES
                         f"PTS discontinuity across a loop: {sorted(steps)[-3:]}")


class TestSynthetic(PanelCase):
    """--synthetic N: generated lineup, streamed deterministic XMLTV, admin reminder instants."""
    overrides = {"synthetic": 400, "synthetic_days_back": 1, "synthetic_days_ahead": 2}

    def xml(self):
        import xml.etree.ElementTree as ET
        st, _, body = self.get("/xmltv.php?username=test&password=test")
        self.assertEqual(st, 200)
        return body, ET.fromstring(body)

    def progs(self, root, ch):
        out = []
        for p in root.iter("programme"):
            if p.get("channel") == ch:
                f = lambda k: int(dt.datetime.strptime(p.get(k), "%Y%m%d%H%M%S %z").timestamp())
                out.append((f("start"), f("stop"), p.find("title").text))
        return out

    def test_lineup_and_groups(self):
        ls = self.api("get_live_streams")
        self.assertEqual(len(ls), 400)
        self.assertEqual([c["num"] for c in ls], list(range(1, 401)))
        self.assertEqual([c["stream_id"] for c in ls], list(range(1, 401)))
        cats = self.api("get_live_categories")
        self.assertEqual(len(cats), 8)
        ids = {int(c["category_id"]) for c in cats}
        self.assertEqual(ids, set(range(1000, 1008)))
        self.assertFalse(ids & {10, 11, 20, 21})
        for c in cats:
            n = len(self.api("get_live_streams", "&category_id=" + c["category_id"]))
            self.assertEqual(n, 50)
        self.assertEqual(cats[0]["category_name"], "News 1")
        self.assertEqual(self.api("get_live_streams", "&category_id=1"), [])
        self.assertEqual(len(self.api("get_vod_streams")), 6)  # VOD catalog unchanged
        self.assertTrue(any("News" in c["name"] for c in ls))

    def test_prefix_archive_shares(self):
        ls = self.api("get_live_streams")
        pre = [c for c in ls if c["name"].startswith(("UK: ", "[DE] ", "FR | "))]
        self.assertEqual(len(pre), 120)
        for p in ("UK: ", "[DE] ", "FR | "):
            self.assertEqual(sum(c["name"].startswith(p) for c in ls), 40)
        arch = [c for c in ls if c["tv_archive"] == 1]
        self.assertEqual(len(arch), 80)
        self.assertEqual({c["tv_archive_duration"] for c in arch}, {3, 7})
        self.assertTrue(all(c["tv_archive_duration"] == 0 for c in ls if c["tv_archive"] == 0))
        self.assertEqual((ls[0]["tv_archive"], ls[0]["tv_archive_duration"]), (1, 7))
        self.assertEqual(sum(1 for c in ls if c["epg_channel_id"]), 380)  # 95 % have EPG

    def test_xmltv_wellformed_and_reminders(self):
        body, root = self.xml()
        chans = root.findall("channel")
        self.assertEqual(len(chans), 380)
        syn = json.loads(self.get("/admin/stats")[2])["synthetic"]
        self.assertEqual(syn["n"], 400)
        r1, r2 = syn["reminder_starts"]
        start = syn["start_unix"]
        self.assertEqual((r1, r2, start % 60), (start + 120, start + 300, 0))
        self.assertLessEqual(abs(start - self.panel.start_time), 60)
        pr = self.progs(root, "syn1.test")
        starts = {a: (b, t) for a, b, t in pr}
        self.assertEqual(starts[r1][0], r2)
        self.assertEqual(starts[r2][0], r2 + 1800)
        # contiguous, non overlapping, whole minutes, durations from the allowed set (r1 truncation excepted)
        for (a, b, _), (a2, _, _) in zip(pr, pr[1:]):
            self.assertEqual(b, a2)
        allowed = {m * 60 for m in (5, 10, 30, 60, 120, 360)}
        bad = [(a, b) for a, b, _ in pr if b - a not in allowed]
        # the programme truncated at the +2 min instant, and the 3 min reminder programme itself
        self.assertEqual(sorted(b for _, b in bad), sorted([r1, r2]))
        for ch in ("syn1.test", "syn2.test", "syn50.test"):
            for a, b, _ in self.progs(root, ch):
                self.assertEqual((a % 60, b % 60), (0, 0))
        self.assertLessEqual(pr[0][0], syn["epg_window"][0])
        self.assertGreaterEqual(pr[-1][1], syn["epg_window"][1])
        titles = [t for ch in ("syn1.test", "syn2.test", "syn3.test", "syn4.test") for _, _, t in self.progs(root, ch)]
        self.assertTrue(any("News" in t for t in titles))

    def test_deterministic_and_ten_percent_long(self):
        b1, root = self.xml()
        b2, _ = self.xml()
        self.assertEqual(b1, b2)
        allt = [p.find("title").text for p in root.iter("programme")]
        share = sum(len(t) > 60 for t in allt) / len(allt)
        self.assertTrue(0.05 < share < 0.15, share)

    def test_epg_version_changes_titles(self):
        _, root = self.xml()
        t0 = {p.get("start") + p.get("channel"): p.find("title").text for p in root.iter("programme")}
        self.admin({"epg_version": 1})
        _, root = self.xml()
        t1 = {p.get("start") + p.get("channel"): p.find("title").text for p in root.iter("programme")}
        self.assertEqual(t0.keys(), t1.keys())
        self.assertTrue(all(t1[k] == t0[k] + " v2" for k in t0))

    def test_player_api_epg_and_m3u(self):
        j = self.api("get_short_epg", "&stream_id=1&limit=3")
        self.assertEqual(len(j["epg_listings"]), 3)
        self.assertEqual(self.api("get_simple_data_table", "&stream_id=9")["epg_listings"], [])  # id 9: no EPG share
        st, _, m3u = self.get("/get.php?username=test&password=test&type=m3u_plus&output=ts")
        text = m3u.decode()
        self.assertEqual(text.count("#EXTINF"), 400)
        self.assertIn("/live/test/test/400.ts", text)
        self.assertIn('group-title="News 1"', text)
        self.assertIn('catchup="xc" catchup-days="7"', text)

    def test_streams_and_unknown(self):
        st, data = self.read_stream("/live/test/test/399.ts")
        self.assertEqual((st, data[:1]), (200, b"\x47"))
        self.assertEqual(self.read_stream("/live/test/test/401.ts")[0], 404)
        recent = dt.datetime.fromtimestamp(time.time() - 7200, ZoneInfo("Asia/Tokyo")).strftime("%Y-%m-%d:%H-%M")
        st, data = self.read_stream(f"/timeshift/test/test/60/{recent}/1.ts")
        self.assertEqual((st, data[:1]), (200, b"\x47"))
        # M4 timeshift_window (default on): a start older than channel 1's 7-day archive is 404
        self.assertEqual(self.read_stream("/timeshift/test/test/60/2020-01-01:10-00/1.ts")[0], 404)
        st, _, pl = self.get("/live/test/test/250.m3u8")
        self.assertEqual(st, 200)
        self.assertIn(b"250_seg", pl)

    def test_unknown_key_and_validation(self):
        for patch in ({"nope": 1}, {"synthetic": 20001}, {"epg_version": "x"}):
            req = urllib.request.Request(f"http://127.0.0.1:{self.port}/admin/config", json.dumps(patch).encode(), method="POST")
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(req, timeout=5)
            self.assertEqual(cm.exception.code, 400)

    def test_gzip_streamed(self):
        self.admin({"epg_gzip": True})
        st, h, body = self.get("/xmltv.php?username=test&password=test")
        self.assertEqual(st, 200)
        self.assertTrue(gzip.decompress(body).startswith(b"<?xml"))


class TestSyntheticLarge(PanelCase):
    overrides = {"synthetic": 2000}

    def test_xmltv_streams_fast(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=60)
        t0 = time.time()
        c.request("GET", "/xmltv.php?username=test&password=test")
        r = c.getresponse()
        self.assertIsNone(r.getheader("Content-Length"))  # streamed, not buffered
        total, first = 0, None
        while True:
            d = r.read(65536)
            if not d:
                break
            first = first or d[:5]
            total += len(d)
        c.close()
        self.assertEqual(first, b"<?xml")
        self.assertGreater(total, 5_000_000)
        self.assertLess(time.time() - t0, 30)
        self.assertEqual(len(self.api("get_live_streams")), 2000)
        self.assertEqual(len(self.api("get_live_categories")), 40)


# ------------------------------------------------------------------ M4 additions (docs/design/m4-player-ux.md §5.6)

def astats(case):
    return json.loads(case.get("/admin/stats")[2])


def post(case, path, body):
    req = urllib.request.Request(f"http://127.0.0.1:{case.port}{path}", json.dumps(body).encode(), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def xc_start(ts, zone="Asia/Tokyo", secs=False):
    return dt.datetime.fromtimestamp(ts, ZoneInfo(zone)).strftime("%Y-%m-%d:%H-%M-%S" if secs else "%Y-%m-%d:%H-%M")


def full_get(port, path, headers=None, timeout=30):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    c.request("GET", path, headers=headers or {})
    r = c.getresponse()
    try:
        body = r.read()
    except http.client.IncompleteRead as e:  # the server cut the body (token_kill)
        body = e.partial
    hdrs = {k.lower(): v for k, v in r.getheaders()}
    c.close()
    return r.status, hdrs, body


class LoopCase(SynthCase):
    """The synthetic 40-frame clip with its true duration (1.6 s), so `duration` mode sizes match its timestamps."""
    overrides = {"pace": False, "timeshift_mode": "duration"}

    @classmethod
    def setUpClass(cls):
        PanelCase.setUpClass.__func__(cls)
        cls.saved = dict(cls.panel.clips)
        cls.src = synth_clip(cls.base)
        clip = fakepanel.Clip(cls.src, FRAMES * FRAME / 90000, [(cls.src[:len(cls.src) // 2], 0.8), (cls.src[len(cls.src) // 2:], 0.8)])
        for k in cls.panel.clips:
            cls.panel.clips[k] = clip
        cls.clip = clip

    def path(self, minutes, back=3600, ch=1):
        return f"/timeshift/test/test/{minutes}/{xc_start(time.time() - back)}/{ch}.ts"


class TestTimeshiftDuration(LoopCase):
    """§5.6 item 1: body sized to `duration`, continuous PCR/PTS/CC across loops, exact Content-Length, Range/206."""

    def expected_len(self, secs):
        return int(secs * self.clip.rate) // 188 * 188

    def test_length_follows_duration_units(self):
        st, h, body = full_get(self.port, self.path(1))
        self.assertEqual(st, 200)
        self.assertEqual(int(h["content-length"]), self.expected_len(60))
        self.assertEqual(len(body), self.expected_len(60))
        self.assertEqual(h.get("accept-ranges"), "bytes")
        php = f"/streaming/timeshift.php?username=test&password=test&stream=1&start={xc_start(time.time() - 3600)}&duration=2"
        st, h, _ = full_get(self.port, php)
        self.assertEqual(int(h["content-length"]), self.expected_len(120))  # php duration is minutes too
        flus = f"/live/test/test/4/archive-{int(time.time()) - 3600}-90.ts"
        st, h, _ = full_get(self.port, flus)
        self.assertEqual((st, int(h["content-length"])), (200, self.expected_len(90)))  # Flussonic DURATION is seconds
        # a 30-minute request is a 30-minute file (not materialised: HEAD-like ranged read of the tail)
        st, h, tail = full_get(self.port, self.path(30), {"Range": "bytes=-1880"})
        self.assertEqual(st, 206)
        self.assertEqual(h["content-range"], f"bytes {self.expected_len(1800) - 1880}-{self.expected_len(1800) - 1}/{self.expected_len(1800)}")

    def test_timestamps_and_cc_continuous_and_true_duration(self):
        st, _, body = full_get(self.port, self.path(1))
        pcrs, pts, dts, ccs = scan(body)
        for a, b in zip(pcrs, pcrs[1:]):
            self.assertEqual((b[0] - a[0]) & MASK33, FRAME)
        for series, step in ((pts[VPID], FRAME), (dts[VPID], FRAME), (pts[APID], FRAME // 2)):
            for a, b in zip(series, series[1:]):
                self.assertEqual((b - a) & MASK33, step)
        for pid in (VPID, APID):
            exp = None
            for cc, payload in ccs[pid]:
                if exp is not None:
                    self.assertEqual(cc, exp if payload else (exp - 1) & 15)
                if payload:
                    exp = (cc + 1) & 15
        # TsDurationReader's view: last PCR - first PCR ~= the requested 60 s (to within one clip loop)
        span = ((pcrs[-1][0] - pcrs[0][0]) & MASK33) / 90000
        self.assertAlmostEqual(span, 60, delta=FRAMES * FRAME / 90000)

    def test_ranged_reads_equal_the_full_body(self):
        _, _, body = full_get(self.port, self.path(1))
        for a, b in ((0, 187), (1000, 50000), (len(body) - 5000, len(body) - 1), (len(self.src) * 7 + 13, len(self.src) * 9)):
            st, h, part = full_get(self.port, self.path(1), {"Range": f"bytes={a}-{b}"})
            self.assertEqual(st, 206)
            self.assertEqual(part, body[a:b + 1], (a, b))
        st, h, _ = full_get(self.port, self.path(1), {"Range": f"bytes={len(body)}-"})
        self.assertEqual(st, 416)

    def test_looped_ts_matches_live_rewriter(self):
        """Random access (emit_loop) produces exactly the bytes the sequential live rewriter (TsState) would."""
        n = len(self.src)
        st = fakepanel.TsState()
        seq = b""
        for _ in range(3):
            seq += bytes(st.emit(self.clip.plan, self.src, 0, n))
            st.off = (st.off + self.clip.plan.dur90k) % fakepanel.MOD33
        lt = fakepanel.LoopedTs(self.clip, 3 * n)
        self.assertEqual(lt.slice(0, 3 * n), seq)
        self.assertEqual(lt.slice(n + 100, 2 * n + 7), seq[n + 100:2 * n + 7])

    def test_range_ignored_mode(self):
        self.admin({"timeshift_range_ignored": True})
        st, h, body = full_get(self.port, self.path(1), {"Range": "bytes=5000-"})
        self.assertEqual(st, 200)
        self.assertEqual(int(h["content-length"]), self.expected_len(60))
        self.assertNotIn("accept-ranges", h)
        self.assertEqual(body[:188], full_get(self.port, self.path(1))[2][:188])

    def test_clip3_default_unchanged(self):
        self.admin({"timeshift_mode": "clip3"})
        st, h, body = full_get(self.port, self.path(30))
        self.assertEqual((st, int(h["content-length"]), body), (200, 3 * len(self.src), self.src * 3))

    def test_mode_validation(self):
        self.assertEqual(post(self, "/admin/config", {"timeshift_mode": "minutes"})[0], 400)


class TestTimeshiftFutureAndLength(LoopCase):
    """U-S9 (code review 2026-10-16): duration mode serves only what is written (`timeshift_future: clamp`, default),
    the whole duration with `serve`, and a close-delimited body without Content-Length with `timeshift_chunked`."""

    def expected_len(self, secs):
        return int(secs * self.clip.rate) // 188 * 188

    def path_sec(self, minutes, back, ch=1):
        return f"/timeshift/test/test/{minutes}/{xc_start(time.time() - back, secs=True)}/{ch}.ts"

    def test_clamp_is_the_default_and_ends_at_the_panels_now(self):
        self.assertEqual(self.panel.cfg["timeshift_future"], "clamp")
        st, h, body = full_get(self.port, self.path_sec(2, back=30))
        self.assertEqual(st, 200)
        n = int(h["content-length"])
        self.assertEqual(len(body), n)
        self.assertLess(n, self.expected_len(60), "2 minutes requested 30 s back: about 30 s are written")
        self.assertTrue(self.expected_len(27) <= n <= self.expected_len(33), (n, self.expected_len(30)))

    def test_clamped_length_is_stable_across_reopens(self):
        path = self.path_sec(2, back=30)
        _, h1, _ = full_get(self.port, path)
        time.sleep(1.2)
        st, h2, _ = full_get(self.port, path, {"Range": "bytes=0-187"})
        self.assertEqual(st, 206)
        self.assertEqual(h2["content-range"].split("/")[1], h1["content-length"], "one length for every reopen of the request")

    def test_clamp_honours_the_archive_lag(self):
        self.admin({"archive_lag_sec": 20, "timeshift_window": False})
        st, h, _ = full_get(self.port, self.path_sec(2, back=50))
        self.assertEqual(st, 200)
        self.assertTrue(self.expected_len(27) <= int(h["content-length"]) <= self.expected_len(33))

    def test_old_starts_are_not_clamped(self):
        st, h, _ = full_get(self.port, self.path(1))  # an hour back, 1 minute
        self.assertEqual(int(h["content-length"]), self.expected_len(60))

    def test_serve_sends_the_whole_duration(self):
        self.admin({"timeshift_future": "serve"})
        st, h, body = full_get(self.port, self.path_sec(2, back=30))
        self.assertEqual((st, int(h["content-length"]), len(body)), (200, self.expected_len(120), self.expected_len(120)))

    def test_chunked_has_no_length_and_no_ranges(self):
        self.admin({"timeshift_chunked": True})
        st, h, body = full_get(self.port, self.path(1))
        self.assertEqual(st, 200)
        self.assertNotIn("content-length", h)
        self.assertNotIn("accept-ranges", h)
        self.assertEqual(len(body), self.expected_len(60))
        st, h, part = full_get(self.port, self.path(1), {"Range": "bytes=5000-"})
        self.assertEqual(st, 200, "a Range is ignored: no seeking inside a length-less body")
        self.assertEqual(part[:188], body[:188])

    def test_validation(self):
        self.assertEqual(post(self, "/admin/config", {"timeshift_future": "later"})[0], 400)
        self.assertEqual(post(self, "/admin/config", {"timeshift_chunked": "yes"})[0], 400)


class TestLiveShiftedAndEvent(LoopCase):
    """§5.6 items 1 and 7: open-ended forms are length-less live-paced streams; -now/timeshift_abs/rel .m3u8 are EVENT."""

    def head_and_some(self, path, n=188 * 400):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", path)
        r = c.getresponse()
        data = r.read(n) if r.status == 200 else r.read()
        hdrs = {k.lower(): v for k, v in r.getheaders()}
        c.close()
        return r.status, hdrs, data

    def test_open_ts_forms_have_no_length(self):
        s = int(time.time()) - 600
        for path in (f"/live/test/test/1/timeshift_abs-{s}.ts", f"/live/test/test/1/index-timeshift_abs-{s}.ts",
                     f"/live/test/test/3.ts?utc={s}&lutc={int(time.time())}",
                     f"/live/test/test/4/archive-{s}-now.ts"):
            st, h, data = self.head_and_some(path)
            self.assertEqual(st, 200, path)
            self.assertNotIn("content-length", h, path)
            self.assertEqual(data[0], 0x47)
            pcrs = scan(data[:len(data) // 188 * 188])[0]
            for a, b in zip(pcrs, pcrs[1:]):
                self.assertEqual((b[0] - a[0]) & MASK33, FRAME)
        forms = [e["form"] for e in astats(self)["timeshift_requests"]]
        self.assertEqual(forms, ["timeshift_abs", "timeshift_abs", "shift", "flussonic_now"])

    def test_clip3_keeps_finite_shift(self):
        self.admin({"timeshift_mode": "clip3"})
        st, h, _ = full_get(self.port, f"/live/test/test/3.ts?utc={int(time.time()) - 600}&lutc={int(time.time())}")
        self.assertEqual((st, int(h["content-length"])), (200, 3 * len(self.src)))
        # the new Flussonic routes are live-like in every mode
        st, h, _ = self.head_and_some(f"/live/test/test/1/timeshift_abs-{int(time.time()) - 600}.ts")
        self.assertNotIn("content-length", h)

    def test_event_playlists_grow_and_never_end(self):
        s = int(time.time()) - 60
        for path in (f"/live/test/test/4/index-{s}-now.m3u8", f"/live/test/test/1/timeshift_abs-{s}.m3u8",
                     "/live/test/test/1/timeshift_rel-60.m3u8", "/live/test/test/1/mono-timeshift_rel-60.m3u8"):
            st, _, body = full_get(self.port, path)
            text = body.decode()
            self.assertEqual(st, 200, path)
            self.assertIn("#EXT-X-PLAYLIST-TYPE:EVENT", text)
            self.assertNotIn("#EXT-X-ENDLIST", text)
            segs = [l for l in text.splitlines() if l.startswith("/tseg/")]
            self.assertAlmostEqual(len(segs), 60 / 0.8, delta=3, msg=path)
        st, _, body = full_get(self.port, "/live/test/test/1/timeshift_rel-60.m3u8")
        first = len([l for l in body.decode().splitlines() if l.startswith("/tseg/")])
        time.sleep(1.7)
        st, _, body = full_get(self.port, "/live/test/test/1/timeshift_rel-60.m3u8")
        self.assertGreater(len([l for l in body.decode().splitlines() if l.startswith("/tseg/")]), first)  # anchored, grows

    def test_vod_duration_playlist_and_continuous_segments(self):
        st, _, body = full_get(self.port, f"/live/test/test/4/archive-{int(time.time()) - 3600}-40.m3u8")
        text = body.decode()
        self.assertIn("#EXT-X-PLAYLIST-TYPE:VOD", text)
        self.assertIn("#EXT-X-ENDLIST", text)
        segs = [l for l in text.splitlines() if l.startswith("/tseg/")]
        self.assertEqual(len(segs), 50)  # 40 s of 0.8 s segments
        a = scan(full_get(self.port, "/tseg/4_0.ts")[2])[1][VPID][0]
        b = scan(full_get(self.port, "/tseg/4_2.ts")[2])[1][VPID][0]  # segment 0 of the next loop
        self.assertEqual((b - a) & MASK33, FRAMES * FRAME)

    def test_clip3_now_m3u8_stays_vod(self):
        self.admin({"timeshift_mode": "clip3"})
        st, _, body = full_get(self.port, f"/live/test/test/4/index-{int(time.time()) - 60}-now.m3u8")
        self.assertIn("#EXT-X-ENDLIST", body.decode())


class TestArchiveWindowAndLag(PanelCase):
    """§5.6 items 2-3: out-of-archive is 404 by default (not only under strict_timeshift_tz); recent-archive lag."""

    def test_window(self):
        now = time.time()
        self.assertEqual(self.get(f"/timeshift/test/test/60/{xc_start(now - 3600)}/1.ts")[0], 200)
        for start, ch in ((now - 4 * 86400, 1), (now + 3600, 1), (now - 3600, 5)):  # catalog: 3 days; ch5 has no archive
            self.assertEqual(self.get(f"/timeshift/test/test/60/{xc_start(start)}/{ch}.ts")[0], 404, (start - now, ch))
        self.assertEqual(astats(self)["rejections"].get("timeshift_window"), 3)
        self.assertEqual(self.get(f"/live/test/test/3.ts?utc={int(now - 4 * 86400)}")[0], 404)
        self.admin({"timeshift_window": False})
        self.assertEqual(self.get(f"/timeshift/test/test/60/{xc_start(now - 4 * 86400)}/1.ts")[0], 200)

    def test_lag(self):
        self.admin({"archive_lag_sec": 120})
        now = time.time()
        self.assertEqual(self.get(f"/timeshift/test/test/1/{xc_start(now - 60)}/1.ts")[0], 404)
        self.assertEqual(self.get(f"/timeshift/test/test/1/{xc_start(now - 300)}/1.ts")[0], 200)
        self.assertEqual(astats(self)["rejections"].get("archive_lag"), 1)
        self.assertEqual(post(self, "/admin/config", {"archive_lag_sec": -1})[0], 400)


class TestRejectForms(PanelCase):
    """§5.6 item 4: per-form (and per-channel) rejection with a configurable status."""

    def urls(self, ch=1, back=3600):
        s = time.time() - back
        return {"path": f"/timeshift/test/test/60/{xc_start(s)}/{ch}.ts",
                "path_sec": f"/timeshift/test/test/60/{xc_start(s, secs=True)}/{ch}.ts",
                "php": f"/streaming/timeshift.php?username=test&password=test&stream={ch}&start={xc_start(s)}&duration=60"}

    def test_global_statuses(self):
        u = self.urls()
        self.admin({"timeshift_reject_forms": {"xc-path": 404, "php": 400}})
        self.assertEqual(self.get(u["path"])[0], 404)
        self.assertEqual(self.get(u["php"])[0], 400)
        self.assertEqual(self.get(u["path_sec"])[0], 200)
        self.admin({"timeshift_reject_forms": {"path": "200-html", "php": "200-empty"}})
        st, h, body = self.get(u["path"])
        self.assertEqual((st, h["Content-Type"]), (200, "text/html"))
        st, h, body = self.get(u["php"])
        self.assertEqual((st, body), (200, b""))
        log = astats(self)["timeshift_requests"]
        self.assertEqual([e["form"] for e in log], ["path", "php", "path_sec", "path", "php"])
        self.assertEqual([e["status"] for e in log], [404, 400, 200, 200, 200])

    def test_per_channel_fault_overrides(self):
        self.admin({"stream_faults": {"1": {"timeshift_reject_forms": {"path": 403}}}})
        self.assertEqual(self.get(self.urls(1)["path"])[0], 403)
        self.assertEqual(self.get(self.urls(7)["path"])[0], 200)

    def test_validation(self):
        for bad in ({"timeshift_reject_forms": {"nope": 404}}, {"timeshift_reject_forms": {"path": "500-html"}},
                    {"stream_faults": {"1": {"timeshift_reject_forms": {"path": True}}}}, {"timeshift_reject_forms": []}):
            self.assertEqual(post(self, "/admin/config", bad)[0], 400, bad)


class TestTimeshiftLogAndFaults(LoopCase):
    """/admin/stats.timeshift_requests; §5.6 item 5 (ranged-open faults, linger) and item 8 (timeshift tokens)."""

    def test_request_log_fields(self):
        s = time.time() - 3600
        full_get(self.port, f"/timeshift/test/test/2/{xc_start(s)}/1.ts", {"Range": "bytes=376-"})
        e = astats(self)["timeshift_requests"][-1]
        self.assertEqual((e["form"], e["channel"], e["duration_s"], e["range"], e["status"], e["ext"]),
                         ("path", 1, 120, "bytes=376-", 206, "ts"))
        self.assertAlmostEqual(e["start_unix"], int(s) // 60 * 60, delta=1)
        for _ in range(55):
            self.get(f"/timeshift/test/test/1/{xc_start(s)}/1.ts", {"Range": "bytes=0-187"})
        st = astats(self)
        self.assertEqual(len(st["timeshift_requests"]), 50)
        self.assertEqual(st["timeshift_request_count"], 56)

    def test_ranged_reopen_rejects(self):
        p = self.path(1)
        for val, code in ((True, 458), (509, 509)):
            self.admin({"faults": {"timeshift_range_rejects": val}})
            self.assertEqual(full_get(self.port, p)[0], 200)
            self.assertEqual(full_get(self.port, p, {"Range": "bytes=0-"})[0], 206)
            self.assertEqual(full_get(self.port, p, {"Range": "bytes=10000-"})[0], code)
        self.assertEqual(astats(self)["rejections"]["timeshift_range_rejects"], 2)

    def test_linger_refuses_the_timeshift_reopen(self):
        self.admin({"max_connections": 1, "faults": {"linger": 5, "linger_status": 458}})
        p = self.path(1)
        self.assertEqual(full_get(self.port, p)[0], 200)
        self.assertEqual(full_get(self.port, p, {"Range": "bytes=-1880"})[0], 458)  # the tail read of a prepare

    def test_timeshift_redirect_keeps_query_and_token_kill(self):
        self.admin({"faults": {"timeshift_redirect": True, "token_ttl": 1, "token_kill": True, "slow": 20000}})
        self.assertEqual(self.read_stream("/live/test/test/1.ts")[0], 200)  # live is not redirected
        php = f"/streaming/timeshift.php?username=test&password=test&stream=1&start={xc_start(time.time() - 3600)}&duration=1"
        st, h, _ = full_get(self.port, php)
        self.assertEqual(st, 302)
        loc = h["location"].split(str(self.panel.lb_port), 1)[1]
        self.assertIn("stream=1", loc)
        self.assertIn("token=", loc)
        t0 = time.time()
        st, h, body = full_get(self.panel.lb_port, loc)
        self.assertEqual(st, 200)
        self.assertLess(len(body), int(h["content-length"]))  # cut when the 1 s token expired
        self.assertLess(time.time() - t0, 4)
        self.assertGreaterEqual(astats(self)["token_expired"], 1)
        # an archive playlist served by the redirect port: its absolute segment paths are served there too
        self.admin({"faults": {"token_ttl": 30, "token_kill": None, "slow": None}})
        st, h, _ = full_get(self.port, f"/live/test/test/4/archive-{int(time.time()) - 600}-20.m3u8")
        loc = h["location"].split(str(self.panel.lb_port), 1)[1]
        st, _, body = full_get(self.panel.lb_port, loc)
        seg = [l for l in body.decode().splitlines() if l.startswith("/tseg/")][0]
        self.assertEqual(full_get(self.panel.lb_port, seg)[0], 200)


class TestPlayerFixtures(PanelCase):
    """§5.6 items 9-11: correction channels, the short-programme channel, multi channels; timeshift_expect helper."""
    overrides = {"synthetic": 50, "synthetic_days_back": 1, "synthetic_days_ahead": 1, "player_fixtures": True,
                 "timezone": "Europe/Berlin"}

    def test_m3u_and_api(self):
        text = self.get("/get.php?username=test&password=test&type=m3u_plus&output=ts")[2].decode()
        lines = text.splitlines()

        def extinf(i):
            return next(l for l in lines if f'tvg-logo="http://127.0.0.1:{self.port}/logo/{i}.png"' in l)

        self.assertIn('catchup="xc" catchup-days="1" catchup-correction="+2"', extinf(3))
        self.assertIn('catchup-correction="-1.5"', extinf(4))
        self.assertIn('catchup="xc" catchup-days="1"', extinf(2))
        self.assertNotIn("catchup-correction", extinf(2))
        self.assertIn('catchup="xc" catchup-days="1"', extinf(7))
        self.assertIn("/live/test/test/8.m3u8", text)
        self.assertIn("/live/test/test/9.ts", text)
        live = {s["stream_id"]: s for s in self.api("get_live_streams")}
        self.assertEqual([live[i]["tv_archive"] for i in (2, 3, 4, 7, 8)], [1, 1, 1, 1, 0])
        self.assertEqual(astats(self)["synthetic"]["player_fixtures"],
                         {"short": 2, "plus2": 3, "minus15": 4, "multi_ts": 7, "multi_hls": 8})

    def test_short_programme_channel(self):
        syn = astats(self)["synthetic"]
        sf, st = syn["short_window"]
        rows = self.api("get_simple_data_table", "&stream_id=2")["epg_listings"]
        spans = [(int(r["start_timestamp"]), int(r["stop_timestamp"])) for r in rows]
        for a, b in zip(spans, spans[1:]):
            self.assertEqual(a[1], b[0])  # contiguous
        minutes = [s for s in spans if s[1] - s[0] == 60 and sf <= s[0] < st]
        self.assertEqual(len(minutes), (st - sf) // 60)
        now = time.time()
        self.assertTrue(any(a <= now < b and b - a == 60 for a, b in spans))
        past = [r for r in rows if int(r["stop_timestamp"]) <= now and int(r["start_timestamp"]) >= sf]
        self.assertTrue(past and all(r["has_archive"] == 1 for r in past))
        self.admin({"player_fixtures": False})
        rows = self.api("get_simple_data_table", "&stream_id=2")["epg_listings"]
        self.assertFalse(any(int(r["stop_timestamp"]) - int(r["start_timestamp"]) == 60 for r in rows))

    def test_xmltv_contains_minute_programmes(self):
        body = self.get("/xmltv.php?username=test&password=test")[2].decode()
        self.assertIn("Minute Programme", body)
        self.assertGreater(body.count('channel="syn2.test"><title lang="en">Minute Programme'), 400)

    def test_timeshift_expect_helper_and_now(self):
        now = json.loads(self.get("/admin/now")[2])
        self.assertAlmostEqual(now["unix"], time.time(), delta=2)
        self.assertEqual(now["timezone"], "Europe/Berlin")
        start = int(time.time()) // 60 * 60 - 1800
        code, r = post(self, "/admin/timeshift_expect", {"start_unix": start, "channel": 3, "padding_before": 60, "strict": True})
        self.assertEqual(code, 200)
        self.assertEqual(r["expect_unix"], start - 7200 - 60)
        self.assertEqual(r["xc_start"], xc_start(start - 7260, "Europe/Berlin"))
        cfg = json.loads(self.get("/admin/config")[2])
        self.assertEqual((cfg["timeshift_expect"], cfg["strict_timeshift_tz"]), ([start - 7260], True))
        # a correct client's request for channel 3 passes, the uncorrected one is 404 (strict)
        self.assertEqual(self.get(f"/timeshift/test/test/30/{r['xc_start']}/3.ts")[0], 200)
        self.assertEqual(self.get(f"/timeshift/test/test/30/{xc_start(start - 60, 'Europe/Berlin')}/3.ts")[0], 404)
        code, r = post(self, "/admin/timeshift_expect", {"start_unix": start, "channel": 4, "apply_correction": False})
        self.assertEqual(r["expect_unix"], start)
        code, r = post(self, "/admin/timeshift_expect", {"start_unix": start, "channel": 4})
        self.assertEqual(r["expect_unix"], start + 5400)
        self.assertEqual(post(self, "/admin/timeshift_expect", {"channel": 3})[0], 400)


class TestMultiHls(PanelCase):
    """§5.6 item 10 (live HLS side): channel 8's master playlist with a WebVTT SUBTITLES rendition."""
    overrides = {"synthetic": 20, "player_fixtures": True}

    def test_master_media_subs(self):
        st, h, body = self.get("/live/test/test/8.m3u8")
        text = body.decode()
        self.assertEqual(st, 200)
        self.assertIn('#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en"', text)
        self.assertIn('SUBTITLES="subs"', text)
        self.assertIn("8_media.m3u8", text)
        media = self.get("/live/test/test/8_media.m3u8")[2].decode()
        subs = self.get("/live/test/test/8_subs.m3u8")[2].decode()
        msegs = [l for l in media.splitlines() if l.startswith("8_tseg")]
        vsegs = [l for l in subs.splitlines() if l.startswith("8_vtt")]
        self.assertEqual([m.split("tseg")[1].split(".")[0] for m in msegs], [v.split("vtt")[1].split(".")[0] for v in vsegs])
        self.assertEqual(self.get("/live/test/test/" + msegs[-1])[0], 200)
        vtts = [self.get("/live/test/test/" + v)[2].decode() for v in vsegs]
        self.assertTrue(all(v.startswith("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:") for v in vtts))
        self.assertTrue(any("Subtitle" in v for v in vtts))
        self.assertEqual(astats(self)["range_requests_seen"], 0)

    def test_other_channels_unchanged(self):
        self.assertIn("1_seg", self.get("/live/test/test/1.m3u8")[2].decode())

    def test_srt_fixture_parses(self):
        cues = fakepanel.load_srt(os.path.join(os.path.dirname(fakepanel.__file__), "multi_subs.srt"))
        self.assertEqual(len(cues), 7)
        self.assertTrue(all(0 <= a < b <= 10 for a, b, _ in cues))


class TestCea608(unittest.TestCase):
    """The SEI writer behind the multi clip's CEA-608 track (pure functions)."""

    def test_parity_and_schedule(self):
        pairs = fakepanel.cea608_pairs(120)
        self.assertEqual(len(pairs), 120)
        for a, b in pairs:
            self.assertEqual(bin(a).count("1") % 2, 1)
            self.assertEqual(bin(b).count("1") % 2, 1)
        self.assertEqual((pairs[0][0] & 0x7F, pairs[0][1] & 0x7F), (0x14, 0x25))  # RU2
        self.assertEqual((pairs[2][0] & 0x7F, pairs[2][1] & 0x7F), (0x14, 0x2D))  # CR
        text = "".join(chr(a & 0x7F) + chr(b & 0x7F) for a, b in pairs[4:10])
        self.assertTrue(text.startswith("CC1 line 1"))
        self.assertEqual((pairs[50][0] & 0x7F, pairs[50][1] & 0x7F), (0x14, 0x25))

    def test_epb(self):
        self.assertEqual(fakepanel._epb(b"\x00\x00\x01\x00\x00\x03\x00\x00\x04"), b"\x00\x00\x03\x01\x00\x00\x03\x03\x00\x00\x04")

    def test_injection_before_first_slice(self):
        aud, sps, slc = b"\x09\xf0", b"\x67\x42\x00", b"\x65\x88\x80"
        au = b"".join(b"\x00\x00\x00\x01" + n for n in (aud, sps, slc, b"\x01\x9a"))
        out, frames = fakepanel.inject_cea608(au * 3)
        self.assertEqual(frames, 3)
        types = [n[0] & 0x1F for n in fakepanel.split_annexb(out)]
        self.assertEqual(types, [9, 7, 6, 5, 1] * 3)
        sei = [n for n in fakepanel.split_annexb(out) if n[0] & 0x1F == 6][0]
        self.assertEqual(sei[1], 4)  # user_data_registered_itu_t_t35
        self.assertIn(b"\xb5\x00\x31GA94\x03", sei)

    @unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not on PATH")
    def test_real_multi_clip(self):
        tmp = tempfile.mkdtemp(prefix="fakepanel-multi-")
        self.addCleanup(shutil.rmtree, tmp, True)
        path = os.path.join(tmp, "h264_multi_25.ts")
        r = fakepanel.build_multi(shutil.which("ffmpeg"), tmp, path, lambda m: None)
        self.assertEqual(r.returncode, 0, r.stderr[-300:])
        data = fakepanel._read(path)
        streams = parse_psi(data[:188 * 2000])
        self.assertEqual(sorted(streams.values()), [0x03, 0x0F, 0x1B])  # MP2 audio, AAC, H.264
        langs = [l for l in (b"eng", b"deu") if l in data[:188 * 50]]
        self.assertEqual(langs, [b"eng", b"deu"])  # ISO-639 descriptors in the PMT
        if shutil.which("ffprobe"):
            import subprocess
            out = subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"movie={path}[out0+subcc]", "-map", "0:s",
                                  "-f", "srt", "-"], capture_output=True, timeout=120).stdout.decode()
            self.assertIn("CC1 line 1", out)
            self.assertIn("CC1 line 4", out)  # line 5 is still on screen when the clip ends


if __name__ == "__main__":
    unittest.main()
