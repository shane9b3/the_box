"""Catalog, JSON/M3U/XMLTV builders for fakepanel (pure functions, no I/O)."""
import base64
import datetime as dt
import gzip
import random
import time
from xml.sax.saxutils import escape, quoteattr

SLOT = 1800  # EPG slot length, seconds
EPG_BACK_DAYS = 3
EPG_FWD_DAYS = 3
SHOWS = ["Morning News", "Quiz Time", "Documentary", "Match Day", "Late Movie", "Cooking", "Weather", "Talk Show"]

LIVE_CATS = [(1, "News"), (2, "Sports"), (3, "Movies"), (4, "Radio")]
VOD_CATS = [(10, "Action"), (11, "Comedy")]
SERIES_CATS = [(20, "Drama"), (21, "Sitcom")]

# media: std = H.264+AAC 25fps, mp2 = H.264+MP2 audio, 50fps = H.264+AAC 50fps, radio = AAC audio only (no video PID)
_CH = [
    (1, "News One", 1, True, "xc", "std"),
    (2, "Sports Arena", 2, True, "default", "std"),
    (3, "Movie Max (MP2 audio)", 3, True, "shift", "mp2"),
    (4, "Sport 50fps", 2, True, "flussonic", "50fps"),
    (5, "Quirk Channel (VLCOPT UA)", 1, False, "", "std"),
    (6, "Pipe Header Channel", 3, False, "", "std"),
    (7, "Daily Docs", 1, True, "xc", "std"),
    (8, "Kids Zone", 3, False, "", "std"),
    (9, "Football Two", 2, True, "xc", "std"),
    (10, "Music Mix", 3, False, "", "std"),
    (11, "World Report", 1, False, "", "std"),
    (12, "Late Cinema", 3, False, "", "mp2"),
    (13, "Radio Test", 4, False, "", "radio"),
]
CHANNELS = [dict(id=i, name=n, cat=c, archive=a, catchup=cu, media=m, tvg_id=f"ch{i}.test")
            for i, n, c, a, cu, m in _CH]
CH_BY_ID = {c["id"]: c for c in CHANNELS}
ARCHIVE_DAYS = 3

MOVIES = [dict(id=100 + i, name=f"Test Movie {i}", cat=10 if i % 2 else 11, year=2000 + i) for i in range(1, 7)]
MOVIE_BY_ID = {m["id"]: m for m in MOVIES}
SERIES = [dict(id=200 + i, name=f"Test Series {i}", cat=20 if i % 2 else 21) for i in range(1, 4)]
SERIES_BY_ID = {s["id"]: s for s in SERIES}


def episode_id(series_id, season, ep):
    return 3000 + (series_id - 200) * 100 + season * 10 + ep


EPISODE_IDS = {episode_id(s["id"], se, e) for s in SERIES for se in (1, 2) for e in (1, 2, 3)}


def _n(cfg, v):
    """Number as int, or as string in quirks mode."""
    return str(v) if cfg.get("quirks") else v


def host_url(host, port):
    return f"http://{host}:{port}"


def user_info(cfg, active_cons, host, port, tz, now=None):
    now = now or time.time()
    exp = None if cfg.get("exp_days") is None else int(now + cfg["exp_days"] * 86400)
    q = cfg.get("quirks")
    ui = {
        "username": cfg["username"], "password": cfg["password"], "message": "Fake panel",
        "auth": 1, "status": cfg["status"],
        "exp_date": (str(exp) if q else exp) if exp is not None else None,
        "is_trial": "0" if q else 0,
        "active_cons": str(active_cons), "created_at": str(int(now - 90 * 86400)),
        "max_connections": str(cfg["max_connections"]),
        "allowed_output_formats": cfg["allowed_output_formats"],
    }
    local = dt.datetime.fromtimestamp(now, tz)
    si = {
        "url": host, "port": str(port), "https_port": "25463", "server_protocol": "http",
        "rtmp_port": "25462", "timezone": cfg["timezone"],
        "timestamp_now": int(now), "time_now": local.strftime("%Y-%m-%d %H:%M:%S"),
    }
    return {"user_info": ui, "server_info": si}


