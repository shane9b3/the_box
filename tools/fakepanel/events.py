"""Event and team channels for the fake panel (docs/design/m6-event-channels.md §11.3; `--event-catalog`).

Commit 0 stub: the switch, the config key and the admin route exist, and every hook answers "nothing", so the panel
serves exactly what it served before. WP-4 fills the catalog (channel ids 80-129, names generated at request time around
the featured game's anchor), the XMLTV placeholder blocks and the admin moves (`shift_min`, `names: "next"`).
"""

DEFAULTS = {
    "event_catalog": False,  # --event-catalog: the event and team channel catalog (stub: no channels yet)
}

# Admin keys `POST /admin/events` accepts (§11.3). The stub validates them and changes nothing.
ADMIN_NAMES = ("next", "reset")


def check_config(patch):
    """Raises ValueError for a bad event key in a config patch."""
    if "event_catalog" in patch and not isinstance(patch["event_catalog"], bool):
        raise ValueError("event_catalog must be true or false")


def enabled(cfg):
    return bool(cfg.get("event_catalog"))


def categories(cfg):
    """Live categories the catalog adds, as catalog.py's (id, name) pairs. Stub: none."""
    return []


def channels(cfg, now_unix):
    """Live channels the catalog adds (catalog.py channel dicts, names generated around now_unix). Stub: none."""
    return []


def programmes(cfg, ch, t_from, t_to, tz):
    """XMLTV programmes for one catalog channel in [t_from, t_to), as catalog.py's programme tuples. Stub: none."""
    return iter(())


def admin(h, path, data):
    """`/admin/events`: GET the state; POST {"shift_min": n} or {"names": "next"} (§11.3). Stub: validated, no effect."""
    if h.command in ("POST", "PUT") and data:
        shift = data.get("shift_min")
        if shift is not None and (isinstance(shift, bool) or not isinstance(shift, int)):
            return h.json({"error": "shift_min must be an integer (minutes)"}, 400)
        names = data.get("names")
        if names is not None and names not in ADMIN_NAMES:
            return h.json({"error": "names must be one of " + ", ".join(ADMIN_NAMES)}, 400)
    return h.json({"enabled": enabled(h.panel.cfg), "stub": True, "channels": 0})
