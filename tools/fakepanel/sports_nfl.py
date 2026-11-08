"""The fake ESPN NFL scoreboard (docs/design/m6-nfl.md section 9; `--sports --sports-nfl`).

Routes (ESPN's own paths, so the app only swaps the origin: debug builds, `files/debug/sports_base`):

    GET /apis/site/v2/sports/football/nfl/scoreboard                       this week (week 9 of the 2026 regular season)
    GET .../scoreboard?seasontype=2&week=N                                 a week (8 and 10 carry one game each; others none)
    GET .../scoreboard?dates=YYYYMMDD                                      one Eastern day (a bad value is HTTP 400 {code, message})
    GET /combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/{abbr}.png&h=200&w=200   a generated placeholder PNG
    anything else under those prefixes                                     404 HTML, as ESPN

Headers: `Date`, `Cache-Control: max-age=5`; no `Age`, no `ETag`. Every body is the state at the request instant (ESPN has
no CDN buckets worth modelling; its max-age is at most 10 s).

The featured game (KC @ BUF, BUF at home, id 401899001) runs the section 9.2 timeline (WINDOWS below: about 985 s at the
default `sports_nfl_timeline_s`): coin toss, kick-off, drive, red zone, touchdown with the 10 s score-before-play window,
try, extra point, side changes, end of quarter, field goal, a +6/+1 pair in one body, halftime, safety with a free kick,
a defensive touchdown with a two-point try drawn as a real down, a second field goal, a KC safety, overtime and a walk-off
touchdown on a `post` body. Every window is at least 25 s except the one 10 s window. Clocks run 1 s/s in running windows
and are frozen in the others (breaks, tries, kick-offs); they step at window starts. `situation.yardLine` is home-relative
(yards from BUF's goal line).

Switches (config keys, also through `POST /admin/config`):
- `sports_nfl` (`--sports-nfl`, needs `--sports`), `sports_nfl_timeline_s` (default 985, 30..86400).
- `sports_nfl_script`: {"<event id>": {state overrides}}; the keys of `state()`: state ("pre"/"in"/"post"), name
  ("STATUS_..."), period, clock, away, home, possession ("away"/"home"/null), down, distance, yardLine, possessionText,
  shortDownDistanceText, isRedZone, lastPlayType (an ESPN play type id such as "67"), lastPlayScoreValue, lastPlayTeam
  ("away"/"home"/null), `situation: null` (the body has no `situation`), `gone: true` (the event leaves every board).
  A +8 pair is `{"401899001": {"away": <score + 8>}}` for one body.
- `sports_nfl_faults`: the NHL fault keys (`status`, `score_status`, `html_404`, `retry_after`, `pad_bytes`, `age`,
  `no_date`, `stall_s`; `landing_status` is accepted and ignored).

`GET /admin/sports-nfl` shows the slate; `POST /admin/sports-nfl {"restart": true}` (or `{"anchor_unix": T}`) re-anchors only
the NFL timeline; `POST /admin/sports {"restart": true}` restarts both (N8, in fakepanel.py's dispatch).

sports.py, its tests and testdata/sports_default_golden.json are untouched.
"""

import datetime as dt
import json
import re
import struct
import threading
import time
import zlib
from zoneinfo import ZoneInfo

import sports

ET = ZoneInfo("America/New_York")
TIMELINE_DEFAULT_S = 985
API_PREFIX = "/apis/site/v2/sports/football/nfl/"
LOGO_PREFIX = "/combiner/i"
FEATURED_ID = "401899001"
SEASON = 2026
THIS_WEEK = 9

DEFAULTS = {
    "sports_nfl": False,              # --sports-nfl: serve the ESPN routes above (needs sports; 404 otherwise)
    "sports_nfl_timeline_s": TIMELINE_DEFAULT_S,
    "sports_nfl_script": {},          # {event id: {state() key: value}}
    "sports_nfl_faults": {},          # the NHL fault keys (sports.FAULT_KEYS)
}
STATE_KEYS = ("state", "name", "period", "clock", "away", "home", "possession", "down", "distance", "yardLine",
              "possessionText", "shortDownDistanceText", "isRedZone", "lastPlayType", "lastPlayScoreValue",
              "lastPlayTeam", "situation", "gone")