def categories(cats):
    return [{"category_id": str(i), "category_name": n, "parent_id": 0} for i, n in cats]


def live_streams(cfg, host, port, category_id=None, syn=None):
    if syn:
        return syn.live_streams(cfg, host, port, category_id)
    out = []
    for n, c in enumerate(CHANNELS, 1):
        cid = c["cat"]
        if cfg.get("quirks") and c["id"] == 5:
            cid = None  # category_id null for one stream
        if category_id is not None and str(cid) != str(category_id):
            continue
        out.append({
            "num": _n(cfg, n), "name": c["name"], "stream_type": "live", "stream_id": _n(cfg, c["id"]),
            "stream_icon": f"http://{host}:{port}/logo/{c['id']}.png", "epg_channel_id": c["tvg_id"],
            "added": "1700000000", "is_adult": "0", "category_id": None if cid is None else (str(cid) if cfg.get("quirks") else cid),
            "category_ids": [] if cid is None else [cid], "custom_sid": "", "tv_archive": _n(cfg, 1 if c["archive"] else 0),
            "direct_source": "", "tv_archive_duration": _n(cfg, ARCHIVE_DAYS if c["archive"] else 0),
        })
    return out


def vod_streams(cfg, host, port, category_id=None):
    out = []
    for n, m in enumerate(MOVIES, 1):
        if category_id is not None and str(m["cat"]) != str(category_id):
            continue
        out.append({
            "num": _n(cfg, n), "name": m["name"], "title": m["name"], "year": str(m["year"]), "stream_type": "movie",
            "stream_id": _n(cfg, m["id"]), "stream_icon": f"http://{host}:{port}/logo/vod{m['id']}.png",
            "rating": "7.5" if cfg.get("quirks") else 7.5, "rating_5based": 3.8, "added": "1700000000",
            "category_id": str(m["cat"]) if cfg.get("quirks") else m["cat"], "container_extension": "mp4",
            "custom_sid": "", "direct_source": "",
        })
    return out


def vod_info(cfg, host, port, vod_id):
    m = MOVIE_BY_ID.get(int(vod_id)) if str(vod_id).isdigit() else None
    if not m:
        return []  # real panels return [] / empty for unknown ids
    q = cfg.get("quirks")
    info = {
        "tmdb_id": str(m["id"]), "name": m["name"], "o_name": m["name"],
        "movie_image": f"http://{host}:{port}/logo/vod{m['id']}.png",
        "backdrop_path": f"http://{host}:{port}/logo/bg{m['id']}.jpg" if (q and m["id"] == 103) else [f"http://{host}:{port}/logo/bg{m['id']}.jpg"],
        "plot": "Plot of " + m["name"], "genre": "Test", "releasedate": f"{m['year']}-01-01",
        "duration_secs": 30 if not q else "30", "duration": "00:00:30", "rating": "7.5",
        "video": {"codec_name": "h264", "width": 1280, "height": 720}, "audio": {"codec_name": "aac"}, "bitrate": 2000,
    }
    if q and m["id"] == 102:
        info = []  # PHP empty array instead of object
    return {"info": info, "movie_data": {
        "stream_id": _n(cfg, m["id"]), "name": m["name"], "added": "1700000000", "category_id": str(m["cat"]),
        "container_extension": "mp4", "custom_sid": "", "direct_source": ""}}


