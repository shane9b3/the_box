"""Self-tests for the event and team channel catalog (docs/design/m6-event-channels.md §11.3). Run from the repo root:

    python3 -I -m unittest discover -s tools/fakepanel -p test_events.py

The tail checks below are a short mirror of the app's five handled shapes and empty slots (not a second parser): every
generated event name must end in one of them, every empty slot must look like one.
"""
import datetime as dt
import os
import re
import sys
import time
import unittest
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog  # noqa: E402
import events  # noqa: E402

CFG = {"username": "u", "password": "p", "event_catalog": True}
BOTH = dict(CFG, sports_hub_catalog=True)
OFF = {"username": "u", "password": "p"}
BASE = "http://h:8080"
ET = ZoneInfo("America/New_York")

# The tail mirror (§3.2): shapes A, D, B, E, C; then the empty slots.
TAILS = {
    "A": re.compile(r" @ \d{1,2} [A-Z][a-z]{2} \d{2}:\d{2} (AM|PM|OM)( (ET|London))?$"),
    "D": re.compile(r" @ [A-Z][a-z]{2} \d{1,2} \d{1,2}:\d{2}(AM|PM) ET$"),
    "B": re.compile(r" \(\d{1,2}\.\d{1,2} \d{1,2}:\d{2} (AM|PM) ET\)$"),
    "E": re.compile(r" \| \d{1,2}\.\d{1,2} \d{1,2}:\d{2} (AM|PM) \| [^|]+$"),
    "C": re.compile(r" \(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\)$"),
}
EMPTY = re.compile(r"^(.+?\d{1,3} ?:( NO EVENT)?|.+ \| Offline|.+\(2098-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\))$")
SLOT = re.compile(r"^([^:|]+?\d{1,3}\)?) ?[:|]")


def shape(name):
    for k, r in TAILS.items():
        if r.search(name):
            return k
    return None


