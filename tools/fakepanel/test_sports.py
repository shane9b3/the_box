"""Self-tests for fakepanel's sports routes (docs/design/m6-score-bug.md section 8). Run from the repo root:

    python3 -I -m unittest tools/fakepanel/test_sports.py
"""
import copy
import datetime as dt
import email.utils
import http.client
import json
import os
import sys
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fakepanel  # noqa: E402
import sports  # noqa: E402


class SportsCase(unittest.TestCase):
    overrides = {"sports": True}

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

    def raw(self, path):
        """GET without following redirects: (status, headers, body)."""
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

    def today(self):
        return dt.datetime.now(sports.ET).date()

    def featured(self):
        return self.admin("/admin/sports", {})["featured"]


class TestRoutes(SportsCase):
    def test_score_now_redirects_to_the_eastern_date(self):
        st, h, body = self.raw("/v1/score/now")
        self.assertEqual(307, st)
        self.assertTrue(h["location"].endswith(f"/v1/score/{self.today().isoformat()}"), h["location"])
        self.assertEqual(b"", body)

    def test_day_list_shape(self):
        d = self.today()
        h, j = self.get_json(f"/v1/score/{d.isoformat()}")
        self.assertEqual(d.isoformat(), j["currentDate"])
        self.assertEqual((d - dt.timedelta(days=1)).isoformat(), j["prevDate"])
        self.assertEqual((d + dt.timedelta(days=1)).isoformat(), j["nextDate"])
        self.assertEqual(5, len(j["games"]))
        g = j["games"][0]
        self.assertEqual("CGY", g["awayTeam"]["abbrev"])
        self.assertEqual({"default": "Flames"}, g["awayTeam"]["name"])
        self.assertNotIn("darkLogo", g["awayTeam"], "the day list has light logos only (facts section 2)")
        self.assertEqual("FUT", g["gameState"])
        self.assertNotIn("score", g["awayTeam"], "no score before the game")
        self.assertNotIn("clock", g)
        self.assertEqual(d.isoformat(), g["gameDate"])
        states = {x["gameState"] for x in j["games"]}
        self.assertTrue({"FUT", "LIVE", "OFF"} <= states, states)
        self.assertEqual("application/json", h["content-type"])
        self.assertEqual("no-transform, max-age=19, s-maxage=19", h["cache-control"])

    def test_neighbour_days_and_empty_days(self):
        d = self.today()
        _, y = self.get_json(f"/v1/score/{(d - dt.timedelta(days=1)).isoformat()}")
        self.assertTrue(y["games"] and all(g["gameState"] == "OFF" for g in y["games"]))
        self.assertTrue(any(g.get("gameOutcome", {}).get("lastPeriodType") == "OT" for g in y["games"]))
        _, t = self.get_json(f"/v1/score/{(d + dt.timedelta(days=1)).isoformat()}")
        self.assertTrue(t["games"] and all(g["gameState"] == "FUT" for g in t["games"]))
        _, e = self.get_json("/v1/score/2026-12-25" if d.isoformat() != "2026-12-25" else "/v1/score/2027-01-30")
        self.assertEqual([], e["games"])

    def test_landing_shape_and_unknown_game(self):
        gid = self.featured()
        _, j = self.get_json(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual(int(gid), j["id"])
        a = j["awayTeam"]
        self.assertEqual({"default": "Flames"}, a["commonName"])
        self.assertEqual({"default": "Calgary"}, a["placeName"])
        self.assertEqual(f"http://127.0.0.1:{self.port}/logos/nhl/svg/CGY_dark.svg", a["darkLogo"])
        self.assertNotIn("name", a)
        self.assertNotIn("period", j, "the landing has no top-level period (facts section 3)")
        st, h, body = self.raw("/v1/gamecenter/1999020001/landing")
        self.assertEqual(404, st)
        self.assertEqual("application/json", h["content-type"])
        json.loads(body)

    def test_logos(self):
        st, h, body = self.raw("/logos/nhl/svg/EDM_dark.svg")
        self.assertEqual(200, st)
        self.assertEqual("image/svg+xml", h["content-type"])
        self.assertIn(b'viewBox="0 0 960 640"', body)
        self.assertNotIn(b"<text", body)
        self.assertEqual(200, self.raw("/logos/nhl/svg/XYZ_light.svg")[0])
        self.assertEqual(404, self.raw("/logos/nhl/svg/edm.png")[0])

    def test_cdn_bucket_headers(self):
        before = time.time()
        _, h, _ = self.raw(f"/v1/score/{self.today().isoformat()}")
        date = email.utils.parsedate_to_datetime(h["date"]).timestamp()
        self.assertLessEqual(abs(date - before), 2)
        age = int(h["age"])
        self.assertTrue(0 <= age < sports.BUCKET_S, age)
        # Date - Age is a bucket boundary (A-S2), within the 1 s resolution of Date.
        self.assertLessEqual(abs((date - age) - sports.Sports.bucket_start(date)), 1.0)
        self.assertTrue(h["etag"].startswith('W/"'))
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", "/v1/score/now")
        r = c.getresponse()
        r.read()
        c.close()
        self.assertEqual(1, sum(1 for k, _ in r.getheaders() if k.lower() == "date"), "one Date: the CDN's")

    def test_off_by_default(self):
        self.panel.update_config({"sports": False})
        self.assertEqual(404, self.raw("/v1/score/now")[0])
        self.assertEqual(404, self.raw("/logos/nhl/svg/EDM_dark.svg")[0])

    def test_requests_are_counted(self):
        self.raw("/v1/score/now")
        self.raw(f"/v1/gamecenter/{self.featured()}/landing")
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/admin/stats", timeout=5) as r:
            s = json.loads(r.read())["sports"]
        self.assertEqual(2, s["requests"])
        self.assertEqual({"/v1/score/now": 1, "/v1/gamecenter/{id}/landing": 1}, s["by_path"])


class TestTimeline(SportsCase):
    """The featured game's compressed timeline, evaluated directly (no sleeping)."""

    def at(self, t):
        sp = self.panel.sports
        return sp.featured(sp.anchor + t)

    def test_phases_in_order(self):
        seen = []
        for t in range(0, 400, 2):
            s = self.at(t)
            key = (s["gameState"], s.get("period"), s.get("inIntermission", False))
            if not seen or seen[-1] != key:
                seen.append(key)
        self.assertEqual([
            ("FUT", None, False), ("PRE", None, False),
            ("LIVE", 1, False), ("LIVE", 1, True), ("LIVE", 2, False), ("LIVE", 2, True),
            ("CRIT", 3, False), ("LIVE", 4, False), ("LIVE", 5, False), ("OFF", 5, False), ("FINAL", 5, False),
        ], seen)

    def test_clock_runs_at_one_second_per_second_with_a_stoppage(self):
        p1 = sports.PRE_PUCK_S
        self.assertTrue(self.at(p1 + 1)["running"])
        self.assertEqual(self.at(p1 + 1)["secondsRemaining"] - 5, self.at(p1 + 6)["secondsRemaining"])
        stopped = [self.at(p1 + t) for t in (21, 25, 29)]
        self.assertTrue(all(not s["running"] for s in stopped))
        self.assertEqual(1, len({s["secondsRemaining"] for s in stopped}), "frozen through the stoppage")
        self.assertLessEqual(self.at(p1 + 59.9)["secondsRemaining"], 1)
        # Intermission counts down too, not running.
        i = self.at(p1 + 65)
        self.assertTrue(i["inIntermission"])
        self.assertFalse(i["running"])
        self.assertGreater(i["secondsRemaining"], self.at(p1 + 70)["secondsRemaining"])

    def test_goals_and_the_shootout_winner(self):
        p2 = sports.PRE_PUCK_S + 60 + 30
        self.assertEqual((0, 0), (self.at(p2 + 29)["away"], self.at(p2 + 29)["home"]))
        self.assertEqual((1, 0), (self.at(p2 + 31)["away"], self.at(p2 + 31)["home"]))
        final = self.at(1000)
        self.assertEqual("FINAL", final["gameState"])
        self.assertEqual((1, 2), (final["away"], final["home"]))
        self.assertEqual("SO", final["lastPeriodType"])

    def test_timeline_scales(self):
        self.panel.update_config({"sports_timeline_s": 720})
        self.assertEqual("FUT", self.at(59)["gameState"])
        self.assertEqual("PRE", self.at(61)["gameState"])

    def test_landing_follows_the_timeline_and_restart(self):
        self.admin("/admin/sports", {"anchor_unix": time.time() - 1000})
        _, j = self.get_json(f"/v1/gamecenter/{self.featured()}/landing")
        self.assertEqual("FINAL", j["gameState"])
        self.assertEqual({"lastPeriodType": "SO"}, j["gameOutcome"])
        self.assertEqual(2, j["homeTeam"]["score"])
        self.admin("/admin/sports", {"restart": True})
        _, j = self.get_json(f"/v1/gamecenter/{self.featured()}/landing")
        self.assertEqual("FUT", j["gameState"])


class TestScriptAndFaults(SportsCase):
    def test_script_overrides_a_game(self):
        gid = self.featured()
        self.panel.update_config({"sports_script": {gid: {"gameState": "LIVE", "period": 2, "periodType": "REG",
                                                          "secondsRemaining": 622, "running": True, "away": 0, "home": 2}}})
        _, j = self.get_json(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual("LIVE", j["gameState"])
        self.assertEqual({"timeRemaining": "10:22", "secondsRemaining": 622, "running": True, "inIntermission": False}, j["clock"])
        self.assertEqual(2, j["periodDescriptor"]["number"])
        self.assertEqual((0, 2), (j["awayTeam"]["score"], j["homeTeam"]["score"]))

    def test_postponed_and_gone(self):
        gid = self.featured()
        self.panel.update_config({"sports_script": {gid: {"scheduleState": "PPD"}}})
        _, j = self.get_json(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual("PPD", j["gameScheduleState"])
        self.panel.update_config({"sports_script": {gid: {"gone": True}}})
        self.assertEqual(404, self.raw(f"/v1/gamecenter/{gid}/landing")[0])
        _, d = self.get_json(f"/v1/score/{self.today().isoformat()}")
        self.assertNotIn(int(gid), [g["id"] for g in d["games"]])

    def test_faults(self):
        gid = self.featured()
        self.panel.update_config({"sports_faults": {"landing_status": 404, "html_404": True}})
        st, h, body = self.raw(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual((404, "text/html"), (st, h["content-type"]))
        self.assertTrue(body.startswith(b"<html>"))
        self.panel.update_config({"sports_faults": {"landing_status": 0, "html_404": False, "status": 429, "retry_after": 30}})
        st, h, _ = self.raw(f"/v1/score/{self.today().isoformat()}")
        self.assertEqual((429, "30"), (st, h["retry-after"]))
        self.panel.update_config({"sports_faults": {"status": 0, "score_status": 503}})
        self.assertEqual(503, self.raw(f"/v1/score/{self.today().isoformat()}")[0])
        self.assertEqual(200, self.raw(f"/v1/gamecenter/{gid}/landing")[0])
        self.panel.update_config({"sports_faults": {"score_status": 0, "pad_bytes": 600000, "age": 99, "no_date": True}})
        st, h, body = self.raw(f"/v1/score/{self.today().isoformat()}")
        self.assertGreater(len(body), 524288, "over the app's 512 KB cap")
        self.assertEqual("99", h["age"], "the app clamps it to 60")
        self.assertNotIn("date", h)

    def test_bad_config_is_rejected(self):
        for patch in ({"sports": "yes"}, {"sports_timeline_s": 1}, {"sports_script": {"1": {"colour": "red"}}},
                      {"sports_faults": {"explode": True}}):
            with self.assertRaises(ValueError, msg=patch):
                self.panel.update_config(patch)


if __name__ == "__main__":
    unittest.main()