NOT_FOUND_HTML = b"<html><body><h1>404 Not Found</h1></body></html>"

# abbrev -> (team id, location, short name, colour, alternate colour)
TEAMS = {
    "KC": ("12", "Kansas City", "Chiefs", "e31837", "ffb612"),
    "BUF": ("2", "Buffalo", "Bills", "00338d", "d50a0a"),
    "NYJ": ("20", "New York", "Jets", "115740", "000000"),
    "NE": ("17", "New England", "Patriots", "002a5c", "c60c30"),
    "SF": ("25", "San Francisco", "49ers", "aa0000", "b3995d"),
    "SEA": ("26", "Seattle", "Seahawks", "002a5c", "69be28"),
    "LV": ("13", "Las Vegas", "Raiders", "000000", "a5acaf"),
    "DEN": ("7", "Denver", "Broncos", "fb4f14", "002244"),
}

# ESPN play type id -> (text, abbreviation)
PLAY_TYPES = {
    "70": ("Coin Toss", "CT"), "53": ("Kickoff", "K"), "5": ("Rush", "RUSH"), "24": ("Pass Reception", "REC"),
    "67": ("Passing Touchdown", "TD"), "68": ("Rushing Touchdown", "TD"), "59": ("Field Goal Good", "FG"),
    "74": ("Official Timeout", "Off TO"), "36": ("Interception Return Touchdown", "TD"),
    "26": ("Pass Interception Return", "INT"), "20": ("Safety", "SF"), "2": ("End Period", "EP"),
    "65": ("End of Half", "EH"),
}
ORD = {1: "1st", 2: "2nd", 3: "3rd", 4: "4th"}

# ------------------------------------------------------------------ the featured timeline (section 9.2)
# Teams: KC away, BUF home. `yl` is home-relative: KC's own 25 is yl 75; BUF's own 25 is yl 25.


def down(d, dist, yl, ptext, poss, goal=False, red=False):
    short = f"{ORD[d]} & Goal" if goal else f"{ORD[d]} & {dist}"
    return {"down": d, "distance": dist, "yardLine": yl, "possessionText": ptext, "shortDownDistanceText": short,
            "possession": poss, "isRedZone": red}


def dead(yl, red=True):
    """The dead ball after a TD (the try) or a FG: down -1, distance 10, no possession (fixtures `try`, `fg-dead-ball`)."""
    return {"down": -1, "distance": 10, "yardLine": yl, "possession": None, "isRedZone": red}


def kick(yl, poss, red=True):
    """A kick-off or free kick: down -1, distance 0, possession of the kicking side (fixtures `kickoff`)."""
    return {"down": -1, "distance": 0, "yardLine": yl, "possession": poss, "isRedZone": red}


def lp(type_id, team, sv=0):
    return {"lastPlayType": type_id, "lastPlayTeam": team, "lastPlayScoreValue": sv}


def win(label, n, period, away, home, run, **kw):
    d = {"label": label, "len": n, "period": period, "away": away, "home": home, "run": run, "state": "in",
         "name": "STATUS_IN_PROGRESS", "situation": True}
    d.update(kw)
    return d


def brk(label, period, away, home, name, prev, last):
    """A break (END_PERIOD or HALFTIME): the situation of [prev] is kept, the clock is 0:00."""
    d = dict(prev)
    d.update(label=label, len=25, period=period, away=away, home=home, run=False, name=name, **last)
    return d


W = []  # the windows, in order; each is a dict (see win)


