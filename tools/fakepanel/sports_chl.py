"""The fake HockeyTech CHL feeds (docs/design/m6-chl-leagues.md section 7.3; `--sports --sports-chl`).

A debug build's `files/debug/sports_base` origin sends the app's CHL requests here (HockeyTechEndpoints.fromOverride):

    GET /hockeytech/feed/index.php?feed=modulekit&view=gamesbydate&fetch_date=YYYY-MM-DD&key=K&client_code=C&fmt=json&lang=en
    GET ...&view=teamsbyseason&season_id=S          GET ...&view=seasons
        The keyed host. K must be `fakepanel-{client}` (the app's debug keys; the real keys never come here): any other
        key is a 200 text/html "Invalid key."; another client's fake key is "Client access denied." (both as HockeyTech).
        The body echoes the key in SiteKit.Parameters, as the real host does. `Cache-Control: max-age=240`.
    GET /firebase/svf/{client}/runningclock/games/{id}.json
    GET /firebase/svf/{client}/{publishedclock|goalssummary|shotssummary|penalties|goals|shootouts}/1/games/{id}.json
        One node at the request instant. With `X-Firebase-ETag: true` the answer carries a content ETag; a matching
        `If-None-Match` is a 304 with an empty body, with or without `X-Firebase-ETag` (as the real database). An unknown game (or a node it does not have) is JSON `null`.
    GET /firebase/svf/{client}/runningclock/games.json?orderBy="DatePlayed"&equalTo="YYYY-MM-DD"   (and .../1/games.json)
        The Today overlay date query: {game id: node} for that date ({} for none; `penalties` has an empty DatePlayed, so
        it never matches, as the real database). Combined with `X-Firebase-ETag` it is Firebase's 400 "Mixing querying
        parameters and ETag requests is not supported".
    GET /firebase/svf.json                          401 {"error" : "Permission denied"} (the closed root)
    GET /leaguestat/{client}/logos/{file}           an RGBA PNG with transparent corners; an RGB PNG (WHL 222.png, WEN);
                                                    a JPEG marker body for .jpg; 404 for the ids with no PNG (OHL 18, BRAM)

The CHL hub's detail views (docs/design/m6-chl-hub.md section 6.1; same key rules), built from this panel's own games:
    GET ...?feed=gc&tab=preview&game_id=G&fmt=json&lang_code=en        frozen at the start: records, Last 10 / Last 5,
        points leaders, tonight's lineups (`pv.visiting_team.lineup` for the visitor), the season series
    GET ...?feed=gc&tab=gamesummary&game_id=G&fmt=json&lang_code=en    the game's goals (assists, an all-null assist
        object on every third), shots and goals by period, stars, team stats, lineups with the goalies' stints added
        An unknown game is 404 {"error": "No such game"}. `Cache-Control: max-age=60`.
    GET ...?feed=modulekit&view=statviewtype&type=topgoalies&season_id=S&team_id=T&...&lang=en
        the team's goalies this season, a traded one (most games, `active` "0", another `latest_team_id`) included
    GET ...?feed=statviewfeed&view=teams&...&season=S&special=false|true&sort=...&lang=en
        the league table as JSONP `([ … ])`, joined by `prop.team_code.teamLink`; `Cache-Control: no-store`
    GET /leaguestat/{client}/240x240/{player id}.jpg   a JPEG marker for a roster player, else 404
Each body is a copy of its league's recorded fixture (core/src/testFixtures/resources/sports/chl/hub/) with the game's
facts written over it, so keys, JSON types and nulls are the feed's. Season figures and rosters are synthetic.

The slate (per league, all on the league's "today" at the anchor, 05:00 rollover in its reference zone):
- the featured game, which plays FEATURED below (520 s at the default `sports_chl_timeline_s`): pre-game "-", puck
  drop, a minor (power play), a second minor (5-on-3), a PP goal (the goal row one segment before the released minor's
  TimeOn), a double minor, the S1 fighting major against an unflagged minor, a break at 0:00 then the "20:00 2nd" form, a
  tie, a 3-on-3 OT with a penalty, the tied 0:00 OT (shootout rule c), shootout attempts with `Shootout` false (the OHL has
  no shootouts node), then Final SO with the winner's +1;
- the S3 game: 4-1 with 0:30 left, 0:00 at status 2 for SEGMENT_S3_HOLD s, then final;
- an evening game (status 1, three hours ahead; not in Firebase: board mode);
- an afternoon final that Firebase no longer lists (board mode, evicted final).

Every listed start is on the league day (emulator pass, defect 3): `date_played` + `schedule_time` in `timezone` (how
HockeyTechMapper reads it) is the game's own start instant, Firebase's `DatePlayed` is the same day, and the instant is
inside that league day: from 05:00 in the reference zone to the game zone's midnight (the latest start a HockeyTech day
can spell). A start that would fall outside is clamped to the edge, so a panel run after midnight (before the 05:00
rollover) lists the featured game at 23:59 of the league day, up to ~6-7 h before its puck drop (never ~24 h off); the
evening game, past that edge, stays "not started" at 23:59, in the past. While the featured start is clamped the timeline
is capped so the game reaches Final at least 30 min before listed start + 8 h (the app's hard expiry); the panel logs
one line when the cap shortens `sports_chl_timeline_s`. For normal behaviour run the panel between 05:00 and 21:00
league time.

Switches (config keys, also through `POST /admin/config`):
- `sports_chl` (`--sports-chl`, needs `--sports`), `sports_chl_timeline_s` (default 520, 60..86400; segments scale).
- `sports_chl_refuse` (`--chl-refuse`): every keyed request is "Invalid key.".
- `sports_chl_firebase_refuse` (`--chl-firebase-refuse`): every Firebase request is 401 "Permission denied".
- `sports_chl_null_live` (`--chl-null-live`, S5): the featured game's nodes are `null` while it is live.
- `sports_chl_drop_penalties` (`--chl-drop-penalties N`, B3): every Nth `penalties` node request is a 503.
- `sports_chl_time_tbd` (`--chl-time-tbd`): the evening game (scheduled: not live, not final) is "Time TBD" in every
  league: `time_tbd` "1" and `schedule_time` "00:00:00"; its listed start is local midnight of the league day in the
  reference zone (HockeyTechMapper's placeholder, `startTbd`).
- (CHL hub) `sports_chl_hub`: three more games per league (HUB_GAMES): a pre-game whose visitor has played no game
  (GP0_TEAMS: every figure 0, no Last 10 / Last 5, a 0-GP table row, 0-GP goalies), a pre-game with no posted lineup,
  and a postponed game (status 1, `game_status` "Postponed"). The other finals: the S3 game and the evicted final are
  regulation wins (one game-winning goal); the featured game ends in a shootout (none).
- (CHL hub) `sports_chl_detail`: "" or one fault on every hub view: `invalid_key` ("Invalid key."), `no_such_game`
  (gc 404), `slow` (3 s), `oversize` (gc bodies over 512 KB), `goalies_fail` / `tables_fail` (503).
- (CHL hub) `sports_chl_playoff`: the previews say `current_season.playoff` "1".
  Not built: the design's `chl_hub=fixtures` mode and the `pulled_goalie` switch (the traded goalie is always listed).

`GET /admin/sports-chl` shows the slate and each game's state; `POST /admin/sports-chl {"restart": true}` (or
`{"anchor_unix": T}`) re-anchors it; `POST /admin/sports {"restart": true}` restarts it with the NHL and NFL timelines.
Requests are noted in `/admin/stats` `sports.log` with the NHL and NFL ones.
"""

