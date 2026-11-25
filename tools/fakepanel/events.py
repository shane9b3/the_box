"""Event and team channels for the fake panel (docs/design/m6-event-channels.md §11.3; `--event-catalog`).

Off by default, so the catalog stays the 13 channels the M2-M6 suites count. On (`event_catalog: true`), catalog.py adds:

- categories 30-42 (`CATEGORIES`) and channels 80-129 (`channels()`), with **names generated at request time** around
  the featured game: VGK @ EDM, which sports.py's hub slate starts 30 min before the server start (game 0908). Its
  minute is `FEATURED_START` (plus the admin shift), so the names always read "today";
- **team channels** (`Sports | NHL Teams`, `Sports | NHL Teams (2)` with a second "Edmonton Oilers" for the "Also in"
  line, `Sports | NFL Teams` with the NFL board's first game NYJ @ NE and its featured KC @ BUF). They have no guide data.
  Config `event_team_channels: false` leaves them out (the "Your guide doesn't cover this game yet" line);
- **seven guide-listed channels** for the featured game ("NHL Hockey: Golden Knights at Oilers" in the XMLTV): with
  `--sports-hub-catalog` also "US: TNT" (given a tvg id here), "CA| SPORTSNET WEST" and Sportsnet Ontario: ten in all,
  more than the hub's cap of 8;
- **event slots** in the five shapes (A `@ 24 Nov 08:00 PM ET`, B `(11.24 8:00 PM ET)`, C `(2026-11-24 19:00:00)`,
  D `@ Nov 24 3:30PM ET`, E `| 11.24 8:00 PM | ESPN`), named relative to the featured game: cities-only `Vegas @ Edmonton`,
  an NBA decoy, a morning show, a stale game that ended 2 h ago and one from yesterday, the owner's `OM ET`, the
  duplicate `NHL 01` in `Sports | NHL` and `Sports | NHL (2)`;
- **empty slots** of every kind: `NO EVENT`, a bare `NN:` and `NN :`, `| Offline`, the 2098 placeholder;
- **BossSports-style provider-linked slots** (`Sports | ESPN+`, shape D, tvg ids `fake.boss.espnplus.0N`) whose XMLTV
  holds 4 h placeholder blocks ("No Event Today", one "Signing Off", one "Next Event: <title> at <h:mm>PM EST on <Mon D>")
  and, on slots 02 and 03, one real 4 h listing starting at the name's minute (02 spells the title differently).

Admin `POST /admin/events`: `{"shift_min": n}` moves every event by n minutes (cumulative); `{"names": "next"}` swaps
the slots to the next set (after a playlist refresh `Sportsnet+ 09`'s event is on `Sportsnet+ 10`: the reminder "moved"
state); `{"names": "reset"}` restores set 0 and shift 0. `GET` answers the state and the current names.
"""
import datetime as dt
import threading
import time
from zoneinfo import ZoneInfo

DEFAULTS = {
    "event_catalog": False,       # --event-catalog: the event and team channel catalog
    "event_team_channels": True,  # false: no team channels (the beyond-guide "guide doesn't cover" line)
}

ADMIN_NAMES = ("next", "reset")

ET = ZoneInfo("America/New_York")
LONDON = ZoneInfo("Europe/London")
TORONTO = ZoneInfo("America/Toronto")
BLOCK = 4 * 3600  # BossSports placeholder and real listings: fixed 4 h blocks
MIN = 60

