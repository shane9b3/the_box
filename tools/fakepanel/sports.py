"""fakepanel --sports: a fake NHL score API for the score bug (docs/design/m6-score-bug.md section 8).

Serves the NHL's own paths, so the app only swaps the origin (debug builds: `files/debug/sports_base`):

    GET /v1/score/now                    307 -> /v1/score/{today}   (today = the panel's Eastern date)
    GET /v1/score/{yyyy-MM-dd}           the day list (yesterday, today and tomorrow have games; other days are empty)
    GET /v1/gamecenter/{id}/landing      one game (404 JSON for an unknown id)
    GET /logos/nhl/svg/{ABBR}_{dark|light}.svg   a neutral placeholder SVG of ours (no NHL art in the repo)

CDN behaviour: every answer is the state at the start of the current 20 s bucket, with `Date` = now and `Age` = the
seconds since the bucket started, `Cache-Control: max-age=19` and a bucket ETag (which the app must ignore, A-S1).

The featured game (CGY @ EDM, id ...0901) runs a compressed timeline anchored at the server start (or the last
`POST /admin/sports {"restart": true}`): FUT -> PRE -> LIVE 1st -> INT -> LIVE 2nd (CGY goal) -> INT -> LIVE 3rd (EDM
goal) -> OT -> SO -> OFF -> FINAL, about 6 min at the default `sports_timeline_s` 360. Live clocks run at 1 s/s, so the
app's local extrapolation matches; the 1st period has a 10 s stoppage (clock stopped).

Scripting (config keys, also through `POST /admin/config`):
- `sports_script`: {"<game id>": {state overrides}}; keys of `state()` below (gameState, period, periodType,
  secondsRemaining, running, inIntermission, away, home, scheduleState, lastPeriodType), plus `"gone": true` (the
  landing answers 404 and the day list leaves the game out).
- `sports_faults`: {"status": N (every /v1 answer), "score_status": N, "landing_status": N, "html_404": true (404s carry
  an HTML body), "retry_after": S, "pad_bytes": N (a filler field, for the 512 KB cap), "age": S (a fixed Age),
  "no_date": true (no Date header), "stall_s": S (wait before answering)}.
`GET /admin/sports` shows the slate; `/admin/stats` carries `sports: {requests, by_path, log}`.
"""
import datetime as dt
import email.utils
import json
import math
import re
import threading
import time
import zlib
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
BUCKET_S = 20
LOG_MAX = 200
TIMELINE_DEFAULT_S = 360

DEFAULTS = {
    "sports": False,              # --sports: serve the routes above (404 otherwise)
    "sports_timeline_s": TIMELINE_DEFAULT_S,
    "sports_script": {},
    "sports_faults": {},
}
FAULT_KEYS = ("status", "score_status", "landing_status", "html_404", "retry_after", "pad_bytes", "age", "no_date", "stall_s")
STATE_KEYS = ("gameState", "period", "periodType", "secondsRemaining", "running", "inIntermission", "away", "home",
              "scheduleState", "lastPeriodType", "gone")

# abbrev -> (team id, place name, common name, colour)
TEAMS = {
    "CGY": (20, "Calgary", "Flames", "#c8102e"),
    "EDM": (22, "Edmonton", "Oilers", "#fc4c02"),
    "UTA": (68, "Utah", "Mammoth", "#6cace4"),
    "MTL": (8, "Montréal", "Canadiens", "#af1e2d"),
    "TOR": (10, "Toronto", "Maple Leafs", "#00205b"),
    "BOS": (6, "Boston", "Bruins", "#fcb514"),
    "NSH": (18, "Nashville", "Predators", "#ffb81c"),
    "COL": (21, "Colorado", "Avalanche", "#6f263d"),
    "OTT": (9, "Ottawa", "Senators", "#c52032"),
    "BUF": (7, "Buffalo", "Sabres", "#003087"),
    "SEA": (55, "Seattle", "Kraken", "#99d9d9"),
    "VGK": (54, "Vegas", "Golden Knights", "#b4975a"),
}

