"""Self-tests for fakepanel's fake NHL video service (docs/design/m6-sports-hub.md section 10.3). Run from the repo root:

    python3 -I -m unittest discover -s tools/fakepanel -p 'test_sports_video.py'

Everything is fake: the keys are test keys and the media is the panel's own clip (the null-packet fallback here, since
the tests build without ffmpeg).
"""
import copy
import http.client
import json
import os
import re
import sys
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fakepanel  # noqa: E402
import sports_video  # noqa: E402

LOOKUP = "/bc/playback/v1/accounts/%s/videos/" % sports_video.ACCOUNT
# The app's scan (KeyScan): a quoted 20-400 character value of [A-Za-z0-9_\-.] after policyKey and a colon.
SCAN = re.compile(r"""(?<![A-Za-z0-9_$])["']?policyKey["']?\s*:\s*(["'])([A-Za-z0-9_\-.]{20,400})\1""")


class VideoCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False, "sports": True, "sports_video": True})
        cls.panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        cls.main, cls.lb = fakepanel.start_servers(cls.panel, "127.0.0.1", 0)
        cls.port = cls.panel.main_port

    @classmethod
    def tearDownClass(cls):
        fakepanel.stop_servers(cls.panel, cls.main, cls.lb)

    def setUp(self):
        self.panel.reset()
        self.panel.cfg["sports"] = True
        self.panel.cfg["sports_video"] = True

    # ---- http
    def raw(self, path, headers=None, method="GET", body=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request(method, path, body=body, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, {k.lower(): v for k, v in r.getheaders()}, data

    def mode(self, m):
        st, _, body = self.raw("/admin/sports_video", {"Content-Type": "application/json"}, "POST", json.dumps({"mode": m}))
        self.assertEqual(200, st, body)
        self.assertEqual(m, json.loads(body)["mode"])

    def key_from_script(self):
        st, h, body = self.raw("/bc/player/index.min.js")
        self.assertEqual(200, st)
        self.assertIn("javascript", h["content-type"])
        m = SCAN.search(body.decode())
        return m.group(2) if m else None

    def lookup(self, vid, key=None):
        headers = {"Accept": "application/json;pk=%s" % key} if key else {"Accept": "application/json"}
        st, _, body = self.raw(LOOKUP + str(vid), headers)
        return st, json.loads(body)

    def path_of(self, url):
        self.assertTrue(url.startswith("http://127.0.0.1:%d/" % self.port), url)
        return url[len("http://127.0.0.1:%d" % self.port):]

    # ---- tests
    def test_routes_are_off_without_the_switch(self):
        self.panel.cfg["sports_video"] = False
        self.assertEqual(404, self.raw("/bc/player/index.min.js")[0])

    def test_the_script_has_an_empty_default_before_the_fake_key(self):
        st, _, body = self.raw("/bc/player/index.min.js")
        text = body.decode()
        self.assertIn('policyKey:""', text)
        self.assertLess(text.index('policyKey:""'), text.index(sports_video.FAKE_KEY))
        self.assertEqual(sports_video.FAKE_KEY, self.key_from_script())
        self.assertTrue(sports_video.FAKE_KEY.startswith("FAKE-KEY-FOR-TESTS"))

    def test_a_lookup_needs_the_key_and_maps_like_the_real_service(self):
        st, body = self.lookup(6408791393112)
        self.assertEqual(401, st)
        self.assertEqual("INVALID_POLICY_KEY", body[0]["error_code"])
        st, v = self.lookup(6408791393112, self.key_from_script())
        self.assertEqual(200, st)
        self.assertEqual("Vilardi strikes first", v["name"])
        self.assertGreater(v["duration"], 0)
        kinds = [s.get("type") or s.get("container") for s in v["sources"]]
        self.assertEqual(["application/dash+xml", "application/x-mpegURL", "MP4"], kinds)
        caps = [t for t in v["text_tracks"] if t["kind"] == "captions" and t["srclang"] == "en"]
        self.assertEqual(1, len(caps))
        for s in v["sources"]:
            self.assertIn("?token=fake-", s["src"])
        self.assertEqual(404, self.lookup(1234404, sports_video.FAKE_KEY)[0])
        self.assertEqual(404, self.raw("/bc/playback/v1/accounts/1/videos/5", {"Accept": "application/json;pk=x"})[0])

    def test_the_hls_clip_and_its_captions_play_out(self):
        _, v = self.lookup(42, sports_video.FAKE_KEY)
        hls = next(s["src"] for s in v["sources"] if s.get("type") == "application/x-mpegURL")
        st, _, master = self.raw(self.path_of(hls))
        self.assertEqual(200, st)
        self.assertIn("#EXT-X-STREAM-INF", master.decode())
        variant = hls.rsplit("/", 1)[0] + "/" + master.decode().strip().splitlines()[-1]
        st, _, pl = self.raw(self.path_of(variant))
        lines = pl.decode().splitlines()
        self.assertIn("#EXT-X-PLAYLIST-TYPE:VOD", lines)
        self.assertEqual("#EXT-X-ENDLIST", lines[-1])
        seg = next(l for l in lines if l.startswith("seg_"))
        st, h, data = self.raw(self.path_of(hls.rsplit("/", 1)[0] + "/" + seg))
        self.assertEqual(200, st)
        self.assertEqual(0x47, data[0])
        vtt = next(t["src"] for t in v["text_tracks"] if t["kind"] == "captions")
        st, h, data = self.raw(self.path_of(vtt))
        self.assertEqual(200, st)
        self.assertTrue(data.decode().startswith("WEBVTT"))
        self.assertIn("00:00:00.000 --> 00:00:01.800", data.decode())
        self.assertEqual(403, self.raw(self.path_of(vtt.split("?")[0]))[0], "no token, no media")

    def test_the_mp4_answers_ranges(self):
        _, v = self.lookup(43, sports_video.FAKE_KEY)
        mp4 = self.path_of(next(s["src"] for s in v["sources"] if s.get("container") == "MP4"))
        st, h, full = self.raw(mp4)
        self.assertEqual(200, st)
        self.assertEqual("bytes", h["accept-ranges"])
        st, h, part = self.raw(mp4, {"Range": "bytes=100-199"})
        self.assertEqual(206, st)
        self.assertEqual(full[100:200], part)
        self.assertEqual("bytes 100-199/%d" % len(full), h["content-range"])
        self.assertEqual(416, self.raw(mp4, {"Range": "bytes=%d-" % (len(full) + 5)})[0])

    def test_rotate_refuses_the_old_key_and_the_script_serves_the_new_one(self):
        self.mode("rotate")
        self.assertEqual(401, self.lookup(1, sports_video.FAKE_KEY)[0])
        new = self.key_from_script()
        self.assertEqual(sports_video.ROTATED_KEY, new)
        self.assertEqual(200, self.lookup(1, new)[0])

    def test_refuse_geo_and_missing(self):
        self.mode("refuse")
        st, body = self.lookup(1, sports_video.FAKE_KEY)
        self.assertEqual(403, st)
        self.assertNotEqual("CLIENT_GEO", body[0].get("error_subcode"))
        self.mode("geo")
        st, body = self.lookup(1, sports_video.FAKE_KEY)
        self.assertEqual(403, st)
        self.assertEqual("CLIENT_GEO", body[0]["error_subcode"])
        self.mode("missing")
        self.assertIsNone(self.key_from_script(), "only the empty default and a placeholder")

    def test_slow_answers_after_the_configured_delay(self):
        self.panel.cfg["sports_video_slow_s"] = 0.3
        self.mode("slow")
        t = time.time()
        self.assertEqual(200, self.lookup(1, sports_video.FAKE_KEY)[0])
        self.assertGreaterEqual(time.time() - t, 0.3)

    def test_expire_serves_the_manifest_then_403s_the_segments(self):
        self.panel.cfg["sports_video_expire_s"] = 1
        self.mode("expire")
        _, v = self.lookup(44, sports_video.FAKE_KEY)
        hls = next(s["src"] for s in v["sources"] if s.get("type") == "application/x-mpegURL")
        base = hls.rsplit("/", 1)[0] + "/"
        tok = "?" + hls.split("?", 1)[1]
        self.assertEqual(200, self.raw(self.path_of(hls))[0])
        self.assertEqual(200, self.raw(self.path_of(base + "seg_000.ts" + tok))[0])
        time.sleep(1.2)
        self.assertEqual(200, self.raw(self.path_of(hls))[0], "the manifest is still served")
        self.assertEqual(403, self.raw(self.path_of(base + "seg_000.ts" + tok))[0])
        self.assertEqual(403, self.raw(self.path_of(base + "text.vtt" + tok))[0])
        self.assertGreaterEqual(self.panel.sports_video.stats()["expired"], 2)

    def test_admin_and_config_validation(self):
        st, _, body = self.raw("/admin/sports_video")
        self.assertEqual(200, st)
        self.assertTrue(json.loads(body)["enabled"])
        st, _, _ = self.raw("/admin/sports_video", {"Content-Type": "application/json"}, "POST", json.dumps({"mode": "nope"}))
        self.assertEqual(400, st)
        for bad in ({"sports_video_mode": "x"}, {"sports_video_expire_s": 0}, {"sports_video_slow_s": True}, {"sports_video": 1}):
            with self.assertRaises(ValueError):
                sports_video.check_config(bad)
        sports_video.check_config({"sports_video_expire_s": 5, "sports_video_slow_s": 0.5, "sports_video_mode": "expire"})

    def test_no_real_looking_key_anywhere_in_the_module(self):
        with open(os.path.join(HERE, "sports_video.py")) as f:
            src = f.read()
        for m in SCAN.finditer(src):
            self.assertTrue(m.group(2).startswith("FAKE-"), m.group(2))
        self.assertIsNone(re.search(r"BCpk[A-Za-z0-9_\-]{10,}", src))


if __name__ == "__main__":
    unittest.main()