def _build():
    # pre
    W.append({"label": "pre", "len": 40, "period": 0, "state": "pre", "name": "STATUS_SCHEDULED", "run": False,
              "situation": False, "away": None, "home": None})
    # Q1
    q1 = [
        win("q1 coin toss", 25, 1, 0, 0, False, down=0, distance=0, yardLine=0, possession=None, isRedZone=False, **lp("70", "home")),
        win("q1 kick-off, no possession", 25, 1, 0, 0, True, **down(1, 10, 75, "KC 25", None), **lp("53", "away")),
        win("q1 KC ball", 25, 1, 0, 0, True, **down(1, 10, 75, "KC 25", "away"), **lp("5", "away")),
        win("q1 3rd and 2, OPP 38", 25, 1, 0, 0, True, **down(3, 2, 38, "BUF 38", "away"), **lp("24", "away")),
        win("q1 1st and goal, red zone", 25, 1, 0, 0, True, **down(1, 4, 4, "BUF 4", "away", goal=True, red=True), **lp("24", "away")),
    ]
    W.extend(q1)
    stale = dict(q1[-1])
    stale.update(label="q1 score before the play (10 s)", len=10, away=6, run=False)
    W.append(stale)
    W.append(win("q1 try", 25, 1, 6, 0, False, **dead(0), **lp("67", "away", 6)))
    W.append(win("q1 extra point, KC kick-off", 25, 1, 7, 0, False, **kick(65, "away"), **lp("74", "away")))
    W.append(win("q1 BUF ball, no possession", 25, 1, 7, 0, True, **down(1, 10, 25, "BUF 25", None), **lp("53", "home")))
    last = win("q1 BUF 2nd and 7", 25, 1, 7, 0, True, **down(2, 7, 28, "BUF 28", "home"), **lp("5", "home"))
    W.append(last)
    W.append(brk("end 1st", 1, 7, 0, "STATUS_END_PERIOD", last, lp("2", "home")))
    # Q2
    W.append(win("q2 BUF 1st at KC 30", 25, 2, 7, 0, True, **down(1, 10, 70, "KC 30", "home"), **lp("24", "home")))
    W.append(win("q2 field goal, dead ball", 25, 2, 7, 3, False, **dead(100), **lp("59", "home", 3)))
    W.append(win("q2 BUF kick-off", 25, 2, 7, 3, False, **kick(35, "home"), **lp("74", "home")))
    W.append(win("q2 KC red zone", 25, 2, 7, 3, True, **down(2, 4, 12, "BUF 12", "away", red=True), **lp("24", "away")))
    W.append(win("q2 +7 pair in one body", 25, 2, 14, 3, False, **kick(65, "away"), **lp("74", "away")))
    last = win("q2 BUF ball", 50, 2, 14, 3, True, **down(1, 10, 25, "BUF 25", "home"), **lp("5", "home"))
    W.append(last)
    W.append(brk("halftime", 2, 14, 3, "STATUS_HALFTIME", last, lp("65", "home")))
    # Q3
    W.append(win("q3 KC at its own 2", 25, 3, 14, 3, True, **down(2, 8, 98, "KC 2", "away"), **lp("5", "away")))
    W.append(win("q3 safety, KC free kick", 25, 3, 14, 5, False, **kick(80, "away", red=False), **lp("20", "home", 2)))
    W.append(win("q3 BUF receives the free kick", 25, 3, 14, 5, True, **down(1, 10, 45, "BUF 45", "home"), **lp("53", "home")))
    last = win("q3 KC 1st at KC 40", 25, 3, 14, 5, True, **down(1, 10, 60, "KC 40", "away"), **lp("5", "away"))
    W.append(last)
    stale = dict(last)
    stale.update(label="q3 defensive TD, stale situation", away=14, home=11, run=False, **lp("36", "home", 6))
    W.append(stale)
    W.append(win("q3 two-point try as a real down", 25, 3, 14, 11, False,
                 **down(1, 2, 98, "KC 2", "home", goal=True, red=True), **lp("36", "home", 6)))
    W.append(win("q3 two-point good, BUF kick-off", 25, 3, 14, 13, False, **kick(35, "home"), **lp("74", "home")))
    last = win("q3 KC ball", 35, 3, 14, 13, True, **down(1, 10, 75, "KC 25", "away"), **lp("53", "away"))
    W.append(last)
    W.append(brk("end 3rd", 3, 14, 13, "STATUS_END_PERIOD", last, lp("2", "away")))
    # Q4
    W.append(win("q4 KC 3rd and 9", 25, 4, 14, 13, True, **down(3, 9, 70, "KC 30", "away"), **lp("5", "away")))
    W.append(win("q4 turnover, BUF in the red zone", 25, 4, 14, 13, True, **down(1, 10, 82, "KC 18", "home", red=True), **lp("26", "home")))
    W.append(win("q4 field goal, dead ball", 25, 4, 14, 16, False, **dead(100), **lp("59", "home", 3)))
    W.append(win("q4 BUF kick-off", 25, 4, 14, 16, False, **kick(35, "home"), **lp("74", "home")))
    W.append(win("q4 BUF 3rd and 9 at its 2", 25, 4, 14, 16, True, **down(3, 9, 2, "BUF 2", "home"), **lp("5", "home")))
    last = win("q4 KC safety, BUF free kick", 25, 4, 16, 16, False, **kick(20, "home", red=False), **lp("20", "away", 2))
    W.append(last)
    W.append(brk("end 4th", 4, 16, 16, "STATUS_END_PERIOD", last, lp("2", "home")))
    # OT
    W.append(win("ot KC 1st", 25, 5, 16, 16, True, **down(1, 10, 75, "KC 25", "away"), **lp("53", "away")))
    W.append(win("ot KC 1st and goal at 8", 25, 5, 16, 16, True, **down(1, 8, 8, "BUF 8", "away", goal=True, red=True), **lp("24", "away")))
    W.append({"label": "ot walk-off touchdown (post)", "len": 25, "period": 5, "state": "post", "name": "STATUS_FINAL",
              "run": False, "situation": False, "away": 22, "home": 16, **lp("68", "away", 6)})
    W.append({"label": "final", "len": None, "period": 5, "state": "post", "name": "STATUS_FINAL", "run": False,
              "situation": False, "away": 22, "home": 16, **lp("68", "away", 6)})