def series_list(cfg, host, port, category_id=None):
    q = cfg.get("quirks")
    out = []
    for n, s in enumerate(SERIES, 1):
        if category_id is not None and str(s["cat"]) != str(category_id):
            continue
        d = {"num": _n(cfg, n), "name": s["name"], "series_id": _n(cfg, s["id"]),
             "cover": f"http://{host}:{port}/logo/s{s['id']}.png", "plot": "Series plot", "cast": "A, B",
             "director": "D", "genre": "Drama", "last_modified": "1700000000", "rating": "8",
             "rating_5based": 4, "backdrop_path": f"http://{host}:{port}/logo/sbg{s['id']}.jpg" if (q and n == 2) else [],
             "youtube_trailer": "", "episode_run_time": "1", "category_id": str(s["cat"]) if q else s["cat"]}
        d["releaseDate" if n % 2 else "release_date"] = "2020-01-01"
        out.append(d)
    return out


def series_info(cfg, host, port, series_id):
    s = SERIES_BY_ID.get(int(series_id)) if str(series_id).isdigit() else None
    if not s:
        return []
    q = cfg.get("quirks")
    seasons, episodes = [], {}
    for se in (1, 2):
        seasons.append({"season_number": se, "name": f"Season {se}", "episode_count": 3, "air_date": "2020-01-01"})
        episodes[str(se)] = [{
            "id": str(episode_id(s["id"], se, e)), "episode_num": _n(cfg, e), "title": f"{s['name']} S{se}E{e}",
            "container_extension": "mp4", "season": se, "added": "1700000000", "custom_sid": "", "direct_source": "",
            "info": {"movie_image": "", "plot": "Episode plot", "releasedate": "2020-01-01", "rating": 8,
                     "duration_secs": 30, "duration": "00:00:30", "tmdb_id": "1"}} for e in (1, 2, 3)]
    eps = list(episodes.values()) if cfg.get("series_episodes_array") else episodes
    return {"seasons": [] if q else seasons,
            "info": {"name": s["name"], "cover": "", "plot": "Series plot", "category_id": str(s["cat"]),
                     "backdrop_path": [] if not q else f"http://{host}:{port}/logo/sbg{s['id']}.jpg"},
            "episodes": eps}


# ---------------------------------------------------------------- EPG