import base64
import copy
import datetime as dt
import hashlib
import json
import os
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
HARD_EXPIRY_S = 8 * 3600   # the app's SportsConstants.HARD_EXPIRY_MS: a follow is removed 8 h after its listed start
EXPIRY_MARGIN_S = 30 * 60  # the featured game reaches Final at least this long before that

DEFAULTS = {
    "sports_chl": False,                  # --sports-chl: serve the routes above (needs sports; 404 otherwise)
    "sports_chl_timeline_s": TIMELINE_DEFAULT_S,
    "sports_chl_refuse": False,           # --chl-refuse
    "sports_chl_firebase_refuse": False,  # --chl-firebase-refuse
    "sports_chl_null_live": False,        # --chl-null-live
    "sports_chl_drop_penalties": 0,       # --chl-drop-penalties N
    "sports_chl_time_tbd": False,         # --chl-time-tbd
    # (CHL hub P2, docs/design/m6-chl-hub.md section 6.1) the hub's detail views; config keys (POST /admin/config)
    "sports_chl_hub": False,              # adds the hub games to every league's slate (HUB_GAMES below)
    "sports_chl_detail": "",              # one of DETAIL_FAULTS: a fault on every hub view
    "sports_chl_playoff": False,          # the previews say current_season.playoff "1" (no ranks, no series)
}
DETAIL_FAULTS = ("", "invalid_key", "no_such_game", "slow", "oversize", "goalies_fail", "tables_fail")
DETAIL_SLOW_S = 3.0
DETAIL_OVERSIZE_BYTES = 600_000  # over the app's MAX_BODY_BYTES (512 KB)

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

