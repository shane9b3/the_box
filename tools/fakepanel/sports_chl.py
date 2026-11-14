"""The fake HockeyTech CHL feeds (docs/design/m6-chl-leagues.md section 7.3; `--sports --sports-chl`).

A debug build's `files/debug/sports_base` origin sends the app's CHL requests here (HockeyTechEndpoints.fromOverride):

    GET /hockeytech/feed/index.php?feed=modulekit&view=gamesbydate&fetch_date=YYYY-MM-DD&key=K&client_code=C&fmt=json&lang=en
    GET ...&view=teamsbyseason&season_id=S          GET ...&view=seasons
        The keyed host. K must be `fakepanel-{client}` (the app's debug keys; the real keys never come here): any other
        key is a 200 text/html "Invalid key."; another client's fake key is "Client access denied." (both as HockeyTech).
        The body echoes the key in SiteKit.Parameters, as the real host does. `Cache-Control: max-age=240`.
    GET /firebase/svf/{client}/runningclock/games/{id}.json
    GET /firebase/svf/{client}/{publishedclock|goalssummary|shotssummary|penalties|goals|shootouts}/1/games/{id}.json
        One node at the request instant. With `X-Firebase-ETag: true` the answer carries a content ETag and a matching
        `If-None-Match` is a 304 with an empty body. An unknown game (or a node it does not have) is JSON `null`.
    GET /firebase/svf/{client}/runningclock/games.json?orderBy="DatePlayed"&equalTo="YYYY-MM-DD"   (and .../1/games.json)
        The Today overlay date query: {game id: node} for that date ({} for none; `penalties` has an empty DatePlayed, so
        it never matches, as the real database). Combined with `X-Firebase-ETag` it is Firebase's 400 "Mixing querying
        parameters and ETag requests is not supported".
    GET /firebase/svf.json                          401 {"error" : "Permission denied"} (the closed root)
    GET /leaguestat/{client}/logos/{file}           an RGBA PNG with transparent corners; an RGB PNG (WHL 222.png, WEN);
                                                    a JPEG marker body for .jpg; 404 for the ids with no PNG (OHL 18, BRAM)

The slate (per league, all on the league's "today" at the anchor, 05:00 rollover in its reference zone):
- the featured game, which plays FEATURED below (520 s at the default `sports_chl_timeline_s`): pre-game "-", puck
  drop, a minor (power play), a second minor (5-on-3), a PP goal (the goal row one segment before the released minor's
  TimeOn), a double minor, the S1 fighting major against an unflagged minor, a break at 0:00 then the "20:00 2nd" form, a
  tie, a 3-on-3 OT with a penalty, the tied 0:00 OT (shootout rule c), shootout attempts with `Shootout` false (the OHL has
  no shootouts node), then Final SO with the winner's +1;
- the S3 game: 4-1 with 0:30 left, 0:00 at status 2 for SEGMENT_S3_HOLD s, then final;
- an evening game (status 1, three hours ahead; not in Firebase: board mode);
- an afternoon final that Firebase no longer lists (board mode, evicted final).

Switches (config keys, also through `POST /admin/config`):
- `sports_chl` (`--sports-chl`, needs `--sports`), `sports_chl_timeline_s` (default 520, 60..86400; segments scale).
- `sports_chl_refuse` (`--chl-refuse`): every keyed request is "Invalid key.".
- `sports_chl_firebase_refuse` (`--chl-firebase-refuse`): every Firebase request is 401 "Permission denied".
- `sports_chl_null_live` (`--chl-null-live`, S5): the featured game's nodes are `null` while it is live.
- `sports_chl_drop_penalties` (`--chl-drop-penalties N`, B3): every Nth `penalties` node request is a 503.

`GET /admin/sports-chl` shows the slate and each game's state; `POST /admin/sports-chl {"restart": true}` (or
`{"anchor_unix": T}`) re-anchors it; `POST /admin/sports {"restart": true}` restarts it with the NHL and NFL timelines.
Requests are noted in `/admin/stats` `sports.log` with the NHL and NFL ones.
"""

