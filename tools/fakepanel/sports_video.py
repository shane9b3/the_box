"""The fake NHL video service (docs/design/m6-sports-hub.md section 10.3; `--sports --sports-video`). WP-2 owns this file.

Everything here is fake: the key below is an obviously fake test key, never the real one, and no request ever leaves the
machine. A debug build pointed at the panel (`files/debug/sports_base`) sends both video hosts here
(`SportsEndpoints.videoFromOverride`):

    GET /bc/player/index.min.js                              a small script in the player script's shape: an empty
                                                             `policyKey:""` default, then the fake key (the app's scan
                                                             must continue past the empty one)
    GET /bc/playback/v1/accounts/{acct}/videos/{id}          the lookup; requires `Accept: application/json;pk=<key>`
    GET /bc/media/{id}/master.m3u8 | variant.m3u8 | subs.m3u8 | seg_NNN.ts | main.mp4 | text.vtt | poster.jpg
                                                             media: the panel's own ffmpeg `testsrc2` clip (the "std"
                                                             variant under tools/fakepanel/media/, git-ignored) as a
                                                             2 s-segment VOD HLS, its MP4 (`vod.mp4`, Range supported)
                                                             and a WebVTT track; every media URL carries a fake token

Modes (`sports_video_mode`, also `POST /admin/sports_video {"mode": ...}`):
- ok:      everything answers.
- refuse:  every lookup is 403 ACCESS_DENIED (not geo): the app re-reads the script once, then holds 10 min.
- rotate:  the script serves a second fake key and only that one is accepted: the app's cached key is refused once,
           the re-read finds the new one, the retried lookup works.
- geo:     every lookup is 403 with `error_subcode` CLIENT_GEO.
- missing: the script carries no valid key (the empty default and a placeholder only).
- slow:    lookups answer after `sports_video_slow_s` (default 12 s, beyond the app's 10 s timeout).
- expire:  the manifest is served, then segments, the MP4 and the captions 403 `sports_video_expire_s` seconds after the
           video's first manifest (or MP4) request: the app's CLIP_EXPIRED path.
A video id whose last three digits are 404 is a 404 VIDEO_NOT_FOUND in every mode.

ESPN clips (NFL hub, docs/design/m6-nfl-hub.md section 8.4; with `--sports --sports-nfl`; served through fakepanel.py's
`nfl_hub` route to `handle_espn`). ESPN's clip media needs no key or token, so nothing here is signed:

    GET /espn-core/v1/video/clips/{id}                      the per-clip record, ESPN's `{resultsCount, ..., videos: [ {...} ]}`
                                                            wrapper (`EspnEndpoints.fromOverride`'s clipBase)
    GET /espn-media/{id}/master.m3u8                        an HLS master of ESPN's shape: four variants (360p, 480p and 720p
                                                            at 29.97 fps from the panel's 25 fps clip, 720p at 59.94 fps
                                                            from its 50 fps clip; the declared rates are ESPN's, the media
                                                            the panel's own) and a WebVTT SUBTITLES rendition, DEFAULT=YES
    GET /espn-media/{id}/v{360,480,720,720p60}.m3u8 | {variant}/seg_NNN.ts | subs.m3u8 | text.vtt | thumb.png

Which games list clips (the summary's `videos[]`, the board's `highlights[]`, these records):
- 401899003 (NYJ @ NE, final): by `sports_nfl_clips` (also `POST /admin/config`): `espn` a Final Game Highlight plus three
  OnePlay clips; `single` the highlight only; `none`; `expired` the highlight with `expirationDate` 1 h ago; `expiring` 2 min
  from now (inside the app's 5 min margin: never offered); `gone` all four listed, but the media answer 403 (the player's
  CLIP_EXPIRED, so the app makes its one fresh record lookup) and the record 404 (the "ESPN removed this video" toast);
  `slow_records` all four, records answering after 8 s.
- 401898001 (DEN @ SEA, week 8, final): always one highlight that expired 1 h ago.
- every other game (the featured live game included, also after its end): none.
Valid clips are posted 3 h before the NFL slate's anchor and expire 72 h after it.
"""

import datetime as dt
import json
import re
import struct
import threading
import time
import urllib.parse
import zlib

PREFIX = "/bc/"
MODES = ("ok", "refuse", "rotate", "geo", "missing", "slow", "expire")
ACCOUNT = "6415718365001"

