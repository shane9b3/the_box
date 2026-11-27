"""Self-tests for fakepanel's CHL routes (docs/design/m6-chl-leagues.md section 7.3). Run from the repo root:

    python3 -I -m unittest discover -s tools/fakepanel -p 'test_sports*.py'

The scenario is checked through the reference power-play model (`tools/chl/pp_replay.py`), so the fake panel plays what
the app's derivation should show: a power play, a 5-on-3, the PP goal releasing a minor, a double minor, the S1 hide, an
OT power play.
"""
import copy
import http.client
import json
import os
import re
import struct
import sys
import time
import unittest
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "chl"))
import fakepanel  # noqa: E402
import pp_replay  # noqa: E402
import sports_chl  # noqa: E402

FEED = "/hockeytech/feed/index.php"


def seg_start(label, scale=1.0):
    t = 0.0
    for s in sports_chl.FEATURED:
        if s["label"] == label:
            return t
        t += s["len"] * scale
    raise KeyError(label)


class ChlCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False, "sports": True, "sports_chl": True})
        cls.panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        cls.main, cls.lb = fakepanel.start_servers(cls.panel, "127.0.0.1", 0)
        cls.port = cls.panel.main_port

    @classmethod
    def tearDownClass(cls):
        fakepanel.stop_servers(cls.panel, cls.main, cls.lb)

    def setUp(self):
        self.panel.reset()
        for k, v in sports_chl.DEFAULTS.items():
            self.panel.cfg[k] = v
        self.panel.cfg["sports_chl"] = True
        self.chl = self.panel.sports_chl

    # ---- http
    def raw(self, path, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", path, headers=headers or {})
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, {k.lower(): v for k, v in r.getheaders()}, body

    def get_json(self, path, headers=None):
        st, h, body = self.raw(path, headers)
        self.assertEqual(200, st, body)
        return h, json.loads(body)

    def feed(self, client, view, key=None, **params):
        q = {"feed": "modulekit", "view": view, "key": key or sports_chl.fake_key(client), "client_code": client,
             "fmt": "json", "lang": "en"}
        q.update(params)
        return self.raw(FEED + "?" + urllib.parse.urlencode(q))

    def node(self, client, node, gid, headers=None):
        mid = "" if node == "runningclock" else "/1"
        return self.raw(f"/firebase/svf/{client}/{node}{mid}/games/{gid}.json", headers)

    def node_json(self, client, node, gid):
        st, _, body = self.node(client, node, gid)
        self.assertEqual(200, st)
        return json.loads(body)

    def admin(self, path, patch):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", json.dumps(patch).encode(), method="POST")
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())

    def at(self, label, offset=1.0):
        with self.chl.lock:
            self.chl.anchor = time.time() - seg_start(label, self.chl.scale()) - offset

    def featured(self, client):
        return sports_chl.SLATE[client][0][0]

    # ---- the reference model at the served state
    def derived(self, client):
        """(advantage side, kind, seconds, hidden) from pp_replay on the featured game's served nodes, or None."""
        gid = self.featured(client)
        rc = self.node_json(client, "runningclock", gid)
        pens = (self.node_json(client, "penalties", gid) or {}).get("GamePenalties") or {}
        goals = []
        for x in ((self.node_json(client, "goals", gid) or {}).get("GameGoals") or {}).values():
            per, t = int(x["Period"]), pp_replay.mmss(x["Time"])
            goals.append(dict(side=pp_replay.side_of(x["IsHome"]), t=pp_replay.g(per, t), pp=x["PowerPlay"] == 1,
                              sh=False, ps=False, en=False, per=per, time=x["Time"]))
        clock = rc["Clock"]
        p = int(clock["period"])
        rem = int(clock["Minutes"]) * 60 + int(clock["Seconds"])
        now = pp_replay.g(p, pp_replay.plen(p) - rem)
        sim = pp_replay.Sim(pp_replay.counted_rows(pens), goals, pp_replay.h9_windows(pens), end=now + 5)
        return sim.state(now)


