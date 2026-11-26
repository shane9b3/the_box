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
        # The real master's shape: its own English rendition, DEFAULT=NO, no closed captions.
        media = next(l for l in master.decode().splitlines() if l.startswith("#EXT-X-MEDIA:TYPE=SUBTITLES"))
        self.assertIn('LANGUAGE="en"', media)
        self.assertIn("DEFAULT=NO", media)
        self.assertIn("CLOSED-CAPTIONS=NONE", master.decode())
        subs = hls.rsplit("/", 1)[0] + "/" + media.split('URI="', 1)[1].rstrip('"')
        st, _, spl = self.raw(self.path_of(subs))
        self.assertEqual(200, st)
        self.assertEqual("#EXT-X-ENDLIST", spl.decode().splitlines()[-1])
        self.assertTrue(any(l.startswith("text.vtt?token=fake-") for l in spl.decode().splitlines()))
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



class EspnVideoCase(unittest.TestCase):
    """(NFL hub section 8.4) The ESPN clip records and HLS media (`handle_espn`), with `--sports --sports-nfl`."""

    @classmethod
    def setUpClass(cls):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False, "sports": True, "sports_nfl": True})
        cls.panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        cls.main, cls.lb = fakepanel.start_servers(cls.panel, "127.0.0.1", 0)
        cls.port = cls.panel.main_port

    @classmethod
    def tearDownClass(cls):
        fakepanel.stop_servers(cls.panel, cls.main, cls.lb)

    def setUp(self):
        self.panel.reset()
        self.panel.cfg["sports_nfl_clips"] = "espn"

    raw = VideoCase.raw
    path_of = VideoCase.path_of

    def summary_videos(self, eid="401899003"):
        st, _, body = self.raw("/apis/site/v2/sports/football/nfl/summary?event=" + eid)
        self.assertEqual(200, st)
        return json.loads(body)["videos"]

    def record(self, cid):
        st, _, body = self.raw("/espn-core/v1/video/clips/%d" % cid)
        return st, json.loads(body)

    def test_the_record_is_the_one_item_wrapper_with_the_summary_object(self):
        v = self.summary_videos()[1]
        st, rec = self.record(v["id"])
        self.assertEqual(200, st)
        self.assertEqual(1, rec["resultsCount"])
        self.assertEqual([v["id"]], [x["id"] for x in rec["videos"]])
        self.assertEqual(v["links"]["source"]["HLS"]["href"], rec["videos"][0]["links"]["source"]["HLS"]["href"])
        self.assertEqual("OnePlay", rec["videos"][0]["tracking"]["coverageType"])
        self.assertEqual(404, self.record(12345)[0])
        self.assertEqual(404, self.raw("/espn-core/v1/video/clips/abc")[0])

    def test_the_master_has_espns_shape_and_the_media_plays_out(self):
        hls = self.summary_videos()[0]["links"]["source"]["HLS"]["href"]
        st, h, master = self.raw(self.path_of(hls))
        self.assertEqual(200, st)
        text = master.decode()
        media = next(l for l in text.splitlines() if l.startswith("#EXT-X-MEDIA:TYPE=SUBTITLES"))
        self.assertIn("DEFAULT=YES", media)
        self.assertIn('LANGUAGE="en"', media)
        infs = [l for l in text.splitlines() if l.startswith("#EXT-X-STREAM-INF")]
        self.assertEqual(4, len(infs))
        self.assertEqual(1, sum("FRAME-RATE=59.940" in l for l in infs))
        self.assertIn("RESOLUTION=640x360", infs[0])
        self.assertNotIn("token", text, "ESPN media is unsigned")
        base = hls.rsplit("/", 1)[0] + "/"
        for var in ("v720", "v720p60"):
            st, _, pl = self.raw(self.path_of(base + var + ".m3u8"))
            lines = pl.decode().splitlines()
            self.assertEqual("#EXT-X-ENDLIST", lines[-1])
            seg = next(l for l in lines if "/seg_" in l)
            st, _, data = self.raw(self.path_of(base + seg))
            self.assertEqual(200, st)
            self.assertEqual(0x47, data[0])
        st, _, spl = self.raw(self.path_of(base + "subs.m3u8"))
        self.assertIn("text.vtt", spl.decode())
        st, _, vtt = self.raw(self.path_of(base + "text.vtt"))
        self.assertTrue(vtt.decode().startswith("WEBVTT"))
        st, h, png = self.raw(self.path_of(self.summary_videos()[0]["thumbnail"]))
        self.assertEqual((200, "image/png"), (st, h["content-type"]))
        self.assertEqual(b"\x89PNG", png[:4])

    def test_gone_lists_the_clips_but_the_master_403s_and_the_record_404s(self):
        # (NFL hub code review 2026-11-26, data #10) 403 is CLIP_EXPIRED to the player (404 is not): the fresh lookup path.
        self.panel.cfg["sports_nfl_clips"] = "gone"
        v = self.summary_videos()[0]
        self.assertEqual(403, self.raw(self.path_of(v["links"]["source"]["HLS"]["href"]))[0])
        self.assertEqual(404, self.record(v["id"])[0])

    def test_board_only_lists_the_highlight_on_the_board_alone_and_its_record_answers(self):
        # (NFL hub section 20, GB @ LAR 2026-11-26) The summary lists the single plays only; the board the highlight.
        self.panel.cfg["sports_nfl_clips"] = "board_only"
        v = self.summary_videos()
        self.assertEqual(["OnePlay"] * 3, [x["tracking"]["coverageType"] for x in v])
        st, _, body = self.raw("/apis/site/v2/sports/football/nfl/scoreboard")
        self.assertEqual(200, st)
        ev = next(e for e in json.loads(body)["events"] if e["id"] == "401899003")
        hl = ev["competitions"][0]["highlights"]
        self.assertEqual(["Final Game Highlight"], [x["tracking"]["coverageType"] for x in hl])
        cid = hl[0]["id"]
        self.assertEqual(sports_video.espn_clip_id("401899003", 0), cid)
        self.assertNotIn(cid, [x["id"] for x in v])
        st, rec = self.record(cid)
        self.assertEqual(200, st, "the record lookup (the app's catalog-miss fallback) finds it")
        self.assertEqual("Final Game Highlight", rec["videos"][0]["tracking"]["coverageType"])
        self.assertEqual(200, self.raw(self.path_of(hl[0]["links"]["source"]["HLS"]["href"]))[0], "and its media plays")
        self.panel.cfg["sports_nfl_clips"] = "espn"
        self.assertEqual("Final Game Highlight", self.summary_videos()[0]["tracking"]["coverageType"], "espn: in both")
        self.assertIn("board_only", fakepanel.NFL_CLIP_MODES)

    def test_slow_records_answer_late(self):
        self.panel.cfg["sports_nfl_clips"] = "slow_records"
        cid = self.summary_videos()[0]["id"]
        old = sports_video.ESPN_SLOW_RECORD_S
        sports_video.ESPN_SLOW_RECORD_S = 0.3
        try:
            t = time.time()
            self.assertEqual(200, self.record(cid)[0])
            self.assertGreaterEqual(time.time() - t, 0.3)
        finally:
            sports_video.ESPN_SLOW_RECORD_S = old

    def test_the_expired_game_and_the_modes(self):
        v = self.summary_videos("401898001")
        self.assertEqual(1, len(v))
        st, rec = self.record(v[0]["id"])
        self.assertEqual(200, st, "the record still answers; the app does not offer it")
        self.panel.cfg["sports_nfl_clips"] = "none"
        self.assertEqual([], self.summary_videos())
        self.assertEqual(404, self.record(sports_video.espn_clip_id("401899003", 0))[0])

    def test_routes_are_off_without_the_nfl(self):
        self.panel.cfg["sports_nfl"] = False
        try:
            self.assertEqual(404, self.raw("/espn-core/v1/video/clips/50990300")[0])
            self.assertEqual(404, self.raw("/espn-media/50990300/master.m3u8")[0])
        finally:
            self.panel.cfg["sports_nfl"] = True


if __name__ == "__main__":
    unittest.main()