# Obviously fake keys (the app's scan wants 20-400 characters of [A-Za-z0-9_\-.]; never a real key).
FAKE_KEY = "FAKE-KEY-FOR-TESTS-ONLY-0001"
ROTATED_KEY = "FAKE-KEY-FOR-TESTS-ONLY-0002"

SEG_S = 2.0

# The recorded clips' titles (core/src/testFixtures/resources/sports/nhl/hub/), so the fake page reads like the real one.
NAMES = {
    "6408795671112": ("Utah Mammoth at Winnipeg Jets | Recap", "UTA@WPG: Recap"),
    "6408795191112": ("Utah Mammoth at Winnipeg Jets | Condensed Game", "UTA@WPG: Condensed game"),
    "6408791393112": ("Vilardi strikes first",
                      "Kyle Connor sends the feed to Gabriel Vilardi who lifts the backhand top-shelf, beating "
                      "Sebastian Cossa to open the scoring"),
}

DEFAULTS = {
    "sports_video": False,          # --sports-video: serve /bc/ (needs sports; 404 otherwise)
    "sports_video_mode": "ok",      # one of MODES
    "sports_video_expire_s": 20,    # "expire": seconds until segments 403
    "sports_video_slow_s": 12,      # "slow": seconds before a lookup answers
}

LOOKUP = re.compile(r"^/bc/playback/v1/accounts/([^/]+)/videos/([^/]+)$")
MEDIA = re.compile(r"^/bc/media/(\d+)/(master\.m3u8|variant\.m3u8|subs\.m3u8|seg_(\d{3})\.ts|main\.mp4|text\.vtt|poster\.jpg)$")


def check_config(patch):
    """Raises ValueError for a bad video key in a config patch."""
    if "sports_video" in patch and not isinstance(patch["sports_video"], bool):
        raise ValueError("sports_video must be true or false")
    if "sports_video_mode" in patch and patch["sports_video_mode"] not in MODES:
        raise ValueError("sports_video_mode must be one of " + ", ".join(MODES))
    for k, lo, hi in (("sports_video_expire_s", 1, 3600), ("sports_video_slow_s", 0.1, 120)):
        if k in patch:
            v = patch[k]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not lo <= v <= hi:
                raise ValueError(f"{k} must be a number {lo}..{hi}")


def accepted_key(mode):
    return ROTATED_KEY if mode == "rotate" else FAKE_KEY


def script_text(mode):
    """The fake player script: an empty default first, then the key (none in "missing")."""
    head = "/*! fake video player for the fake panel; not a real script */\n" \
           "!function(e){var d={accountId:\"%s\",playerId:\"default\",policyKey:\"\"};e.bc=d;" % ACCOUNT
    if mode == "missing":
        body = "e.cfg={\"policyKey\":\"{{policyKey}}\"};"
    else:
        body = "e.videojs=e.videojs||{};e.videojs.options={embed:\"default\",\"policyKey\" : \"%s\",autoplay:!1};" % accepted_key(mode)
    return head + body + "}(window);\n"


def vtt_time(sec):
    ms = int(round(sec * 1000))
    return "%02d:%02d:%02d.%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)


def vtt_text(segments):
    cues = ["WEBVTT", ""]
    for i in range(max(1, segments)):
        cues += ["%s --> %s" % (vtt_time(i * SEG_S), vtt_time(i * SEG_S + SEG_S - 0.2)), "Fake caption %d" % (i + 1), ""]
    return "\n".join(cues)