_build()


def _clocks():
    """Window start clocks (seconds): 900 at each quarter (600 in OT); running windows consume their length."""
    out, ptr, cur = [], 900, None
    for w in W:
        if w["state"] == "pre":
            out.append(0)
            continue
        if w["period"] != cur:
            cur, ptr = w["period"], (600 if w["period"] == 5 else 900)
        if w["name"] in ("STATUS_END_PERIOD", "STATUS_HALFTIME") or w["state"] == "post":
            out.append(0)
            continue
        out.append(ptr)
        if w["run"]:
            ptr -= w["len"]
    return out


CLOCK0 = _clocks()
PRE_S = W[0]["len"]


# ------------------------------------------------------------------ config


def check_config(patch):
    """Raises ValueError for a bad NFL key in a config patch."""
    if "sports_nfl" in patch and not isinstance(patch["sports_nfl"], bool):
        raise ValueError("sports_nfl must be true or false")
    if "sports_nfl_timeline_s" in patch:
        v = patch["sports_nfl_timeline_s"]
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 30 <= v <= 86400:
            raise ValueError("sports_nfl_timeline_s must be a number 30..86400")
    if "sports_nfl_script" in patch:
        s = patch["sports_nfl_script"]
        if not isinstance(s, dict) or not all(isinstance(v, dict) for v in s.values()):
            raise ValueError("sports_nfl_script must be an object {event id: {state overrides}}")
        for v in s.values():
            bad = [k for k in v if k not in STATE_KEYS]
            if bad:
                raise ValueError("unknown sports_nfl_script keys: " + ", ".join(bad))
            if "possession" in v and v["possession"] not in ("away", "home", None):
                raise ValueError('possession must be "away", "home" or null')
            if "lastPlayTeam" in v and v["lastPlayTeam"] not in ("away", "home", None):
                raise ValueError('lastPlayTeam must be "away", "home" or null')
            if "state" in v and v["state"] not in ("pre", "in", "post"):
                raise ValueError('state must be "pre", "in" or "post"')
            if "situation" in v and v["situation"] is not None:
                raise ValueError("situation may only be null (the body has no situation)")
    if "sports_nfl_faults" in patch:
        f = patch["sports_nfl_faults"]
        if not isinstance(f, dict):
            raise ValueError("sports_nfl_faults must be an object")
        bad = [k for k in f if k not in sports.FAULT_KEYS]
        if bad:
            raise ValueError("unknown sports_nfl_faults keys: " + ", ".join(bad))