class RoutesTest(ChlCase):
    def test_off_without_the_flag(self):
        self.panel.cfg["sports_chl"] = False
        self.assertEqual(404, self.feed("ohl", "seasons")[0])
        self.assertEqual(404, self.node("ohl", "runningclock", "900001")[0])

    def test_keys(self):
        st, h, body = self.feed("ohl", "seasons", key="nope")
        self.assertEqual((200, b"Invalid key."), (st, body))
        self.assertIn("text/html", h["content-type"])
        self.assertEqual(b"Client access denied.", self.feed("ohl", "seasons", key=sports_chl.fake_key("whl"))[2])
        self.panel.cfg["sports_chl_refuse"] = True
        self.assertEqual(b"Invalid key.", self.feed("ohl", "seasons")[2])

    def test_board_teams_and_seasons(self):
        day = self.chl.day("whl").isoformat()
        st, h, body = self.feed("whl", "gamesbydate", fetch_date=day)
        self.assertEqual(200, st)
        self.assertEqual("max-age=240", h["cache-control"])
        kit = json.loads(body)["SiteKit"]
        self.assertEqual(sports_chl.fake_key("whl"), kit["Parameters"]["key"])  # echoed, as HockeyTech
        self.assertEqual(4, len(kit["Gamesbydate"]))
        g = kit["Gamesbydate"][0]
        for k in ("game_id", "season_id", "date_played", "schedule_time", "timezone", "status", "final", "periods",
                  "home_team_code", "visiting_team_nickname", "time_tbd"):
            self.assertIn(k, g)
        self.assertEqual([], json.loads(self.feed("whl", "gamesbydate", fetch_date="2001-01-01")[2])["SiteKit"]["Gamesbydate"])
        teams = json.loads(self.feed("whl", "teamsbyseason", season_id="295")[2])["SiteKit"]["Teamsbyseason"]
        self.assertEqual({"CGY", "EDM", "WEN", "VIC"}, {t["code"] for t in teams})
        self.assertTrue(all(t["team_logo_url"].startswith(f"http://127.0.0.1:{self.port}/leaguestat/whl/logos/") for t in teams))
        q = json.loads(self.feed("lhjmq", "teamsbyseason", season_id="214")[2])["SiteKit"]["Teamsbyseason"]
        self.assertIn("Cha", {t["code"] for t in q})  # mixed case, as the QMJHL's feed
        self.assertIn("NFL", {t["code"] for t in q})  # a team code, never a league (L2)
        seasons = json.loads(self.feed("ohl", "seasons")[2])["SiteKit"]["Seasons"]
        self.assertEqual("0", next(s for s in seasons if s["season_id"] == "88")["playoff"])

    def test_etag_and_304(self):
        gid = self.featured("ohl")
        st, h, body = self.node("ohl", "goalssummary", gid)
        self.assertEqual(200, st)
        self.assertNotIn("etag", h)  # only with X-Firebase-ETag, as Firebase
        st, h, body = self.node("ohl", "goalssummary", gid, {"X-Firebase-ETag": "true"})
        tag = h["etag"]
        st, h2, body2 = self.node("ohl", "goalssummary", gid, {"X-Firebase-ETag": "true", "If-None-Match": tag})
        self.assertEqual((304, b""), (st, body2))
        self.assertEqual(tag, h2["etag"])
        st, _, _ = self.node("ohl", "goalssummary", gid, {"X-Firebase-ETag": "true", "If-None-Match": "other"})
        self.assertEqual(200, st)
        st, h3, body3 = self.node("ohl", "goalssummary", gid, {"If-None-Match": tag})  # as Firebase: no X-Firebase-ETag needed
        self.assertEqual((304, b""), (st, body3))
        self.assertNotIn("etag", h3)

    def test_unknown_game_root_and_refusal(self):
        st, h, body = self.node("ohl", "runningclock", "123", {"X-Firebase-ETag": "true"})
        self.assertEqual((200, b"null", "null_etag"), (st, body, h["etag"]))
        self.assertEqual(401, self.raw("/firebase/svf.json")[0])
        self.panel.cfg["sports_chl_firebase_refuse"] = True
        st, _, body = self.node("ohl", "runningclock", self.featured("ohl"))
        self.assertEqual(401, st)
        self.assertEqual("Permission denied", json.loads(body)["error"])

    def test_date_queries(self):
        self.at("2nd")
        day = self.chl.day("ohl").isoformat()
        q = urllib.parse.urlencode({"orderBy": '"DatePlayed"', "equalTo": f'"{day}"'})
        _, j = self.get_json(f"/firebase/svf/ohl/runningclock/games.json?{q}")
        self.assertIn(self.featured("ohl"), j)
        self.assertNotIn(sports_chl.SLATE["ohl"][2][0], j)  # the evening game is not in the database
        _, j = self.get_json(f"/firebase/svf/ohl/goalssummary/1/games.json?{q}")
        self.assertIn("HomeGoalTotal", j[self.featured("ohl")])
        _, j = self.get_json(f"/firebase/svf/ohl/penalties/1/games.json?{q}")
        self.assertEqual({}, j)  # its DatePlayed is empty, as the real node
        st, _, body = self.raw(f"/firebase/svf/ohl/runningclock/games.json?{q}", {"X-Firebase-ETag": "true"})
        self.assertEqual(400, st)
        self.assertIn(b"Mixing querying parameters", body)

    def test_logos(self):
        st, h, body = self.raw("/leaguestat/ohl/logos/14.png")
        self.assertEqual((200, "image/png"), (st, h["content-type"]))
        self.assertEqual(6, body[25])  # RGBA
        self.assertEqual(2, self.raw("/leaguestat/whl/logos/222.png")[2][25])  # RGB: WEN falls back
        self.assertEqual(404, self.raw("/leaguestat/ohl/logos/18.png")[0])  # BRAM: no PNG after the swap
        st, h, body = self.raw("/leaguestat/ohl/logos/18_88.jpg")
        self.assertEqual((200, "image/jpeg"), (st, h["content-type"]))
        self.assertEqual(404, self.raw("/leaguestat/ohl/logos/x.png")[0])

    def test_admin(self):
        j = self.admin("/admin/sports-chl", {"restart": True})
        self.assertTrue(j["enabled"])
        self.assertEqual(4, len(j["leagues"]["lhjmq"]["games"]))
        self.assertEqual(sports_chl.TIMELINE_DEFAULT_S, round(j["timeline_s"]))

    def test_check_config(self):
        for bad in ({"sports_chl": 1}, {"sports_chl_timeline_s": 5}, {"sports_chl_drop_penalties": -1},
                    {"sports_chl_drop_penalties": True}, {"sports_chl_null_live": "yes"}):
            with self.assertRaises(ValueError, msg=bad):
                sports_chl.check_config(bad)
        sports_chl.check_config({"sports_chl_timeline_s": 600, "sports_chl_drop_penalties": 3})


