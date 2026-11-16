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
        self.assertEqual(sports_nfl.TIMELINE_DEFAULT_S, 985)
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


class TestStackedEvents(NflCase):
    """docs/design/m6-nfl-stacked.md 9.4: the scripted timeouts and flags, window by window."""

    def sit(self, label, off=1):
        return self.comp(self.at(label, off))["situation"]

    @staticmethod
    def counts(s):
        return s.get("awayTimeouts"), s.get("homeTimeouts")

    def test_every_body_carries_counts_and_a_numeric_strictly_increasing_play_id(self):
        ids = []
        for w in sports_nfl.W:
            if not w.get("situation"):
                continue
            s = self.sit(w["label"])
            self.assertIsInstance(s["awayTimeouts"], int, w["label"])
            self.assertIsInstance(s["homeTimeouts"], int, w["label"])
            ids.append(int(s["lastPlay"]["id"]))
        self.assertEqual(sorted(set(ids)), ids)

    def test_the_counts_persist_and_the_second_half_resets_them(self):
        self.assertEqual((3, 3), self.counts(self.sit("q1 coin toss")))
        self.assertEqual((3, 3), self.counts(self.sit("q2 field goal, dead ball")))
        self.assertEqual((3, 2), self.counts(self.sit("q2 BUF ball")))
        self.assertEqual((3, 2), self.counts(self.sit("halftime")), "held through the break")
        self.assertEqual((3, 3), self.counts(self.sit("q3 KC at its own 2")), "the half reset")
        self.assertEqual((3, 3), self.counts(self.sit("end 3rd")))

    def test_home_team_timeout_is_a_type_21_with_the_count_dropping_in_the_same_poll_held_over_two_polls(self):
        before = self.sit("q2 +7 pair in one body")
        self.assertEqual(3, before["homeTimeouts"])
        a, b = self.sit("q2 BUF ball", 1), self.sit("q2 BUF ball", 40)
        for s in (a, b):
            self.assertEqual(("21", "Timeout", "TO"), tuple(s["lastPlay"]["type"][k] for k in ("id", "text", "abbreviation")))
            self.assertEqual("2", s["lastPlay"]["team"]["id"])
            self.assertEqual("Timeout #1 by BUF at 01:35.", s["lastPlay"]["text"])
            self.assertEqual((3, 2), self.counts(s))
        self.assertEqual(a["lastPlay"]["id"], b["lastPlay"]["id"], "held: the same play across polls")
        self.assertGreaterEqual(sports_nfl.W[[w["label"] for w in sports_nfl.W].index("q2 BUF ball")]["len"], 50)
        nxt = self.sit("halftime")
        self.assertGreater(int(nxt["lastPlay"]["id"]), int(a["lastPlay"]["id"]))
        self.assertEqual("65", nxt["lastPlay"]["type"]["id"])

    def test_away_team_timeout_flap_and_the_lost_challenge(self):
        s = self.sit("q4 KC 3rd and 9")
        self.assertEqual(("21", "12", (2, 3)), (s["lastPlay"]["type"]["id"], s["lastPlay"]["team"]["id"], self.counts(s)))
        flap = self.sit("q4 turnover, BUF in the red zone")
        self.assertEqual((3, 3), self.counts(flap))
        self.assertNotEqual("21", flap["lastPlay"]["type"]["id"])
        again = self.sit("q4 BUF 2nd and 6, rush")
        self.assertEqual((2, 3), self.counts(again))
        self.assertEqual("5", again["lastPlay"]["type"]["id"], "a live rush, no type 21")
        self.assertEqual(0, again["lastPlay"]["scoreValue"], "not a score: the fallback check really runs")
        self.assertEqual({"away": "14", "home": "13"}, self.score(self.at("q4 BUF 2nd and 6, rush", 1)))
        self.assertGreater(again["down"], 0, "a live down")
        fg = self.sit("q4 field goal, dead ball")
        self.assertEqual("59", fg["lastPlay"]["type"]["id"])
        self.assertEqual((2, 3), self.counts(fg), "the FG carries the same count: no count change")
        labels = [w["label"] for w in sports_nfl.W]
        order = [labels.index(x) for x in ("q4 turnover, BUF in the red zone", "q4 BUF 2nd and 6, rush", "q4 field goal, dead ball")]
        self.assertEqual(sorted(order), order, "flap, then the re-drop, then the FG")
        lost = self.sit("q4 BUF 3rd and 9 at its 2")
        self.assertEqual((2, 2), self.counts(lost))
        self.assertEqual("5", lost["lastPlay"]["type"]["id"], "no 21: the fallback 8 s mark")

    def test_overtime_counts_are_2_2_and_the_ot_timeout_is_a_type_21(self):
        self.assertEqual((2, 2), self.counts(self.sit("ot KC 1st")))
        s = self.sit("ot KC 1st and goal at 8")
        self.assertEqual((2, 1), self.counts(s))
        self.assertEqual(("21", "2"), (s["lastPlay"]["type"]["id"], s["lastPlay"]["team"]["id"]))
        self.assertNotIn("situation", self.comp(self.at("ot walk-off touchdown (post)", 1)))

    def test_accepted_penalty_on_a_no_play_is_type_8(self):
        s = self.sit("q1 3rd and 2, OPP 38")
        lp_ = s["lastPlay"]
        self.assertEqual(("8", "Penalty", "PEN"), tuple(lp_["type"][k] for k in ("id", "text", "abbreviation")))
        self.assertIn("PENALTY on BUF-", lp_["text"])
        self.assertEqual((3, 3), self.counts(s))

    def test_accepted_foul_on_a_play_that_stands_is_a_normal_type_with_upper_case_penalty_on(self):
        lp_ = self.sit("q1 KC ball")["lastPlay"]
        self.assertEqual("24", lp_["type"]["id"])
        self.assertIn("PENALTY on BUF-", lp_["text"])

    def test_declined_foul_is_mixed_case_and_not_type_8(self):
        lp_ = self.sit("q1 BUF 2nd and 7")["lastPlay"]
        self.assertEqual("3", lp_["type"]["id"])
        self.assertIn("Penalty on KC-", lp_["text"])
        self.assertIn("declined", lp_["text"])
        self.assertNotIn("PENALTY on", lp_["text"])

    def test_touchdown_with_a_penalty_in_the_same_update(self):
        e = self.at("q1 score before the play (10 s)", 1)
        self.assertEqual({"away": "6", "home": "0"}, self.score(e))
        text = self.comp(e)["situation"]["lastPlay"]["text"]
        self.assertIn("TOUCHDOWN. Penalty on BUF-J.Poyer", text)
        self.assertIn("declined", text)
        self.assertNotIn("PENALTY on", text, "ESPN writes a declined foul in mixed case")

    def test_an_accepted_upper_case_foul_rides_on_a_scoring_window_so_score_wins_is_exercised(self):
        e = self.at("q1 extra point, KC kick-off", 1)
        self.assertEqual({"away": "7", "home": "0"}, self.score(e))
        text = self.comp(e)["situation"]["lastPlay"]["text"]
        self.assertIn("PENALTY on KC-", text)
        self.assertIn("enforced on the kickoff", text)
        self.assertNotIn("declined", text)

    def test_official_timeout_kickoff_after_a_score_is_red_zone_with_down_minus_1(self):
        s = self.sit("q1 extra point, KC kick-off")
        self.assertEqual(("74", -1, True, "12"), (s["lastPlay"]["type"]["id"], s["down"], s["isRedZone"], s["possession"]))
        self.assertEqual(0, s["distance"])
        self.assertEqual(35, 100 - s["yardLine"])

    def test_two_minute_warning_is_type_75(self):
        s = self.sit("q2 KC red zone, two-minute warning")
        self.assertEqual(("75", "Two-minute warning"), (s["lastPlay"]["type"]["id"], s["lastPlay"]["type"]["text"]))
        self.assertEqual((3, 3), self.counts(s), "type 75 never marks: no count change")
        self.assertEqual((3, 3), self.counts(self.sit("q2 +7 pair in one body")))
        self.assertEqual("2:00", self.comp(self.at("q2 KC red zone, two-minute warning", 0))["status"]["displayClock"])
        for w in sports_nfl.W:
            if w.get("lastPlayType") == "75":
                self.assertIn(w["period"], (2, 4), w["label"])
        self.assertEqual(["q2 KC red zone, two-minute warning"],
                         [w["label"] for w in sports_nfl.W if w.get("lastPlayType") == "75"])
        self.assertNotEqual("75", self.sit("q3 KC 1st at KC 40")["lastPlay"]["type"]["id"])

    def test_no_possession_during_a_kickoff_return(self):
        s = self.sit("q1 BUF ball, no possession")
        self.assertNotIn("possession", s)
        self.assertEqual(("53", "1st & 10"), (s["lastPlay"]["type"]["id"], s["shortDownDistanceText"]))

    def test_end_of_quarter_and_halftime_keep_the_situation_and_counts(self):
        for label, name in (("end 1st", "STATUS_END_PERIOD"), ("halftime", "STATUS_HALFTIME"), ("end 4th", "STATUS_END_PERIOD")):
            c = self.comp(self.at(label, 1))
            self.assertEqual(name, c["status"]["type"]["name"])
            self.assertIn("awayTimeouts", c["situation"])
            self.assertNotIn("PENALTY", c["situation"]["lastPlay"]["text"])

    def test_script_keys_override_counts_text_and_id(self):
        self.admin("/admin/config", {"sports_nfl_script": {sports_nfl.FEATURED_ID: {
            "awayTimeouts": 1, "homeTimeouts": None, "lastPlayText": "Penalty on X, declined.", "lastPlayId": "401899001999"}}})
        s = self.sit("q1 KC ball")
        self.assertEqual(1, s["awayTimeouts"])
        self.assertNotIn("homeTimeouts", s)
        self.assertEqual("Penalty on X, declined.", s["lastPlay"]["text"])
        self.assertEqual("401899001999", s["lastPlay"]["id"])

    def test_new_key_rejections(self):
        for v in ({"awayTimeouts": 4}, {"homeTimeouts": -1}, {"awayTimeouts": True}, {"lastPlayText": 5}, {"lastPlayId": "abc"}):
            with self.assertRaises(ValueError, msg=str(v)):
                sports_nfl.check_config({"sports_nfl_script": {"1": v}})
        sports_nfl.check_config({"sports_nfl_script": {"1": {"awayTimeouts": 0, "homeTimeouts": None, "lastPlayId": 5, "lastPlayText": ""}}})


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