# ------------------------------------------------------------------ the slate


def png(w, h, rgb):
    """A valid w x h solid-colour 8-bit RGB PNG (pure Python, no team art)."""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    row = b"\x00" + bytes(rgb) * w
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * h, 9)) + chunk(b"IEND", b""))


def iso_min(unix):
    return dt.datetime.fromtimestamp(int(unix), dt.timezone.utc).strftime("%Y-%m-%dT%H:%MZ")


def clock_text(secs):
    secs = max(0, int(secs))
    return f"{secs // 60}:{secs % 60:02d}"


class SportsNfl:
    """The NFL slate's clock. Thread-safe; one per Panel."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.anchor = time.time()

    def reset(self):
        with self.lock:
            self.anchor = time.time()

    def scale(self):
        return float(self.panel.cfg.get("sports_nfl_timeline_s") or TIMELINE_DEFAULT_S) / TIMELINE_DEFAULT_S

    def lengths(self):
        """The windows' lengths in seconds at the current scale (None = open-ended)."""
        k = self.scale()
        return [None if w["len"] is None else w["len"] * k for w in W]

    def featured_start(self):
        return self.anchor + PRE_S * self.scale()

    def featured_day(self):
        return dt.datetime.fromtimestamp(self.featured_start(), ET).date()

    def featured(self, at):
        """The state of the featured game at unix [at]."""
        t = at - self.anchor
        ln = self.lengths()
        i = 0
        while i < len(W) - 1 and t >= ln[i]:
            t -= ln[i]
            i += 1
        w = W[i]
        s = {k: v for k, v in w.items() if k not in ("label", "len", "run")}
        if w["state"] != "pre":
            c0 = CLOCK0[i]
            s["clock"] = max(0, c0 - int(t / self.scale())) if w["run"] else c0
        else:
            s["clock"] = 0
        s["window"] = i
        return s

    @staticmethod
    def fixed(**kw):
        base = {"state": "pre", "name": "STATUS_SCHEDULED", "period": 0, "clock": 0, "away": None, "home": None,
                "situation": False}
        base.update(kw)
        return lambda at: dict(base)

    def games(self):
        """[(id, away, home, start unix, state fn)] the week lists (week 9)."""
        a = self.anchor
        final = {"state": "post", "name": "STATUS_FINAL", "period": 4, "clock": 0, "situation": False}
        return [
            ("401899003", "NYJ", "NE", a - 3 * 86400, self.fixed(away=20, home=23, **final)),
            (FEATURED_ID, "KC", "BUF", self.featured_start(), self.featured),
            ("401899002", "SF", "SEA", a + 7200, self.fixed()),
            ("401899004", "LV", "DEN", a + 86400, self.fixed()),
        ]

    def other_week_games(self, week):
        a = self.anchor
        if week == 8:
            return [("401898001", "DEN", "SEA", a - 7 * 86400, self.fixed(
                state="post", name="STATUS_FINAL", period=4, away=17, home=20))]
        if week == 10:
            return [("401900001", "SEA", "DEN", a + 7 * 86400, self.fixed())]
        return []

    def state(self, game, at):
        s = dict(game[4](at))
        over = (self.panel.cfg.get("sports_nfl_script") or {}).get(game[0])
        if over:
            s.update(over)
            if over.get("situation", 1) is None:
                s["situation"] = False
        return s

    def week_start(self):
        """The current week's start (Wednesday 3 am ET on or before the anchor's date)."""
        d = dt.datetime.fromtimestamp(self.anchor, ET).date()
        wed = d - dt.timedelta(days=(d.weekday() - 2) % 7)
        return dt.datetime.combine(wed, dt.time(3, 0), ET)

    def calendar(self):
        start = self.week_start() - dt.timedelta(weeks=THIS_WEEK - 1)

        def z(x):
            return x.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%MZ")

        def entry(label, value, s, e):
            return {"label": label, "alternateLabel": label, "detail": "", "value": str(value), "startDate": z(s), "endDate": z(e)}

        reg = [entry(f"Week {n}", n, start + dt.timedelta(weeks=n - 1), start + dt.timedelta(weeks=n) - dt.timedelta(minutes=1))
               for n in range(1, 19)]
        post_start = start + dt.timedelta(weeks=18)
        post = [entry(label, n, post_start + dt.timedelta(weeks=n - 1), post_start + dt.timedelta(weeks=n) - dt.timedelta(minutes=1))
                for n, label in enumerate(("Wild Card", "Divisional Round", "Conference Championship", "Super Bowl"), 1)]
        return [
            {"label": "Preseason", "value": "1", "startDate": z(start - dt.timedelta(weeks=5)), "endDate": z(start), "entries": []},
            {"label": "Regular Season", "value": "2", "startDate": z(start), "endDate": z(post_start), "entries": reg},
            {"label": "Postseason", "value": "3", "startDate": z(post_start), "endDate": z(post_start + dt.timedelta(weeks=4)), "entries": post},
            {"label": "Off Season", "value": "4", "startDate": z(post_start + dt.timedelta(weeks=4)), "endDate": z(post_start + dt.timedelta(weeks=30)), "entries": []},
        ]

    def team(self, abbr, base):
        tid, loc, name, colour, alt = TEAMS[abbr]
        return {"id": tid, "uid": f"s:20~l:28~t:{tid}", "location": loc, "name": name, "abbreviation": abbr,
                "displayName": f"{loc} {name}", "shortDisplayName": name, "color": colour, "alternateColor": alt,
                "isActive": True, "logo": f"https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/{abbr.lower()}.png"}

    def event(self, game, at, base):
        eid, away, home, start, _ = game
        s = self.state(game, at)
        state = s.get("state", "pre")
        started = state != "pre"
        period = s.get("period", 0) or 0
        clock = s.get("clock", 0) or 0
        name = s.get("name", "STATUS_SCHEDULED")
        if state == "pre":
            local = dt.datetime.fromtimestamp(start, ET)
            detail = local.strftime("%a, %B %-d at %-I:%M %p ET")
            short = local.strftime("%-m/%-d - %-I:%M %p ET")
            typ = {"id": "1", "name": name, "state": "pre", "completed": False, "description": "Scheduled", "detail": detail, "shortDetail": short}
        elif state == "post":
            fin = "Final/OT" if period > 4 else "Final"
            typ = {"id": "3", "name": name, "state": "post", "completed": True, "description": "Final", "detail": fin, "shortDetail": fin}
        else:
            if name == "STATUS_END_PERIOD":
                det, sh, ident = f"End of {ORD.get(period, 'OT')} Quarter", f"End of {ORD.get(period, 'OT')}", "2"
            elif name == "STATUS_HALFTIME":
                det = sh = "Halftime"
                ident = "23"
            else:
                q = ORD.get(period, "OT")
                det, sh, ident = f"{clock_text(clock)} - {q} Quarter", f"{clock_text(clock)} - {q}", "2"
            typ = {"id": ident, "name": name, "state": "in", "completed": False, "description": "In Progress", "detail": det, "shortDetail": sh}
        status = {"clock": float(clock), "displayClock": clock_text(clock), "period": period, "type": typ}
        ids = {"away": TEAMS[away][0], "home": TEAMS[home][0]}

        def competitor(side, abbr, order):
            c = {"id": ids[side], "uid": f"s:20~l:28~t:{ids[side]}", "type": "team", "order": order, "homeAway": side,
                 "team": self.team(abbr, base)}
            if state == "post":
                c["winner"] = (s.get("away") or 0) > (s.get("home") or 0) if side == "away" else (s.get("home") or 0) > (s.get("away") or 0)
            if started:
                # `score` is a string, as ESPN's. linescores: all points in the current quarter (the app never reads them).
                sc = s.get(side) or 0
                c["score"] = str(sc)
                c["linescores"] = [{"value": float(sc if p == max(period, 1) else 0), "displayValue": str(sc if p == max(period, 1) else 0), "period": p}
                                   for p in range(1, max(period, 1) + 1)]
            else:
                c["score"] = "0"
            return c

        comp = {"id": eid, "uid": f"s:20~l:28~e:{eid}~c:{eid}", "date": iso_min(start), "startDate": iso_min(start),
                "timeValid": True, "neutralSite": False,
                "competitors": [competitor("home", home, 0), competitor("away", away, 1)], "status": status}
        if started and s.get("situation") and state == "in":
            comp["situation"] = self.situation(eid, s, ids, away, home)
        local = dt.datetime.fromtimestamp(start, ET)
        return {"id": eid, "uid": f"s:20~l:28~e:{eid}", "date": iso_min(start), "name": f"{TEAMS[away][1]} {TEAMS[away][2]} at {TEAMS[home][1]} {TEAMS[home][2]}",
                "shortName": f"{away} @ {home}", "season": {"year": SEASON, "type": 2, "slug": "regular-season"},
                "week": {"number": THIS_WEEK}, "competitions": [comp], "status": status, "_local": local.isoformat()}

    @staticmethod
    def situation(eid, s, ids, away, home):
        d = s.get("down", 0)
        yl = s.get("yardLine", 0)
        sit = {}
        tid = s.get("lastPlayType")
        if tid is not None:
            text, abbr = PLAY_TYPES.get(str(tid), (str(tid), "PL"))
            lp_ = {"id": f"{eid}{int(s.get('window', 0)):03d}", "type": {"id": str(tid), "text": text, "abbreviation": abbr},
                   "text": text, "scoreValue": s.get("lastPlayScoreValue") or 0, "statYardage": 0,
                   "start": {"yardLine": yl}, "end": {"yardLine": yl}}
            side = s.get("lastPlayTeam")
            if side in ids:
                lp_["team"] = {"id": ids[side]}
            sit["lastPlay"] = lp_
        sit.update({"down": d, "yardLine": yl, "distance": s.get("distance", 0)})
        if d >= 1:
            short = s.get("shortDownDistanceText") or f"{ORD.get(d, str(d))} & {s.get('distance', 0)}"
            ptext = s.get("possessionText")
            sit["downDistanceText"] = f"{short} at {ptext}" if ptext else short
            sit["shortDownDistanceText"] = short
            if ptext:
                sit["possessionText"] = ptext
        sit.update({"isRedZone": bool(s.get("isRedZone")), "homeTimeouts": 3, "awayTimeouts": 3})
        if s.get("possession") in ids:
            sit["possession"] = ids[s["possession"]]
        return sit

    def leagues(self):
        return [{"id": "28", "uid": "s:20~l:28", "name": "National Football League", "abbreviation": "NFL", "slug": "nfl",
                 "season": {"year": SEASON, "displayName": str(SEASON), "type": {"id": "2", "type": 2, "name": "Regular Season", "abbreviation": "reg"}},
                 "calendarType": "list", "calendarIsWhitelist": True, "calendar": self.calendar()}]

    def board(self, games, at, base, week=None, day=None, season_type=2):
        events = [self.event(g, at, base) for g in games if not self.state(g, at).get("gone")]
        for e in events:
            e.pop("_local", None)
        o = {"leagues": self.leagues(), "events": events}
        if day is not None:
            o["day"] = {"date": day.isoformat()}
        else:
            o["season"] = {"type": season_type, "year": SEASON}
            o["week"] = {"number": THIS_WEEK if week is None else week, "teamsOnBye": []}
        return o

    def dated_games(self, day):
        """The featured game's Eastern date has it and the Sunday game; every other date is empty."""
        if day != self.featured_day():
            return []
        return [g for g in self.games() if g[0] in (FEATURED_ID, "401899002")]


