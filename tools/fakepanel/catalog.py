"""Catalog, JSON/M3U/XMLTV builders for fakepanel (pure functions, no I/O)."""
import base64
import datetime as dt
import gzip
import time
from xml.sax.saxutils import escape, quoteattr

SLOT = 1800  # EPG slot length, seconds
EPG_BACK_DAYS = 3
EPG_FWD_DAYS = 3
SHOWS = ["Morning News", "Quiz Time", "Documentary", "Match Day", "Late Movie", "Cooking", "Weather", "Talk Show"]

LIVE_CATS = [(1, "News"), (2, "Sports"), (3, "Movies")]
VOD_CATS = [(10, "Action"), (11, "Comedy")]
SERIES_CATS = [(20, "Drama"), (21, "Sitcom")]

# media: std = H.264+AAC 25fps, mp2 = H.264+MP2 audio, 50fps = H.264+AAC 50fps
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


def live_streams(cfg, host, port, category_id=None):
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


def epg_listings(cfg, ch, tz, now, short=False, limit=4):
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

def m3u(cfg, base, host, port, ext, plus=True):
    u, p = cfg["username"], cfg["password"]
    L = [f'#EXTM3U x-tvg-url="{base}/xmltv.php?username={u}&password={p}"']
    for c in CHANNELS:
        attrs = f'tvg-id="{c["tvg_id"]}" tvg-name="{c["name"]}" tvg-logo="{base}/logo/{c["id"]}.png" group-title="{dict(LIVE_CATS)[c["cat"]]}"'
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
