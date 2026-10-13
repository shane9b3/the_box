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


if __name__ == "__main__":
    unittest.main()
