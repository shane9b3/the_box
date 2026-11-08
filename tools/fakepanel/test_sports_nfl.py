"""Self-tests for fakepanel's NFL routes (docs/design/m6-nfl.md section 9.3). Run from the repo root:

    python3 -I -m unittest discover -s tools/fakepanel -p 'test_sports*.py'
"""
import copy
import http.client
import json
import os
import struct
import sys
import unittest
import urllib.request
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fakepanel  # noqa: E402
import sports_nfl  # noqa: E402

BOARD = "/apis/site/v2/sports/football/nfl/scoreboard"
ANCHOR = 1_794_157_000.0  # 2026-11-08 16:56:40 UTC (a Sunday, 11:56 ET)


def window_start(label):
    """Seconds from the anchor to the start of the window named [label] at the default scale."""
    t = 0
    for w in sports_nfl.W:
        if w["label"] == label:
            return t
        t += w["len"]
    raise KeyError(label)


class NflCase(unittest.TestCase):
    overrides = {"sports": True, "sports_nfl": True}

    @classmethod
    def setUpClass(cls):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False})
        cfg.update(cls.overrides)
        cls.panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        cls.main, cls.lb = fakepanel.start_servers(cls.panel, "127.0.0.1", 0)
        cls.port = cls.panel.main_port

    @classmethod
    def tearDownClass(cls):
        fakepanel.stop_servers(cls.panel, cls.main, cls.lb)

    def setUp(self):
        self.panel.reset()
        self.panel.cfg["sports_nfl_script"] = {}
        self.panel.cfg["sports_nfl_faults"] = {}
        self.panel.cfg["sports_nfl_timeline_s"] = sports_nfl.TIMELINE_DEFAULT_S
        self.nfl = self.panel.sports_nfl

    def raw(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", path)
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, {k.lower(): v for k, v in r.getheaders()}, body

    def get_json(self, path):
        st, h, body = self.raw(path)
        self.assertEqual(200, st, body)
        return h, json.loads(body)

    def admin(self, path, patch):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", json.dumps(patch).encode(), method="POST")
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())

    def at(self, label, offset=0.0):
        """Anchors the timeline so that "now" is [offset] seconds into window [label]; returns the featured event."""
        import time
        with self.nfl.lock:
            self.nfl.anchor = time.time() - window_start(label) - offset
        _, j = self.get_json(BOARD)
        return next(e for e in j["events"] if e["id"] == sports_nfl.FEATURED_ID)

    @staticmethod
    def comp(e):
        return e["competitions"][0]

    @staticmethod
    def score(e):
        return {c["homeAway"]: c["score"] for c in e["competitions"][0]["competitors"]}