class SportsVideo:
    """Per-run state: counts and the first manifest time of each video (for "expire")."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        with self.lock:
            self.counts = {"script": 0, "lookup": 0, "media": 0, "refused": 0, "expired": 0}
            self.manifest_at = {}
            self.tokens = {}

    def stats(self):
        with self.lock:
            return dict(self.counts)

    def bump(self, k):
        with self.lock:
            self.counts[k] = self.counts.get(k, 0) + 1

    def token(self, vid):
        """The fake signed token of video [vid] (one per lookup; never a real token)."""
        with self.lock:
            n = self.tokens.get(vid, 0) + 1
            self.tokens[vid] = n
        return "fake-%s-%d" % (vid, n)

    def mark_manifest(self, vid):
        with self.lock:
            self.manifest_at.setdefault(vid, time.time())

    def expired(self, vid, after_s):
        with self.lock:
            t = self.manifest_at.get(vid)
        return t is not None and time.time() - t > after_s

    # ---- media from the panel's own clips (built by fakepanel.build_media; ffmpeg testsrc2, or the fallback TS)
    def segments(self):
        clip = self.panel.clips.get("std")
        return [s[0] for s in clip.segments] if clip is not None else []

    def mp4(self):
        return self.panel.vod_data, self.panel.vod_ctype


def video_json(h, vid):
    base = h.base() + "/bc/media/" + vid + "/"
    tok = "?token=" + h.panel.sports_video.token(vid)
    name, long = NAMES.get(vid, ("Fake clip %s" % vid, "A fake highlight for the fake panel (%s)." % vid))
    segs = len(h.panel.sports_video.segments())
    duration_ms = int(max(1, segs) * SEG_S * 1000)
    return {
        "id": vid,
        "account_id": ACCOUNT,
        "name": name,
        "description": name,
        "long_description": long,
        "duration": duration_ms,
        "poster": base + "poster.jpg" + tok,
        "thumbnail": base + "poster.jpg" + tok,
        "sources": [
            {"src": base.replace("/bc/media/", "/bc/media-dash/") + "manifest.mpd" + tok, "type": "application/dash+xml"},
            {"src": base + "master.m3u8" + tok, "type": "application/x-mpegURL", "ext_x_version": "4"},
            {"src": base + "main.mp4" + tok, "container": "MP4", "codec": "H264", "height": 720, "width": 1280,
             "duration": duration_ms},
        ],
        "text_tracks": [
            {"id": "fake-captions", "src": base + "text.vtt" + tok, "srclang": "en", "label": "en", "kind": "captions",
             "mime_type": "text/webvtt", "sources": [{"src": base + "text.vtt" + tok}], "default": False},
            {"id": None, "src": base + "thumbnails.webvtt" + tok, "srclang": None, "label": "thumbnails",
             "kind": "metadata", "mime_type": "text/webvtt", "sources": None, "default": False},
        ],
        "manifest_url_ttl": 3600,
    }


def error(h, status, code, subcode=None, message=""):
    e = {"error_code": code, "message": message or code}
    if subcode:
        e["error_subcode"] = subcode
    h.send(status, json.dumps([e]), "application/json")


def handle(h, path):
    """Routes one video request on fakepanel's Handler [h]. Returns True when [path] was a video path."""
    if not path.startswith(PREFIX):
        return False
    p = h.panel
    cfg = p.cfg
    if not (cfg.get("sports") and cfg.get("sports_video")):
        h.send(404, b"video routes are off (--sports --sports-video)", "text/plain")
        return True
    v = p.sports_video
    mode = cfg.get("sports_video_mode", "ok")
    if path == "/bc/player/index.min.js":
        v.bump("script")
        h.send(200, script_text(mode), "application/javascript")
        return True
    m = LOOKUP.match(path)
    if m:
        v.bump("lookup")
        lookup(h, m.group(1), m.group(2), mode)
        return True
    m = MEDIA.match(path)
    if m:
        v.bump("media")
        media(h, m.group(1), m.group(2), m.group(3), mode)
        return True
    h.send(404, b"no such video path", "text/plain")
    return True


def lookup(h, acct, vid, mode):
    p, v = h.panel, h.panel.sports_video
    if acct != ACCOUNT or not vid.isdigit():
        return error(h, 404, "RESOURCE_NOT_FOUND")
    if mode == "slow":
        # Answer late (beyond the app's 10 s call timeout); give up early when the panel stops.
        if p.stopping.wait(float(p.cfg.get("sports_video_slow_s", 12))):
            return None
    accept = h.headers.get("Accept", "")
    pk = accept.split("pk=", 1)[1].strip() if "pk=" in accept else ""
    if mode == "geo":
        return error(h, 403, "ACCESS_DENIED", "CLIENT_GEO", "Access denied by geographic restrictions")
    if mode == "refuse":
        v.bump("refused")
        return error(h, 403, "ACCESS_DENIED", "DOMAIN_RESTRICTED", "Access denied")
    if pk != accepted_key(mode):
        v.bump("refused")
        return error(h, 401, "INVALID_POLICY_KEY", message="Request policy key is missing or invalid")
    if vid.endswith("404"):
        return error(h, 404, "VIDEO_NOT_FOUND", message="The designated resource was not found.")
    return h.json(video_json(h, vid))