# (CHL hub) With `sports_chl_hub`: (id, away, home, hours after the anchor, kind). `hub_gp0`: the visitor (GP0_TEAMS) has
# played no game (every season figure 0, no Last 10 / Last 5, a 0-GP table row); `hub_nolineup`: no lineup posted
# (`lineup` null on both sides, as the WHL); `hub_postponed`: status 1 with game_status "Postponed" (the only marking
# HockeyTech is thought to use; unverified, m6-chl-leagues section 3.4: the board mapper keeps it SCHEDULED).
HUB_GAMES = {
    "ohl": [("900005", "5", "14", 2, "hub_gp0"), ("900006", "10", "18", 4, "hub_nolineup"), ("900007", "14", "5", 5, "hub_postponed")],
    "whl": [("9100005", "227", "202", 2, "hub_gp0"), ("9100006", "228", "222", 4, "hub_nolineup"),
            ("9100007", "202", "227", 5, "hub_postponed")],
    "lhjmq": [("92005", "3", "2", 2, "hub_gp0"), ("92006", "7", "18", 4, "hub_nolineup"), ("92007", "2", "3", 5, "hub_postponed")],
}
GP0_TEAMS = {"ohl": "5", "whl": "227", "lhjmq": "3"}

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
    for k in ("sports_chl", "sports_chl_refuse", "sports_chl_firebase_refuse", "sports_chl_null_live", "sports_chl_time_tbd",
              "sports_chl_hub", "sports_chl_playoff"):
        if k in patch and not isinstance(patch[k], bool):
            raise ValueError(k + " must be true or false")
    if "sports_chl_detail" in patch and patch["sports_chl_detail"] not in DETAIL_FAULTS:
        raise ValueError("sports_chl_detail must be one of " + ", ".join(repr(f) for f in DETAIL_FAULTS))
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

    def __init__(self, panel, clock=time.time):
        self.panel = panel
        self.clock = clock  # unix s; tests inject a fixed one
        self.lock = threading.Lock()
        self.anchor = clock()
        self.penalty_requests = 0
        self.note_cap()

    def reset(self):
        with self.lock:
            self.anchor = self.clock()
            self.penalty_requests = 0
        self.note_cap()

    def raw_scale(self):
        return float(self.panel.cfg.get("sports_chl_timeline_s") or TIMELINE_DEFAULT_S) / TIMELINE_DEFAULT_S

    def scale_cap(self):
        """The largest scale that keeps the featured game's Final at least EXPIRY_MARGIN_S before its listed start +
        HARD_EXPIRY_S, in every league whose listed start the window clamps (a run after the game zone's midnight,
        before 05:00); None when no league clamps it."""
        k = self.raw_scale()
        final_at = sum(s["len"] for s in FEATURED[:SEG["final so"]])  # Final's offset from the anchor, default scale
        cap = None
        for c in CLIENTS:
            _, last = self.start_window(c)
            if self.anchor + FEATURED[0]["len"] * k > last:  # the listed start is clamped to `last`
                c_cap = (last + HARD_EXPIRY_S - EXPIRY_MARGIN_S - self.anchor) / final_at
                cap = c_cap if cap is None else min(cap, c_cap)
        return cap

    def scale(self):
        """The timeline scale: sports_chl_timeline_s / 520, capped by scale_cap while the listed start is clamped."""
        k, cap = self.raw_scale(), self.scale_cap()
        return k if cap is None else min(k, cap)

    def note_cap(self):
        """One log line when the cap shortens the configured timeline (at start-up and on a restart)."""
        k, cap = self.raw_scale(), self.scale_cap()
        if cap is not None and cap < k and hasattr(self.panel, "log"):
            self.panel.log(f"CHL: the featured game is listed at 23:59 of the league day (run before 05:00), so its "
                           f"timeline is capped at {TIMELINE_DEFAULT_S * cap:.0f} s (asked {TIMELINE_DEFAULT_S * k:.0f} s) "
                           f"to end before the app's 8 h expiry")

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

    def start_window(self, client):
        """(first, last) unix s a listed start of the league day can take: 05:00 of the day in the reference zone, to
        one minute before the game zone's next midnight (`date_played` + `schedule_time` cannot spell a later start)."""
        d = self.day(client)
        ref, gz = ZoneInfo(ZONES[client]), ZoneInfo(GAME_ZONES[client])
        nxt = d + dt.timedelta(days=1)
        first = dt.datetime.combine(d, dt.time(ROLLOVER_H), ref).timestamp()
        last = min(dt.datetime.combine(nxt, dt.time(ROLLOVER_H), ref).timestamp(),
                   dt.datetime.combine(nxt, dt.time(0), gz).timestamp()) - 60
        return int(first), int(last)

    def tbd_start(self, client):
        """A "Time TBD" game's listed start: local midnight of the league day in the reference zone (the mapper's)."""
        return int(dt.datetime.combine(self.day(client), dt.time(0), ZoneInfo(ZONES[client])).timestamp())

    def games(self, client):
        """[(id, away id, home id, start unix, kind)] for the league's slate; every start whole seconds, on the league
        day (start_window), or tbd_start for the "Time TBD" evening game."""
        first, last = self.start_window(client)

        def on_day(t):
            return max(first, min(last, int(t)))

        ids = SLATE[client]
        evening = self.tbd_start(client) if self.panel.cfg.get("sports_chl_time_tbd") else on_day(self.anchor + 3 * 3600)
        out = [
            (ids[0][0], ids[0][1], ids[0][2], on_day(self.featured_start()), "featured"),
            (ids[1][0], ids[1][1], ids[1][2], on_day(self.anchor - 2 * 3600), "s3"),
            (ids[2][0], ids[2][1], ids[2][2], evening, "evening"),
            (ids[3][0], ids[3][1], ids[3][2], on_day(self.anchor - 4 * 3600), "evicted"),
        ]
        if self.panel.cfg.get("sports_chl_hub"):
            out += [(gid, away, home, on_day(self.anchor + h * 3600), kind) for gid, away, home, h, kind in HUB_GAMES[client]]
        return out

    def tbd(self, client, game):
        return game[4] == "evening" and bool(self.panel.cfg.get("sports_chl_time_tbd"))

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
        if kind in ("evening", "hub_gp0", "hub_nolineup", "hub_postponed"):
            return dict(period=1, rem=None, run=False, status=1, final=False, pens=[], goals=[], so_attempts=0,
                        shootout=False, firebase=False, segment="postponed" if kind == "hub_postponed" else "scheduled")
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
        tbd = self.tbd(client, game)
        a_goals = sum(1 for g in st["goals"] if not g[0])
        h_goals = sum(1 for g in st["goals"] if g[0]) + (1 if st["shootout"] else 0)
        started = st["status"] >= 2
        periods = {str(q): {"id": str(q), "length": "1200"} for q in (1, 2, 3)}
        if st["period"] >= 4:
            periods["4"] = {"id": "4", "length": "300"}
        e = {"game_id": gid, "id": gid, "season_id": SEASONS[client], "date_played": self.day(client).isoformat(),
             "schedule_time": "00:00:00" if tbd else local.strftime("%H:%M:%S"), "time_tbd": "1" if tbd else "0", "timezone": GAME_ZONES[client],
             "status": str(st["status"]), "started": "1" if started else "0", "final": "1" if st["final"] else "0",
             "period": str(st["period"]), "game_clock": "00:" + mmss(st["rem"] or 0) if st["rem"] is not None else "",
             "game_status": "Postponed" if kind == "hub_postponed" else "", "intermission": "0", "shootout": "1" if st["shootout"] else "0", "playoff": "0",
             "home_team": home, "visiting_team": away, "periods": periods,
             "home_goal_count": str(h_goals) if started else "0", "visiting_goal_count": str(a_goals) if started else "0",
             "home_shots": str(self.board_shots(st, h_goals, started)), "visiting_shots": str(self.board_shots(st, a_goals, started))}
        for prefix, tid in (("home_team", home), ("visiting_team", away)):
            code, city, nick, _ = TEAMS[client][tid]
            e[prefix + "_code"], e[prefix + "_city"], e[prefix + "_nickname"] = code, city, nick
            e[prefix + "_name"] = f"{city} {nick}"
        return e

    @staticmethod
    def board_shots(st, goals, started):
        """(Sports Hub section 10.3) The board's shots for the panel: 0 before the game, else 4 + 2 * goals + 6 * periods
        started (capped at 3 plus overtime), so a live game's shots rise with the period and the score."""
        if not started:
            return 0
        return 4 + 2 * goals + 6 * min(int(st.get("period") or 1), 4)

    def sitekit(self, view, params, payload):
        return {"SiteKit": dict({"Parameters": params, view: payload},
                                **({"Copyright": {"required_copyright": "Official statistics provided by the fake panel",
                                                  "powered_by": "Powered by HockeyTech.com"}} if view == "Gamesbydate" else {}))}