class TestRoutes(NflCase):
    def test_week_board_shape_headers_and_events(self):
        h, j = self.get_json(BOARD)
        self.assertEqual({"type": 2, "year": 2026}, j["season"])
        self.assertEqual(9, j["week"]["number"])
        self.assertEqual("max-age=5", h["cache-control"])
        self.assertIn("date", h)
        self.assertNotIn("age", h)
        self.assertNotIn("etag", h)
        ids = [e["id"] for e in j["events"]]
        self.assertEqual(["401899003", "401899001", "401899002", "401899004"], ids)
        cal = j["leagues"][0]["calendar"]
        self.assertEqual(["Preseason", "Regular Season", "Postseason", "Off Season"], [c["label"] for c in cal])
        self.assertEqual("Week 9", cal[1]["entries"][8]["label"])
        self.assertEqual("Super Bowl", cal[2]["entries"][3]["label"])

    def test_week_board_lists_the_thursday_final_the_sunday_and_the_monday_game(self):
        _, j = self.get_json(BOARD)
        by = {e["id"]: e for e in j["events"]}
        thu = by["401899003"]
        self.assertEqual("STATUS_FINAL", thu["competitions"][0]["status"]["type"]["name"])
        self.assertEqual({"away": "20", "home": "23"}, self.score(thu))
        self.assertEqual("NYJ @ NE", thu["shortName"])
        for eid, short in (("401899002", "SF @ SEA"), ("401899004", "LV @ DEN")):
            self.assertEqual("STATUS_SCHEDULED", by[eid]["competitions"][0]["status"]["type"]["name"])
            self.assertEqual(short, by[eid]["shortName"])

    def test_week_query_and_neighbours(self):
        _, j = self.get_json(BOARD + "?seasontype=2&week=9")
        self.assertEqual(4, len(j["events"]))
        _, j = self.get_json(BOARD + "?seasontype=2&week=8")
        self.assertEqual(1, len(j["events"]))
        self.assertEqual(8, j["week"]["number"])
        _, j = self.get_json(BOARD + "?seasontype=2&week=10")
        self.assertEqual(1, len(j["events"]))
        _, j = self.get_json(BOARD + "?seasontype=2&week=30")
        self.assertEqual([], j["events"])

    def test_week_board_echoes_the_requested_season_type_and_week(self):
        # C2: the season type is echoed (a postseason week reads type 3), the week passes through unchanged.
        _, j = self.get_json(BOARD + "?seasontype=3&week=1")
        self.assertEqual({"type": 3, "year": 2026}, j["season"])
        self.assertEqual(1, j["week"]["number"])
        self.assertEqual([], j["events"])
        _, j = self.get_json(BOARD + "?seasontype=1&week=3")
        self.assertEqual(1, j["season"]["type"])
        self.assertEqual(3, j["week"]["number"])
        _, j = self.get_json(BOARD + "?seasontype=2&week=8")
        self.assertEqual(2, j["season"]["type"])

    def test_week_zero_or_a_bad_week_is_a_400(self):
        for q in ("?seasontype=2&week=0", "?seasontype=2&week=-1", "?seasontype=2&week=x", "?seasontype=2", "?seasontype=0&week=9"):
            st, _, body = self.raw(BOARD + q)
            self.assertEqual(400, st, q)
            self.assertEqual(400, json.loads(body)["code"], q)

    def test_dated_board_has_the_featured_day_only(self):
        day = self.nfl.featured_day()
        _, j = self.get_json(BOARD + "?dates=" + day.strftime("%Y%m%d"))
        self.assertEqual(["401899001", "401899002"], [e["id"] for e in j["events"]])
        _, j = self.get_json(BOARD + "?dates=20300101")
        self.assertEqual([], j["events"])

    def test_a_bad_dates_value_is_http_400_with_code_and_message(self):
        for bad in ("2026110", "abcdefgh", "20261340"):
            st, _, body = self.raw(BOARD + "?dates=" + bad)
            self.assertEqual(400, st, bad)
            j = json.loads(body)
            self.assertEqual(400, j["code"])
            self.assertIn("message", j)

    def test_unknown_paths_are_html_404(self):
        st, h, body = self.raw("/apis/site/v2/sports/football/nfl/nothing")
        self.assertEqual(404, st)
        self.assertTrue(h["content-type"].startswith("text/html"))
        self.assertIn(b"404", body)

    def test_the_logo_is_a_valid_png(self):
        st, h, body = self.raw("/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/kc.png&h=200&w=200")
        self.assertEqual(200, st)
        self.assertEqual("image/png", h["content-type"])
        self.assertEqual(b"\x89PNG\r\n\x1a\n", body[:8])
        pos, idat, size = 8, b"", None
        while pos < len(body):
            n = struct.unpack(">I", body[pos:pos + 4])[0]
            tag, data = body[pos + 4:pos + 8], body[pos + 8:pos + 8 + n]
            crc = struct.unpack(">I", body[pos + 8 + n:pos + 12 + n])[0]
            self.assertEqual(zlib.crc32(tag + data) & 0xFFFFFFFF, crc)
            if tag == b"IHDR":
                size = struct.unpack(">II", data[:8])
            if tag == b"IDAT":
                idat += data
            pos += 12 + n
        self.assertEqual((200, 200), size)
        self.assertEqual(200 * (1 + 600), len(zlib.decompress(idat)))
        st, h, _ = self.raw("/combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/%21.png")
        self.assertEqual(404, st)