# The featured game's timeline at TIMELINE_DEFAULT_S: (length s, state). Live clocks start at the segment length.
SEGMENTS = [
    (30, {"gameState": "FUT"}),
    (15, {"gameState": "PRE"}),
    (60, {"gameState": "LIVE", "period": 1, "periodType": "REG", "clock": "run", "stoppage": (20, 30)}),
    (30, {"gameState": "LIVE", "period": 1, "periodType": "REG", "clock": "int"}),
    (60, {"gameState": "LIVE", "period": 2, "periodType": "REG", "clock": "run", "goal": ("away", 30)}),
    (20, {"gameState": "LIVE", "period": 2, "periodType": "REG", "clock": "int"}),
    (60, {"gameState": "CRIT", "period": 3, "periodType": "REG", "clock": "run", "goal": ("home", 40)}),
    (30, {"gameState": "LIVE", "period": 4, "periodType": "OT", "clock": "run"}),
    (20, {"gameState": "LIVE", "period": 5, "periodType": "SO", "clock": "zero"}),
    (20, {"gameState": "OFF", "period": 5, "periodType": "SO", "clock": "zero", "winner": "home"}),
    (None, {"gameState": "FINAL", "period": 5, "periodType": "SO", "clock": "zero", "winner": "home"}),
]
PRE_PUCK_S = SEGMENTS[0][0] + SEGMENTS[1][0]


def check_config(patch):
    """Raises ValueError for a bad sports key in a config patch."""
    if "sports" in patch and not isinstance(patch["sports"], bool):
        raise ValueError("sports must be true or false")
    if "sports_timeline_s" in patch:
        v = patch["sports_timeline_s"]
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 30 <= v <= 86400:
            raise ValueError("sports_timeline_s must be a number 30..86400")
    if "sports_script" in patch:
        s = patch["sports_script"]
        if not isinstance(s, dict) or not all(isinstance(v, dict) for v in s.values()):
            raise ValueError("sports_script must be an object {game id: {state overrides}}")
        for v in s.values():
            bad = [k for k in v if k not in STATE_KEYS]
            if bad:
                raise ValueError("unknown sports_script keys: " + ", ".join(bad))
    if "sports_faults" in patch:
        f = patch["sports_faults"]
        if not isinstance(f, dict):
            raise ValueError("sports_faults must be an object")
        bad = [k for k in f if k not in FAULT_KEYS]
        if bad:
            raise ValueError("unknown sports_faults keys: " + ", ".join(bad))


def http_date(unix):
    return email.utils.formatdate(unix, usegmt=True)