# ------------------------------------------------------------------ the hub's detail views (CHL hub P2)
# docs/design/m6-chl-hub.md section 6.1: `feed=gc&tab=preview|gamesummary`, `modulekit&view=statviewtype&type=topgoalies`
# and `statviewfeed&view=teams` (JSONP array), generated from this panel's own games and teams so ids, codes, names and
# scores agree with the board. The recorded fixtures are the templates for the shape (keys, JSON types, nulls): each
# body is a copy of its league's fixture with the game's facts written over it. Season figures, rosters and stats are
# synthetic and deterministic (a CRC of the league and team id); GP0_TEAMS has played no game.

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "testFixtures", "resources",
                        "sports", "chl", "hub")
TEMPLATE_FILES = {
    "preview": {"ohl": "gc-preview-pre-ohl-29246.json", "whl": "gc-preview-pre-whl-1023348.json",
                "lhjmq": "gc-preview-pre-lhjmq-32953.json"},
    "gamesummary": {"ohl": "gc-gamesummary-final-ohl-29239.json", "whl": "gc-gamesummary-final-whl-1023337.json",
                    "lhjmq": "gc-gamesummary-final-ot-lhjmq-32952.json"},
    "topgoalies": {"ohl": "mk-topgoalies-ohl-t14.json", "whl": "mk-topgoalies-whl-t206.json", "lhjmq": "mk-topgoalies-lhjmq-t2.json"},
    # the tables not captured (WHL standard, QMJHL special) take the OHL's shape
    "standard": {"ohl": "svf-teams-ohl.json", "whl": "svf-teams-ohl.json", "lhjmq": "svf-teams-lhjmq.json"},
    "special": {"ohl": "svf-teams-special-ohl.json", "whl": "svf-teams-special-whl.json", "lhjmq": "svf-teams-special-ohl.json"},
}
_templates = {}
_templates_lock = threading.Lock()


def template(kind, client):
    """A fresh deep copy of the fixture body (the statviewfeed tables without their JSONP wrapper)."""
    name = TEMPLATE_FILES[kind][client]
    with _templates_lock:
        if name not in _templates:
            with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
                text = f.read().strip()
            if text.startswith("("):
                text = text[1:-1]
            _templates[name] = json.loads(text)
        return copy.deepcopy(_templates[name])


FIRST_NAMES = ["Liam", "Noah", "Owen", "Ethan", "Lucas", "Mason", "Logan", "Jacob", "Carter", "Nathan", "Felix", "Henri", "Samuel"]
LAST_NAMES = ["Abbott", "Bergeron", "Caron", "Dubois", "Ellis", "Fraser", "Gagnon", "Hayes", "Isaac", "Jensen", "Kerr", "Lavoie",
              "Morin", "Nolan", "Ouellet", "Paquette", "Quinn"]
SKATER_POS = ["C", "LW", "RW", "D", "D", "C"]


def _h(*parts):
    return zlib.crc32(":".join(parts).encode()) & 0xFFFFFFFF