class ScenarioTest(ChlCase):
    def clock(self, client="ohl"):
        rc = self.node_json(client, "runningclock", self.featured(client))
        return rc["Clock"], rc["status_id"]

    def score(self, client="ohl"):
        gs = self.node_json(client, "goalssummary", self.featured(client))
        return gs["VisitorGoalTotal"], gs["HomeGoalTotal"]

    def test_pre_game_and_puck_drop(self):
        self.at("pre")
        c, status = self.clock()
        self.assertEqual(("-", "-", "1", 1), (c["Minutes"], c["Seconds"], c["period"], status))
        self.assertIsNone(self.node_json("ohl", "penalties", self.featured("ohl")))
        board = json.loads(self.feed("ohl", "gamesbydate", fetch_date=self.chl.day("ohl").isoformat())[2])
        self.assertEqual("1", board["SiteKit"]["Gamesbydate"][0]["status"])
        self.at("puck drop", 5)
        c, status = self.clock()
        self.assertEqual((2, True, 1), (status, c["Running"], c["period"]))

    def test_power_play_and_5_on_3(self):
        self.at("power play", 10)
        self.assertEqual(("H", "ONE_MAN"), self.derived("ohl")[:2])
        self.at("5-on-3", 5)
        self.assertEqual(("H", "TWO_MAN"), self.derived("ohl")[:2])

    def test_pp_goal_then_time_on_one_poll_later(self):
        gid = self.featured("ohl")
        self.at("pp goal", 2)
        goals = self.node_json("ohl", "goals", gid)["GameGoals"]
        self.assertEqual(1, len(goals))
        self.assertEqual(1, next(iter(goals.values()))["PowerPlay"])
        self.assertEqual((0, 1), self.score())
        self.assertEqual(0, self.node_json("ohl", "penalties", gid)["GamePenalties"]["101"]["TimeOnSet"])
        self.assertEqual(("H", "ONE_MAN"), self.derived("ohl")[:2])  # the goal released one of the two minors
        self.at("pp goal, TimeOn", 2)
        p = self.node_json("ohl", "penalties", gid)["GamePenalties"]["101"]
        self.assertEqual((1, "09:10"), (p["TimeOnSet"], p["TimeOn"]))

    def test_double_minor_and_the_s1_hide(self):
        self.at("double minor", 5)
        st = self.derived("ohl")
        self.assertEqual(("A", "ONE_MAN"), st[:2])
        self.assertGreater(st[2], 120)  # counts down once from 4:00
        self.at("major vs minor", 5)
        self.assertTrue(self.derived("ohl")[3], "H9: a flagged major against an unflagged minor hides the bar")

    def test_break_forms_tie_and_ot(self):
        self.at("break", 5)
        c, status = self.clock()
        self.assertEqual(("00", "00", False, 1, 2), (c["Minutes"], c["Seconds"], c["Running"], c["period"], status))
        self.at("20:00 2nd", 5)
        c, _ = self.clock()
        self.assertEqual(("20", "00", False, 2), (c["Minutes"], c["Seconds"], c["Running"], c["period"]))
        self.at("tie", 5)
        self.assertEqual((1, 1), self.score())
        self.at("ot", 15)
        c, _ = self.clock()
        self.assertEqual(4, c["period"])
        self.assertEqual("H", self.derived("ohl")[0])  # 4-on-3 for the home side
        board = json.loads(self.feed("ohl", "gamesbydate", fetch_date=self.chl.day("ohl").isoformat())[2])
        self.assertEqual("300", board["SiteKit"]["Gamesbydate"][0]["periods"]["4"]["length"])

    def test_shootout_and_final(self):
        self.at("ot 0:00", 5)
        c, status = self.clock("whl")
        self.assertEqual(("00", "00", False, 4, 2), (c["Minutes"], c["Seconds"], c["Running"], c["period"], status))
        self.at("shootout", 5)
        for client in ("whl", "lhjmq"):
            so = self.node_json(client, "shootouts", self.featured(client))
            self.assertEqual(2, len(so["ShootoutAttempts"]))
            self.assertFalse(self.node_json(client, "goalssummary", self.featured(client))["Shootout"])
        self.assertIsNone(self.node_json("ohl", "shootouts", self.featured("ohl")))  # the OHL has no node
        self.at("final so", 5)
        gs = self.node_json("whl", "goalssummary", self.featured("whl"))
        self.assertTrue(gs["Shootout"])
        self.assertEqual((1, 2), (gs["VisitorGoalTotal"], gs["HomeGoalTotal"]))  # the winner's +1
        self.assertEqual([None, 1, 0, 0, 0], gs["HomeGoalsByPeriod"])  # the +1 is in the total only
        pc = self.node_json("whl", "publishedclock", self.featured("whl"))
        self.assertEqual((True, 4), (pc["Final"], pc["StatusId"]))

    def test_the_s3_game_holds_0_00_at_status_2(self):
        gid = sports_chl.SLATE["ohl"][1][0]
        with self.chl.lock:
            self.chl.anchor = time.time() - sports_chl.SEGMENT_S3_RUN - 5
        rc = self.node_json("ohl", "runningclock", gid)
        gs = self.node_json("ohl", "goalssummary", gid)
        self.assertEqual(("00", "00", 2), (rc["Clock"]["Minutes"], rc["Clock"]["Seconds"], rc["status_id"]))
        self.assertEqual((1, 4), (gs["VisitorGoalTotal"], gs["HomeGoalTotal"]))
        with self.chl.lock:
            self.chl.anchor = time.time() - sports_chl.SEGMENT_S3_RUN - sports_chl.SEGMENT_S3_HOLD - 5
        self.assertEqual(4, self.node_json("ohl", "runningclock", gid)["status_id"])

    def test_board_mode_games(self):
        evening, evicted = sports_chl.SLATE["ohl"][2][0], sports_chl.SLATE["ohl"][3][0]
        self.assertIsNone(self.node_json("ohl", "runningclock", evening))
        self.assertIsNone(self.node_json("ohl", "runningclock", evicted))
        games = json.loads(self.feed("ohl", "gamesbydate", fetch_date=self.chl.day("ohl").isoformat())[2])["SiteKit"]["Gamesbydate"]
        by = {g["game_id"]: g for g in games}
        self.assertEqual(("1", "0"), (by[evening]["status"], by[evening]["final"]))
        self.assertEqual(("4", "1"), (by[evicted]["status"], by[evicted]["final"]))

    def test_switches(self):
        self.at("2nd", 5)
        self.panel.cfg["sports_chl_null_live"] = True
        self.assertIsNone(self.node_json("ohl", "runningclock", self.featured("ohl")))
        self.panel.cfg["sports_chl_null_live"] = False
        self.panel.cfg["sports_chl_drop_penalties"] = 3
        codes = [self.node("ohl", "penalties", self.featured("ohl"))[0] for _ in range(6)]
        self.assertEqual([200, 200, 503, 200, 200, 503], codes)

    def test_the_timeline_scales(self):
        self.panel.cfg["sports_chl_timeline_s"] = sports_chl.TIMELINE_DEFAULT_S * 2
        self.at("5-on-3", 5)
        self.assertEqual(("H", "TWO_MAN"), self.derived("ohl")[:2])


class LiveGateTest(ChlCase):
    """`pp_replay.py live` on a recording of the featured game, in record_live.py's format, taken every 5 s."""

    def record(self, folder, client, drop_penalties=False):
        sp = self.chl
        game = sp.games(client)[0]
        _, total = sp.seg_starts()
        last = {}
        with open(os.path.join(folder, "polls.jsonl"), "w") as f:
            t, k = 0.0, 0
            while t <= total + 10:
                nodes = sp.nodes(client, game, sp.anchor + t)
                k += 1
                failed = drop_penalties and k % 3 == 0
                status = {n: 200 for n in sports_chl.NODES}
                changed = {}
                for n, v in nodes.items():
                    if n == "penalties" and failed:
                        status[n] = 503
                        continue
                    if last.get(n, "unset") != v:
                        changed[n] = v
                        last[n] = v
                f.write(json.dumps({"unix": sp.anchor + t, "status": status, "nodes": changed}) + "\n")
                t += 5.0
        day = sp.day(client).isoformat()
        for name, at in (("board-start.json", sp.anchor), ("board-end.json", sp.anchor + total + 10)):
            params = {"feed": "modulekit", "view": "gamesbydate", "key": "KEY_REDACTED", "client_code": client,
                      "fetch_date": day}
            with open(os.path.join(folder, name), "w") as f:
                json.dump(sp.sitekit("Gamesbydate", params, [sp.board_entry(client, g, at) for g in sp.games(client)]), f)
        with open(os.path.join(folder, "meta.json"), "w") as f:
            json.dump({"league": client, "game": game[0], "interval_s": 5, "date": day}, f)

    def test_the_fake_game_passes_the_gate(self):
        import io
        import tempfile
        from contextlib import redirect_stdout
        for client in ("ohl", "whl"):
            with tempfile.TemporaryDirectory() as d:
                self.record(d, client)
                buf = io.StringIO()
                with redirect_stdout(buf):
                    rc = pp_replay.cmd_live(d)
                self.assertEqual(0, rc, buf.getvalue())
                self.assertIn("PASS", buf.getvalue())
                self.assertRegex(buf.getvalue(), r"final-model PP [1-9]")

    def test_dropped_penalties_count_as_hidden(self):
        import io
        import re
        import tempfile
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory() as d:
            self.record(d, "ohl", drop_penalties=True)
            buf = io.StringIO()
            with redirect_stdout(buf):
                pp_replay.cmd_live(d)
            hidden = int(re.search(r"hidden (\d+) s", buf.getvalue())[1])
            self.assertGreater(hidden, 0, buf.getvalue())