def iso_z(unix):
    return dt.datetime.fromtimestamp(int(unix), dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def mmss(secs):
    secs = max(0, int(secs))
    return f"{secs // 60:02d}:{secs % 60:02d}"


def season_of(day):
    y = day.year if day.month >= 9 else day.year - 1
    return y, int(f"{y}{y + 1}")


class Sports:
    """The slate and the clock. Thread-safe; one per Panel."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.anchor = time.time()
        self.reset_stats()

    def reset(self):
        with self.lock:
            self.anchor = time.time()
        self.reset_stats()

    def reset_stats(self):
        with self.lock:
            self.requests = 0
            self.by_path = {}
            self.log = []

    def stats(self):
        with self.lock:
            return {"requests": self.requests, "by_path": dict(self.by_path), "log": list(self.log), "anchor_unix": self.anchor}

    def note(self, path, status):
        kind = re.sub(r"/\d{4}-\d{2}-\d{2}$", "/{date}", re.sub(r"/gamecenter/\d+/", "/gamecenter/{id}/", path))
        with self.lock:
            self.requests += 1
            self.by_path[kind] = self.by_path.get(kind, 0) + 1
            self.log = (self.log + [{"path": path, "status": status, "unix": time.time()}])[-LOG_MAX:]

    # ---- time
    def scale(self):
        return float(self.panel.cfg.get("sports_timeline_s") or TIMELINE_DEFAULT_S) / TIMELINE_DEFAULT_S

    @staticmethod
    def bucket_start(now):
        return math.floor(now / BUCKET_S) * BUCKET_S

    def today(self, at):
        return dt.datetime.fromtimestamp(at, ET).date()

    # ---- the slate
    def slate(self, day, at):
        """Games of Eastern [day] as (id, away, home, start unix, state fn(at) -> dict)."""
        today = self.today(self.anchor)
        y, _ = season_of(day)
        delta = (day - today).days
        gid = lambda n: f"{y}02{n:04d}"  # noqa: E731
        evening = dt.datetime.combine(day, dt.time(19, 0), ET).timestamp()
        if delta == 0:
            return [
                (gid(901), "CGY", "EDM", self.anchor + PRE_PUCK_S * self.scale(), self.featured),
                (gid(902), "TOR", "BOS", evening, self.looping_live),
                (gid(903), "UTA", "MTL", evening, lambda a: {"gameState": "LIVE", "period": 1, "periodType": "REG",
                                                            "secondsRemaining": 958 - int((a - self.anchor) % 900), "running": False,
                                                            "inIntermission": True, "away": 2, "home": 2}),
                (gid(904), "NSH", "COL", self.anchor + 7200, lambda a: {"gameState": "FUT"}),
                (gid(905), "OTT", "BUF", evening - 3600, lambda a: {"gameState": "OFF", "period": 4, "periodType": "OT",
                                                                    "secondsRemaining": 245, "running": False, "inIntermission": False,
                                                                    "away": 2, "home": 1, "lastPeriodType": "OT"}),
            ]
        if delta == -1:
            return [
                (gid(801), "SEA", "VGK", evening, lambda a: {"gameState": "OFF", "period": 3, "periodType": "REG", "secondsRemaining": 0,
                                                             "running": False, "inIntermission": False, "away": 1, "home": 4,
                                                             "lastPeriodType": "REG"}),
                (gid(802), "CGY", "TOR", evening + 1800, lambda a: {"gameState": "OFF", "period": 4, "periodType": "OT", "secondsRemaining": 130,
                                                                    "running": False, "inIntermission": False, "away": 3, "home": 2,
                                                                    "lastPeriodType": "OT"}),
            ]
        if delta == 1:
            return [
                (gid(951), "MTL", "OTT", evening, lambda a: {"gameState": "FUT"}),
                (gid(952), "EDM", "SEA", evening + 10800, lambda a: {"gameState": "FUT"}),
            ]
        return []

    def find(self, game_id, at):
        today = self.today(self.anchor)
        for d in (-1, 0, 1):
            day = today + dt.timedelta(days=d)
            for g in self.slate(day, at):
                if g[0] == game_id:
                    return day, g
        return None, None

    def featured(self, at):
        """The compressed timeline: the state of the featured game at unix [at]."""
        t = at - self.anchor
        k = self.scale()
        away = home = 0
        for length, seg in SEGMENTS:
            ln = None if length is None else length * k
            g = seg.get("goal")
            if ln is not None and t >= ln:
                if g:
                    away, home = (away + 1, home) if g[0] == "away" else (away, home + 1)
                t -= ln
                continue
            if g and t >= g[1] * k:
                away, home = (away + 1, home) if g[0] == "away" else (away, home + 1)
            s = {"gameState": seg["gameState"]}
            if seg["gameState"] in ("FUT", "PRE"):
                return s
            s.update({"period": seg["period"], "periodType": seg["periodType"], "away": away, "home": home})
            clock = seg.get("clock")
            if clock == "run":
                # Runs at 1 s/s from (length - stoppage) to 0 at the segment end; frozen through the stoppage.
                stop = seg.get("stoppage")
                s0, s1 = (stop[0] * k, stop[1] * k) if stop else (0.0, 0.0)
                paused = max(0.0, min(t, s1) - s0) if stop else 0.0
                left = (ln - (s1 - s0)) - (t - paused)
                s.update(secondsRemaining=max(0, int(math.ceil(left))), running=not (stop and s0 <= t < s1),
                         inIntermission=False)
            elif clock == "int":
                s.update(secondsRemaining=max(0, int(math.ceil(ln - t))), running=False, inIntermission=True)
            else:
                s.update(secondsRemaining=0, running=False, inIntermission=False)
            if seg.get("winner"):
                if seg["winner"] == "home":
                    s["home"] = home + 1
                else:
                    s["away"] = away + 1
                s["lastPeriodType"] = seg["periodType"]
            return s
        return {"gameState": "FINAL"}

    def featured_id(self):
        return self.slate(self.today(self.anchor), self.anchor)[0][0]

    def looping_live(self, at):
        left = 1200 - int((at - self.anchor) % 1200)
        return {"gameState": "LIVE", "period": 2, "periodType": "REG", "secondsRemaining": left, "running": True,
                "inIntermission": False, "away": 1, "home": 1}

    def state(self, g, at):
        s = dict(g[4](at))
        s.update(self.panel.cfg.get("sports_script", {}).get(g[0], {}))
        return s

    # ---- bodies
    def team(self, abbrev, score, base, landing, s):
        tid, place, common, _ = TEAMS[abbrev]
        t = {"id": tid}
        if landing:
            t.update({"commonName": {"default": common}, "abbrev": abbrev, "placeName": {"default": place}})
        else:
            t.update({"name": {"default": common}, "abbrev": abbrev})
        if score is not None:
            t["score"] = score
            t["sog"] = 3 + 2 * score + int(s.get("period") or 0) * 4
        t["logo"] = f"{base}/logos/nhl/svg/{abbrev}_light.svg"
        if landing:
            t["darkLogo"] = f"{base}/logos/nhl/svg/{abbrev}_dark.svg"
        return t

    def game(self, day, g, at, base, landing):
        gid, away, home, start, _ = g
        s = self.state(g, at)
        y, season = season_of(day)
        state = s.get("gameState", "FUT")
        started = state not in ("FUT", "PRE")
        o = {"id": int(gid), "season": season, "gameType": 2, "gameDate": day.isoformat(),
             "venue": {"default": f"{TEAMS[home][1]} Arena"}, "startTimeUTC": iso_z(start),
             "easternUTCOffset": "-05:00", "venueUTCOffset": "-05:00", "venueTimezone": "America/New_York",
             "gameState": state, "gameScheduleState": s.get("scheduleState", "OK"),
             "awayTeam": self.team(away, s.get("away", 0) if started else None, base, landing, s),
             "homeTeam": self.team(home, s.get("home", 0) if started else None, base, landing, s)}
        if not landing:
            o["gameCenterLink"] = f"/gamecenter/{away.lower()}-vs-{home.lower()}/{day:%Y/%m/%d}/{gid}"
        if started:
            secs = int(s.get("secondsRemaining") or 0)
            o["clock"] = {"timeRemaining": mmss(secs), "secondsRemaining": secs, "running": bool(s.get("running")),
                          "inIntermission": bool(s.get("inIntermission"))}
            if not landing:
                o["period"] = s.get("period", 1)
            o["periodDescriptor"] = {"number": s.get("period", 1), "periodType": s.get("periodType", "REG"), "maxRegulationPeriods": 3}
            if state in ("OFF", "FINAL"):
                last = s.get("lastPeriodType") or s.get("periodType", "REG")
                o["gameOutcome"] = {"lastPeriodType": last} | ({"otPeriods": 1} if last == "OT" else {})
        if landing:
            o.update({"shootoutInUse": True, "maxPeriods": 5, "regPeriods": 3, "otInUse": True, "tiesInUse": False})
        return o

    def day_body(self, day, at, base):
        games = [self.game(day, g, at, base, False) for g in self.slate(day, at) if not self.state(g, at).get("gone")]
        return {"prevDate": (day - dt.timedelta(days=1)).isoformat(), "currentDate": day.isoformat(),
                "nextDate": (day + dt.timedelta(days=1)).isoformat(), "gameWeek": [], "oddsPartners": [], "games": games}

    def logo_svg(self, abbrev, dark):
        colour = TEAMS.get(abbrev, (0, "", "", "#%06x" % (zlib.crc32(abbrev.encode()) & 0xFFFFFF)))[3]
        ring = "#ffffff" if dark else "#111111"
        # A neutral badge on the NHL's 960 x 640 viewBox, art inside about 70 % of it (the trim test case), paths only.
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 640">'
                f'<path fill="{ring}" d="M480 96c140 0 240 60 240 60v170c0 120-110 196-240 218-130-22-240-98-240-218V156s100-60 240-60z"/>'
                f'<path fill="{colour}" d="M480 124c124 0 212 52 212 52v150c0 104-96 170-212 190-116-20-212-86-212-190V176s88-52 212-52z"/>'
                f'</svg>').encode()


def send(h, status, body, ctype, headers):
    """fakepanel's Handler.send, but with our own `Date` (the CDN's; absent for the `no_date` fault) instead of
    BaseHTTPRequestHandler's automatic one."""
    if isinstance(body, str):
        body = body.encode()
    h.set_status(status)
    h.log_request(status)
    h.send_response_only(status)
    h.send_header("Server", h.version_string())
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(body)))
    for k, v in headers.items():
        h.send_header(k, v)
    h.end_headers()
    if h.command != "HEAD":
        h.wfile.write(body)
        h._bytes += len(body)


