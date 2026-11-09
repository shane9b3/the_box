"""Self-tests for fakepanel's sports routes (docs/design/m6-score-bug.md section 8). Run from the repo root:

    python3 -I -m unittest tools/fakepanel/test_sports.py
"""
import copy
import datetime as dt
import email.utils
import hashlib
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


# The default-timeline golden (power play section 8, code review N8): every body the default (sports_pp off) timeline
# serves, at a fixed anchor, captured from the pre-power-play sports.py (`git show b30ef7a:tools/fakepanel/sports.py`)
# with `default_bodies` below. Full bodies at GOLDEN_FULL instants, a SHA-256 over all of them at each second 0..420.
GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testdata", "sports_default_golden.json")
GOLDEN_ANCHOR = 1762632000.0  # 2025-11-08 15:00 Eastern
GOLDEN_BASE = "http://golden"
GOLDEN_FULL = (45, 250, 346)


def default_bodies(sp, at):
    """[(name, body)] for the day lists of yesterday, today and tomorrow and every game's landing and list entry, as
    `json.dumps` serialises them for the wire (sports.handle's `answer`)."""
    today = sp.today(sp.anchor)
    out = []
    for d in (-1, 0, 1):
        day = today + dt.timedelta(days=d)
        out.append(("day %+d" % d, json.dumps(sp.day_body(day, at, GOLDEN_BASE))))
        for g in sp.slate(day, at):
            for landing in (True, False):
                out.append((f"{g[0]} {'landing' if landing else 'list'}", json.dumps(sp.game(day, g, at, GOLDEN_BASE, landing))))
    return out


def bodies_digest(bodies):
    h = hashlib.sha256()
    for k, b in bodies:
        h.update(k.encode()); h.update(b"\0"); h.update(b.encode()); h.update(b"\0")  # noqa: E702
    return h.hexdigest()