class _StubPanel:
    def __init__(self, **cfg):
        self.cfg = dict(sports_chl.DEFAULTS, sports=True, sports_chl=True, **cfg)


def _unix(client_zone, y, mo, d, h, mi):
    import datetime as dt
    from zoneinfo import ZoneInfo
    return dt.datetime(y, mo, d, h, mi, tzinfo=ZoneInfo(client_zone)).timestamp()


def _mapper_start(entry, client):
    """HockeyTechMapper.boardGame's start: date_played + schedule_time in timezone; "Time TBD" = midnight of
    date_played in the league's reference zone. Returns (unix s, tbd)."""
    import datetime as dt
    from zoneinfo import ZoneInfo
    day = dt.date.fromisoformat(entry["date_played"])
    if entry["time_tbd"] in ("1", "true"):
        return int(dt.datetime.combine(day, dt.time(0), ZoneInfo(sports_chl.ZONES[client])).timestamp()), True
    t = dt.time.fromisoformat(entry["schedule_time"])
    return int(dt.datetime.combine(day, t, ZoneInfo(entry["timezone"])).timestamp()), False


class StartTimeTest(unittest.TestCase):
    """(Emulator pass, defect 3) Every date/time field of a game names one league day (05:00 rollover), on both sides of
    05:00, in every league zone, over three consecutive days and the DST change days. The clock is injected."""

    DAYS = [(2026, 11, 12), (2026, 11, 13), (2026, 11, 14), (2026, 3, 8), (2026, 11, 1)]
    TIMES = [(0, 0), (0, 30), (2, 30), (3, 30), (4, 59), (5, 0), (5, 1), (8, 0), (12, 0), (19, 0), (21, 30), (22, 59),
             (23, 30), (23, 59)]

    def slate(self, client, unix, **cfg):
        sp = sports_chl.SportsChl(_StubPanel(**cfg), clock=lambda: unix)
        self.assertEqual(unix, sp.anchor)
        return sp

    def check(self, sp, client, label, at):
        import datetime as dt
        from zoneinfo import ZoneInfo
        ref = ZoneInfo(sports_chl.ZONES[client])
        day = sp.day(client)
        want_day = (dt.datetime.fromtimestamp(sp.anchor, ref) - dt.timedelta(hours=5)).date()
        self.assertEqual(want_day, day, label)
        for g in sp.games(client):
            e = sp.board_entry(client, g, at)
            start, tbd = _mapper_start(e, client)
            self.assertEqual(day.isoformat(), e["date_played"], label)
            self.assertEqual(g[3], start, f"{label} {g[4]}: date_played + schedule_time in timezone is the listed start")
            if not tbd:
                league_day = (dt.datetime.fromtimestamp(start, ref) - dt.timedelta(hours=5)).date()
                self.assertEqual(day, league_day, f"{label} {g[4]}: the start is on the league day")
            nodes = sp.nodes(client, g, at)
            for n in ("runningclock", "publishedclock", "goalssummary", "shotssummary"):
                if nodes[n] is not None:
                    self.assertEqual(day.isoformat(), nodes[n]["DatePlayed"], f"{label} {g[4]} {n}")
        featured = sp.games(client)[0][3]
        drop = sp.featured_start()
        self.assertLessEqual(featured, drop + 1, label)
        # At or before the puck drop: never ~24 h off, inside the app's 8 h hard expiry. The worst case is a run just before
        # 05:00 Vancouver (listed at 23:59 Edmonton): 6 h, 7 h on the night the clocks go back.
        self.assertLess(drop - featured, 7.5 * 3600, label)
        return featured, drop

    def test_every_field_names_one_league_day(self):
        for client in sports_chl.CLIENTS:
            zone = sports_chl.ZONES[client]
            for (y, mo, d) in self.DAYS:
                for (h, mi) in self.TIMES:
                    unix = _unix(zone, y, mo, d, h, mi)
                    sp = self.slate(client, unix)
                    label = f"{client} {y}-{mo:02d}-{d:02d} {h:02d}:{mi:02d} {zone}"
                    _, total = sp.seg_starts()
                    for at in (unix, unix + 60, unix + total + 10):
                        featured, drop = self.check(sp, client, label, at)
                    if 5 <= h < 22:
                        # The usual evening: the listed start is the puck drop itself (whole seconds).
                        self.assertEqual(int(drop), featured, label)

    def test_the_reported_whl_case_is_not_a_day_off(self):
        # WHL 9100001 at 02:30 Vancouver (03:30 Edmonton) on 2026-11-14: it was listed as 2026-11-13 03:30 Edmonton.
        unix = _unix("America/Vancouver", 2026, 11, 14, 2, 30)
        sp = self.slate("whl", unix)
        e = sp.board_entry("whl", sp.games("whl")[0], unix + 60)
        self.assertEqual("9100001", e["game_id"])
        self.assertEqual("2026-11-13", e["date_played"])
        start, _ = _mapper_start(e, "whl")
        self.assertGreater(start, unix - 8 * 3600, "inside the 8 h hard expiry")
        self.assertLessEqual(start, unix + 60)
        self.assertEqual("23:59:00", e["schedule_time"])

    def test_both_sides_of_the_rollover(self):
        for client in sports_chl.CLIENTS:
            zone = sports_chl.ZONES[client]
            before = self.slate(client, _unix(zone, 2026, 11, 14, 4, 59))
            after = self.slate(client, _unix(zone, 2026, 11, 14, 5, 0))
            self.assertEqual("2026-11-13", before.day(client).isoformat(), client)
            self.assertEqual("2026-11-14", after.day(client).isoformat(), client)
            for sp in (before, after):
                for g in sp.games(client):
                    self.assertEqual(sp.day(client).isoformat(), sp.board_entry(client, g, sp.anchor)["date_played"])
            # After the rollover the featured game is listed at its puck drop.
            self.assertEqual(int(after.featured_start()), after.games(client)[0][3], client)

    def test_a_clamped_start_caps_the_timeline_before_the_hard_expiry(self):
        long = 86400
        for client in sports_chl.CLIENTS:
            zone = sports_chl.ZONES[client]
            for (h, mi) in ((0, 30), (2, 30), (4, 59)):
                unix = _unix(zone, 2026, 11, 14, h, mi)
                logs = []
                panel = _StubPanel(sports_chl_timeline_s=long)
                panel.log = logs.append
                sp = sports_chl.SportsChl(panel, clock=lambda: unix)
                label = f"{client} {h:02d}:{mi:02d}"
                self.assertLess(sp.scale(), sp.raw_scale(), label)
                starts, _ = sp.seg_starts()
                final_at = sp.anchor + starts[sports_chl.SEG["final so"]]
                for c in sports_chl.CLIENTS:
                    listed = sp.games(c)[0][3]
                    self.assertLessEqual(final_at, listed + sports_chl.HARD_EXPIRY_S - sports_chl.EXPIRY_MARGIN_S, f"{label} {c}")
                    self.assertEqual(4, sp.state(c, sp.games(c)[0], final_at + 1)["status"], f"{label} {c}: final by then")
                self.assertEqual(1, len(logs), label)
                self.assertIn("capped", logs[0])
                sp.reset()  # a restart logs it again, once
                self.assertEqual(2, len(logs), label)

    def test_no_cap_in_normal_hours_or_at_the_default_timeline(self):
        for client in sports_chl.CLIENTS:
            zone = sports_chl.ZONES[client]
            # One scale serves every league, so "normal hours" must be normal in all three zones (08:00 Toronto is 05:00
            # Vancouver); the 24 h timeline's 1.8 h pre-game must also end before the eastmost game zone's midnight.
            for (h, mi) in ((8, 0), (12, 0), (16, 0)):
                logs = []
                panel = _StubPanel(sports_chl_timeline_s=86400)
                panel.log = logs.append
                sp = sports_chl.SportsChl(panel, clock=lambda: _unix(zone, 2026, 11, 14, h, mi))
                self.assertIsNone(sp.scale_cap(), f"{client} {h}:{mi}")
                self.assertEqual(sp.raw_scale(), sp.scale())
                self.assertEqual([], logs)
            for (y, mo, d) in self.DAYS:
                for (h, mi) in self.TIMES:  # the default 520 s is never capped, clamped or not
                    sp = self.slate(client, _unix(zone, y, mo, d, h, mi))
                    self.assertEqual(1.0, sp.scale(), f"{client} {y}-{mo}-{d} {h}:{mi}")

    def test_time_tbd(self):
        for client in sports_chl.CLIENTS:
            zone = sports_chl.ZONES[client]
            for (h, mi) in ((2, 30), (5, 0), (14, 0), (23, 30)):
                unix = _unix(zone, 2026, 11, 14, h, mi)
                off = self.slate(client, unix)
                on = self.slate(client, unix, sports_chl_time_tbd=True)
                entries = [on.board_entry(client, g, unix + 60) for g in on.games(client)]
                tbd = [e for e in entries if e["time_tbd"] == "1"]
                self.assertEqual(1, len(tbd), f"{client} {h}:{mi}: exactly one TBD game per league")
                e = tbd[0]
                self.assertEqual(sports_chl.SLATE[client][2][0], e["game_id"])
                self.assertEqual(("1", "0", "0"), (e["status"], e["final"], e["started"]), "scheduled: not live, not final")
                self.assertEqual("00:00:00", e["schedule_time"])
                start, is_tbd = _mapper_start(e, client)
                self.assertTrue(is_tbd)
                self.assertEqual(on.games(client)[2][3], start, "the listed start is the mapper's placeholder")
                self.assertEqual(on.day(client).isoformat(), e["date_played"])
                # The other games are unchanged by the switch.
                plain = [off.board_entry(client, g, unix + 60) for g in off.games(client)]
                for a, b in zip(plain, entries):
                    if a["game_id"] != e["game_id"]:
                        self.assertEqual(a, b)
                self.assertTrue(all(x["time_tbd"] == "0" for x in plain))

    def test_time_tbd_config_and_cli(self):
        sports_chl.check_config({"sports_chl_time_tbd": True})
        with self.assertRaises(ValueError):
            sports_chl.check_config({"sports_chl_time_tbd": "yes"})
        self.assertIs(False, sports_chl.DEFAULTS["sports_chl_time_tbd"])
        self.assertIn("sports_chl_time_tbd", fakepanel.DEFAULTS)
        from unittest import mock

        class Stop(Exception):
            pass

        seen = {}

        def capture(cfg, *a, **k):
            seen.update(cfg)
            raise Stop()

        with mock.patch.object(fakepanel, "Panel", side_effect=capture):
            with self.assertRaises(Stop):
                fakepanel.main(["--sports", "--sports-chl", "--chl-time-tbd"])
        self.assertIs(True, seen["sports_chl_time_tbd"])
        with mock.patch.object(fakepanel, "Panel", side_effect=capture), mock.patch("sys.stderr"):
            with self.assertRaises(SystemExit):
                fakepanel.main(["--sports", "--chl-time-tbd"])  # needs --sports-chl