def handle(h, path):
    """Routes one sports request on fakepanel's Handler [h]. Returns True when [path] was a sports path."""
    p = h.panel
    if not (path.startswith("/v1/") or path.startswith("/logos/nhl/")):
        return False
    if not p.cfg.get("sports"):
        h.send(404, b"sports routes are off (--sports)", "text/plain")
        return True
    sp = p.sports
    m = re.match(r"^/logos/nhl/svg/([A-Z]{2,4})_(dark|light)\.svg$", path)
    if m:
        sp.note(path, 200)
        send(h, 200, sp.logo_svg(m[1], m[2] == "dark"), "image/svg+xml", {"Cache-Control": "public, max-age=7200", "Date": http_date(time.time())})
        return True
    f = p.cfg.get("sports_faults") or {}
    if f.get("stall_s"):
        time.sleep(float(f["stall_s"]))
    now = time.time()
    at = Sports.bucket_start(now)
    base = h.base()
    headers = {"Cache-Control": "no-transform, max-age=19, s-maxage=19", "Vary": "Accept-Encoding",
               "ETag": 'W/"%08x--gzip"' % int(at), "Age": str(int(f["age"]) if f.get("age") is not None else int(now - at))}
    if not f.get("no_date"):
        headers["Date"] = http_date(now)

    def fail(status, kind):
        st = int(status)
        hd = dict(headers)
        if f.get("retry_after") is not None:
            hd["Retry-After"] = str(int(f["retry_after"]))
        sp.note(path, st)
        if st == 404 and f.get("html_404"):
            send(h, 404, b"<html><body><h1>404 Not Found</h1></body></html>", "text/html", hd)
        else:
            send(h, st, json.dumps({"message": f"fakepanel sports fault ({kind})"}), "application/json", hd)
        return True

    def answer(obj):
        if f.get("pad_bytes"):
            obj = dict(obj, pad="x" * int(f["pad_bytes"]))
        sp.note(path, 200)
        send(h, 200, json.dumps(obj), "application/json", headers)
        return True

    if f.get("status"):
        return fail(f["status"], "status")
    if path == "/v1/score/now":
        day = sp.today(now)
        sp.note(path, 307)
        send(h, 307, b"", "application/json", dict(headers, Location=f"{base}/v1/score/{day.isoformat()}"))
        return True
    m = re.match(r"^/v1/score/(\d{4}-\d{2}-\d{2})$", path)
    if m:
        if f.get("score_status"):
            return fail(f["score_status"], "score_status")
        try:
            day = dt.date.fromisoformat(m[1])
        except ValueError:
            return fail(400, "bad date")
        return answer(sp.day_body(day, at, base))
    m = re.match(r"^/v1/gamecenter/(\d+)/landing$", path)
    if m:
        if f.get("landing_status"):
            return fail(f["landing_status"], "landing_status")
        day, g = sp.find(m[1], at)
        if g is None or sp.state(g, at).get("gone"):
            return fail(404, "unknown game")
        return answer(sp.game(day, g, at, base, True))
    return fail(404, "unknown sports path")


def admin(h, path, data):
    """`/admin/sports`: GET the slate; POST {"restart": true} re-anchors the featured timeline at now."""
    sp = h.panel.sports
    if h.command in ("POST", "PUT") and data:
        if data.get("restart"):
            sp.reset()
        elif "anchor_unix" in data:
            with sp.lock:
                sp.anchor = float(data["anchor_unix"])
    now = time.time()
    at = Sports.bucket_start(now)
    today = sp.today(sp.anchor)
    games = []
    for d in (-1, 0, 1):
        day = today + dt.timedelta(days=d)
        for g in sp.slate(day, at):
            games.append({"id": g[0], "day": day.isoformat(), "away": g[1], "home": g[2], "start_unix": g[3], "state": sp.state(g, at)})
    return h.json({"enabled": bool(h.panel.cfg.get("sports")), "anchor_unix": sp.anchor, "bucket_unix": at,
                   "today": sp.today(now).isoformat(), "featured": sp.featured_id(), "games": games})