def media(h, vid, name, seg, mode):
    p, v = h.panel, h.panel.sports_video
    token = h.q.get("token", "")
    if not token.startswith("fake-"):
        return h.send(403, b"missing signature", "text/plain")
    first = name in ("master.m3u8", "main.mp4")
    if first:
        v.mark_manifest(vid)
    if mode == "expire" and name not in ("master.m3u8", "variant.m3u8") and v.expired(vid, p.cfg.get("sports_video_expire_s", 20)):
        v.bump("expired")
        return h.send(403, b"signature expired", "text/plain")
    q = "?token=" + urllib.parse.quote(token)
    segs = v.segments()
    if name == "master.m3u8":
        # The real master's shape (lead recording 2026-11-24): its own English subtitles rendition, DEFAULT=NO, and
        # CLOSED-CAPTIONS=NONE on the variant.
        body = "#EXTM3U\n#EXT-X-VERSION:4\n" \
               "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"en\",DEFAULT=NO,AUTOSELECT=NO,LANGUAGE=\"en\"," \
               "URI=\"subs.m3u8" + q + "\"\n" \
               "#EXT-X-STREAM-INF:BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2200000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"," \
               "SUBTITLES=\"subs\",CLOSED-CAPTIONS=NONE\n" \
               "variant.m3u8" + q + "\n"
        return h.send(200, body, "application/vnd.apple.mpegurl")
    if name == "variant.m3u8":
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % int(SEG_S + 0.5), "#EXT-X-PLAYLIST-TYPE:VOD",
                 "#EXT-X-MEDIA-SEQUENCE:0"]
        for i in range(len(segs)):
            lines += ["#EXTINF:%.3f," % SEG_S, "seg_%03d.ts%s" % (i, q)]
        lines.append("#EXT-X-ENDLIST")
        return h.send(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")
    if name == "subs.m3u8":
        total = SEG_S * len(segs)
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % int(total + 0.5), "#EXT-X-PLAYLIST-TYPE:VOD",
                 "#EXT-X-MEDIA-SEQUENCE:0", "#EXTINF:%.3f," % total, "text.vtt" + q, "#EXT-X-ENDLIST"]
        return h.send(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")
    if name.startswith("seg_"):
        i = int(seg)
        if i >= len(segs):
            return h.send(404, b"no such segment", "text/plain")
        return h.send(200, segs[i], "video/mp2t")
    if name == "text.vtt":
        return h.send(200, vtt_text(len(segs)), "text/vtt")
    if name == "poster.jpg":
        return h.send(404, b"no poster in the fake panel", "text/plain")
    data, ctype = v.mp4()
    return send_range(h, data, ctype)


def send_range(h, data, ctype):
    """The MP4 with `Range: bytes=a-b` support (the app seeks the MP4 fallback by range)."""
    rng = h.headers.get("Range")
    m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip()) if rng else None
    if not m or (m.group(1) == "" and m.group(2) == ""):
        return h.send(200, data, ctype, {"Accept-Ranges": "bytes"})
    n = len(data)
    if m.group(1) == "":
        start, end = max(0, n - int(m.group(2))), n - 1
    else:
        start = int(m.group(1))
        end = min(n - 1, int(m.group(2))) if m.group(2) else n - 1
    if start >= n or start > end:
        return h.send(416, b"", ctype, {"Content-Range": "bytes */%d" % n})
    return h.send(206, data[start:end + 1], ctype, {"Content-Range": "bytes %d-%d/%d" % (start, end, n), "Accept-Ranges": "bytes"})


def admin(h, path, data):
    """`/admin/sports_video`: GET the mode and counts; POST {"mode": "..."} sets the mode (and clears the expiry clock)."""
    p = h.panel
    if h.command in ("POST", "PUT") and data and "mode" in data:
        try:
            check_config({"sports_video_mode": data["mode"]})
        except ValueError as e:
            return h.json({"error": str(e)}, 400)
        with p.lock:
            p.cfg["sports_video_mode"] = data["mode"]
        with p.sports_video.lock:
            p.sports_video.manifest_at = {}
    return h.json({"enabled": bool(p.cfg.get("sports") and p.cfg.get("sports_video")),
                   "mode": p.cfg.get("sports_video_mode"), "counts": p.sports_video.stats()})


# ------------------------------------------------------------------ ESPN clips (NFL hub section 8.4)

ESPN_CORE_PREFIX = "/espn-core/v1/video/clips/"
ESPN_MEDIA_PREFIX = "/espn-media/"
# The game whose clips follow `sports_nfl_clips`, and the week-8 final with an expired highlight.
ESPN_CLIP_GAME = "401899003"
ESPN_EXPIRED_GAME = "401898001"
ESPN_GONE_MODE = "gone"
ESPN_SLOW_RECORD_S = 8.0
ESPN_DEVICES = ["settop", "tablet", "handset", "ipad", "desktop"]
# (variant, width, height, frame rate, bandwidth, panel clip)
ESPN_VARIANTS = (("v360", 640, 360, "29.970", 900000, "std"), ("v480", 854, 480, "29.970", 1600000, "std"),
                 ("v720", 1280, 720, "29.970", 3200000, "std"), ("v720p60", 1280, 720, "59.940", 5400000, "50fps"))