def roster(client, tid):
    """[(player_id, first, last, pos, jersey)]: two goalies (starter first), then six skaters. Ids are digits only."""
    seed = _h(client, tid)
    out = []
    for k, pos in enumerate(["G", "G"] + SKATER_POS):
        pid = str(int(tid) * 100 + 10 + k)
        out.append((pid, FIRST_NAMES[(seed + 7 * k) % len(FIRST_NAMES)], LAST_NAMES[(seed // 7 + 5 * k) % len(LAST_NAMES)],
                    pos, str(30 + k if pos == "G" else 2 + 3 * k)))
    return out


def traded_goalie(client, tid):
    """The goalie who played most for the team this season but was traded: `active` "0", another `latest_team_id`."""
    pid = str(int(tid) * 100 + 99)
    return pid, "Victor", "Traded", "G", "39"


def is_gp0(sp, client, tid):
    return bool(sp.panel.cfg.get("sports_chl_hub")) and GP0_TEAMS[client] == tid


def team_stats(sp, client, tid):
    """The team's season to date (synthetic, deterministic); all zero for a GP0 team (with the hub games on)."""
    if is_gp0(sp, client, tid):
        return dict(gp=0, w=0, l=0, otl=0, sol=0, gf=0, ga=0, pp=0, ppg=0, tsh=0, ppga=0, l10=(0, 0, 0, 0))
    h = _h("stats", client, tid)
    gp = 20 + h % 6
    otl, sol = (h >> 6) % 3, (h >> 8) % 2
    losses = 5 + (h >> 3) % 5
    wins = gp - losses - otl - sol
    o10, s10 = min(otl, 1), min(sol, 1)
    w10 = 4 + h % 3
    return dict(gp=gp, w=wins, l=losses, otl=otl, sol=sol, gf=3 * gp + (h >> 10) % 20, ga=3 * gp + (h >> 14) % 18,
                pp=70 + (h >> 18) % 30, ppg=12 + (h >> 20) % 10, tsh=75 + (h >> 22) % 25, ppga=14 + (h >> 24) % 10,
                l10=(w10, 10 - w10 - o10 - s10, o10, s10))


def _name(client, tid):
    code, city, nick, _ = TEAMS[client][tid]
    return f"{city}, {nick}" if client == "lhjmq" and city == "Cape Breton" else f"{city} {nick}"


def _like(tpl_value, v):
    """v in the template value's JSON type (the feed mixes strings and ints)."""
    if isinstance(tpl_value, str):
        return str(v)
    if isinstance(tpl_value, bool):
        return bool(v)
    if isinstance(tpl_value, int) and not isinstance(v, bool):
        return int(v)
    return v


def _over(obj, **values):
    """Writes [values] over [obj], each in the JSON type the template already has for that key."""
    for k, v in values.items():
        obj[k] = _like(obj[k], v) if k in obj and obj[k] is not None and v is not None else v
    return obj


def _echo(q, keys):
    """The request echo the real host puts in `Parameters` (with the request's key: the fake key, never a real one)."""
    return {k: q.get(k, "") for k in keys}


def _person(tpl, p, client=None, tid=None):
    pid, first, last, pos, jersey = p
    out = _over(copy.deepcopy(tpl), player_id=pid, first_name=first, last_name=last, jersey_number=jersey)
    if "person_id" in out:
        out["person_id"] = str(int(pid) + 5000)
    if "position_str" in out:
        out["position_str"] = pos
    if client is not None and "team_code" in out:
        out["team_id"], out["team_code"] = tid, TEAMS[client][tid][0]
    return out


def _lineup(client, tid, starter, lineup_tpl):
    """A preview lineup: both goalies (start "1" on the starter only when [starter]) and the skaters."""
    g_tpl, p_tpl = lineup_tpl["goalies"][0], lineup_tpl["players"][0]
    people = roster(client, tid)
    goalies = []
    for k, p in enumerate(people[:2]):
        e = _person(g_tpl, p)
        e["start"] = "1" if starter and k == 0 else "0"
        goalies.append(e)
    # the backup listed first (as the OHL fixture lists Jaswal before Cooke): the starter's order is the mapper's
    goalies.reverse()
    return {"goalies": goalies, "players": [_person(p_tpl, p) for p in people[2:]]}


def _last_games(sp, client, tid, tpl, day):
    """Five past games, oldest first, against the league's other teams (none for a GP0 team)."""
    if is_gp0(sp, client, tid):
        return []
    others = [t for t in TEAMS[client] if t != tid]
    out = []
    for k in range(5):
        opp = others[k % len(others)]
        at_home = k % 2 == 0
        won = (_h("lg", client, tid, str(k)) % 3) != 0
        mine, theirs = (4, 2) if won else (1, 3)
        home, away = (tid, opp) if at_home else (opp, tid)
        hg, vg = (mine, theirs) if at_home else (theirs, mine)
        d = day - dt.timedelta(days=15 - 3 * k)
        e = copy.deepcopy(tpl)
        _over(e, id=str(800000 + int(tid) * 10 + k), date_played=d.isoformat(), game_date_iso_8601=f"{d.isoformat()}T19:00:00-05:00",
              home_team=home, homeCode=TEAMS[client][home][0], homeCity=TEAMS[client][home][1], home_goal_count=hg,
              visiting_team=away, visitingCode=TEAMS[client][away][0], visitingCity=TEAMS[client][away][1],
              visiting_goal_count=vg, winner=tid if won else opp)
        out.append(e)
    return out


def _fill_team(sp, team, client, tid, day, last_tpl, scorer_tpl):
    code, city, nick, _ = TEAMS[client][tid]
    s = team_stats(sp, client, tid)
    _over(team, team_id=tid, name=_name(client, tid), city=city, team_code=code, nickname=nick, wins=s["w"], losses=s["l"],
          ties=0, ot_losses=s["otl"], shootout_losses=s["sol"], points=2 * s["w"] + s["otl"] + s["sol"], goals_for=s["gf"],
          goals_against=s["ga"], power_plays=s["pp"], power_play_goals=s["ppg"], times_short_handed=s["tsh"],
          power_play_goals_against=s["ppga"], gp=s["gp"], gamesPlayed=s["gp"], past_10_wins=s["l10"][0],
          past_10_losses=s["l10"][1], past_10_ot_losses=s["l10"][2], past_10_shootout_losses=s["l10"][3],
          last10="-".join(str(x) for x in s["l10"]))
    if isinstance(team.get("pp"), dict):
        _over(team["pp"], power_plays=s["pp"], power_play_goals=s["ppg"], percentage=round(100 * s["ppg"] / s["pp"], 2) if s["pp"] else 0)
    if isinstance(team.get("pk"), dict):
        _over(team["pk"], times_short_handed=s["tsh"], power_play_goals_against=s["ppga"],
              percentage=round(100 - 100 * s["ppga"] / s["tsh"], 2) if s["tsh"] else 0)
    team["lastGames"] = _last_games(sp, client, tid, last_tpl, day)
    scorers = []
    if s["gp"]:
        for k, p in enumerate(roster(client, tid)[2:5]):
            g, a = 12 - 2 * k, 15 - 3 * k
            scorers.append(_over(_person(scorer_tpl, p), goals=g, assists=a, points=g + a, rookie="0", penalty_minutes=10 + k))
    team["leadingScorers"] = scorers


# A previous meeting's shape (the OHL template has none; the WHL fixture lists three).
def _meeting_tpl():
    return template("preview", "whl")["GC"]["Preview"]["previous_meetings"][0]


def preview_body(sp, client, game, q):
    """`feed=gc&tab=preview` for one of this panel's games: frozen at the game's start, whatever its state now."""
    gid, away, home, start, kind = game
    body = template("preview", client)
    gc = body["GC"]
    gc["Parameters"] = dict(_echo(q, ("feed", "tab", "game_id", "fmt", "lang_code", "key", "client_code")), lang_id=1)
    pv = gc["Preview"]
    ohl_pv = template("preview", "ohl")["GC"]["Preview"]  # the lineup, lastGames and leadingScorers element shapes
    lineup_tpl, last_tpl, scorer_tpl = ohl_pv["home_team"]["lineup"], ohl_pv["home_team"]["lastGames"][0], ohl_pv["home_team"]["leadingScorers"][0]
    _over(pv["current_season"], id=SEASONS[client], playoff="1" if sp.panel.cfg.get("sports_chl_playoff") else "0")
    day = sp.day(client)
    _fill_team(sp, pv["visitor_team"], client, away, day, last_tpl, scorer_tpl)
    _fill_team(sp, pv["home_team"], client, home, day, last_tpl, scorer_tpl)
    if kind == "hub_nolineup":
        pv["home_team"]["lineup"], pv["visiting_team"] = None, {"lineup": None}
    else:
        starter = kind != "hub_gp0"  # the gp0 game: tonight's dressed pair, nobody marked
        pv["home_team"]["lineup"] = _lineup(client, home, starter, lineup_tpl)
        pv["visiting_team"] = {"lineup": _lineup(client, away, starter and kind == "evening", lineup_tpl)}
    h2h = pv["HeadToHeadRecord"]
    for side, tid in (("visiting_team", away), ("home_team", home)):
        _over(h2h[side], name=_name(client, tid), w=0, l=0, otl=0, sl=0, t=0, otw=0, sw=0)
    pv["previous_meetings"] = []
    if kind == "evening":  # one earlier meeting this season, the home side's 3-2 win (the others: a first meeting)
        h2h["home_team"]["w"], h2h["visiting_team"]["l"] = 1, 1
        d = day - dt.timedelta(days=9)
        pv["previous_meetings"] = [_over(_meeting_tpl(), gameId=str(int(gid) - 50), visitingTeamId=away,
                                         visitingCity=TEAMS[client][away][1], visitingScore="2", homeTeamId=home,
                                         homeCity=TEAMS[client][home][1], homeScore="3", datePlayed=d.isoformat(),
                                         game_date_iso_8601=f"{d.isoformat()}T19:00:00-05:00", status="Final")]
    return body


def _clock(t):
    """ "09:10" -> ("9:10", 550)."""
    m, s = (int(x) for x in t.split(":"))
    return f"{m}:{s:02d}", m * 60 + s


def gamesummary_body(sp, client, game, q, at):
    """`feed=gc&tab=gamesummary` for one of this panel's games at [at]: its goals, shots, stars and lineups."""
    gid, away, home, start, kind = game
    st = sp.state(client, game, at)
    body = template("gamesummary", client)
    gc = body["GC"]
    gc["Parameters"] = dict(_echo(q, ("feed", "tab", "game_id", "fmt", "lang_code", "key", "client_code")), lang_id=1)
    gs = gc["Gamesummary"]
    final, status = st["final"], 4 if st["final"] else st["status"]
    pre = status == 1
    goals = sorted(st["goals"], key=lambda g: (g[1], _clock(g[2])[1]))
    periods = max(3, st["period"]) if not pre else 3
    sides = {"visitor": away, "home": home}
    people = {s: roster(client, t) for s, t in sides.items()}
    goal_tpl = template("gamesummary", "ohl")["GC"]["Gamesummary"]["goals"][0]
    null_person = {k: None for k in goal_tpl["assist2_player"]}
    count = {"visitor": 0, "home": 0}
    by_player = {}
    items = []
    for i, (is_home, per, tm, pp) in enumerate(goals):
        side = "home" if is_home else "visitor"
        tid = sides[side]
        sk = people[side][2:]
        scorer, a1, a2 = sk[i % 6], sk[(i + 1) % 6], sk[(i + 2) % 6]
        count[side] += 1
        by_player.setdefault(scorer[0], [0, 0])[0] += 1
        text, secs = _clock(tm)
        e = copy.deepcopy(goal_tpl)
        _over(e, time=text, team_id=tid, home="1" if is_home else "0", period_id=str(per), power_play="1" if pp else "0",
              empty_net="0", short_handed="0", penalty_shot="0", game_winning="0", game_tieing="0", insurance_goal="0",
              scorer_goal_num=str(3 + count[side]), s=secs)
        e["goal_scorer"] = _person(goal_tpl["goal_scorer"], scorer, client, tid)
        e["assist1_player"] = _person(goal_tpl["assist1_player"], a1, client, tid)
        by_player.setdefault(a1[0], [0, 0])[1] += 1
        if i % 3 == 2:  # every third goal has one assist: the other slot is an object of nulls, as the feed sends
            e["assist2_player"] = dict(null_person)
        else:
            e["assist2_player"] = _person(goal_tpl["assist2_player"], a2, client, tid)
            by_player.setdefault(a2[0], [0, 0])[1] += 1
        e["plus"], e["minus"] = [], []
        items.append((side, e, scorer))
    a_total, h_total = count["visitor"], count["home"]
    winner = None
    if final:
        winner = "home" if (h_total > a_total or st["shootout"]) else "visitor"
        if not st["shootout"]:
            need = min(a_total, h_total) + 1
            n = 0
            for side, e, _ in items:
                if side == winner:
                    n += 1
                    if n == need:
                        e["game_winning"] = "1"
    gs["goals"] = [e for _, e, _ in items]
    gbp = {s: {str(p): sum(1 for side, e, _ in items if side == s and e["period_id"] == str(p)) for p in range(1, periods + 1)}
           for s in sides}
    sbp = {s: {str(p): 0 if pre else 6 + 2 * gbp[s][str(p)] + _h("shots", client, gid, s, str(p)) % 5 for p in range(1, periods + 1)}
           for s in sides}
    shots = {s: sum(v.values()) for s, v in sbp.items()}
    gs["goalsByPeriod"], gs["shotsByPeriod"] = gbp, sbp
    gs["totalShots"], gs["totalShotsOn"] = dict(shots), dict(shots)
    gs["totalGoals"], gs["goalCount"] = dict(count), dict(count)
    ppg = {s: sum(1 for side, e, _ in items if side == s and e["power_play"] == "1") for s in sides}
    gs["powerPlayGoals"] = ppg
    gs["powerPlayCount"] = {s: 0 if pre else ppg[s] + 2 for s in sides}
    gs["totalFaceoffs"] = {"visitor": {"att": 0 if pre else 60, "won": 0 if pre else 29}, "home": {"att": 0 if pre else 60, "won": 0 if pre else 31}}
    gs["pimTotal"] = {"visitor": 0 if pre else 8, "home": 0 if pre else 6}
    hits = client in ("lhjmq",) and not pre
    gs["totalHits"] = {"visitor": 14 if hits else 0, "home": 19 if hits else 0}
    for s, tid in sides.items():
        code, city, nick, _ = TEAMS[client][tid]
        _over(gs[s], id=tid, code=code, team_code=code, team_id=tid, name=_name(client, tid), city=city, nickname=nick,
              season_id=SEASONS[client])
    gs["venue"] = f"{TEAMS[client][home][1]} Arena"
    d = sp.day(client)
    stars = []
    if final:
        # First star the game winner's scorer, then the other scorers, the winning side first.
        ranked = sorted(((side, scorer, e["game_winning"] == "1") for side, e, scorer in items), key=lambda x: (not x[2], x[0] != winner))
        ranked = [(side, p) for side, p, _ in ranked]
        seen = []
        for side, p in ranked:
            if p[0] not in [x[1][0] for x in seen]:
                seen.append((side, p))
        for side, p in seen[:3]:
            stars.append({"player_id": p[0], "first_name": p[1], "last_name": p[2], "jersey_number": p[4], "home": 1 if side == "home" else 0})
    gs["mvps"] = stars + [None] * (3 - len(stars))
    meta = gs["meta"]
    _over(meta, id=gid, season_id=SEASONS[client], home_team=home, visiting_team=away, date_played=d.isoformat(),
          status=str(status), started="0" if pre else "1", final="1" if final else "0", period=str(st["period"]),
          shootout="1" if st["shootout"] else "0", home_goal_count=h_total + (1 if st["shootout"] else 0),
          visiting_goal_count=a_total, attendance="" if pre else str(2000 + _h("att", client, gid) % 3000),
          mvp1=stars[0]["player_id"] if stars else "", mvp2=stars[1]["player_id"] if len(stars) > 1 else "",
          mvp3=stars[2]["player_id"] if len(stars) > 2 else "")
    _over(gs, game_ident=gid, shootout="1" if st["shootout"] else "0",
          status_value="Final" if final else ("Not Started" if pre else "In Progress"))
    gs["penalties"] = None if pre else []
    # Lineups: the skaters' points from the goals; both goalies, the starter with the whole game.
    lt = template("gamesummary", "ohl")["GC"]["Gamesummary"]["home_team_lineup"]
    stint_tpl = template("gamesummary", "ohl")["GC"]["Gamesummary"]["goalies"]["home"][0]
    length = 0 if pre else 3600 + (300 if st["period"] >= 4 else 0)
    stints = {}
    for s, tid in sides.items():
        other = "home" if s == "visitor" else "visitor"
        sa, ga = shots[other], count[other]
        players = []
        for p in people[s][2:]:
            g, a = by_player.get(p[0], [0, 0])
            players.append(_over(_person(lt["players"][0], p), goals=g, assists=a, plusminus=0, shots=str(g + 1 if not pre else 0)))
        goalies = []
        for k, p in enumerate(people[s][:2]):
            on = k == 0 and length > 0
            e = _over(_person(lt["goalies"][0], p), start="1" if k == 0 else "0", seconds=length if on else 0,
                      shots_against=sa if on else 0, goals_against=ga if on else 0, saves=(sa - ga) if on else 0)
            e["time"] = f"{length // 60}:{length % 60:02d}" if on else None
            goalies.append(e)
        gs[f"{s}_team_lineup"] = {"goalies": goalies, "players": players}
        stints[s] = [] if pre else [_over(_person(stint_tpl, people[s][0]), id=str(_h("stint", gid, s) % 90000), saves=sa - ga,
                                          secs=length, seconds=length, goals_against=ga, shots_against=sa,
                                          win="1" if winner == s else "0", loss="0" if winner == s else "1",
                                          secs_mmss=f"{length // 60}:{length % 60:02d}")]
    gs["goalies"] = stints
    return body


def topgoalies_body(sp, client, season, tid, q):
    """`modulekit&view=statviewtype&type=topgoalies&team_id`: everyone who kept goal for the team this season, the traded
    goalie (most games, `active` "0", another `latest_team_id`) included; strings as the feed sends them."""
    body = template("topgoalies", client)
    kit = body["SiteKit"]
    kit["Parameters"] = dict(_echo(q, ("feed", "view", "type", "season_id", "team_id", "first", "limit", "fmt", "lang", "key",
                                       "client_code")), lang_id=1)
    rows = []
    if season == SEASONS[client] and tid in TEAMS[client]:
        row_tpl = kit["Statviewtype"][0]
        gp0 = is_gp0(sp, client, tid)
        others = [t for t in TEAMS[client] if t != tid]
        entries = [(p, (0, 0) if gp0 else (14, 0) if k == 0 else (6, 0), True) for k, p in enumerate(roster(client, tid)[:2])]
        if not gp0:
            entries.append((traded_goalie(client, tid), (18, 1), False))
        for p, (gp, _), active in entries:
            w = gp // 2
            losses = gp - w - (1 if gp else 0)
            ga = 3 * gp
            shots = 30 * gp
            e = _over(copy.deepcopy(row_tpl), player_id=p[0], first_name=p[1], last_name=p[2], name=f"{p[1]} {p[2]}",
                      shortname=f"{p[1][0]}. {p[2]}", jersey_number=p[4], team_id=tid, team_code=TEAMS[client][tid][0],
                      team_name=_name(client, tid), active="1" if active else "0",
                      latest_team_id=tid if active else others[0], num_teams="1" if active else "2",
                      games_played=str(gp), has_games_played="1" if gp else "0", wins=str(w), losses=str(losses),
                      ot_losses="1" if gp else "0", shootout_losses="0", goals_against=str(ga), shots=str(shots),
                      saves=str(shots - ga), minutes_played=60 * gp,
                      goals_against_average=f"{3.0 if gp else 0:.2f}",
                      save_percentage=f"{(shots - ga) / shots:.3f}".lstrip("0") if shots else "0.000",
                      photo=f"{q.get('_base', '')}{LOGO_PREFIX}{client}/60x60/{p[0]}.jpg")
            rows.append(e)
        rows.sort(key=lambda e: -int(e["games_played"]))
    kit["Statviewtype"] = rows
    return body


def table_body(sp, client, season, special):
    """`statviewfeed&view=teams` (`special=false` GF/GA, `special=true` PP/PK) for the league's teams: the array the
    JSONP wrapper goes around. `rank` is the sort position, as the feed's (the app computes its own ranks)."""
    arr = template("special" if special else "standard", client)
    sec = arr[0]["sections"][0]
    row_tpl = sec["data"][0]
    out = []
    if season == SEASONS[client]:
        for tid in TEAMS[client]:
            s = team_stats(sp, client, tid)
            e = copy.deepcopy(row_tpl)
            e["prop"] = {"team_code": {"teamLink": tid}, "name": {"teamLink": tid}}
            r = e["row"]
            common = dict(team_code=TEAMS[client][tid][0], name=_name(client, tid), games_played=s["gp"], ot_losses=s["otl"],
                          shootout_losses=s["sol"])
            if special:
                _over(r, power_play_goals=s["ppg"], power_plays=s["pp"], power_play_goals_against=s["ppga"],
                      times_short_handed=s["tsh"], power_play_pct=f"{100 * s['ppg'] / s['pp'] if s['pp'] else 0:.1f}%",
                      penalty_kill_pct=f"{100 - 100 * s['ppga'] / s['tsh'] if s['tsh'] else 0:.1f}%", **common)
            else:
                _over(r, wins=s["w"], losses=s["l"], points=2 * s["w"] + s["otl"] + s["sol"], goals_for=s["gf"],
                      goals_against=s["ga"], goals_diff=s["gf"] - s["ga"], past_10="-".join(str(x) for x in s["l10"]), **common)
            out.append(e)
        key = (lambda e: -int(e["row"]["power_play_goals"])) if special else (lambda e: -int(e["row"]["points"]))
        out.sort(key=key)
        for i, e in enumerate(out):
            e["row"]["rank"] = i + 1
            if "overall_rank" in e["row"]:
                e["row"]["overall_rank"] = str(i + 1)
    sec["data"] = out
    return arr


def _hub(h, sp, cfg, q, now, client, feed):
    """One hub view, after the key check; [feed] is gc, modulekit (topgoalies) or statviewfeed."""
    p = h.panel
    fault = cfg.get("sports_chl_detail") or ""
    label = {"gc": "gc:" + q.get("tab", ""), "modulekit": "topgoalies", "statviewfeed": "teams"}[feed]
    p.sports.note(FEED_PATH + "?view=" + label, 200)
    if fault == "invalid_key":
        return _send(h, 200, b"Invalid key.", "text/html; charset=UTF-8")
    if fault == "slow":
        time.sleep(DETAIL_SLOW_S)
    js = "application/json; charset=utf-8"
    if feed == "gc":
        game = next((g for g in sp.games(client) if g[0] == q.get("game_id")), None)
        tab = q.get("tab")
        if fault == "no_such_game" or game is None or tab not in ("preview", "gamesummary"):
            return _send(h, 404, json.dumps({"error": "No such game"}).encode(), js)
        body = preview_body(sp, client, game, q) if tab == "preview" else gamesummary_body(sp, client, game, q, now)
        if fault == "oversize":
            body["GC"]["pad"] = "x" * DETAIL_OVERSIZE_BYTES
        return _send(h, 200, json.dumps(body).encode(), js, {"Cache-Control": "max-age=60"})
    if feed == "modulekit":
        if fault == "goalies_fail":
            return _send(h, 503, b'{"error":"Service Unavailable"}', js)
        q = dict(q, _base=h.base())
        body = topgoalies_body(sp, client, q.get("season_id", ""), q.get("team_id", ""), q)
        return _send(h, 200, json.dumps(body).encode(), js, {"Cache-Control": "max-age=240"})
    if fault == "tables_fail":
        return _send(h, 503, b'{"error":"Service Unavailable"}', js)
    arr = table_body(sp, client, q.get("season", ""), q.get("special") == "true")
    return _send(h, 200, b"(" + json.dumps(arr).encode() + b")", "application/javascript; charset=utf-8", {"Cache-Control": "no-store"})


def _headshot(h, sp, path):
    """`/leaguestat/{client}/240x240/{id}.jpg` (and the 60x60 `photo`): a JPEG marker for a roster player, else 404."""
    m = re.fullmatch(r"/leaguestat/([a-z]+)/(?:240x240|60x60)/([0-9]{1,12})\.jpg", path)
    if m and m[1] in CLIENTS:
        ids = {pl[0] for t in TEAMS[m[1]] for pl in roster(m[1], t) + [traded_goalie(m[1], t)]}
        if m[2] in ids:
            return _send(h, 200, JPEG_MARKERS, "image/jpeg", {"Cache-Control": "max-age=86400"})
    return _send(h, 404, b"<html><body>404</body></html>", "text/html")


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
        if "/logos/" not in path:
            return _headshot(h, sp, path)
        return _logo(h, path)
    return _firebase(h, sp, cfg, path, q, now)


def _feed(h, sp, cfg, q, now):
    p = h.panel
    client, key, view = q.get("client_code", ""), q.get("key", ""), q.get("view", "")
    params = {"feed": q.get("feed", ""), "view": view, "key": key, "client_code": client, "fmt": q.get("fmt", ""),
              "lang": q.get("lang", "")}
    owners = {fake_key(c): c for c in CLIENTS}
    feed = q.get("feed")
    if cfg.get("sports_chl_refuse") or key not in owners or feed not in ("modulekit", "gc", "statviewfeed"):
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Invalid key.", "text/html; charset=UTF-8")
    if owners[key] != client:
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Client access denied.", "text/html; charset=UTF-8")
    # (CHL hub) the detail views
    if feed == "gc" or feed == "statviewfeed" and view == "teams" or feed == "modulekit" and view == "statviewtype" \
            and q.get("type") == "topgoalies":
        return _hub(h, sp, cfg, q, now, client, feed)
    if feed != "modulekit":
        p.sports.note(FEED_PATH + "?view=" + view, 200)
        return _send(h, 200, b"Invalid key.", "text/html; charset=UTF-8")
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
    tag = etag_of(body)
    if wants_tag:
        headers["ETag"] = tag
        headers["Access-Control-Expose-Headers"] = "ETag"
    # As the real database (checked 2026-11-14, WP-3 review L1): a matching If-None-Match is a 304 with or without
    # X-Firebase-ETag; only the ETag *header* needs X-Firebase-ETag.
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