class TestTimeline(NflCase):
    def test_scores_are_strings_and_pre_game_has_no_situation(self):
        e = self.at("pre", 5)
        self.assertEqual("STATUS_SCHEDULED", e["status"]["type"]["name"])
        self.assertNotIn("situation", self.comp(e))
        self.assertTrue(all(isinstance(c["score"], str) for c in self.comp(e)["competitors"]))

    def test_the_total_is_985_s_and_every_window_is_25_s_or_more_but_the_one_10_s_window(self):
        lens = [w["len"] for w in sports_nfl.W if w["len"] is not None]
        self.assertEqual(985, sum(lens))
        short = [w["label"] for w in sports_nfl.W if w["len"] is not None and w["len"] < 25]
        self.assertEqual(["q1 score before the play (10 s)"], short)
        self.assertEqual(10, sports_nfl.W[[w["label"] for w in sports_nfl.W].index(short[0])]["len"])

    def test_coin_toss_then_kickoff_without_possession_then_with(self):
        e = self.at("q1 coin toss", 1)
        s = self.comp(e)["situation"]
        self.assertEqual(0, s["down"])
        self.assertEqual("70", s["lastPlay"]["type"]["id"])
        self.assertNotIn("possession", s)
        e = self.at("q1 kick-off, no possession", 1)
        s = self.comp(e)["situation"]
        self.assertEqual("1st & 10", s["shortDownDistanceText"])
        self.assertEqual("KC 25", s["possessionText"])
        self.assertNotIn("possession", s)
        self.assertEqual("53", s["lastPlay"]["type"]["id"])
        self.assertEqual("12", s["lastPlay"]["team"]["id"])
        e = self.at("q1 KC ball", 1)
        self.assertEqual("12", self.comp(e)["situation"]["possession"])

    def test_yard_line_is_home_relative(self):
        s = self.comp(self.at("q1 KC ball", 1))["situation"]
        self.assertEqual(75, s["yardLine"])  # KC's own 25 is 75 yards from BUF's goal line
        s = self.comp(self.at("q1 3rd and 2, OPP 38", 1))["situation"]
        self.assertEqual((38, "BUF 38", 2), (s["yardLine"], s["possessionText"], s["distance"]))
        s = self.comp(self.at("q1 BUF 2nd and 7", 1))["situation"]
        self.assertEqual((28, "BUF 28"), (s["yardLine"], s["possessionText"]))
        s = self.comp(self.at("q1 extra point, KC kick-off", 1))["situation"]
        self.assertEqual((65, -1, 0), (s["yardLine"], s["down"], s["distance"]))  # KC kicks from KC 35

    def test_red_zone_goal_to_go_and_the_ten_second_stale_window(self):
        s = self.comp(self.at("q1 1st and goal, red zone", 1))["situation"]
        self.assertTrue(s["isRedZone"])
        self.assertEqual("1st & Goal", s["shortDownDistanceText"])
        e = self.at("q1 score before the play (10 s)", 1)
        self.assertEqual({"away": "6", "home": "0"}, self.score(e))
        stale = self.comp(e)["situation"]
        self.assertEqual("1st & Goal", stale["shortDownDistanceText"], "the situation is still the previous window's")
        self.assertEqual(10, sports_nfl.W[[w["label"] for w in sports_nfl.W].index("q1 score before the play (10 s)")]["len"])

    def test_try_extra_point_and_kickoff_bodies(self):
        e = self.at("q1 try", 1)
        s = self.comp(e)["situation"]
        self.assertEqual((-1, 10, True), (s["down"], s["distance"], s["isRedZone"]))
        self.assertNotIn("possession", s)
        self.assertEqual((67, 6), (int(s["lastPlay"]["type"]["id"]), s["lastPlay"]["scoreValue"]))
        e = self.at("q1 extra point, KC kick-off", 1)
        self.assertEqual({"away": "7", "home": "0"}, self.score(e))
        self.assertEqual("12", self.comp(e)["situation"]["possession"])

    def test_every_scoring_kind_is_in_the_timeline(self):
        e = self.at("q2 field goal, dead ball", 1)
        s = self.comp(e)["situation"]
        self.assertEqual({"away": "7", "home": "3"}, self.score(e))
        self.assertEqual(("59", 3), (s["lastPlay"]["type"]["id"], s["lastPlay"]["scoreValue"]))
        e = self.at("q2 +7 pair in one body", 1)
        self.assertEqual({"away": "14", "home": "3"}, self.score(e))
        e = self.at("q3 safety, KC free kick", 1)
        s = self.comp(e)["situation"]
        self.assertEqual({"away": "14", "home": "5"}, self.score(e))
        self.assertEqual("Safety", s["lastPlay"]["type"]["text"])
        self.assertEqual(80, s["yardLine"])  # a free kick from KC's 20
        e = self.at("q4 KC safety, BUF free kick", 1)
        self.assertEqual({"away": "16", "home": "16"}, self.score(e))
        self.assertEqual(20, self.comp(e)["situation"]["yardLine"])

    def test_buf_receives_kcs_free_kick(self):
        s = self.comp(self.at("q3 BUF receives the free kick", 1))["situation"]
        self.assertEqual("2", s["possession"])  # BUF's team id
        self.assertEqual("BUF 45", s["possessionText"])

    def test_defensive_td_and_the_two_point_try_as_a_real_down(self):
        e = self.at("q3 defensive TD, stale situation", 1)
        self.assertEqual({"away": "14", "home": "11"}, self.score(e))
        s = self.comp(e)["situation"]
        self.assertEqual("KC 40", s["possessionText"], "stale")
        self.assertEqual("36", s["lastPlay"]["type"]["id"])
        e = self.at("q3 two-point try as a real down", 1)
        s = self.comp(e)["situation"]
        self.assertEqual((1, 2, "2"), (s["down"], s["distance"], s["possession"]))
        self.assertEqual("1st & Goal", s["shortDownDistanceText"])
        e = self.at("q3 two-point good, BUF kick-off", 1)
        self.assertEqual({"away": "14", "home": "13"}, self.score(e))

    def test_clocks_are_frozen_through_every_try_window(self):
        for first, last in (("q1 score before the play (10 s)", "q1 extra point, KC kick-off"),
                            ("q3 defensive TD, stale situation", "q3 two-point good, BUF kick-off")):
            labels = [w["label"] for w in sports_nfl.W]
            clocks = set()
            for i in range(labels.index(first), labels.index(last) + 1):
                for off in (1, sports_nfl.W[i]["len"] - 1):
                    e = self.at(labels[i], off)
                    clocks.add(self.comp(e)["status"]["clock"])
            self.assertEqual(1, len(clocks), (first, clocks))
        # while a running window counts down 1 s/s
        a = self.comp(self.at("q1 KC ball", 1))["status"]["clock"]
        b = self.comp(self.at("q1 KC ball", 11))["status"]["clock"]
        self.assertEqual(10.0, a - b)

    def test_breaks_and_overtime_and_the_post_body(self):
        s = self.comp(self.at("end 1st", 1))
        self.assertEqual("STATUS_END_PERIOD", s["status"]["type"]["name"])
        self.assertEqual("End of 1st", s["status"]["type"]["shortDetail"])
        self.assertIn("situation", s, "the situation is kept through the break")
        self.assertEqual("STATUS_HALFTIME", self.comp(self.at("halftime", 1))["status"]["type"]["name"])
        self.assertEqual(5, self.comp(self.at("ot KC 1st", 1))["status"]["period"])
        e = self.at("ot walk-off touchdown (post)", 1)
        c = self.comp(e)
        self.assertEqual("post", c["status"]["type"]["state"])
        self.assertEqual(5, c["status"]["period"])
        self.assertEqual("Final/OT", c["status"]["type"]["shortDetail"])
        self.assertEqual({"away": "22", "home": "16"}, self.score(e))
        self.assertNotIn("situation", c)
        e = self.at("final", 3000)
        self.assertEqual("STATUS_FINAL", self.comp(e)["status"]["type"]["name"])

    def test_timeline_scale(self):
        self.panel.cfg["sports_nfl_timeline_s"] = 985 * 2
        import time
        with self.nfl.lock:
            self.nfl.anchor = time.time() - 60
        self.assertEqual(0, self.nfl.featured(time.time())["window"])  # pre lasts 80 s at x2