class TimeTbdRouteTest(ChlCase):
    def test_admin_config_switches_it(self):
        self.admin("/admin/config", {"sports_chl_time_tbd": True})
        games = json.loads(self.feed("ohl", "gamesbydate", fetch_date=self.chl.day("ohl").isoformat())[2])["SiteKit"]["Gamesbydate"]
        self.assertEqual([sports_chl.SLATE["ohl"][2][0]], [g["game_id"] for g in games if g["time_tbd"] == "1"])
        self.admin("/admin/config", {"sports_chl_time_tbd": False})
        games = json.loads(self.feed("ohl", "gamesbydate", fetch_date=self.chl.day("ohl").isoformat())[2])["SiteKit"]["Gamesbydate"]
        self.assertTrue(all(g["time_tbd"] == "0" for g in games))



class BoardShotsTest(ChlCase):
    """Sports Hub (docs/design/m6-sports-hub.md section 10.3): the board's shots rise with the game; none before it."""

    def test_shots(self):
        self.at("pre")
        day = self.chl.day("ohl").isoformat()
        g = json.loads(self.feed("ohl", "gamesbydate", fetch_date=day)[2])["SiteKit"]["Gamesbydate"][0]
        self.assertEqual(("0", "0"), (g["visiting_shots"], g["home_shots"]))
        self.at("puck drop", 5)
        g = json.loads(self.feed("ohl", "gamesbydate", fetch_date=day)[2])["SiteKit"]["Gamesbydate"][0]
        self.assertGreater(int(g["visiting_shots"]), 0)
        self.assertGreater(int(g["home_shots"]), 0)