ESPN_CORE_RE = re.compile(r"^/espn-core/v1/video/clips/(\d+)$")
ESPN_MEDIA_RE = re.compile(r"^/espn-media/(\d+)/(master\.m3u8|subs\.m3u8|text\.vtt|thumb\.png|(v360|v480|v720|v720p60)\.m3u8|"
                           r"(v360|v480|v720|v720p60)/seg_(\d{3})\.ts)$")
ONE_PLAYS = ("Tee Higgins hauls in a 31-yard touchdown", "A 47-yard field goal splits the uprights",
             "Pick-six seals it in the fourth quarter")


def espn_clip_id(eid, i):
    """A numeric clip id for game [eid], clip [i] (0 = the highlight): 50 000 000 + last four digits x 100 + i."""
    return 50_000_000 + (int(eid) % 10000) * 100 + i


def iso_z(unix):
    return dt.datetime.fromtimestamp(unix, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def espn_clip_specs(panel, eid, now=None):
    """[(clip id, coverage type, headline, duration s, published unix, expires unix)] the fake lists for game [eid]."""
    now = time.time() if now is None else now
    mode = panel.cfg.get("sports_nfl_clips", "espn")
    anchor = panel.sports_nfl.anchor
    posted, life = anchor - 3 * 3600, anchor + 72 * 3600
    if eid == ESPN_EXPIRED_GAME:
        return [(espn_clip_id(eid, 0), "Final Game Highlight", "Broncos at Seahawks: game highlights", 68, now - 49 * 3600, now - 3600)]
    if eid != ESPN_CLIP_GAME or mode == "none":
        return []
    hl = "Jets at Patriots: game highlights"
    if mode == "expired":
        return [(espn_clip_id(eid, 0), "Final Game Highlight", hl, 74, now - 49 * 3600, now - 3600)]
    if mode == "expiring":
        return [(espn_clip_id(eid, 0), "Final Game Highlight", hl, 74, now - 47 * 3600, now + 120)]
    out = [(espn_clip_id(eid, 0), "Final Game Highlight", hl, 74, posted, life)]
    if mode != "single":
        out += [(espn_clip_id(eid, i + 1), "OnePlay", t, 23 + 4 * i, posted - 3600 + 900 * i, life) for i, t in enumerate(ONE_PLAYS)]
    return out


def espn_video(base, eid, spec):
    """One ESPN video object (the summary's `videos[]`, the board's `highlights[]`, the record's `videos[]`)."""
    cid, coverage, headline, dur, posted, expires = spec
    media = "%s%s%d/" % (base, ESPN_MEDIA_PREFIX, cid)
    return {
        "id": cid, "headline": headline, "description": headline, "duration": dur,
        "originalPublishDate": iso_z(posted), "lastModified": iso_z(posted + 60),
        "timeRestrictions": {"embargoDate": iso_z(posted - 1), "expirationDate": iso_z(expires)},
        "deviceRestrictions": {"type": "whitelist", "devices": list(ESPN_DEVICES)},
        "gameId": int(eid), "thumbnail": media + "thumb.png",
        "links": {"source": {"href": media + "source.mp4", "HLS": {"href": media + "master.m3u8", "cmaf": {"href": media + "cmaf.m3u8"}}},
                  "mobile": {"href": "https://login.invalid/%d" % cid}},
        "tracking": {"coverageType": coverage, "leagueName": "NFL", "sportName": "football"},
    }


def espn_videos(h, eid):
    """The summary's `videos[]` of game [eid] (empty for most games)."""
    return [espn_video(h.base(), eid, sp) for sp in espn_clip_specs(h.panel, eid)]


def espn_highlights(panel, eid, base):
    """The board's `highlights[]` of game [eid]: the Final Game Highlight only, as ESPN's board."""
    return [espn_video(base, eid, sp) for sp in espn_clip_specs(panel, eid) if sp[1] == "Final Game Highlight"]


def espn_spec_of(panel, cid):
    for eid in (ESPN_CLIP_GAME, ESPN_EXPIRED_GAME):
        for sp in espn_clip_specs(panel, eid):
            if sp[0] == cid:
                return eid, sp
    return None, None


def solid_png(w, h, rgb):
    """A valid w x h solid-colour PNG (a stand-in thumbnail)."""
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def handle_espn(h, path):
    """(NFL hub) The ESPN clip records and HLS media; fakepanel.py's `nfl_hub` has already checked the NFL switches."""
    p = h.panel
    v = p.sports_video
    mode = p.cfg.get("sports_nfl_clips", "espn")
    m = ESPN_CORE_RE.match(path)
    if m:
        v.bump("espn_record")
        eid, sp = espn_spec_of(p, int(m.group(1)))
        if mode == "slow_records" and p.stopping.wait(ESPN_SLOW_RECORD_S):
            return None
        if sp is None or (mode == ESPN_GONE_MODE and eid == ESPN_CLIP_GAME):
            return h.send(404, json.dumps({"resultsCount": 0, "videos": [], "status": "not found"}), "application/json")
        body = {"resultsCount": 1, "resultsLimit": 10, "resultsOffset": 0, "videos": [espn_video(h.base(), eid, sp)],
                "timestamp": iso_z(time.time()), "status": "success"}
        return h.send(200, json.dumps(body), "application/json", {"Cache-Control": "max-age=60"})
    m = ESPN_MEDIA_RE.match(path)
    if not m:
        return h.send(404, b"no such ESPN path", "text/plain")
    v.bump("espn_media")
    eid, sp = espn_spec_of(p, int(m.group(1)))
    if sp is None:
        return h.send(404, b"<html><body>404</body></html>", "text/html")
    if mode == ESPN_GONE_MODE and eid == ESPN_CLIP_GAME:
        # (NFL hub code review 2026-11-26, data #10) A dead CDN link answers 403, which the player classifies as
        # CLIP_EXPIRED (404 is not), so the app makes its one fresh record lookup, which then 404s.
        return h.send(403, b"<html><body>403 Forbidden</body></html>", "text/html")
    name = m.group(2)
    segs_of = {k: [s[0] for s in p.clips[k].segments] if p.clips.get(k) is not None else [] for k in ("std", "50fps")}
    if name == "master.m3u8":
        lines = ["#EXTM3U", "#EXT-X-VERSION:4", "#EXT-X-INDEPENDENT-SEGMENTS",
                 '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,'
                 'LANGUAGE="en",URI="subs.m3u8"']
        for var, w, ht, fps, bw, _clip in ESPN_VARIANTS:
            lines += ['#EXT-X-STREAM-INF:BANDWIDTH=%d,AVERAGE-BANDWIDTH=%d,RESOLUTION=%dx%d,FRAME-RATE=%s,'
                      'CODECS="avc1.64001f,mp4a.40.2",SUBTITLES="subs"' % (bw, bw * 9 // 10, w, ht, fps), var + ".m3u8"]
        return h.send(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")
    if m.group(3):
        clip = dict((x[0], x[5]) for x in ESPN_VARIANTS)[m.group(3)]
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % int(SEG_S + 0.5), "#EXT-X-PLAYLIST-TYPE:VOD",
                 "#EXT-X-MEDIA-SEQUENCE:0"]
        for i in range(len(segs_of[clip])):
            lines += ["#EXTINF:%.3f," % SEG_S, "%s/seg_%03d.ts" % (m.group(3), i)]
        lines.append("#EXT-X-ENDLIST")
        return h.send(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")
    if m.group(4):
        segs = segs_of[dict((x[0], x[5]) for x in ESPN_VARIANTS)[m.group(4)]]
        i = int(m.group(5))
        if i >= len(segs):
            return h.send(404, b"no such segment", "text/plain")
        return h.send(200, segs[i], "video/mp2t")
    n = len(segs_of["std"])
    if name == "subs.m3u8":
        total = SEG_S * max(1, n)
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % int(total + 0.5), "#EXT-X-PLAYLIST-TYPE:VOD",
                 "#EXT-X-MEDIA-SEQUENCE:0", "#EXTINF:%.3f," % total, "text.vtt", "#EXT-X-ENDLIST"]
        return h.send(200, "\n".join(lines) + "\n", "application/vnd.apple.mpegurl")
    if name == "text.vtt":
        return h.send(200, vtt_text(n), "text/vtt")
    return h.send(200, solid_png(64, 36, (0x1f, 0x3a, 0x5f)), "image/png", {"Cache-Control": "max-age=3600"})
