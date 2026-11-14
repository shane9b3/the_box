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


if __name__ == "__main__":
    unittest.main()
