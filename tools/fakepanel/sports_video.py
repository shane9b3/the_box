"""The fake NHL video service (docs/design/m6-sports-hub.md section 10.3; `--sports --sports-video`). WP-2 owns this file.

Commit 0 stub: the config keys, the mode list, the admin route and the routing hook exist (fakepanel.py calls them), so
WP-2 never edits fakepanel.py. While `sports_video` is on, every `/bc/` path answers 501 until WP-2 builds:

    GET /bc/player/index.min.js                              a small script: an empty key default, then the fake test
                                                             key (never a real key), in the player script's shape
    GET /bc/playback/v1/accounts/{acct}/videos/{id}          the lookup; requires the fake key in the `Accept` header
    media from `ffmpeg -f lavfi testsrc` under tools/fakepanel/media/ (git-ignored) with a WebVTT track

Switches (config keys, also through `POST /admin/config`):
- `sports_video` (`--sports-video`, needs `--sports`).
- `sports_video_mode`: ok | refuse | rotate | geo | missing | slow | expire (also `POST /admin/sports_video {"mode": ...}`).
- `sports_video_expire_s`: with "expire", segments answer 403 this many seconds after the manifest was served.
"""

import threading

PREFIX = "/bc/"
MODES = ("ok", "refuse", "rotate", "geo", "missing", "slow", "expire")

DEFAULTS = {
    "sports_video": False,          # --sports-video: serve /bc/ (needs sports; 404 otherwise)
    "sports_video_mode": "ok",      # one of MODES
    "sports_video_expire_s": 20,    # "expire": seconds until segments 403
}


def check_config(patch):
    """Raises ValueError for a bad video key in a config patch."""
    if "sports_video" in patch and not isinstance(patch["sports_video"], bool):
        raise ValueError("sports_video must be true or false")
    if "sports_video_mode" in patch and patch["sports_video_mode"] not in MODES:
        raise ValueError("sports_video_mode must be one of " + ", ".join(MODES))
    if "sports_video_expire_s" in patch:
        v = patch["sports_video_expire_s"]
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 1 <= v <= 3600:
            raise ValueError("sports_video_expire_s must be a number 1..3600")


class SportsVideo:
    """Per-run state (key reads, lookups, manifest times); WP-2 fills it."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        with self.lock:
            self.counts = {"script": 0, "lookup": 0, "media": 0}

    def stats(self):
        with self.lock:
            return dict(self.counts)


def handle(h, path):
    """Routes one video request on fakepanel's Handler [h]. Returns True when [path] was a video path."""
    if not path.startswith(PREFIX):
        return False
    cfg = h.panel.cfg
    if not (cfg.get("sports") and cfg.get("sports_video")):
        h.send(404, b"video routes are off (--sports --sports-video)", "text/plain")
        return True
    h.send(501, b"sports_video: not built yet (WP-2)", "text/plain")
    return True


def admin(h, path, data):
    """`/admin/sports_video`: GET the mode and counts; POST {"mode": "..."} sets the mode."""
    p = h.panel
    if h.command in ("POST", "PUT") and data and "mode" in data:
        try:
            check_config({"sports_video_mode": data["mode"]})
        except ValueError as e:
            return h.json({"error": str(e)}, 400)
        with p.lock:
            p.cfg["sports_video_mode"] = data["mode"]
    return h.json({"enabled": bool(p.cfg.get("sports") and p.cfg.get("sports_video")),
                   "mode": p.cfg.get("sports_video_mode"), "counts": p.sports_video.stats()})
