"""The fake ESPN NFL scoreboard (docs/design/m6-nfl.md section 9).

Commit 0 stub: the routes, the switches and the restart hook are wired into fakepanel.py, but every NFL path answers
404 (HTML, as ESPN does for an unknown path). WP-5 writes the 985 s featured-game timeline, the week and day boards,
the placeholder logo PNG, the script and the faults behind the same entry points:

- handle(h, path): GET /apis/site/v2/sports/football/nfl/scoreboard and /combiner/i (only with --sports --sports-nfl);
- admin(h, path, data): /admin/sports-nfl (GET the slate; POST {"restart": true} re-anchors only the NFL timeline);
- SportsNfl.reset(): called by fakepanel.py whenever the NHL anchor resets (POST /admin/sports {"restart": true}, and
  the panel's own reset), so one restart restarts both timelines (N8).

sports.py, its tests and testdata/sports_default_golden.json are untouched: the NHL golden is byte-identical by
construction.
"""

import threading
import time

TIMELINE_DEFAULT_S = 985
API_PREFIX = "/apis/site/v2/sports/football/nfl/"
LOGO_PREFIX = "/combiner/i"

DEFAULTS = {
    "sports_nfl": False,              # --sports-nfl: serve the ESPN routes above (needs sports; 404 otherwise)
    "sports_nfl_timeline_s": TIMELINE_DEFAULT_S,
    "sports_nfl_script": {},          # {event id: {state() key: value}} (WP-5)
    "sports_nfl_faults": {},          # the NHL fault keys (sports.FAULT_KEYS; WP-5)
}

NOT_FOUND_HTML = b"<html><body><h1>404 Not Found</h1></body></html>"


def check_config(patch):
    """Raises ValueError for a bad NFL key in a config patch (WP-5 adds the script and fault checks)."""
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
    if "sports_nfl_faults" in patch and not isinstance(patch["sports_nfl_faults"], dict):
        raise ValueError("sports_nfl_faults must be an object")


class SportsNfl:
    """The NFL slate's clock. Thread-safe; one per Panel."""

    def __init__(self, panel):
        self.panel = panel
        self.lock = threading.Lock()
        self.anchor = time.time()

    def reset(self):
        with self.lock:
            self.anchor = time.time()


def is_nfl_path(path):
    return path.startswith(API_PREFIX) or path == LOGO_PREFIX or path.startswith(LOGO_PREFIX + "/")


def handle(h, path):
    """Routes one NFL request on fakepanel's Handler [h]. Returns True when [path] was an NFL path."""
    if not is_nfl_path(path):
        return False
    cfg = h.panel.cfg
    if not (cfg.get("sports") and cfg.get("sports_nfl")):
        h.send(404, b"NFL routes are off (--sports --sports-nfl)", "text/plain")
        return True
    # Commit 0: nothing is served yet (WP-5). An HTML 404, as ESPN answers an unknown path.
    h.send(404, NOT_FOUND_HTML, "text/html")
    return True


def admin(h, path, data):
    """`/admin/sports-nfl`: POST {"restart": true} re-anchors only the NFL timeline. GET: the anchor (WP-5: the slate)."""
    sp = h.panel.sports_nfl
    if h.command in ("POST", "PUT") and data:
        if data.get("restart"):
            sp.reset()
        elif "anchor_unix" in data:
            with sp.lock:
                sp.anchor = float(data["anchor_unix"])
    cfg = h.panel.cfg
    return h.json({"enabled": bool(cfg.get("sports") and cfg.get("sports_nfl")), "anchor_unix": sp.anchor})