import base64
import datetime as dt
import hashlib
import json
import re
import struct
import threading
import time
import zlib
from zoneinfo import ZoneInfo

import sports

FEED_PATH = "/hockeytech/feed/index.php"
FIREBASE_PREFIX = "/firebase/svf/"
LOGO_PREFIX = "/leaguestat/"
CLIENTS = ("ohl", "whl", "lhjmq")
NODES = ("runningclock", "publishedclock", "goalssummary", "shotssummary", "penalties", "goals", "shootouts")
ZONES = {"ohl": "America/Toronto", "whl": "America/Vancouver", "lhjmq": "America/Toronto"}
GAME_ZONES = {"ohl": "America/Toronto", "whl": "America/Edmonton", "lhjmq": "America/Halifax"}
SEASONS = {"ohl": "88", "whl": "295", "lhjmq": "214"}
ROLLOVER_H = 5
TIMELINE_DEFAULT_S = 520  # the sum of the FEATURED segments
SEGMENT_S3_RUN = 30
SEGMENT_S3_HOLD = 60

DEFAULTS = {
    "sports_chl": False,                  # --sports-chl: serve the routes above (needs sports; 404 otherwise)
    "sports_chl_timeline_s": TIMELINE_DEFAULT_S,
    "sports_chl_refuse": False,           # --chl-refuse
    "sports_chl_firebase_refuse": False,  # --chl-firebase-refuse
    "sports_chl_null_live": False,        # --chl-null-live
    "sports_chl_drop_penalties": 0,       # --chl-drop-penalties N
}

# team id -> (code as the feed spells it, city, nickname, logo file); the QMJHL's codes are mixed case in the feed.
TEAMS = {
    "ohl": {"14": ("LDN", "London", "Knights", "14_88.png"), "10": ("KIT", "Kitchener", "Rangers", "10.png"),
            "18": ("BRAM", "Brampton", "Steelheads", "18_88.jpg"), "5": ("OTT", "Ottawa", "67's", "5_88.png")},
    "whl": {"202": ("CGY", "Calgary", "Hitmen", "202.png"), "228": ("EDM", "Edmonton", "Oil Kings", "228_295.png"),
            "222": ("WEN", "Wenatchee", "Wild", "222_295.jpg"), "227": ("VIC", "Victoria", "Royals", "227.png")},
    "lhjmq": {"2": ("NFL", "Newfoundland", "Regiment", "2_214.png"), "7": ("Cha", "Charlottetown", "Islanders", "7.png"),
              "18": ("Rim", "Rimouski", "Océanic", "18.png"), "3": ("Cap", "Cape Breton", "Eagles", "3.jpg")},
}
NO_PNG = {("ohl", "18")}         # the JPEG swap finds no PNG (BRAM: the capsule fallback)
RGB_PNG = {("whl", "222")}       # a PNG without alpha (WEN: falls back)

# Game ids: featured, S3, evening, evicted final; (away id, home id).
SLATE = {
    "ohl": [("900001", "14", "10"), ("900002", "18", "5"), ("900003", "10", "14"), ("900004", "5", "18")],
    "whl": [("9100001", "202", "228"), ("9100002", "222", "227"), ("9100003", "228", "202"), ("9100004", "227", "222")],
    "lhjmq": [("92001", "2", "7"), ("92002", "18", "3"), ("92003", "7", "2"), ("92004", "3", "18")],
}