# ---------------------------------------------------------------- CHL hub P2 (docs/design/m6-chl-hub.md section 6.1)
HEX16 = re.compile(r"(?<![0-9a-fA-F])[0-9a-f]{16}(?![0-9a-fA-F])")


def fixture(name):
    with open(os.path.join(sports_chl.FIXTURES, name), encoding="utf-8") as f:
        t = f.read().strip()
    return json.loads(t[1:-1] if t.startswith("(") else t)


class HubCase(ChlCase):
    def setUp(self):
        super().setUp()
        self.panel.cfg["sports_chl_hub"] = True

    def view(self, client, feed, key=None, **params):
        q = {"feed": feed}
        q.update(params)
        q.update({"key": key or sports_chl.fake_key(client), "client_code": client})
        return self.raw(FEED + "?" + urllib.parse.urlencode(q))

    def gc(self, client, tab, gid, **kw):
        return self.view(client, "gc", tab=tab, game_id=gid, fmt="json", lang_code="en", **kw)

    def gc_json(self, client, tab, gid):
        st, h, body = self.gc(client, tab, gid)
        self.assertEqual(200, st, body[:200])
        return json.loads(body)["GC"]

    def goalies(self, client, team, season=None):
        st, _, body = self.view(client, "modulekit", view="statviewtype", type="topgoalies",
                                season_id=season or sports_chl.SEASONS[client], team_id=team, first="0", limit="10",
                                fmt="json", lang="en")
        return st, body

    def table(self, client, special, season=None):
        return self.view(client, "statviewfeed", view="teams", groupTeamsBy="league", context="overall",
                         season=season or sports_chl.SEASONS[client], special="true" if special else "false",
                         sort="power_play_pct" if special else "points", lang="en")

    def game(self, client, kind):
        return next(g for g in self.chl.games(client) if g[4] == kind)

    def board(self, client):
        day = self.chl.day(client).isoformat()
        kit = json.loads(self.feed(client, "gamesbydate", fetch_date=day)[2])["SiteKit"]
        return {g["game_id"]: g for g in kit["Gamesbydate"]}

    def assertShape(self, tpl, got, path="$"):
        """Every key of the fixture is there with the fixture's JSON type (a null on either side is the feed's own)."""
        if isinstance(tpl, dict) and tpl and all(k.isdigit() for k in tpl):  # a period map ({"1": …, "4": …}): any periods
            self.assertIsInstance(got, dict, path)
            for v in got.values():
                self.assertShape(next(iter(tpl.values())), v, f"{path}.n")
        elif isinstance(tpl, dict):
            self.assertIsInstance(got, dict, path)
            missing = set(tpl) - set(got)
            self.assertFalse(missing, f"{path}: missing {sorted(missing)}")
            for k, v in tpl.items():
                self.assertShape(v, got[k], f"{path}.{k}")
        elif isinstance(tpl, list):
            self.assertIsInstance(got, list, path)
            if tpl and got:
                for g in got:
                    self.assertShape(tpl[0], g, f"{path}[]")
        elif tpl is not None and got is not None:
            self.assertEqual(type(tpl), type(got), f"{path}: {tpl!r} vs {got!r}")