# The featured game's minute: sports.py's hub slate starts VGK @ EDM (game 0908) 30 min before the server start.
FEATURED_START = (int(time.time()) // MIN * MIN) - 1800

_lock = threading.Lock()
_state = {"shift_min": 0, "set": 0}

CATEGORIES = [
    (30, "Sports | NHL Teams"), (31, "Sports | NHL Teams (2)"), (32, "Sports | NFL Teams"), (33, "Sports | Sportsnet+"),
    (34, "Sports | NHL"), (35, "Sports | NHL (2)"), (36, "Sports | ESPN+"), (37, "Sports | NCAA Basketball (2)"),
    (38, "Sports | TSN+ (2)"), (39, "Sports | NBA League Pass"), (40, "UK | Sky Sports+"), (41, "Sports | NBA"),
    (42, "CA | Sports"),
]
TEAM_CATS = (30, 31, 32)

# (id, name, category, tvg id): team channels (no guide data).
TEAMS = [
    (80, "Vegas Golden Knights", 30, "VegasGoldenKnights.nhl"),
    (81, "Edmonton Oilers", 30, "EdmontonOilers.nhl"),
    (82, "Montreal Canadiens", 30, "MontrealCanadiens.nhl"),
    (83, "Utah Mammoth", 30, "UtahHockeyClub.nhl"),
    (84, "Edmonton Oilers", 31, "EdmontonOilers.nhl"),
    (85, "New York Jets", 32, "NewYorkJets.nfl"),
    (86, "New England Patriots", 32, "NewEnglandPatriots.nfl"),
    (87, "Kansas City Chiefs", 32, "KansasCityChiefs.nfl"),
    (88, "Buffalo Bills", 32, "BuffaloBills.nfl"),
]
# (id, name, tvg id): ordinary channels whose guide lists the featured game.
GUIDE_LISTED = [
    (90, "CA| SPORTSNET OILERS", "ev.snoilers.test"),
    (91, "CA| SPORTSNET PACIFIC", "ev.snpacific.test"),
    (92, "CA| SPORTSNET EAST", "ev.sneast.test"),
    (93, "CA| SPORTSNET ONE", "ev.snone.test"),
    (94, "US: TRUTV", "ev.trutv.test"),
    (95, "CA| TSN 2", "ev.tsn2.test"),
    (96, "US: ESPN", "ev.espn.test"),
]
FEATURED_TITLE = "NHL Hockey: Golden Knights at Oilers"
# The hub catalog's TNT (id 65) gets a tvg id with this catalog on (§11.3: "now with a tvg id"); see catalog.py.
HUB_TNT_TVG = "ev.tnt.test"
HUB_LISTED_TVG = (HUB_TNT_TVG,)

BOSS = {106: "fake.boss.espnplus.01", 107: "fake.boss.espnplus.02", 108: "fake.boss.espnplus.03"}
# Slot 02's real listing spells the title differently from the name (search's de-duplication, §7).
BOSS_LISTING = {107: "Florida Atlantic vs. SIU Edwardsville", 108: "Duke vs. Kansas"}

PLACEHOLDER = "No Event Today"
SIGN_OFF = "Signing Off"


def check_config(patch):
    """Raises ValueError for a bad event key in a config patch."""
    for k in ("event_catalog", "event_team_channels"):
        if k in patch and not isinstance(patch[k], bool):
            raise ValueError(k + " must be true or false")


def enabled(cfg):
    return bool(cfg and cfg.get("event_catalog"))


def state():
    with _lock:
        return dict(_state)


def anchor():
    """The featured game's start (unix s) with the admin shift."""
    return FEATURED_START + state()["shift_min"] * MIN


# ---------------------------------------------------------------- the name formats (§3.2)

def _ampm(t):
    return t.strftime("%p")


def shape_a(slot, title, unix, zone=ET, word="ET", om=False):
    """`Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET` (om: the owner's typo, `09:30 OM ET`)."""
    t = dt.datetime.fromtimestamp(unix, zone)
    ampm = "OM" if om and _ampm(t) == "PM" else _ampm(t)
    return f"{slot}: {title} @ {t.day} {t:%b} {t:%I:%M} {ampm} {word}"


def shape_b(slot, title, unix):
    """`NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)`."""
    t = dt.datetime.fromtimestamp(unix, ET)
    return f"{slot}: {title} ({t.month}.{t.day} {t.hour % 12 or 12}:{t:%M} {_ampm(t)} ET)"


def shape_c(prefix, title, unix, zone):
    """`CA (TSN+ 008) | NCAA Men`s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)` (local time of [zone])."""
    t = dt.datetime.fromtimestamp(unix, zone)
    return f"{prefix} | {title} ({t:%Y-%m-%d %H:%M:%S})"


def shape_d(slot, title, unix):
    """`ESPN+ 01: Long Beach State vs. Arkansas State @ Nov 24 3:30PM ET`."""
    t = dt.datetime.fromtimestamp(unix, ET)
    return f"{slot}: {title} @ {t:%b} {t.day} {t.hour % 12 or 12}:{t:%M}{_ampm(t)} ET"


def shape_e(slot, title, unix, net):
    """`NCAAB 081: 9 TEXAS TECH @ 23 LOUISVILLE (M) | 11.24 8:00 PM | ESPN` (Eastern by convention, no zone word)."""
    t = dt.datetime.fromtimestamp(unix, ET)
    return f"{slot}: {title} | {t.month}.{t.day} {t.hour % 12 or 12}:{t:%M} {_ampm(t)} | {net}"


def next_event(title, unix):
    """The provider's placeholder `Next Event: <title> at 07:30PM EST on Nov 24`."""
    t = dt.datetime.fromtimestamp(unix, ET)
    return f"Next Event: {title} at {t:%I:%M%p} EST on {t:%b} {t.day}"


# ---------------------------------------------------------------- the catalog

def categories(cfg):
    """Live categories the catalog adds, as catalog.py's (id, name) pairs."""
    if not enabled(cfg):
        return []
    teams = cfg.get("event_team_channels", True)
    return [c for c in CATEGORIES if teams or c[0] not in TEAM_CATS]


def _ch(i, name, cat, tvg="", **kw):
    return dict(id=i, name=name, cat=cat, archive=False, catchup="", media="std", tvg_id=tvg, event=True, **kw)


def _sportsnet_plus(a, now_unix, s):
    """Sportsnet+ (shape A, ET). Set 1 moves Vegas @ Edmonton from slot 09 (id 100) to slot 10 (id 109)."""
    day = dt.datetime.fromtimestamp(a, ET).date()
    morning = int(dt.datetime.combine(day, dt.time(9, 0), ET).timestamp())
    stale = now_unix // MIN * MIN - 5 * 3600  # an estimated 3 h game that ended 2 h ago (the "may still be on" state)
    yesterday = a - 86400 - 3600
    featured = shape_a("Sportsnet+ 09", "Vegas @ Edmonton", a)
    out = [
        _ch(100, featured if s == 0 else "Sportsnet+ 09: NO EVENT", 33),
        _ch(109, "Sportsnet+ 10: NO EVENT" if s == 0 else featured.replace("Sportsnet+ 09", "Sportsnet+ 10", 1), 33),
        _ch(101, shape_a("Sportsnet+ 08", "Detroit @ Orlando", a), 33),
        _ch(102, shape_a("Sportsnet+ 01", f"Sportsnet Central: {day:%b}. {day.day}", morning), 33),
        _ch(103, "Sportsnet+ 11: NO EVENT", 33),
        _ch(104, "Sportsnet+ 12:", 33),
        _ch(105, shape_a("Sportsnet+ 14", "Calgary @ Seattle", stale), 33),
        _ch(110, shape_a("Sportsnet+ 15", "Seattle @ Vancouver", yesterday), 33),
        _ch(111, shape_a("Sportsnet+ 23", "Montreal @ Colorado", a + 3 * 3600 + 1800, om=True), 33),
        _ch(112, "Sportsnet+ 29 :", 33),
    ]
    return out


def channels(cfg, now_unix):
    """Live channels the catalog adds (catalog.py channel dicts), names generated around the featured game."""
    if not enabled(cfg):
        return []
    a = anchor()
    s = state()["set"]
    out = []
    if cfg.get("event_team_channels", True):
        out += [_ch(i, n, c, t, noguide=True) for i, n, c, t in TEAMS]
    out += [_ch(i, n, 42, t) for i, n, t in GUIDE_LISTED]
    out += _sportsnet_plus(a, int(now_unix), s)
    out += [
        # Sports | NHL and (2): one short name "NHL 01", two events (shape B at the minute, shape A 30 min early).
        _ch(113, shape_b("NHL 01", "Golden Knights vs. Oilers", a), 34),
        _ch(114, shape_a("NHL 01", "NHL on TNT: Golden Knights at Oilers", a - 1800), 35),
        _ch(115, "NHL 02:", 34),
        # BossSports-style provider-linked slots (shape D) with their own guide.
        _ch(106, shape_d("ESPN+ 01", "Long Beach State vs. Arkansas State", a + 3600), 36, BOSS[106]),
        _ch(107, shape_d("ESPN+ 02", "FAU vs. SIUE", a - 3600), 36, BOSS[107]),
        _ch(108, shape_d("ESPN+ 03", "Duke vs. Kansas", a + 7200), 36, BOSS[108]),
        # Shape E, shape C (tagged, untagged, 2098), London, NBA.
        _ch(116, shape_e("NCAAB 081", "9 TEXAS TECH @ 23 LOUISVILLE (M)", a, "ESPN"), 37),
        _ch(117, shape_c("CA (TSN+ 008)", "NCAA Men`s Hockey: Bentley vs. Harvard", a - 3600, TORONTO), 38),
        _ch(118, "(CA) (CBC 13) |  (2098-12-31 08:00:01)", 38),
        _ch(119, shape_c("NBA LP 01", "Grizzlies @ Hornets", a + 3600, ET), 39),
        _ch(120, "NFL GP 02 | Offline", 39),
        _ch(121, shape_a("Sky Sports + 20", "Basketball NBA Boston Celtics @ Washington Wizards", a, LONDON, "London"), 40),
        _ch(122, shape_a("Sky Sports + 21", "Premier League: Arsenal v Chelsea", a - 4 * 3600, LONDON, "London"), 40),
        _ch(123, shape_a("NBA 03", "Mavericks vs. Nuggets", a + 7200), 41),
        _ch(124, "Sportsnet Oilers", 42),  # never a team channel: not in a "Teams" group, no team tvg id
    ]
    return out


def channel_by_id(cfg, i):
    for c in channels(cfg, time.time()):
        if c["id"] == i:
            return c
    return None


# ---------------------------------------------------------------- XMLTV

def featured_window():
    a = anchor()
    return a - 1800, a + 3 * 3600 + 1800


def _blocks(t_from, t_to):
    s = int(t_from) // BLOCK * BLOCK
    while s < t_to:
        yield s
        s += BLOCK


def boss_programmes(ch, t_from, t_to):
    """4 h blocks: "No Event Today", the block before the slot's event "Next Event: …", the block after it "Signing
    Off"; slots 02 and 03 carry one real 4 h listing at the name's minute (blocks it overlaps are left out)."""
    a = anchor()
    start = {106: a + 3600, 107: a - 3600, 108: a + 7200}[ch["id"]]
    title = {106: "Long Beach State vs. Arkansas State", 107: "FAU vs. SIUE", 108: "Duke vs. Kansas"}[ch["id"]]
    real = BOSS_LISTING.get(ch["id"])
    ev_block = start // BLOCK * BLOCK
    out = []
    if real and start + BLOCK > t_from and start < t_to:
        out.append({"start": start, "stop": start + BLOCK, "title": real, "desc": f"{real}. Live."})
    for b in _blocks(t_from, t_to):
        if real and b < start + BLOCK and start < b + BLOCK:
            continue
        if b == ev_block - BLOCK:
            t = next_event(title, start)
        elif b == ev_block + BLOCK:
            t = SIGN_OFF
        else:
            t = PLACEHOLDER
        out.append({"start": b, "stop": b + BLOCK, "title": t, "desc": t})
    return sorted(out, key=lambda p: p["start"])


def listed(ch):
    """True for a channel whose guide lists the featured game (this catalog's seven, and the hub's TNT)."""
    return ch.get("tvg_id") in {t for _, _, t in GUIDE_LISTED} or ch.get("tvg_id") == HUB_TNT_TVG


def programmes(cfg, ch, t_from, t_to, tz):
    """XMLTV programmes for one catalog channel in [t_from, t_to): the BossSports blocks, or the featured game for a
    guide-listed channel (the caller lays the usual slots around it). Other event channels have no guide."""
    if ch.get("tvg_id") in BOSS.values():
        yield from (p for p in boss_programmes(ch, t_from, t_to) if p["stop"] > t_from and p["start"] < t_to)
        return
    if listed(ch):
        a, b = featured_window()
        if b > t_from and a < t_to:
            yield {"start": a, "stop": b, "title": FEATURED_TITLE, "desc": f"{ch['name']} - {FEATURED_TITLE}"}


# ---------------------------------------------------------------- admin

def admin(h, path, data):
    """`/admin/events`: GET the state; POST {"shift_min": n} or {"names": "next" | "reset"} (§11.3)."""
    if h.command in ("POST", "PUT") and data:
        shift = data.get("shift_min")
        if shift is not None and (isinstance(shift, bool) or not isinstance(shift, int)):
            return h.json({"error": "shift_min must be an integer (minutes)"}, 400)
        names = data.get("names")
        if names is not None and names not in ADMIN_NAMES:
            return h.json({"error": "names must be one of " + ", ".join(ADMIN_NAMES)}, 400)
        apply(shift, names)
    cfg = h.panel.cfg
    return h.json({"enabled": enabled(cfg), **state(), "anchor_unix": anchor(),
                   "channels": [{"id": c["id"], "name": c["name"]} for c in channels(cfg, time.time())]})


def apply(shift=None, names=None):
    """The admin moves, also for tests."""
    with _lock:
        if names == "reset":
            _state.update(shift_min=0, set=0)
        elif names == "next":
            _state["set"] = (_state["set"] + 1) % 2
        if shift is not None:
            _state["shift_min"] += int(shift)