# ------------------------------------------------------------------ the featured scenario
# Each segment: len (s at the default scale), period, rem (remaining s at the segment start; None = "-" pre-game),
# run (the clock runs 1 s/s), status (status_id), and what appears from it on. Penalties and goals are cumulative:
# PENS / GOALS list (first segment index, row). A penalty's TimeOn is set from its `on_seg`.
FEATURED = [
    dict(label="pre", len=40, period=1, rem=None, run=False, status=1),
    dict(label="puck drop", len=30, period=1, rem=1200, run=True, status=2),
    dict(label="power play", len=40, period=1, rem=720, run=True, status=2),           # away minor at 08:00
    dict(label="5-on-3", len=30, period=1, rem=680, run=True, status=2),               # away minor at 08:40
    dict(label="pp goal", len=10, period=1, rem=650, run=False, status=2),             # home PP goal at 09:10
    dict(label="pp goal, TimeOn", len=10, period=1, rem=650, run=False, status=2),     # the released minor's TimeOn
    dict(label="one man left", len=20, period=1, rem=650, run=True, status=2),
    dict(label="double minor", len=30, period=1, rem=360, run=True, status=2),         # home double minor at 14:00
    dict(label="major vs minor", len=30, period=1, rem=240, run=True, status=2),       # S1 at 16:00
    dict(label="break", len=30, period=1, rem=0, run=False, status=2),
    dict(label="20:00 2nd", len=30, period=2, rem=1200, run=False, status=2),
    dict(label="2nd", len=20, period=2, rem=1200, run=True, status=2),
    dict(label="tie", len=20, period=2, rem=600, run=True, status=2),                  # away goal at 10:00: 1-1
    dict(label="end of 3rd", len=30, period=3, rem=30, run=True, status=2),
    dict(label="3rd buzzer", len=20, period=3, rem=0, run=False, status=2),
    dict(label="ot", len=40, period=4, rem=280, run=True, status=2),                   # away minor at OT 00:30
    dict(label="ot 0:00", len=20, period=4, rem=0, run=False, status=2),
    dict(label="shootout", len=30, period=4, rem=0, run=False, status=2, so=2),        # two attempts, Shootout false
    dict(label="final so", len=40, period=4, rem=0, run=False, status=4, so=3, final=True),
]
SEG = {s["label"]: i for i, s in enumerate(FEATURED)}


def _pen(eid, home, period, time_, minutes, pp, player, on_seg=None, on=None):
    return dict(LSEventId=eid, Home=1 if home else 0, Period=period, Time=time_, TimeStart=time_, TimeStartSet=1,
                Minutes=minutes, PowerPlay=1 if pp else 0, PenaltyShot=0, PenalizedPlayerId=player, ServedPlayerId=player,
                Bench=0, Offence=1, OffenceDescription="", on_seg=on_seg, on=on)


PENS = [
    (SEG["power play"], _pen(101, False, 1, "08:00", 2, True, 11, on_seg=SEG["pp goal, TimeOn"], on="09:10")),
    (SEG["5-on-3"], _pen(102, False, 1, "08:40", 2, True, 12)),
    (SEG["double minor"], _pen(103, True, 1, "14:00", 4, True, 21)),
    (SEG["major vs minor"], _pen(104, True, 1, "16:00", 5, True, 22)),
    (SEG["major vs minor"], _pen(105, False, 1, "16:00", 2, False, 13)),
    (SEG["ot"], _pen(106, False, 4, "00:30", 2, True, 14)),
]
# (first segment, home, period, time, pp)
GOALS = [
    (SEG["pp goal"], True, 1, "09:10", True),
    (SEG["tie"], False, 2, "10:00", False),
]


def _png(w, h, rgba, alpha=True):
    """A w x h 8-bit PNG: RGBA with transparent corners (a disc), or plain RGB."""
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    rows = []
    r2 = (min(w, h) / 2.0) ** 2
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            inside = (x - w / 2 + 0.5) ** 2 + (y - h / 2 + 0.5) ** 2 <= r2
            if alpha:
                row += bytes(rgba[:3]) + (b"\xff" if inside else b"\x00")
            else:
                row += bytes(rgba[:3])
        rows.append(bytes(row))
    ctype = 6 if alpha else 2
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


JPEG_MARKERS = b"\xff\xd8\xff\xd9"  # SOI + EOI: "a JPEG" for the route; the app swaps every .jpg for {id}.png anyway


