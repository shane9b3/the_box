"""Self-tests for the Sports Hub channel-link catalog (docs/design/m6-sports-hub.md section 10.3, WP-3). Run from the repo
root:

    python3 -I -m unittest discover -s tools/fakepanel -p test_catalog_hub.py
"""
import datetime as dt
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog  # noqa: E402

BASE = "http://h:8080"
CFG = {"username": "u", "password": "p", "sports_hub_catalog": True}
OFF = {"username": "u", "password": "p"}
UTC = dt.timezone.utc


class CatalogHubTest(unittest.TestCase):
    def test_off_by_default(self):
        self.assertEqual(13, len(catalog.channels(OFF)))
        self.assertIsNone(catalog.channel_by_id(OFF, 60))
        self.assertNotIn(b"SPORTSNET", catalog.m3u(OFF, BASE, "h", 8080, "ts"))

    def test_channels_and_m3u(self):
        names = [c["name"] for c in catalog.channels(CFG)]
        for n in ("CA| SPORTSNET ONTARIO HD", "CA| SN ONTARIO FHD", "SN - Ontario", "CA| SPORTSNET 360", "CA| TVA SPORTS", "US: TNT",
                  "UK: TNT SPORTS 1", "US: ABC 7 NEW YORK", "US: ESPN 2", "CA| SPORTSNET WEST", "US: NHL NETWORK"):
            self.assertIn(n, names)
        self.assertEqual(24, len(names))
        self.assertEqual("CA| TVA SPORTS", catalog.channel_by_id(CFG, 64)["name"])
        m3u = catalog.m3u(CFG, BASE, "h", 8080, "ts").decode()
        self.assertIn(',CA| SPORTSNET ONTARIO HD\n', m3u)
        line = next(ln for ln in m3u.splitlines() if ln.endswith(",CA| SPORTSNET ONTARIO HD"))
        self.assertIn('catchup="xc" catchup-days="3"', line)
        self.assertEqual(24, len(catalog.live_streams(CFG, "h", 8080)))

    def test_airings(self):
        a = 1_764_032_400  # 2025-11-25 01:00 UTC
        by = {c["tvg_id"]: c for c in catalog.HUB_CHANNELS if c["tvg_id"]}
        sno = catalog.hub_airings(by["hub.sno.test"], a)
        self.assertIn((a - 3600, a + 18000, "NHL Hockey: Golden Knights at Oilers"), sno)
        past = [x for x in sno if "Kraken" in x[2]]
        self.assertEqual(1, len(past))
        self.assertTrue(a - 2 * 86400 < past[0][0] < a, "yesterday, inside the 3-day archive")
        self.assertEqual([(a - 3600, a + 18000, "Hockey LNH")], catalog.hub_airings(by["hub.tvas.test"], a))
        self.assertEqual([], catalog.hub_airings(by["hub.sn360.test"], a), "SN360 is not listed for the game")
        self.assertEqual(["NHL Hockey: Kings at Ducks"], [x[2] for x in catalog.hub_airings(by["hub.abc7.test"], a)])

    def test_overlay_has_no_overlaps(self):
        a = catalog.HUB_ANCHOR
        ch = catalog.channel_by_id(CFG, 60)
        progs = list(catalog.programmes(ch, a - 86400, a + 86400, UTC))
        for p, q in zip(progs, progs[1:]):
            self.assertLessEqual(p["stop"], q["start"], (p, q))
        self.assertIn("NHL Hockey: Golden Knights at Oilers", [p["title"] for p in progs])

    def test_xmltv(self):
        x = catalog.xmltv(CFG, BASE, None, catalog.HUB_ANCHOR, "+0000").decode()
        self.assertEqual(1, x.count('<channel id="hub.sno.test">'), "60 and 61 share one guide id")
        self.assertNotIn('<channel id="">', x)
        self.assertIn("<title lang=\"en\">Hockey LNH</title>", x)
        self.assertEqual(3, len(re.findall(r'channel="hub\.(sno|snw|nhln)\.test"><title lang="en">NHL Hockey: Golden Knights at Oilers', x)))

    def test_short_epg(self):
        tvas = catalog.channel_by_id(CFG, 64)
        cfg = dict(CFG, epg_plain=True)
        rows = catalog.epg_listings(cfg, tvas, UTC, catalog.HUB_ANCHOR, short=True, limit=4)["epg_listings"]
        self.assertEqual("Hockey LNH", rows[0]["title"])
        self.assertEqual([], catalog.epg_listings(cfg, catalog.channel_by_id(CFG, 65), UTC, catalog.HUB_ANCHOR)["epg_listings"])


if __name__ == "__main__":
    unittest.main()