# ------------------------------------------------------------------ http


def is_nfl_path(path):
    return path.startswith(API_PREFIX) or path == LOGO_PREFIX or path.startswith(LOGO_PREFIX + "/")


def handle(h, path):
    """Routes one NFL request on fakepanel's Handler [h]. Returns True when [path] was an NFL path."""
    if not is_nfl_path(path):
        return False
    p = h.panel
    cfg = p.cfg
    if not (cfg.get("sports") and cfg.get("sports_nfl")):
        h.send(404, b"NFL routes are off (--sports --sports-nfl)", "text/plain")
        return True
    sp = p.sports_nfl
    f = cfg.get("sports_nfl_faults") or {}
    if f.get("stall_s"):
        time.sleep(float(f["stall_s"]))
    now = time.time()
    headers = {"Cache-Control": "max-age=5"}
    if f.get("age") is not None:
        headers["Age"] = str(int(f["age"]))
    if not f.get("no_date"):
        headers["Date"] = sports.http_date(now)
    q = getattr(h, "q", {}) or {}

    def fail(status, body=None, ctype="application/json"):
        st = int(status)
        hd = dict(headers)
        if f.get("retry_after") is not None:
            hd["Retry-After"] = str(int(f["retry_after"]))
        if body is None:
            if st == 404:
                body, ctype = NOT_FOUND_HTML, "text/html"
            else:
                body = json.dumps({"code": st, "message": "fakepanel NFL fault"})
        elif st == 404 and f.get("html_404"):
            body, ctype = NOT_FOUND_HTML, "text/html"
        sports.send(h, st, body, ctype, hd)
        return True

    def answer(obj):
        if f.get("pad_bytes"):
            obj = dict(obj, pad="x" * int(f["pad_bytes"]))
        sports.send(h, 200, json.dumps(obj), "application/json", headers)
        return True

    if path == LOGO_PREFIX:
        m = re.search(r"/teamlogos/nfl/500(?:-dark)?/scoreboard/([a-z]{2,3})\.png$", q.get("img", ""))
        if not m:
            return fail(404, NOT_FOUND_HTML, "text/html")
        colour = TEAMS.get(m[1].upper(), (0, "", "", "%06x" % (zlib.crc32(m[1].encode()) & 0xFFFFFF), ""))[3]
        rgb = bytes.fromhex(colour)
        sports.send(h, 200, png(200, 200, rgb), "image/png", {"Cache-Control": "public, max-age=7200", "Date": sports.http_date(now)})
        return True
    if f.get("status"):
        return fail(f["status"])
    if path != API_PREFIX + "scoreboard":
        return fail(404, NOT_FOUND_HTML, "text/html")
    if f.get("score_status"):
        return fail(f["score_status"])
    base = h.base()
    if "dates" in q:
        m = re.fullmatch(r"(\d{4})(\d{2})(\d{2})", q["dates"])
        try:
            day = dt.date(int(m[1]), int(m[2]), int(m[3])) if m else None
        except ValueError:
            day = None
        if day is None:
            return fail(400, json.dumps({"code": 400, "message": "Invalid dates parameter: " + q["dates"]}))
        return answer(sp.board(sp.dated_games(day), now, base, day=day))
    if "week" in q or "seasontype" in q:
        try:
            t, n = int(q.get("seasontype", "2")), int(q["week"])
        except (KeyError, ValueError):
            t, n = None, None
        if t is None or n < 1 or t < 1:
            msg = "Invalid week parameter: " + q.get("seasontype", "") + "/" + q.get("week", "")
            return fail(400, json.dumps({"code": 400, "message": msg}))
        games = sp.games() if (t, n) == (2, THIS_WEEK) else (sp.other_week_games(n) if t == 2 else [])
        return answer(sp.board(games, now, base, week=n, season_type=t))
    return answer(sp.board(sp.games(), now, base))


def admin(h, path, data):
    """`/admin/sports-nfl`: GET the slate; POST {"restart": true} re-anchors only the NFL timeline."""
    sp = h.panel.sports_nfl
    if h.command in ("POST", "PUT") and data:
        if data.get("restart"):
            sp.reset()
        elif "anchor_unix" in data:
            with sp.lock:
                sp.anchor = float(data["anchor_unix"])
    now = time.time()
    games = [{"id": g[0], "away": g[1], "home": g[2], "start_unix": g[3], "state": sp.state(g, now)} for g in sp.games()]
    return h.json({"enabled": bool(h.panel.cfg.get("sports") and h.panel.cfg.get("sports_nfl")), "anchor_unix": sp.anchor,
                   "featured": FEATURED_ID, "featured_day": sp.featured_day().isoformat(), "games": games})