def capture_golden(sp, path):
    """Writes the golden for Sports [sp] (its anchor is set to GOLDEN_ANCHOR)."""
    sp.anchor = GOLDEN_ANCHOR
    digests, full = [], {}
    for t in range(0, 421):
        b = default_bodies(sp, GOLDEN_ANCHOR + t)
        digests.append(bodies_digest(b))
        if t in GOLDEN_FULL:
            full[str(t)] = dict(b)
    with open(path, "w") as f:
        json.dump({"anchor": GOLDEN_ANCHOR, "base": GOLDEN_BASE, "full": full, "sha256": digests}, f, indent=0, sort_keys=True)


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

    def test_stall_ms_and_max_age(self):
        """docs/design/m6-sports-update-interval.md section 6: a slow box, and a different feed refresh."""
        import time
        gid = self.featured()
        self.panel.update_config({"sports_faults": {"max_age": 7}})
        st, h, _ = self.raw(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual((200, "no-transform, max-age=7, s-maxage=7"), (st, h["cache-control"]))
        self.panel.update_config({"sports_faults": {"stall_ms": 250}})
        t0 = time.monotonic()
        st, h, _ = self.raw(f"/v1/gamecenter/{gid}/landing")
        self.assertGreaterEqual(time.monotonic() - t0, 0.24)
        self.assertEqual((200, "no-transform, max-age=19, s-maxage=19"), (st, h["cache-control"]), "the default is unchanged")

    def test_bad_config_is_rejected(self):
        for patch in ({"sports": "yes"}, {"sports_timeline_s": 1}, {"sports_script": {"1": {"colour": "red"}}},
                      {"sports_faults": {"explode": True}}):
            with self.assertRaises(ValueError, msg=patch):
                self.panel.update_config(patch)


class PpCase(SportsCase):
    """Evaluate the featured game's states and bodies directly (no sleeping)."""

    def at(self, t):
        sp = self.panel.sports
        return sp.featured(sp.anchor + t)

    def body(self, t, landing):
        sp = self.panel.sports
        day = sp.today(sp.anchor)
        g = sp.slate(day, sp.anchor + t)[0]
        return sp.game(day, g, sp.anchor + t, "http://x", landing)

    def sit(self, t, landing=True):
        return self.body(t, landing).get("situation")

    def scoring(self, t):
        return self.body(t, True)["summary"]["scoring"]

    def goals(self, t):
        return [g for p in self.scoring(t) for g in p["goals"]]


class TestPowerPlayOff(SportsCase):
    def test_default_has_no_situation_and_no_summary(self):
        self.assertFalse(self.panel.cfg["sports_pp"])
        sp = self.panel.sports
        day = sp.today(sp.anchor)
        for t in range(0, 421):
            at = sp.anchor + t
            g = sp.slate(day, at)[0]
            self.assertNotIn("situationCode", sp.featured(at))
            self.assertNotIn("scoring", sp.featured(at))
            for landing in (True, False):
                self.assertNotIn("situation", sp.game(day, g, at, "http://x", landing), t)
                self.assertNotIn("summary", sp.game(day, g, at, "http://x", landing), t)
            self.assertNotIn("situation", sp.day_body(day, at, "http://x")["games"][0])

    def test_the_default_bodies_match_the_pre_power_play_golden_byte_for_byte(self):
        with open(GOLDEN) as f:
            golden = json.load(f)
        sp = self.panel.sports
        self.assertFalse(self.panel.cfg["sports_pp"])
        sp.anchor = golden["anchor"]
        try:
            for t, bodies in golden["full"].items():
                now = dict(default_bodies(sp, golden["anchor"] + int(t)))
                self.assertEqual(sorted(bodies), sorted(now), t)
                for k, b in bodies.items():
                    self.assertEqual(b.encode(), now[k].encode(), (t, k))
            for t, digest in enumerate(golden["sha256"]):
                self.assertEqual(digest, bodies_digest(default_bodies(sp, golden["anchor"] + t)), t)
        finally:
            sp.reset()

    def test_the_default_timeline_is_not_longer(self):
        self.assertEqual("OFF", self.panel.sports.featured(self.panel.sports.anchor + 344)["gameState"])
        self.assertEqual("FINAL", self.panel.sports.featured(self.panel.sports.anchor + 346)["gameState"])


class TestPowerPlayTimeline(PpCase):
    overrides = {"sports": True, "sports_pp": True}
    P1 = sports.PRE_PUCK_S            # 45: 1st LIVE
    P1I = P1 + 60                     # 1st INT
    P2 = P1I + 30                     # 2nd LIVE
    P2I = P2 + 60
    P3 = P2I + 20                     # 3rd CRIT (110 s)
    OT = P3 + 110
    SO = OT + 30

    def check(self, t, code, secs):
        for landing in (True, False):
            sit = self.sit(t, landing)
            if code is None:
                self.assertIsNone(sit, (t, landing))
                continue
            self.assertEqual(code, sit["situationCode"], t)
            self.assertEqual(secs, sit["secondsRemaining"], t)
            self.assertEqual(sports.mmss(secs), sit["timeRemaining"])

    def test_every_row(self):
        self.check(self.P1 + 14, None, None)
        self.check(self.P1 + 34, None, None)
        self.check(self.P1 + 35, "1451", 30)
        self.check(self.P1 + 59, "1451", 6)
        self.check(self.P1I, "1451", 5)
        self.check(self.P1I + 29, "1451", 5)
        self.check(self.P2, "1451", 5)
        self.check(self.P2 + 4, "1451", 1)
        self.check(self.P2 + 5, None, None)
        self.check(self.P2 + 7, None, None)
        self.check(self.P2 + 8, "1541", 100)
        self.check(self.P2 + 29, "1541", 79)
        self.check(self.P2 + 30, None, None)
        self.check(self.P2I + 1, None, None)
        self.check(self.P3, "1451", 120)
        self.check(self.P3 + 19, "1451", 101)
        self.check(self.P3 + 20, "1351", 40)
        self.check(self.P3 + 39, "1351", 21)
        self.check(self.P3 + 40, "1451", 80)
        self.check(self.P3 + 84, "1451", 36)
        self.check(self.P3 + 85, None, None)
        self.check(self.P3 + 100, None, None)
        self.check(self.OT + 4, None, None)
        self.check(self.OT + 5, "1431", 60)
        self.check(self.OT + 24, "1431", 41)
        self.check(self.OT + 25, None, None)
        for t in (self.SO + 1, self.SO + 25, self.SO + 45, 1000):
            self.check(t, None, None)

    def test_windows_are_long_enough_and_seconds_count_down(self):
        windows = []  # [code, first t, last t, first secs, last secs]
        for t in range(0, 421):
            sit = self.sit(t)
            if sit is None:
                continue
            w = windows[-1] if windows else None
            if w and w[0] == sit["situationCode"] and w[2] == t - 1:
                w[2], w[4] = t, sit["secondsRemaining"]
            else:
                windows.append([sit["situationCode"], t, t, sit["secondsRemaining"], sit["secondsRemaining"]])
        # The 1st-period 1451 runs on through the intermission and the 2nd period's opening 5 s: one 60 s window.
        self.assertEqual(["1451", "1541", "1451", "1351", "1451", "1431"], [w[0] for w in windows])
        self.assertEqual([25 + 30 + 5, 22, 20, 20, 45, 20], [w[2] - w[1] + 1 for w in windows])
        self.assertTrue(all(w[2] - w[1] + 1 >= 20 for w in windows))
        self.assertEqual([21, 19, 19, 44, 19], [w[3] - w[4] for w in windows[1:]], "1 s/s outside the intermission")

    def test_body_shape_and_pp_marker(self):
        sit = self.sit(self.P1 + 40)
        self.assertEqual({"homeTeam": {"abbrev": "EDM", "strength": 5, "situationDescriptions": ["PP"]},
                          "awayTeam": {"abbrev": "CGY", "strength": 4}, "situationCode": "1451",
                          "timeRemaining": "00:25", "secondsRemaining": 25}, sit)
        self.assertEqual(["PP"], self.sit(self.P2 + 10)["awayTeam"]["situationDescriptions"])
        self.assertNotIn("situationDescriptions", self.sit(self.P2 + 10)["homeTeam"])
        self.assertEqual(sit, self.sit(self.P1 + 40, landing=False))
        sp = self.panel.sports
        day_game = sp.day_body(sp.today(sp.anchor), sp.anchor + self.P1 + 40, "http://x")["games"][0]
        self.assertEqual(sit, day_game["situation"])
        self.assertNotIn("summary", day_game)
        self.assertNotIn("summary", self.body(self.P1 + 40, False))
        five_on_three, four_on_three = self.sit(self.P3 + 25), self.sit(self.OT + 10)
        self.assertEqual((5, 3), (five_on_three["homeTeam"]["strength"], five_on_three["awayTeam"]["strength"]))
        self.assertEqual((3, 4), (four_on_three["homeTeam"]["strength"], four_on_three["awayTeam"]["strength"]))

    def test_intermission_freezes_the_seconds(self):
        self.assertEqual({5}, {self.sit(t)["secondsRemaining"] for t in range(self.P1I, self.P2)})
        self.assertTrue(self.at(self.P1I + 10)["inIntermission"])

    def test_score_rises_at_the_instant_the_situation_goes(self):
        before, after = self.at(self.P2 + 29.5), self.at(self.P2 + 30)
        self.assertEqual("1541", before["situationCode"])
        self.assertNotIn("situationCode", after)
        self.assertEqual((1, 0), (before["away"], before["home"]))
        self.assertEqual((2, 0), (after["away"], after["home"]))

    def test_third_period_pp_goal_goes_5on3_to_5on4(self):
        self.assertEqual("1351", self.at(self.P3 + 39.5)["situationCode"])
        self.assertEqual((2, 0), (self.at(self.P3 + 39.5)["away"], self.at(self.P3 + 39.5)["home"]))
        self.assertEqual("1451", self.at(self.P3 + 40)["situationCode"])
        self.assertEqual((2, 1), (self.at(self.P3 + 40)["away"], self.at(self.P3 + 40)["home"]))
        self.assertEqual((2, 1), (self.at(self.P3 + 99)["away"], self.at(self.P3 + 99)["home"]))
        self.assertEqual((2, 2), (self.at(self.P3 + 100)["away"], self.at(self.P3 + 100)["home"]))

    def test_summary_scoring(self):
        first = self.scoring(self.P1 + 1)
        self.assertEqual(1, len(first))
        self.assertEqual({"number": 1, "periodType": "REG", "maxRegulationPeriods": 3}, first[0]["periodDescriptor"])
        self.assertEqual([], first[0]["goals"])
        self.assertEqual(1, len(self.goals(self.P1 + 15)))
        self.assertEqual(1, len(self.goals(self.P2 + 29)))
        self.assertEqual(2, len(self.goals(self.P2 + 30)))
        self.assertEqual(3, len(self.goals(self.P3 + 40)))
        self.assertEqual([1, 2, 3, 4], [p["periodDescriptor"]["number"] for p in self.scoring(self.OT + 1)])
        self.assertEqual("OT", self.scoring(self.OT + 1)[3]["periodDescriptor"]["periodType"])
        for t in (self.SO + 5, 1000):
            periods = self.scoring(t)
            self.assertEqual([1, 2, 3, 4], [p["periodDescriptor"]["number"] for p in periods], "no shootout entry")
            goals = self.goals(t)
            self.assertEqual(["ev", "pp", "pp", "ev"], [g["strength"] for g in goals])
            self.assertEqual([(1, 0), (2, 0), (2, 1), (2, 2)], [(g["awayScore"], g["homeScore"]) for g in goals])
            self.assertEqual(["CGY", "CGY", "EDM", "EDM"], [g["teamAbbrev"]["default"] for g in goals])
            self.assertEqual([False, False, True, True], [g["isHome"] for g in goals])
            self.assertEqual(["1551", "1541", "1351", "1551"], [g["situationCode"] for g in goals])
            self.assertEqual(["00:15", "00:30", "00:40", "01:40"], [g["timeInPeriod"] for g in goals])

    def test_tie_into_the_shootout_and_final(self):
        s = self.at(self.SO + 1)
        self.assertEqual((5, "SO", 2, 2), (s["period"], s["periodType"], s["away"], s["home"]))
        final = self.at(1000)
        self.assertEqual("FINAL", final["gameState"])
        self.assertEqual((2, 3), (final["away"], final["home"]))
        self.assertEqual("SO", final["lastPeriodType"])
        self.assertNotIn("situationCode", final)

    def test_phase_order_with_the_110_s_third(self):
        seen, starts = [], {}
        for t in range(0, 460):
            s = self.at(t)
            key = (s["gameState"], s.get("period"), s.get("inIntermission", False))
            if not seen or seen[-1] != key:
                seen.append(key)
                starts[key] = t
        self.assertEqual([
            ("FUT", None, False), ("PRE", None, False),
            ("LIVE", 1, False), ("LIVE", 1, True), ("LIVE", 2, False), ("LIVE", 2, True),
            ("CRIT", 3, False), ("LIVE", 4, False), ("LIVE", 5, False), ("OVER", 5, False), ("OFF", 5, False), ("FINAL", 5, False),
        ], seen)
        self.assertEqual(110, starts[("LIVE", 4, False)] - starts[("CRIT", 3, False)])
        self.assertEqual(415, starts[("FINAL", 5, False)])

    def test_scales_with_the_timeline(self):
        self.panel.update_config({"sports_timeline_s": 720})
        t = 2 * (self.P1 + 35)
        self.assertNotIn("situationCode", self.at(t - 1))
        self.assertEqual("1451", self.at(t)["situationCode"])
        self.assertEqual(60, self.at(t)["situationSeconds"])

    def test_served_over_http_and_listed_by_admin(self):
        self.admin("/admin/sports", {"anchor_unix": time.time() - (self.P1 + 58)})  # the 20 s bucket start lands in 39..58
        gid = self.featured()
        _, j = self.get_json(f"/v1/gamecenter/{gid}/landing")
        self.assertEqual("1451", j["situation"]["situationCode"])
        self.assertIn("scoring", j["summary"])
        _, d = self.get_json(f"/v1/score/{self.today().isoformat()}")
        self.assertEqual("1451", d["games"][0]["situation"]["situationCode"])
        self.assertNotIn("summary", d["games"][0])
        listed = {g["id"]: g for g in self.admin("/admin/sports", {})["games"]}
        self.assertEqual("1451", listed[gid]["state"]["situationCode"])


class TestPowerPlayScripts(PpCase):
    overrides = {"sports": True, "sports_pp": True}
    P1 = TestPowerPlayTimeline.P1
    P3 = TestPowerPlayTimeline.P3

    def script(self, patch):
        self.panel.update_config({"sports_script": {self.featured(): patch}})

    def test_goal_strength_overrides_the_latest_entry(self):
        self.script({"goalStrength": "sh"})
        self.assertEqual(["ev", "pp", "sh"], [g["strength"] for g in self.goals(self.P3 + 50)])
        self.assertEqual(["sh"], [g["strength"] for g in self.goals(self.P1 + 20)])

    def test_scripted_empty_net_code_and_seconds(self):
        self.script({"situationCode": "0651", "situationSeconds": 42})  # no timeline situation at P1 + 10
        sit = self.sit(self.P1 + 10)
        self.assertEqual(("0651", 42, "00:42"), (sit["situationCode"], sit["secondsRemaining"], sit["timeRemaining"]))
        self.assertEqual((6, 5), (sit["awayTeam"]["strength"], sit["homeTeam"]["strength"]))
        self.assertEqual(["PP"], sit["awayTeam"]["situationDescriptions"])
        self.assertEqual(sit, self.sit(self.P1 + 10, landing=False))

    def test_scripted_code_default_seconds_and_undecodable_codes(self):
        self.script({"situationCode": "1571"})
        self.assertEqual(sports.SIT_DEFAULT_S, self.sit(self.P1 + 10)["secondsRemaining"])
        self.assertEqual("1571", self.sit(self.P1 + 10)["situationCode"])
        self.assertEqual(30, self.sit(self.P1 + 35)["secondsRemaining"], "the timeline's seconds under a scripted code")
        self.script({"situationCode": "0331"})
        self.assertEqual("0331", self.sit(self.P1 + 10)["situationCode"])

    def test_null_clears_the_timeline_situation_and_seconds_override(self):
        t = self.P1 + 40
        self.assertIsNotNone(self.sit(t))
        self.script({"situationCode": None})
        self.assertIsNone(self.sit(t))
        self.assertIsNone(self.sit(t, landing=False))
        self.script({"situationSeconds": 7})
        self.assertEqual(7, self.sit(t)["secondsRemaining"])

    def test_scripted_code_works_with_sports_pp_off(self):
        self.panel.update_config({"sports_pp": False})
        self.script({"situationCode": "1541", "situationSeconds": 90})
        body = self.body(self.P1 + 10, True)
        self.assertEqual("1541", body["situation"]["situationCode"])
        self.assertNotIn("summary", body)

    def test_bad_config_is_rejected(self):
        bad = [{"sports_pp": "yes"}, {"sports_pp": 1}]
        for key, v in (("situationCode", "15x1"), ("situationCode", "151"), ("situationCode", "14511"), ("situationCode", 1451),
                       ("situationCode", True), ("situationSeconds", 600), ("situationSeconds", -1), ("situationSeconds", True),
                       ("situationSeconds", 1.5), ("situationSeconds", "30"), ("goalStrength", "xx"), ("goalStrength", None)):
            bad.append({"sports_script": {"1": {key: v}}})
        for patch in bad:
            with self.assertRaises(ValueError, msg=patch):
                self.panel.update_config(patch)
        self.panel.update_config({"sports_script": {"1": {"situationCode": None, "situationSeconds": 0, "goalStrength": "pp"}}})


if __name__ == "__main__":
    unittest.main()
