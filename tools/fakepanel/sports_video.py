"""The fake NHL video service (docs/design/m6-sports-hub.md section 10.3; `--sports --sports-video`). WP-2 owns this file.

Everything here is fake: the key below is an obviously fake test key, never the real one, and no request ever leaves the
machine. A debug build pointed at the panel (`files/debug/sports_base`) sends both video hosts here
(`SportsEndpoints.videoFromOverride`):

    GET /bc/player/index.min.js                              a small script in the player script's shape: an empty
                                                             `policyKey:""` default, then the fake key (the app's scan
                                                             must continue past the empty one)
    GET /bc/playback/v1/accounts/{acct}/videos/{id}          the lookup; requires `Accept: application/json;pk=<key>`
    GET /bc/media/{id}/master.m3u8 | variant.m3u8 | seg_NNN.ts | main.mp4 | text.vtt | poster.jpg
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
"""

import json
import re
import threading
import time
import urllib.parse

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
MEDIA = re.compile(r"^/bc/media/(\d+)/(master\.m3u8|variant\.m3u8|seg_(\d{3})\.ts|main\.mp4|text\.vtt|poster\.jpg)$")


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
        body = "#EXTM3U\n#EXT-X-VERSION:4\n" \
               "#EXT-X-STREAM-INF:BANDWIDTH=2400000,AVERAGE-BANDWIDTH=2200000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"\n" \
               "variant.m3u8" + q + "\n"
        return h.send(200, body, "application/vnd.apple.mpegurl")
    if name == "variant.m3u8":
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % int(SEG_S + 0.5), "#EXT-X-PLAYLIST-TYPE:VOD",
                 "#EXT-X-MEDIA-SEQUENCE:0"]
        for i in range(len(segs)):
            lines += ["#EXTINF:%.3f," % SEG_S, "seg_%03d.ts%s" % (i, q)]
        lines.append("#EXT-X-ENDLIST")
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