def programme(ch, slot_start, tz):
    local = dt.datetime.fromtimestamp(slot_start, tz)
    show = SHOWS[(slot_start // SLOT) % len(SHOWS)]
    return {"start": slot_start, "stop": slot_start + SLOT,
            "title": f"{show} ({local.strftime('%H:%M')})", "desc": f"{ch['name']} - {show}"}


def programmes(ch, t_from, t_to, tz):
    s = int(t_from) // SLOT * SLOT
    while s < t_to:
        yield programme(ch, s, tz)
        s += SLOT


def epg_listings(cfg, ch, tz, now, short=False, limit=4, syn=None):
    if syn and ch.get("synthetic"):
        return syn.epg_listings(cfg, ch, tz, now, short, limit)
    plain = cfg.get("epg_plain")
    enc = (lambda s: s) if plain else (lambda s: base64.b64encode(s.encode()).decode())
    cur = int(now) // SLOT * SLOT
    rng = programmes(ch, cur, cur + 2 * 86400, tz) if short else programmes(
        ch, now - EPG_BACK_DAYS * 86400, now + EPG_FWD_DAYS * 86400, tz)
    out = []
    for p in rng:
        a = dt.datetime.fromtimestamp(p["start"], tz)
        b = dt.datetime.fromtimestamp(p["stop"], tz)
        out.append({
            "id": str(p["start"]), "epg_id": "1", "title": enc(p["title"]), "lang": "en",
            "start": a.strftime("%Y-%m-%d %H:%M:%S"), "end": b.strftime("%Y-%m-%d %H:%M:%S"),
            "description": enc(p["desc"]), "channel_id": ch["tvg_id"],
            "start_timestamp": str(p["start"]), "stop_timestamp": str(p["stop"]),
            "now_playing": 1 if p["start"] <= now < p["stop"] else 0,
            "has_archive": 1 if (ch["archive"] and p["stop"] <= now and p["start"] >= now - ARCHIVE_DAYS * 86400) else 0})
        if short and len(out) >= limit:
            break
    return {"epg_listings": out}


def xmltv(cfg, base, tz_unused, now, offset):
    sign = -1 if offset.startswith("-") else 1
    off_s = sign * (int(offset[1:3]) * 3600 + int(offset[3:5]) * 60)
    zone = dt.timezone(dt.timedelta(seconds=off_s))
    parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fakepanel">\n']
    for c in CHANNELS:
        parts.append(f'<channel id={quoteattr(c["tvg_id"])}><display-name>{escape(c["name"])}</display-name>'
                     f'<icon src={quoteattr(base + "/logo/" + str(c["id"]) + ".png")}/></channel>\n')
    fmt = lambda ts: dt.datetime.fromtimestamp(ts, zone).strftime("%Y%m%d%H%M%S") + " " + offset
    for c in CHANNELS:
        for p in programmes(c, now - EPG_BACK_DAYS * 86400, now + EPG_FWD_DAYS * 86400, zone):
            parts.append(f'<programme start="{fmt(p["start"])}" stop="{fmt(p["stop"])}" channel={quoteattr(c["tvg_id"])}>'
                         f'<title lang="en">{escape(p["title"])}</title><desc lang="en">{escape(p["desc"])}</desc></programme>\n')
    parts.append("</tv>\n")
    return "".join(parts).encode()


# ---------------------------------------------------------------- M3U

def m3u(cfg, base, host, port, ext, plus=True, syn=None):
    if syn:
        return syn.m3u(cfg, base, ext, plus)
    u, p = cfg["username"], cfg["password"]
    L = [f'#EXTM3U x-tvg-url="{base}/xmltv.php?username={u}&password={p}"']
    for c in CHANNELS:
        attrs = f'tvg-id="{c["tvg_id"]}" tvg-name="{c["name"]}" tvg-logo="{base}/logo/{c["id"]}.png" group-title="{dict(LIVE_CATS)[c["cat"]]}"'
        if c["media"] == "radio":
            attrs += ' radio="true"'  # EntryClassifier: Attr.truthy(radio) -> RADIO
        if plus and c["catchup"]:
            cu = c["catchup"]
            attrs += f' catchup="{cu}" catchup-days="{ARCHIVE_DAYS}"'
            if cu == "default":
                attrs += (f' catchup-source="{base}/streaming/timeshift.php?username={u}&password={p}&stream={c["id"]}'
                          '&start={Y}-{m}-{d}:{H}-{M}&duration={duration:60}"')
            elif cu == "flussonic":
                attrs += f' catchup-source="{base}/live/{u}/{p}/{c["id"]}/archive-${{start}}-${{duration}}.m3u8"'
        L.append(f"#EXTINF:-1 {attrs},{c['name']}" if plus else f"#EXTINF:-1,{c['name']}")
        url = f"{base}/live/{u}/{p}/{c['id']}.{ext}"
        if c["id"] == 5:
            L += ["#EXTVLCOPT:http-user-agent=VLC/3.0.20 LibVLC/3.0.20", "#EXTVLCOPT:http-referrer=http://example.com/"]
        elif c["id"] == 6:
            url += "|User-Agent=Mozilla%2F5.0%20(Fake)&Referer=http%3A%2F%2Fexample.com%2F"
        L.append(url)
    return ("\n".join(L) + "\n").encode()


def maybe_gzip(data, on):
    return gzip.compress(data) if on else data


# ---------------------------------------------------------------- synthetic mode (--synthetic N)

SYN_GROUP = 50
SYN_CAT_BASE = 1000  # category ids 1000+g never collide with VOD/series (10, 11, 20, 21)
SYN_THEMES = ["News", "Sports", "Movies", "Kids", "Music", "Docs", "Nature", "Cinema"]
SYN_PREFIX = {3: "UK: ", 5: "[DE] ", 7: "FR | "}  # by id % 10 -> 30 % of channels
SYN_DURS = [m * 60 for m in (5, 10, 30, 60, 120, 360)]
SYN_TITLES = ["Morning News", "Evening News", "News at Ten", "Sports News Roundup", "Quiz Time", "Documentary",
              "Match Day", "Late Movie", "Cooking with Friends", "Weather Report", "Talk Show", "Nature Wild",
              "Kids Cartoon Hour", "Music Mix", "Arts & Culture", "Drama Series"]
SYN_NT = len(SYN_TITLES)
SYN_TITLES += ["Reminder Test Start", "Reminder Test Next"]  # forced programmes on channel 1
SYN_TITLES += ["Minute Programme"]  # player fixtures: channel 2's 1-minute programmes (index SYN_NT + 2)
SYN_TITLES_ESC = [escape(t) for t in SYN_TITLES]
# M4 player fixtures (docs/design/m4-player-ux.md §5.6 items 9-11; config player_fixtures, synthetic mode only).
# id -> overrides of the generated channel. Archive 1 day, plain Xtream `xc` catch-up.
SYN_FIXTURES = {
    2: {"role": "short", "archive": True, "archive_days": 1, "catchup": "xc"},
    3: {"role": "plus2", "archive": True, "archive_days": 1, "catchup": "xc", "correction": 2.0},
    4: {"role": "minus15", "archive": True, "archive_days": 1, "catchup": "xc", "correction": -1.5},
    7: {"role": "multi_ts", "archive": True, "archive_days": 1, "catchup": "xc", "media": "multi"},
    8: {"role": "multi_hls", "media": "multi", "hls": True},
}
SYN_SHORT_BACK = 3 * 3600   # channel 2: 1-minute programmes from server start - 3 h ...
SYN_SHORT_AHEAD = 4 * 3600  # ... to server start + 4 h (normal schedule outside)
SYN_LONG = " - The Extended Special Edition With Additional Commentary and Bonus Material"  # makes a title > 60 chars
SYN_CHUNK = 64 * 1024


class Synthetic:
    """Deterministic large lineup. Everything derives from (cfg, server start time), so the schedule is identical on
    every request during one server run. ids and `num` are 1..n; groups of 50; EPG window anchored to the start hour."""

    def __init__(self, cfg, start_time):
        self.n = int(cfg["synthetic"])
        self.back = int(cfg.get("synthetic_days_back", 3))
        self.ahead = int(cfg.get("synthetic_days_ahead", 7))
        self.ver = int(cfg.get("epg_version") or 0)
        self.suffix = f" v{self.ver + 1}" if self.ver else ""
        hour = int(start_time) // 3600 * 3600
        self.start_unix = int(start_time) // 60 * 60
        self.s0 = hour - self.back * 86400
        self.e = hour + 3600 + self.ahead * 86400
        self.reminders = (self.start_unix + 120, self.start_unix + 300)
        self.groups = -(-self.n // SYN_GROUP)
        self.fx = bool(cfg.get("player_fixtures"))
        self.short = (self.start_unix - SYN_SHORT_BACK, self.start_unix + SYN_SHORT_AHEAD)

    def info(self):
        out = {"n": self.n, "start_unix": self.start_unix, "reminder_starts": list(self.reminders),
               "epg_window": [self.s0, self.e], "epg_version": self.ver,
               "days_back": self.back, "days_ahead": self.ahead, "group_size": SYN_GROUP}
        if self.fx:
            out["player_fixtures"] = {v["role"]: k for k, v in SYN_FIXTURES.items() if k <= self.n}
            out["short_window"] = list(self.short)
        return out

    def categories(self):
        return [(SYN_CAT_BASE + g, f"{SYN_THEMES[g % len(SYN_THEMES)]} {g + 1}") for g in range(self.groups)]

    def channel(self, i):
        if not 1 <= i <= self.n:
            return None
        g = (i - 1) // SYN_GROUP
        archive = i % 5 == 1
        has_epg = i == 1 or i % 20 != 9
        ch = dict(id=i, name=f"{SYN_PREFIX.get(i % 10, '')}{SYN_THEMES[g % len(SYN_THEMES)]} {i}", cat=SYN_CAT_BASE + g,
                  archive=archive, archive_days=(7 if (i == 1 or (i // 5) % 2 == 0) else 3) if archive else 0,
                  catchup="xc" if archive else "", media="std", has_epg=has_epg,
                  tvg_id=f"syn{i}.test" if has_epg else "", synthetic=True)
        if self.fx and i in SYN_FIXTURES:
            ch.update({k: v for k, v in SYN_FIXTURES[i].items() if k != "role"})
        return ch

    def _ids(self, category_id=None):
        if category_id is None:
            return range(1, self.n + 1)
        try:
            g = int(category_id) - SYN_CAT_BASE
        except ValueError:
            return range(0)
        if not 0 <= g < self.groups:
            return range(0)
        return range(g * SYN_GROUP + 1, min(self.n, (g + 1) * SYN_GROUP) + 1)

    def live_streams(self, cfg, host, port, category_id=None):
        out, q = [], cfg.get("quirks")
        for i in self._ids(category_id):
            c = self.channel(i)
            out.append({
                "num": _n(cfg, i), "name": c["name"], "stream_type": "live", "stream_id": _n(cfg, i),
                "stream_icon": f"http://{host}:{port}/logo/{i}.png", "epg_channel_id": c["tvg_id"],
                "added": "1700000000", "is_adult": "0", "category_id": str(c["cat"]) if q else c["cat"],
                "category_ids": [c["cat"]], "custom_sid": "", "tv_archive": _n(cfg, 1 if c["archive"] else 0),
                "direct_source": "", "tv_archive_duration": _n(cfg, c["archive_days"])})
        return out

    def schedule(self, cid):
        """Yield (start, stop, title_index, long_flag) in unix seconds, contiguous from s0 past e. Whole minutes.
        Player fixtures: channel 2 has 1-minute programmes inside `short` (the generated ones are cut at its edges)."""
        if not (self.fx and cid == 2):
            yield from self._schedule(cid)
            return
        sf, st = self.short
        done = False
        for a, b, ti, lng in self._schedule(cid):
            if b <= sf or a >= st:
                if a >= st and not done:
                    yield from self._minutes(sf, st)
                    done = True
                yield a, b, ti, lng
                continue
            if a < sf:
                yield a, sf, ti, lng
            if not done:
                yield from self._minutes(sf, st)
                done = True
            if b > st:
                yield st, b, ti, lng

    @staticmethod
    def _minutes(sf, st):
        for t in range(sf, st, 60):
            yield t, t + 60, SYN_NT + 2, False

    def _schedule(self, cid):
        bits = random.Random(cid * 7919 + 17).getrandbits
        r1, r2 = self.reminders if cid == 1 else (None, None)
        t, e, durs, nt = self.s0, self.e, SYN_DURS, SYN_NT
        while t < e:
            if t == r1:
                yield t, r2, nt, False
                yield r2, r2 + 1800, nt + 1, False
                t, r1 = r2 + 1800, None
                continue
            b = bits(24)
            stop = t + durs[b % 6]
            if r1 is not None and t < r1 < stop:
                stop = r1
            yield t, stop, (b >> 3) % nt, (b >> 12) % 10 == 0
            t = stop

    def title(self, ti, lng):
        return SYN_TITLES[ti] + (SYN_LONG if lng else "") + self.suffix

    def epg_listings(self, cfg, ch, tz, now, short, limit):
        if not ch["has_epg"]:
            return {"epg_listings": []}
        plain = cfg.get("epg_plain")
        enc = (lambda s: s) if plain else (lambda s: base64.b64encode(s.encode()).decode())
        out = []
        for a, b, ti, lng in self.schedule(ch["id"]):
            if short and b <= now:
                continue
            t = self.title(ti, lng)
            out.append({
                "id": str(a), "epg_id": "1", "title": enc(t), "lang": "en",
                "start": dt.datetime.fromtimestamp(a, tz).strftime("%Y-%m-%d %H:%M:%S"),
                "end": dt.datetime.fromtimestamp(b, tz).strftime("%Y-%m-%d %H:%M:%S"),
                "description": enc(f"{ch['name']} - {t}"), "channel_id": ch["tvg_id"],
                "start_timestamp": str(a), "stop_timestamp": str(b),
                "now_playing": 1 if a <= now < b else 0,
                "has_archive": 1 if (ch["archive"] and b <= now and a >= now - ch["archive_days"] * 86400) else 0})
            if short and len(out) >= limit:
                break
        return {"epg_listings": out}

    def xmltv_chunks(self, base, offset):
        """Generator of ~64 KB bytes chunks; the document is never held in memory."""
        sign = -1 if offset.startswith("-") else 1
        zone = dt.timezone(dt.timedelta(seconds=sign * (int(offset[1:3]) * 3600 + int(offset[3:5]) * 60)))
        cache = {}

        def fmt(ts):
            r = cache.get(ts)
            if r is None:
                r = cache[ts] = dt.datetime.fromtimestamp(ts, zone).strftime("%Y%m%d%H%M%S") + " " + offset
            return r

        parts, size = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fakepanel">\n'], 0
        suffix = self.suffix
        titles = [t + suffix for t in SYN_TITLES_ESC]
        longs = [t + escape(SYN_LONG) + suffix for t in SYN_TITLES_ESC]
        for pass_ in (0, 1):  # 0: <channel> elements, 1: <programme> elements
            for i in range(1, self.n + 1):
                c = self.channel(i)
                if not c["has_epg"]:
                    continue
                tvg = c["tvg_id"]
                if pass_ == 0:
                    s = (f'<channel id="{tvg}"><display-name>{escape(c["name"])}</display-name>'
                         f'<icon src="{base}/logo/{i}.png"/></channel>\n')
                    parts.append(s)
                    size += len(s)
                else:
                    tail = f'" channel="{tvg}"><title lang="en">'
                    for a, b, ti, lng in self.schedule(i):
                        s = f'<programme start="{fmt(a)}" stop="{fmt(b)}{tail}{longs[ti] if lng else titles[ti]}</title></programme>\n'
                        parts.append(s)
                        size += len(s)
                        if size >= SYN_CHUNK:
                            yield "".join(parts).encode()
                            parts, size = [], 0
                if size >= SYN_CHUNK:
                    yield "".join(parts).encode()
                    parts, size = [], 0
        parts.append("</tv>\n")
        yield "".join(parts).encode()

    def m3u(self, cfg, base, ext, plus=True):
        u, p = cfg["username"], cfg["password"]
        names = dict(self.categories())
        L = [f'#EXTM3U x-tvg-url="{base}/xmltv.php?username={u}&password={p}"']
        for i in range(1, self.n + 1):
            c = self.channel(i)
            if plus:
                attrs = f'tvg-id="{c["tvg_id"]}" tvg-name="{c["name"]}" tvg-logo="{base}/logo/{i}.png" group-title="{names[c["cat"]]}"'
                if c["archive"]:
                    attrs += f' catchup="xc" catchup-days="{c["archive_days"]}"'
                    if c.get("correction"):
                        attrs += f' catchup-correction="{c["correction"]:+g}"'
                L.append(f"#EXTINF:-1 {attrs},{c['name']}")
            else:
                L.append(f"#EXTINF:-1,{c['name']}")
            L.append(f"{base}/live/{u}/{p}/{i}.{'m3u8' if c.get('hls') else ext}")
        return ("\n".join(L) + "\n").encode()