class EventCatalogTest(unittest.TestCase):
    def setUp(self):
        events.apply(names="reset")

    def tearDown(self):
        events.apply(names="reset")

    def by_cat(self, cfg=CFG):
        cats = dict(catalog.live_cats(cfg))
        out = {}
        for c in catalog.channels(cfg):
            if c.get("event"):
                out.setdefault(cats[c["cat"]], []).append(c)
        return out

    def test_off_by_default(self):
        self.assertEqual(13, len(catalog.channels(OFF)))
        self.assertEqual([], events.channels(OFF, time.time()))
        self.assertIsNone(catalog.channel_by_id(OFF, 100))
        self.assertNotIn(b"Sportsnet+", catalog.m3u(OFF, BASE, "h", 8080, "ts"))

    def test_ids_categories_and_counts(self):
        ev = [c for c in catalog.channels(CFG) if c.get("event")]
        ids = [c["id"] for c in ev]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(80 <= i <= 129 for i in ids))
        counts = {k: len(v) for k, v in self.by_cat().items()}
        self.assertEqual({
            "Sports | NHL Teams": 4, "Sports | NHL Teams (2)": 1, "Sports | NFL Teams": 4, "Sports | Sportsnet+": 10,
            "Sports | NHL": 2, "Sports | NHL (2)": 1, "Sports | ESPN+": 3, "Sports | NCAA Basketball (2)": 1,
            "Sports | TSN+ (2)": 2, "Sports | NBA League Pass": 2, "UK | Sky Sports+": 2, "Sports | NBA": 1, "CA | Sports": 8,
        }, counts)
        self.assertEqual("Edmonton Oilers", catalog.channel_by_id(CFG, 84)["name"])
        no_teams = dict(CFG, event_team_channels=False)
        self.assertNotIn("Sports | NHL Teams", self.by_cat(no_teams))
        self.assertNotIn("Sports | NHL Teams", dict(catalog.live_cats(no_teams)).values())

    def test_every_event_name_has_a_handled_tail_and_every_empty_slot_looks_empty(self):
        shapes = set()
        for c in catalog.channels(CFG):
            if not c.get("event") or c["cat"] in events.TEAM_CATS or c["id"] in {i for i, _, _ in events.GUIDE_LISTED}:
                continue
            n = c["name"]
            if n == "Sportsnet Oilers":
                continue
            if EMPTY.match(n):
                continue
            k = shape(n)
            self.assertIsNotNone(k, n)
            shapes.add(k)
            self.assertTrue(SLOT.match(n), "a slot before the title: " + n)
        self.assertEqual({"A", "B", "C", "D", "E"}, shapes)
        empties = [c["name"] for c in catalog.channels(CFG) if c.get("event") and EMPTY.match(c["name"])]
        for n in ("Sportsnet+ 11: NO EVENT", "Sportsnet+ 12:", "Sportsnet+ 29 :", "NHL 02:", "NFL GP 02 | Offline",
                  "(CA) (CBC 13) |  (2098-12-31 08:00:01)"):
            self.assertIn(n, empties)

    def test_names_around_the_featured_game(self):
        a = events.anchor()
        t = dt.datetime.fromtimestamp(a, ET)
        names = {c["id"]: c["name"] for c in catalog.channels(CFG)}
        self.assertEqual(f"Sportsnet+ 09: Vegas @ Edmonton @ {t.day} {t:%b} {t:%I:%M %p} ET", names[100])
        self.assertTrue(names[113].startswith("NHL 01: Golden Knights vs. Oilers ("), names[113])
        self.assertTrue(names[114].startswith("NHL 01: NHL on TNT: Golden Knights at Oilers @ "))
        early = dt.datetime.fromtimestamp(a - 1800, ET)
        self.assertTrue(names[114].endswith(f"{early:%I:%M %p} ET"))
        self.assertRegex(names[111], r" OM ET$")
        self.assertEqual("Sportsnet Oilers", names[124])
        # The stale game ended 2 h before "now" (an estimated 3 h matchup that started 5 h ago).
        stale = dt.datetime.fromtimestamp(time.time() // 60 * 60 - 5 * 3600, ET)
        self.assertTrue(names[105].endswith(f"{stale:%I:%M %p} ET"))

    def test_guide_listed_channels_for_the_featured_game(self):
        a, b = events.featured_window()
        xml = catalog.xmltv(BOTH, BASE, None, time.time(), "+0000").decode()
        listed = re.findall(r'channel="([^"]+)"><title lang="en">NHL Hockey: Golden Knights at Oilers<', xml)
        self.assertGreaterEqual(len(set(listed) - {"hub.sno.test", "hub.snw.test", "hub.nhln.test"}), 7)
        self.assertIn(events.HUB_TNT_TVG, listed)
        tnt = catalog.channel_by_id(BOTH, 65)
        self.assertEqual(events.HUB_TNT_TVG, tnt["tvg_id"])
        self.assertEqual("", catalog.channel_by_id(dict(BOTH, event_catalog=False), 65)["tvg_id"], "unchanged without the switch")
        # Team channels and plain event slots carry no guide.
        self.assertNotIn("EdmontonOilers.nhl", xml)
        self.assertEqual([], catalog.epg_listings(CFG, catalog.channel_by_id(CFG, 81), dt.timezone.utc, time.time())["epg_listings"])

    def test_boss_blocks_are_four_hours_and_hold_the_placeholders(self):
        now = time.time()
        xml = catalog.xmltv(CFG, BASE, None, now, "+0000").decode()
        fmt = "%Y%m%d%H%M%S %z"
        found = {"No Event Today": 0, "Signing Off": 0, "Next Event": 0, "real": 0}
        for tvg in events.BOSS.values():
            for start, stop, title in re.findall(
                    rf'<programme start="([^"]+)" stop="([^"]+)" channel="{re.escape(tvg)}"><title lang="en">([^<]+)<', xml):
                mins = (dt.datetime.strptime(stop, fmt) - dt.datetime.strptime(start, fmt)).total_seconds() / 60
                self.assertEqual(240, mins, title)
                if title.startswith("Next Event: "):
                    found["Next Event"] += 1
                    self.assertRegex(title, r"^Next Event: .+ at \d{2}:\d{2}(AM|PM) EST on [A-Z][a-z]{2} \d{1,2}$")
                elif title in found:
                    found[title] += 1
                else:
                    found["real"] += 1
        self.assertTrue(all(v > 0 for v in found.values()), found)
        # Slot 02's real listing starts at the name's minute and spells the title differently.
        a = events.anchor()
        ps = [p for p in events.boss_programmes(catalog.channel_by_id(CFG, 107), a - 86400, a + 86400) if p["title"] == events.BOSS_LISTING[107]]
        self.assertEqual([a - 3600], [p["start"] for p in ps])
        self.assertIn("FAU vs. SIUE", catalog.channel_by_id(CFG, 107)["name"])

    def test_admin_shift_and_next_names(self):
        before = catalog.channel_by_id(CFG, 100)["name"]
        events.apply(shift=60)
        after = catalog.channel_by_id(CFG, 100)["name"]
        self.assertNotEqual(before, after)
        t = dt.datetime.fromtimestamp(events.FEATURED_START + 3600, ET)
        self.assertTrue(after.endswith(f"{t:%I:%M %p} ET"), after)
        events.apply(names="next")
        self.assertEqual("Sportsnet+ 09: NO EVENT", catalog.channel_by_id(CFG, 100)["name"])
        self.assertTrue(catalog.channel_by_id(CFG, 109)["name"].startswith("Sportsnet+ 10: Vegas @ Edmonton @ "))
        events.apply(names="reset")
        self.assertEqual(before, catalog.channel_by_id(CFG, 100)["name"])

    def test_m3u_groups_and_team_tvg_ids(self):
        m3u = catalog.m3u(CFG, BASE, "h", 8080, "ts").decode()
        self.assertIn('tvg-id="EdmontonOilers.nhl"', m3u)
        self.assertIn('group-title="Sports | NHL Teams (2)"', m3u)
        self.assertIn(",Sportsnet+ 11: NO EVENT\n", m3u)
        self.assertEqual(len(catalog.channels(CFG)), len(catalog.live_streams(CFG, "h", 8080)))

    def test_config_check(self):
        with self.assertRaises(ValueError):
            events.check_config({"event_catalog": "yes"})
        with self.assertRaises(ValueError):
            events.check_config({"event_team_channels": 1})
        events.check_config({"event_catalog": True, "event_team_channels": False})


if __name__ == "__main__":
    unittest.main()