class HubRoutesTest(HubCase):
    def test_the_hub_games_join_the_slate(self):
        self.panel.cfg["sports_chl_hub"] = False
        self.assertEqual(4, len(self.board("ohl")))
        self.panel.cfg["sports_chl_hub"] = True
        for c in sports_chl.CLIENTS:
            b = self.board(c)
            self.assertEqual(7, len(b))
            gid = self.game(c, "hub_postponed")[0]
            self.assertEqual(("1", "Postponed", "0"), (b[gid]["status"], b[gid]["game_status"], b[gid]["started"]))
            self.assertEqual("", b[self.game(c, "hub_gp0")[0]]["game_status"])

    def test_previews_use_the_boards_games_and_teams(self):
        for c in sports_chl.CLIENTS:
            b = self.board(c)
            for gid, away, home, _, kind in self.chl.games(c):
                pv = self.gc_json(c, "preview", gid)["Preview"]
                self.assertEqual((away, home), (pv["visitor_team"]["team_id"], pv["home_team"]["team_id"]), kind)
                self.assertEqual(b[gid]["visiting_team_code"], pv["visitor_team"]["team_code"])
                self.assertEqual(b[gid]["home_team_code"], pv["home_team"]["team_code"])
                self.assertEqual(sports_chl.SEASONS[c], pv["current_season"]["id"])
                self.assertEqual("0", pv["current_season"]["playoff"])
                v = pv["visitor_team"]
                if away != sports_chl.GP0_TEAMS[c]:
                    self.assertEqual(v["gp"], v["wins"] + v["losses"] + v["ot_losses"] + v["shootout_losses"])
                    self.assertEqual(5, len(v["lastGames"]))
                    self.assertTrue(all(x["winner"] in (x["home_team"], x["visiting_team"]) for x in v["lastGames"]))
                    self.assertTrue(all(away in (x["home_team"], x["visiting_team"]) for x in v["lastGames"]))

    def test_lineups_and_starters(self):
        def starts(lineup):
            return None if lineup is None else [g["start"] for g in lineup["goalies"]]
        for c in sports_chl.CLIENTS:
            pv = self.gc_json(c, "preview", self.game(c, "evening")[0])["Preview"]
            self.assertEqual(["0", "1"], starts(pv["home_team"]["lineup"]))  # the backup listed first, the starter marked
            self.assertEqual(["0", "1"], starts(pv["visiting_team"]["lineup"]))  # the visitor's lineup is its own key
            self.assertNotIn("lineup", pv["visitor_team"])
            pv = self.gc_json(c, "preview", self.game(c, "featured")[0])["Preview"]
            self.assertEqual(["0", "1"], starts(pv["home_team"]["lineup"]))
            self.assertEqual(["0", "0"], starts(pv["visiting_team"]["lineup"]))
            pv = self.gc_json(c, "preview", self.game(c, "hub_gp0")[0])["Preview"]
            self.assertEqual(["0", "0"], starts(pv["home_team"]["lineup"]))
            pv = self.gc_json(c, "preview", self.game(c, "hub_nolineup")[0])["Preview"]
            self.assertIsNone(pv["home_team"]["lineup"])
            self.assertEqual({"lineup": None}, pv["visiting_team"])

    def test_a_team_with_no_games_played(self):
        for c in sports_chl.CLIENTS:
            gid, away, home, _, _ = self.game(c, "hub_gp0")
            self.assertEqual(sports_chl.GP0_TEAMS[c], away)
            pv = self.gc_json(c, "preview", gid)["Preview"]
            v = pv["visitor_team"]
            self.assertEqual((0, 0, 0, 0, 0, 0, 0), (v["gp"], v["wins"], v["goals_for"], v["goals_against"], v["power_plays"],
                                                     v["times_short_handed"], len(v["lastGames"])))
            self.assertEqual("0-0-0-0", v["last10"])
            self.assertEqual([], v["leadingScorers"])
            self.assertGreater(pv["home_team"]["gp"], 0)
            rows = json.loads(self.goalies(c, away)[1])["SiteKit"]["Statviewtype"]
            self.assertEqual(["0", "0"], [r["games_played"] for r in rows])
            t = json.loads(self.table(c, False)[2][1:-1])[0]["sections"][0]["data"]
            row = next(r["row"] for r in t if r["prop"]["team_code"]["teamLink"] == away)
            self.assertEqual("0", row["games_played"])
        # Without the hub games that team has played.
        self.panel.cfg["sports_chl_hub"] = False
        pv = self.gc_json("ohl", "preview", self.game("ohl", "s3")[0])["Preview"]
        self.assertTrue(all(t["gp"] > 0 for t in (pv["home_team"], pv["visitor_team"])))

    def test_series_and_playoff(self):
        pv = self.gc_json("whl", "preview", self.game("whl", "evening")[0])["Preview"]
        self.assertEqual((1, 0), (pv["HeadToHeadRecord"]["home_team"]["w"], pv["HeadToHeadRecord"]["visiting_team"]["w"]))
        self.assertEqual(1, len(pv["previous_meetings"]))
        pv = self.gc_json("whl", "preview", self.game("whl", "hub_gp0")[0])["Preview"]
        self.assertEqual([], pv["previous_meetings"])
        self.admin("/admin/config", {"sports_chl_playoff": True})
        self.assertEqual("1", self.gc_json("ohl", "preview", self.game("ohl", "evening")[0])["Preview"]["current_season"]["playoff"])

    def test_a_regulation_final(self):
        for c in sports_chl.CLIENTS:
            gid, away, home, _, _ = self.game(c, "evicted")
            b = self.board(c)[gid]
            gs = self.gc_json(c, "gamesummary", gid)["Gamesummary"]
            self.assertEqual(("4", "1", "0"), (gs["meta"]["status"], gs["meta"]["final"], gs["shootout"]))
            self.assertEqual((away, home), (gs["meta"]["visiting_team"], gs["meta"]["home_team"]))
            self.assertEqual((b["visiting_team_code"], b["home_team_code"]), (gs["visitor"]["team_code"], gs["home"]["team_code"]))
            a, h = int(b["visiting_goal_count"]), int(b["home_goal_count"])
            self.assertEqual({"visitor": a, "home": h}, gs["goalCount"])
            self.assertEqual(a + h, len(gs["goals"]))
            self.assertEqual(a, sum(gs["goalsByPeriod"]["visitor"].values()))
            gw = [g for g in gs["goals"] if g["game_winning"] == "1"]
            self.assertEqual(1, len(gw))
            self.assertEqual("1" if h > a else "0", gw[0]["home"])
            self.assertEqual(3, len([m for m in gs["mvps"] if m]))
            self.assertEqual(gw[0]["goal_scorer"]["player_id"], gs["mvps"][0]["player_id"])
            for side, other in (("visitor", "home"), ("home", "visitor")):
                self.assertEqual(gs["totalShots"][side], sum(gs["shotsByPeriod"][side].values()))
                on = [g for g in gs[f"{side}_team_lineup"]["goalies"] if g["seconds"] > 0]
                self.assertEqual(1, len(on))
                self.assertEqual(gs["totalShots"][other], on[0]["shots_against"])
                self.assertEqual(on[0]["shots_against"] - on[0]["goals_against"], on[0]["saves"])
                self.assertEqual("60:00", on[0]["time"])
                self.assertEqual(1, len(gs["goalies"][side]))
            self.assertTrue(gs["meta"]["attendance"].isdigit())
            self.assertEqual(c == "lhjmq", gs["totalHits"]["home"] > 0)

    def test_unassisted_goals_are_objects_of_nulls(self):
        gs = self.gc_json("ohl", "gamesummary", self.game("ohl", "s3")[0])["Gamesummary"]
        self.assertEqual(5, len(gs["goals"]))
        nulls = [g for g in gs["goals"] if g["assist2_player"]["last_name"] is None]
        self.assertTrue(nulls)
        self.assertTrue(all(v is None for v in nulls[0]["assist2_player"].values()))
        players = gs["home_team_lineup"]["players"] + gs["visitor_team_lineup"]["players"]
        self.assertEqual(5, sum(p["goals"] for p in players))

    def test_the_shootout_final_has_no_game_winner(self):
        self.at("final so")
        for c in sports_chl.CLIENTS:
            gs = self.gc_json(c, "gamesummary", self.featured(c))["Gamesummary"]
            self.assertEqual(("4", "1"), (gs["meta"]["status"], gs["shootout"]))
            self.assertFalse([g for g in gs["goals"] if g["game_winning"] == "1"])
            self.assertEqual(gs["goalCount"]["home"] + 1, int(gs["meta"]["home_goal_count"]))
            self.assertEqual("4", gs["meta"]["period"])

    def test_a_pre_game_summary(self):
        gs = self.gc_json("ohl", "gamesummary", self.game("ohl", "evening")[0])["Gamesummary"]
        self.assertEqual(("1", "0"), (gs["meta"]["status"], gs["meta"]["final"]))
        self.assertEqual([], gs["goals"])
        self.assertIsNone(gs["penalties"])
        self.assertEqual([None, None, None], gs["mvps"])
        self.assertTrue(all(g["seconds"] == 0 for g in gs["home_team_lineup"]["goalies"]))

    def test_topgoalies_list_the_traded_goalie_too(self):
        for c in sports_chl.CLIENTS:
            team = self.game(c, "evening")[2]
            st, body = self.goalies(c, team)
            self.assertEqual(200, st)
            rows = json.loads(body)["SiteKit"]["Statviewtype"]
            self.assertEqual(3, len(rows))
            self.assertEqual("0", rows[0]["active"])  # most games, but traded
            self.assertNotEqual(team, rows[0]["latest_team_id"])
            self.assertEqual("2", rows[0]["num_teams"])
            active = [r for r in rows if r["active"] == "1"]
            self.assertEqual([team, team], [r["latest_team_id"] for r in active])
            self.assertTrue(all(isinstance(r["games_played"], str) for r in rows))
            # The preview's marked starter is the active goalie with the most games.
            pv = self.gc_json(c, "preview", self.game(c, "evening")[0])["Preview"]
            starter = next(g for g in pv["home_team"]["lineup"]["goalies"] if g["start"] == "1")
            self.assertEqual(active[0]["player_id"], starter["player_id"])
            self.assertEqual([], json.loads(self.goalies(c, team, season="1")[1])["SiteKit"]["Statviewtype"])

    def test_the_tables_are_a_jsonp_array_joined_by_team_id(self):
        for c in sports_chl.CLIENTS:
            for special in (False, True):
                st, h, body = self.table(c, special)
                self.assertEqual(200, st)
                self.assertTrue(body.startswith(b"(") and body.endswith(b")"))
                arr = json.loads(body[1:-1])
                self.assertIsInstance(arr, list)
                rows = arr[0]["sections"][0]["data"]
                self.assertEqual(set(sports_chl.TEAMS[c]), {r["prop"]["team_code"]["teamLink"] for r in rows})
                self.assertEqual(list(range(1, len(rows) + 1)), [r["row"]["rank"] for r in rows])
                key = "power_plays" if special else "goals_for"
                self.assertTrue(all(isinstance(r["row"][key], str) for r in rows))
        self.assertEqual([], json.loads(self.table("ohl", False, season="1")[2][1:-1])[0]["sections"][0]["data"])

    def test_shapes_match_the_fixtures(self):
        files = sports_chl.TEMPLATE_FILES
        for c in sports_chl.CLIENTS:
            self.assertShape(fixture(files["preview"][c])["GC"]["Preview"],
                             self.gc_json(c, "preview", self.game(c, "evening")[0])["Preview"])
            self.assertShape(fixture(files["gamesummary"][c])["GC"]["Gamesummary"],
                             self.gc_json(c, "gamesummary", self.game(c, "evicted")[0])["Gamesummary"])
            team = self.game(c, "evening")[2]
            self.assertShape(fixture(files["topgoalies"][c])["SiteKit"]["Statviewtype"],
                             json.loads(self.goalies(c, team)[1])["SiteKit"]["Statviewtype"])
            for special in (False, True):
                self.assertShape(fixture(files["special" if special else "standard"][c]), json.loads(self.table(c, special)[2][1:-1]))

    def test_headshots(self):
        pv = self.gc_json("ohl", "preview", self.game("ohl", "evening")[0])["Preview"]
        pid = pv["home_team"]["leadingScorers"][0]["player_id"]
        st, h, body = self.raw(f"/leaguestat/ohl/240x240/{pid}.jpg")
        self.assertEqual((200, sports_chl.JPEG_MARKERS), (st, body))
        self.assertEqual(404, self.raw("/leaguestat/ohl/240x240/1.jpg")[0])
        self.assertEqual(404, self.raw(f"/leaguestat/whl/240x240/{pid}x.jpg")[0])
        self.assertEqual(200, self.raw("/leaguestat/ohl/logos/14.png")[0])  # the logos are unchanged

    def test_keys_unknown_games_and_no_real_key(self):
        gid = self.game("ohl", "evening")[0]
        self.assertEqual(b"Invalid key.", self.gc("ohl", "preview", gid, key="nope")[2])
        self.assertEqual(b"Client access denied.", self.gc("ohl", "preview", gid, key=sports_chl.fake_key("whl"))[2])
        st, _, body = self.gc("ohl", "preview", "123")
        self.assertEqual((404, {"error": "No such game"}), (st, json.loads(body)))
        self.assertEqual(404, self.gc("ohl", "pxpverbose", gid)[0])
        bodies = []
        for c in sports_chl.CLIENTS:
            for g in self.chl.games(c):
                bodies += [self.gc(c, "preview", g[0])[2], self.gc(c, "gamesummary", g[0])[2], self.goalies(c, g[1])[1]]
            bodies += [self.table(c, False)[2], self.table(c, True)[2]]
        for b in bodies:
            self.assertIsNone(HEX16.search(b.decode()), "a 16-hex token (a real key's form) in a body")
        log = json.dumps(self.panel.sports.log)
        self.assertIn("gc:preview", log)
        self.assertNotIn("fakepanel-", log)  # the request log never holds a key