class TestScriptAndFaults(NflCase):
    def test_script_overrides_and_situation_null(self):
        self.admin("/admin/config", {"sports_nfl_script": {sports_nfl.FEATURED_ID: {"away": 8, "state": "in", "name": "STATUS_IN_PROGRESS", "period": 2, "clock": 100}}})
        _, j = self.get_json(BOARD)
        e = next(x for x in j["events"] if x["id"] == sports_nfl.FEATURED_ID)
        self.assertEqual("8", self.score(e)["away"])
        self.assertEqual(2, self.comp(e)["status"]["period"])
        self.admin("/admin/config", {"sports_nfl_script": {sports_nfl.FEATURED_ID: {"state": "in", "name": "STATUS_IN_PROGRESS", "period": 1, "situation": None}}})
        _, j = self.get_json(BOARD)
        e = next(x for x in j["events"] if x["id"] == sports_nfl.FEATURED_ID)
        self.assertNotIn("situation", self.comp(e))
        self.admin("/admin/config", {"sports_nfl_script": {sports_nfl.FEATURED_ID: {"gone": True}}})
        _, j = self.get_json(BOARD)
        self.assertNotIn(sports_nfl.FEATURED_ID, [x["id"] for x in j["events"]])

    def test_config_rejections(self):
        for patch in ({"sports_nfl": "yes"}, {"sports_nfl_timeline_s": 5}, {"sports_nfl_script": []},
                      {"sports_nfl_script": {"1": {"bogus": 1}}}, {"sports_nfl_script": {"1": {"possession": "left"}}},
                      {"sports_nfl_script": {"1": {"situation": 3}}}, {"sports_nfl_faults": {"nope": 1}}, {"sports_nfl_faults": []}):
            with self.assertRaises(ValueError, msg=str(patch)):
                sports_nfl.check_config(patch)
            req = urllib.request.Request(f"http://127.0.0.1:{self.port}/admin/config", json.dumps(patch).encode(), method="POST")
            with self.assertRaises(urllib.error.HTTPError, msg=str(patch)):
                urllib.request.urlopen(req, timeout=5)

    def test_faults(self):
        self.admin("/admin/config", {"sports_nfl_faults": {"score_status": 503}})
        self.assertEqual(503, self.raw(BOARD)[0])
        self.admin("/admin/config", {"sports_nfl_faults": {"status": 429, "retry_after": 7}})
        st, h, _ = self.raw(BOARD)
        self.assertEqual((429, "7"), (st, h["retry-after"]))
        self.admin("/admin/config", {"sports_nfl_faults": {"no_date": True, "age": 3}})
        st, h, _ = self.raw(BOARD)
        self.assertEqual(200, st)
        self.assertNotIn("date", h)
        self.assertEqual("3", h["age"])