class TestPerEvent(NflCase):
    """docs/design/m6-sports-update-interval.md section 6: ESPN's per-event endpoint and its faults."""

    EVENT = BOARD + "/" + sports_nfl.FEATURED_ID

    def test_one_event_object_like_the_boards_entry_with_max_age_1(self):
        e = self.at("q1 KC ball", 2)
        h, j = self.get_json(self.EVENT)
        self.assertEqual("max-age=1", h["cache-control"])
        self.assertIn("date", h)
        self.assertNotIn("leagues", j)
        self.assertNotIn("events", j)
        self.assertEqual(sports_nfl.FEATURED_ID, j["id"])
        self.assertNotIn("_local", j)
        for k in ("date", "competitions", "status"):
            self.assertEqual(e[k], j[k], k)

    def test_unknown_digits_are_a_json_404_cached_for_an_hour(self):
        st, h, body = self.raw(BOARD + "/999999999")
        self.assertEqual(404, st)
        self.assertTrue(h["content-type"].startswith("application/json"))
        self.assertEqual("max-age=3600", h["cache-control"])
        self.assertEqual({"code": 404, "message": "No event found for eventId: 999999999"}, json.loads(body))

    def test_non_digits_are_a_gzip_json_400(self):
        st, h, body = self.raw(BOARD + "/abc")
        self.assertEqual(400, st)
        self.assertEqual("gzip", h["content-encoding"])
        self.assertEqual("max-age=3019", h["cache-control"])
        import gzip
        self.assertEqual(400, json.loads(gzip.decompress(body))["code"])

    def test_a_gone_event_is_a_404(self):
        self.panel.cfg["sports_nfl_script"] = {sports_nfl.FEATURED_ID: {"gone": True}}
        self.assertEqual(404, self.raw(self.EVENT)[0])

    def test_event_status_hits_the_per_event_route_only(self):
        self.admin("/admin/config", {"sports_nfl_faults": {"event_status": 400}})
        self.assertEqual(400, self.raw(self.EVENT)[0])
        self.assertEqual(200, self.raw(BOARD)[0], "the board keeps answering (the fallback)")
        self.admin("/admin/config", {"sports_nfl_faults": {"event_status": 404}})
        st, h, body = self.raw(self.EVENT)
        self.assertEqual((404, "application/json"), (st, h["content-type"]))
        self.assertIn("No event found", json.loads(body)["message"])
        self.admin("/admin/config", {"sports_nfl_faults": {"event_status": 404, "html_404": True}})
        st, h, _ = self.raw(self.EVENT)
        self.assertEqual(404, st)
        self.assertTrue(h["content-type"].startswith("text/html"))
        self.admin("/admin/config", {"sports_nfl_faults": {"event_status": 403}})
        self.assertEqual(403, self.raw(self.EVENT)[0])
        self.admin("/admin/config", {"sports_nfl_faults": {"event_status": 429, "retry_after": 30}})
        st, h, _ = self.raw(self.EVENT)
        self.assertEqual((429, "30"), (st, h["retry-after"]))

    def test_max_age_and_stall_ms(self):
        import time
        self.admin("/admin/config", {"sports_nfl_faults": {"max_age": 9}})
        self.assertEqual("max-age=9", self.raw(self.EVENT)[1]["cache-control"])
        self.assertEqual("max-age=9", self.raw(BOARD)[1]["cache-control"])
        self.admin("/admin/config", {"sports_nfl_faults": {"stall_ms": 300}})
        t0 = time.monotonic()
        self.assertEqual(200, self.raw(self.EVENT)[0])
        self.assertGreaterEqual(time.monotonic() - t0, 0.29)

    def test_event_older_s_serves_an_older_body(self):
        self.at("q1 KC ball", 2)
        _, now = self.get_json(self.EVENT)
        self.admin("/admin/config", {"sports_nfl_faults": {"event_older_s": 60}})
        _, old = self.get_json(self.EVENT)
        self.assertNotEqual(now["competitions"][0]["status"], old["competitions"][0]["status"])

    def test_requests_are_in_the_sports_log(self):
        self.raw(self.EVENT)
        self.raw(BOARD)
        st, _, body = self.raw("/admin/stats")
        log = json.loads(body)["sports"]["log"]
        paths = [r["path"] for r in log]
        self.assertIn(self.EVENT, paths)
        self.assertIn(BOARD, paths)
        self.assertTrue(all("unix" in r for r in log))
        self.assertIn("/apis/site/v2/sports/football/nfl/scoreboard/{event}", json.loads(body)["sports"]["by_path"])

    def test_new_fault_keys_are_accepted_and_others_rejected(self):
        sports_nfl.check_config({"sports_nfl_faults": {"event_status": 400, "event_older_s": 5, "stall_ms": 1, "max_age": 1}})
        with self.assertRaises(ValueError):
            sports_nfl.check_config({"sports_nfl_faults": {"event_stat": 400}})


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