class HubFaultsTest(HubCase):
    def set_fault(self, f):
        self.admin("/admin/config", {"sports_chl_detail": f})

    def test_faults(self):
        gid, _, team, _, _ = self.game("ohl", "evening")
        self.set_fault("invalid_key")
        self.assertEqual(b"Invalid key.", self.gc("ohl", "preview", gid)[2])
        self.assertEqual(b"Invalid key.", self.table("ohl", True)[2])
        self.set_fault("no_such_game")
        self.assertEqual(404, self.gc("ohl", "gamesummary", gid)[0])
        self.set_fault("oversize")
        st, _, body = self.gc("ohl", "preview", gid)
        self.assertEqual(200, st)
        self.assertGreater(len(body), 524_288)
        self.set_fault("goalies_fail")
        self.assertEqual(503, self.goalies("ohl", team)[0])
        self.assertEqual(200, self.gc("ohl", "preview", gid)[0])
        self.assertEqual(200, self.table("ohl", False)[0])
        self.set_fault("tables_fail")
        self.assertEqual(503, self.table("ohl", False)[0])
        self.assertEqual(503, self.table("ohl", True)[0])
        self.assertEqual(200, self.goalies("ohl", team)[0])
        old = sports_chl.DETAIL_SLOW_S
        sports_chl.DETAIL_SLOW_S = 0.3
        try:
            self.set_fault("slow")
            t0 = time.time()
            self.assertEqual(200, self.gc("ohl", "preview", gid)[0])
            self.assertGreaterEqual(time.time() - t0, 0.29)
        finally:
            sports_chl.DETAIL_SLOW_S = old
        self.set_fault("")
        self.assertEqual(200, self.table("ohl", True)[0])

    def test_bad_config(self):
        for patch in ({"sports_chl_detail": "nope"}, {"sports_chl_hub": "yes"}, {"sports_chl_playoff": 1}):
            with self.assertRaises(ValueError):
                sports_chl.check_config(patch)
        sports_chl.check_config({"sports_chl_detail": "tables_fail", "sports_chl_hub": True})


if __name__ == "__main__":
    unittest.main()