def check_config(patch):
    """Raises ValueError for a bad CHL key in a config patch."""
    for k in ("sports_chl", "sports_chl_refuse", "sports_chl_firebase_refuse", "sports_chl_null_live"):
        if k in patch and not isinstance(patch[k], bool):
            raise ValueError(k + " must be true or false")
    if "sports_chl_timeline_s" in patch:
        v = patch["sports_chl_timeline_s"]
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 60 <= v <= 86400:
            raise ValueError("sports_chl_timeline_s must be a number 60..86400")
    if "sports_chl_drop_penalties" in patch:
        v = patch["sports_chl_drop_penalties"]
        if isinstance(v, bool) or not isinstance(v, int) or v < 0:
            raise ValueError("sports_chl_drop_penalties must be an integer >= 0 (0 = off)")


def fake_key(client):
    return "fakepanel-" + client


def mmss(secs):
    secs = max(0, int(secs))
    return f"{secs // 60:02d}:{secs % 60:02d}"


def etag_of(body):
    if body == b"null":
        return "null_etag"
    return base64.b64encode(hashlib.sha1(body).digest()).decode()


class SportsChl:
    """The CHL slate's clock. Thread-safe; one per Panel."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.anchor = time.time()
        self.penalty_requests = 0

    def reset(self):
        with self.lock:
            self.anchor = time.time()
            self.penalty_requests = 0

    def scale(self):
        return float(self.panel.cfg.get("sports_chl_timeline_s") or TIMELINE_DEFAULT_S) / TIMELINE_DEFAULT_S

    # ---- time
    def seg_starts(self):
        k = self.scale()
        out, t = [], 0.0
        for s in FEATURED:
            out.append(t)
            t += s["len"] * k
        return out, t

    def featured_start(self):
        """Puck drop (the end of the pre-game segment), unix s."""
        return self.anchor + FEATURED[0]["len"] * self.scale()

    def day(self, client):
        z = ZoneInfo(ZONES[client])
        local = dt.datetime.fromtimestamp(self.anchor, z) - dt.timedelta(hours=ROLLOVER_H)
        return local.date()

    def games(self, client):
        """[(id, away id, home id, start unix, kind)] for the league's slate."""
        f = self.featured_start()
        ids = SLATE[client]
        return [
            (ids[0][0], ids[0][1], ids[0][2], f, "featured"),
            (ids[1][0], ids[1][1], ids[1][2], self.anchor - 2 * 3600, "s3"),
            (ids[2][0], ids[2][1], ids[2][2], self.anchor + 3 * 3600, "evening"),
            (ids[3][0], ids[3][1], ids[3][2], self.anchor - 4 * 3600, "evicted"),
        ]

    # ---- the state of a game at unix [at]
    def state(self, client, game, at):
        gid, away, home, start, kind = game
        if kind == "featured":
            return self.featured_state(client, at)
        if kind == "s3":
            t = (at - self.anchor) / self.scale() if self.scale() else 0
            if t < SEGMENT_S3_RUN:
                rem, run, status, final = max(0, int(30 - t)), True, 2, False
            elif t < SEGMENT_S3_RUN + SEGMENT_S3_HOLD:
                rem, run, status, final = 0, False, 2, False
            else:
                rem, run, status, final = 0, False, 4, True
            goals = [(False, 1, "05:00", False), (True, 2, "02:00", False), (True, 2, "11:00", False),
                     (True, 3, "04:00", True), (True, 3, "15:00", False)]
            return dict(period=3, rem=rem, run=run, status=status, final=final, pens=[], goals=goals, so_attempts=0,
                        shootout=False, firebase=True, segment="s3")
        if kind == "evening":
            return dict(period=1, rem=None, run=False, status=1, final=False, pens=[], goals=[], so_attempts=0,
                        shootout=False, firebase=False, segment="scheduled")
        goals = [(True, 1, "03:00", False), (True, 2, "08:00", False), (False, 3, "12:00", False)]
        return dict(period=3, rem=0, run=False, status=4, final=True, pens=[], goals=goals, so_attempts=0,
                    shootout=False, firebase=False, segment="evicted final")

    def featured_state(self, client, at):
        starts, total = self.seg_starts()
        t = at - self.anchor
        i = 0
        for j, s0 in enumerate(starts):
            if t >= s0:
                i = j
        if t < 0:
            i = 0
        s = FEATURED[i]
        into = max(0.0, t - starts[i])
        rem = s["rem"]
        if rem is not None and s["run"]:
            rem = max(0, int(rem - into))
        pens = []
        for first, p in PENS:
            if i >= first:
                row = {k: v for k, v in p.items() if k not in ("on_seg", "on")}
                if p["on_seg"] is not None and i >= p["on_seg"]:
                    row["TimeOn"], row["TimeOnSet"] = p["on"], 1
                else:
                    row["TimeOn"], row["TimeOnSet"] = "", 0
                pens.append(row)
        goals = [(h, per, tm, pp) for first, h, per, tm, pp in GOALS if i >= first]
        so = s.get("so", 0)
        return dict(period=s["period"], rem=rem, run=s["run"], status=s["status"], final=bool(s.get("final")),
                    pens=pens, goals=goals, so_attempts=so, shootout=bool(s.get("final")) and so > 0, firebase=True,
                    segment=s["label"], segment_index=i)

    # ---- the bodies
    def nodes(self, client, game, at):
        """{node: dict or None} for one game at [at] (None = the database answers null)."""
        st = self.state(client, game, at)
        gid = game[0]
        if not st["firebase"]:
            return {n: None for n in NODES}
        live = st["status"] == 2
        if live and game[4] == "featured" and self.panel.cfg.get("sports_chl_null_live"):
            return {n: None for n in NODES}
        day = self.day(client).isoformat()
        pre = st["rem"] is None
        period = st["period"]
        clock = {"Minutes": "-" if pre else f"{st['rem'] // 60:02d}", "Running": st["run"],
                 "Seconds": "-" if pre else f"{st['rem'] % 60:02d}", "period": str(period) if pre else period}
        a_goals = [g for g in st["goals"] if not g[0]]
        h_goals = [g for g in st["goals"] if g[0]]
        away, home = len(a_goals), len(h_goals)
        so_winner_home = None
        if st["shootout"]:
            so_winner_home = True
            home += 1
        started = not pre and st["status"] >= 2
        long_name = {1: "1st", 2: "2nd", 3: "3rd"}.get(period, "OT")
        rc = {"Clock": clock, "DatePlayed": day, "status_id": st["status"]}
        pc = {"ClockMinutes": clock["Minutes"], "ClockSeconds": clock["Seconds"], "DatePlayed": day, "Final": st["final"],
              "PeriodId": period, "PeriodLongName": long_name, "PeriodShortName": str(period) if period <= 3 else "OT",
              "ProgressString": "", "Started": started, "StatusId": st["status"],
              "StatusName": {1: "Not Started", 2: "In Progress", 4: "Final"}.get(st["status"], "")}

        def by_period(gl):
            out = [None] + [0] * max(3, period)
            for g in gl:
                out[g[1]] += 1
            return out

        pp_att_home = sum(1 for p in st["pens"] if p["Home"] == 0 and p["PowerPlay"] == 1)
        pp_att_away = sum(1 for p in st["pens"] if p["Home"] == 1 and p["PowerPlay"] == 1)
        gs = {"DatePlayed": day, "HomeGoalTotal": home, "VisitorGoalTotal": away,
              "HomeGoalsByPeriod": by_period(h_goals), "VisitorGoalsByPeriod": by_period(a_goals),
              "HomePowerPlayGoals": sum(1 for g in h_goals if g[3]), "VisitorPowerPlayGoals": sum(1 for g in a_goals if g[3]),
              "HomePowerPlayAttempts": pp_att_home, "VisitorPowerPlayAttempts": pp_att_away,
              "Shootout": st["shootout"], "PeriodsInfo": [None] + [{"PeriodId": q} for q in range(1, max(3, period) + 1)]}
        el = 0 if pre else (1200 if period <= 3 else 300) - st["rem"]
        shots = {"DatePlayed": day, "HomeShotTotal": 3 + period * 7 + el // 120, "VisitorShotTotal": 2 + period * 6 + el // 150}
        if pre:
            shots = {"DatePlayed": day, "HomeShotTotal": 0, "VisitorShotTotal": 0}
        pens = {"DatePlayed": "", "GamePenalties": {str(p["LSEventId"]): p for p in st["pens"]}}
        goals = {"GameGoals": {str(500 + i): {"IsHome": 1 if g[0] else 0, "Period": g[1], "Time": g[2],
                                              "PowerPlay": 1 if g[3] else 0, "ShortHanded": 0, "EmptyNet": 0,
                                              "PenaltyShot": 0, "LSEventId": 500 + i}
                               for i, g in enumerate(st["goals"])}}
        out = {"runningclock": rc, "publishedclock": pc, "goalssummary": gs, "shotssummary": shots,
               "penalties": pens if started else None, "goals": goals if started else None, "shootouts": None}
        if st["so_attempts"] and client != "ohl":
            atts = {}
            for k in range(st["so_attempts"]):
                is_home = k % 2 == 1
                atts[str(700 + k)] = {"Goal": 1 if (is_home and so_winner_home) else 0, "IsHome": 1 if is_home else 0,
                                      "LSEventId": 700 + k}
            out["shootouts"] = {"ShootoutAttempts": atts}
        return out

    def team_entry(self, client, tid, base):
        code, city, nick, logo = TEAMS[client][tid]
        name = f"{city}, {nick}" if client == "lhjmq" and city == "Cape Breton" else f"{city} {nick}"
        return {"id": tid, "name": name, "city": city, "code": code, "nickname": nick,
                "team_logo_url": f"{base}{LOGO_PREFIX}{client}/logos/{logo}"}

    def board_entry(self, client, game, at):
        gid, away, home, start, kind = game
        st = self.state(client, game, at)
        z = ZoneInfo(GAME_ZONES[client])
        local = dt.datetime.fromtimestamp(start, z)
        a_goals = sum(1 for g in st["goals"] if not g[0])
        h_goals = sum(1 for g in st["goals"] if g[0]) + (1 if st["shootout"] else 0)
        started = st["status"] >= 2
        periods = {str(q): {"id": str(q), "length": "1200"} for q in (1, 2, 3)}
        if st["period"] >= 4:
            periods["4"] = {"id": "4", "length": "300"}
        e = {"game_id": gid, "id": gid, "season_id": SEASONS[client], "date_played": self.day(client).isoformat(),
             "schedule_time": local.strftime("%H:%M:%S"), "time_tbd": "0", "timezone": GAME_ZONES[client],
             "status": str(st["status"]), "started": "1" if started else "0", "final": "1" if st["final"] else "0",
             "period": str(st["period"]), "game_clock": "00:" + mmss(st["rem"] or 0) if st["rem"] is not None else "",
             "game_status": "", "intermission": "0", "shootout": "1" if st["shootout"] else "0", "playoff": "0",
             "home_team": home, "visiting_team": away, "periods": periods,
             "home_goal_count": str(h_goals) if started else "0", "visiting_goal_count": str(a_goals) if started else "0",
             "home_shots": "0", "visiting_shots": "0"}
        for prefix, tid in (("home_team", home), ("visiting_team", away)):
            code, city, nick, _ = TEAMS[client][tid]
            e[prefix + "_code"], e[prefix + "_city"], e[prefix + "_nickname"] = code, city, nick
            e[prefix + "_name"] = f"{city} {nick}"
        return e

    def sitekit(self, view, params, payload):
        return {"SiteKit": dict({"Parameters": params, view: payload},
                                **({"Copyright": {"required_copyright": "Official statistics provided by the fake panel",
                                                  "powered_by": "Powered by HockeyTech.com"}} if view == "Gamesbydate" else {}))}


# ------------------------------------------------------------------ http


def is_chl_path(path):
    return path == FEED_PATH or path.startswith(FIREBASE_PREFIX) or path == "/firebase/svf.json" or path.startswith(LOGO_PREFIX)


def _send(h, status, body, ctype, headers=None):
    hd = {"Date": sports.http_date(time.time())}
    hd.update(headers or {})
    sports.send(h, status, body, ctype, hd)
    return True


def handle(h, path):
    """Routes one CHL request on fakepanel's Handler [h]. Returns True when [path] was a CHL path."""
    if not is_chl_path(path):
        return False
    p = h.panel
    cfg = p.cfg
    if not (cfg.get("sports") and cfg.get("sports_chl")):
        h.send(404, b"CHL routes are off (--sports --sports-chl)", "text/plain")
        return True
    sp = p.sports_chl
    q = getattr(h, "q", {}) or {}
    now = time.time()
    if path == FEED_PATH:
        return _feed(h, sp, cfg, q, now)
    if path.startswith(LOGO_PREFIX):
        return _logo(h, path)
    return _firebase(h, sp, cfg, path, q, now)


def _feed(h, sp, cfg, q, now):
    p = h.panel
    client, key, view = q.get("client_code", ""), q.get("key", ""), q.get("view", "")
    params = {"feed": q.get("feed", ""), "view": view, "key": key, "client_code": client, "fmt": q.get("fmt", ""),
              "lang": q.get("lang", "")}
    owners = {fake_key(c): c for c in CLIENTS}
    if cfg.get("sports_chl_refuse") or key not in owners or q.get("feed") != "modulekit":
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Invalid key.", "text/html; charset=UTF-8")
    if owners[key] != client:
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Client access denied.", "text/html; charset=UTF-8")
    base = h.base()
    if view == "gamesbydate":
        day = q.get("fetch_date", "")
        params["fetch_date"] = day
        games = [sp.board_entry(client, g, now) for g in sp.games(client)] if day == sp.day(client).isoformat() else []
        body = sp.sitekit("Gamesbydate", params, games)
    elif view == "teamsbyseason":
        sid = q.get("season_id", "")
        params["season_id"] = sid
        teams = [sp.team_entry(client, t, base) for t in TEAMS[client]] if sid == SEASONS[client] else []
        body = sp.sitekit("Teamsbyseason", params, teams)
    elif view == "seasons":
        y = sp.day(client).year if sp.day(client).month >= 8 else sp.day(client).year - 1
        body = sp.sitekit("Seasons", params, [
            {"season_id": SEASONS[client], "season_name": f"{y}-{y + 1} Regular Season", "playoff": "0",
             "start_date": f"{y}-09-18", "end_date": f"{y + 1}-03-28"},
            {"season_id": "1", "season_name": f"{y} Playoffs", "playoff": "1", "start_date": f"{y}-03-27",
             "end_date": f"{y}-05-18"},
        ])
    else:
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Invalid key.", "text/html; charset=UTF-8")
    p.sports.note(FEED_PATH + "?view=" + view, 200)
    return _send(h, 200, json.dumps(body).encode(), "application/json; charset=utf-8", {"Cache-Control": "max-age=240"})


NODE_RE = re.compile(r"/firebase/svf/([a-z]+)/(runningclock|publishedclock|goalssummary|shotssummary|penalties|goals|shootouts)"
                     r"(?:/1)?/games(?:/([^/]+))?\.json$")


def _firebase(h, sp, cfg, path, q, now):
    p = h.panel
    denied = json.dumps({"error": "Permission denied"}, indent=2).encode()
    if path == "/firebase/svf.json" or cfg.get("sports_chl_firebase_refuse"):
        p.sports.note(path, 401)
        return _send(h, 401, denied, "application/json; charset=utf-8", {"Cache-Control": "no-cache"})
    m = NODE_RE.fullmatch(path)
    if not m or m[1] not in CLIENTS:
        p.sports.note(path, 401)
        return _send(h, 401, denied, "application/json; charset=utf-8", {"Cache-Control": "no-cache"})
    client, node, gid = m[1], m[2], m[3]
    # the shape: runningclock has no "/1/"; every other node has it
    if (node == "runningclock") == (f"/{node}/1/games" in path):
        return _send(h, 200, b"null", "application/json; charset=utf-8", {"Cache-Control": "no-cache"})
    wants_tag = (h.headers.get("X-Firebase-ETag") or "").lower() == "true"
    if gid is None:
        if "orderBy" in q:
            if wants_tag:
                p.sports.note(path, 400)
                err = json.dumps({"error": "Mixing querying parameters and ETag requests is not supported"}, indent=2)
                return _send(h, 400, err.encode(), "application/json; charset=utf-8")
            want = q.get("equalTo", "").strip('"')
            out = {}
            for g in sp.games(client):
                n = sp.nodes(client, g, now).get(node)
                if n is not None and n.get("DatePlayed") == want and q.get("orderBy") == '"DatePlayed"':
                    out[g[0]] = n
            p.sports.note(path, 200)
            return _send(h, 200, json.dumps(out).encode(), "application/json; charset=utf-8", {"Cache-Control": "no-cache"})
        out = {g[0]: sp.nodes(client, g, now).get(node) for g in sp.games(client)}
        out = {k: v for k, v in out.items() if v is not None}
        p.sports.note(path, 200)
        return _send(h, 200, json.dumps(out).encode(), "application/json; charset=utf-8", {"Cache-Control": "no-cache"})
    if node == "penalties":
        n = int(cfg.get("sports_chl_drop_penalties") or 0)
        with sp.lock:
            sp.penalty_requests += 1
            k = sp.penalty_requests
        if n and k % n == 0:
            p.sports.note(path, 503)
            return _send(h, 503, b'{"error":"Service Unavailable"}', "application/json; charset=utf-8")
    game = next((g for g in sp.games(client) if g[0] == gid), None)
    value = sp.nodes(client, game, now).get(node) if game else None
    body = json.dumps(value).encode()
    headers = {"Cache-Control": "no-cache"}
    if wants_tag:
        tag = etag_of(body)
        headers["ETag"] = tag
        headers["Access-Control-Expose-Headers"] = "ETag"
        if h.headers.get("If-None-Match") == tag:
            p.sports.note(path, 304)
            sports.send(h, 304, b"", "application/json; charset=utf-8", dict(headers, Date=sports.http_date(now)))
            return True
    p.sports.note(path, 200)
    return _send(h, 200, body, "application/json; charset=utf-8", headers)


def _logo(h, path):
    m = re.fullmatch(r"/leaguestat/([a-z]+)/logos/([0-9]+)(?:_[0-9]+)?\.(png|jpg|jpeg)", path)
    if not m or m[1] not in CLIENTS:
        return _send(h, 404, b"<html><body>404</body></html>", "text/html")
    client, tid, ext = m[1], m[2], m[3]
    if ext != "png":
        return _send(h, 200, JPEG_MARKERS, "image/jpeg", {"Cache-Control": "max-age=86400"})
    if (client, tid) in NO_PNG or tid not in TEAMS[client]:
        return _send(h, 404, b"<html><body>404</body></html>", "text/html")
    colour = zlib.crc32(f"{client}{tid}".encode()) & 0xFFFFFF
    rgb = ((colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF)
    body = _png(64, 64, rgb, alpha=(client, tid) not in RGB_PNG)
    return _send(h, 200, body, "image/png", {"Cache-Control": "max-age=86400"})


def admin(h, path, data):
    """`/admin/sports-chl`: GET the slate; POST {"restart": true} or {"anchor_unix": T} re-anchors it."""
    sp = h.panel.sports_chl
    if h.command in ("POST", "PUT") and data:
        if data.get("restart"):
            sp.reset()
        elif "anchor_unix" in data:
            with sp.lock:
                sp.anchor = float(data["anchor_unix"])
    now = time.time()
    out = {}
    for c in CLIENTS:
        out[c] = {"day": sp.day(c).isoformat(), "games": [
            {"id": g[0], "kind": g[4], "away": TEAMS[c][g[1]][0], "home": TEAMS[c][g[2]][0], "start_unix": g[3],
             "state": {k: v for k, v in sp.state(c, g, now).items() if k not in ("pens", "goals")}}
            for g in sp.games(c)]}
    return h.json({"enabled": bool(h.panel.cfg.get("sports") and h.panel.cfg.get("sports_chl")), "anchor_unix": sp.anchor,
                   "timeline_s": sp.seg_starts()[1], "leagues": out})