class TestRestart(NflCase):
    def test_each_restart_reanchors_the_right_timelines(self):
        self.admin("/admin/sports-nfl", {"anchor_unix": 1000})
        self.admin("/admin/sports", {"anchor_unix": 1000})
        self.assertEqual(1000, self.nfl.anchor)
        self.admin("/admin/sports-nfl", {"restart": True})
        self.assertGreater(self.nfl.anchor, 1e9)
        self.assertEqual(1000, self.panel.sports.anchor, "the NFL restart leaves the NHL anchor")
        with self.nfl.lock:
            self.nfl.anchor = 1000
        self.admin("/admin/sports", {"restart": True})
        self.assertGreater(self.nfl.anchor, 1e9, "N8: /admin/sports restarts both")
        self.assertGreater(self.panel.sports.anchor, 1e9)


class TestOff(unittest.TestCase):
    def test_sports_without_nfl_serves_no_nfl_route(self):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False, "sports": True})
        panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        main, lb = fakepanel.start_servers(panel, "127.0.0.1", 0)
        try:
            for path in (BOARD, "/combiner/i?img=/x.png"):
                c = http.client.HTTPConnection("127.0.0.1", panel.main_port, timeout=10)
                c.request("GET", path)
                self.assertEqual(404, c.getresponse().status)
                c.close()
        finally:
            fakepanel.stop_servers(panel, main, lb)


if __name__ == "__main__":
    unittest.main()
