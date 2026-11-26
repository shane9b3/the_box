"""(NFL hub integration, docs/design/m6-nfl-hub.md section 18) The fake panel's game summary, pinned for the app.

Three small samples of `GET .../summary?event=` (pre-game, live, final with ESPN clips; `drives` padding off) are
checked in under `core/src/testFixtures/resources/sports/nfl/hub/fakepanel-summary-{pre,live,final}.json`, with the
panel's origin rewritten to `http://fakepanel.invalid`. The app's `NflHubPipelineDataTest` decodes each through the
real `EspnSummaryDto`, `EspnDetailSource` and `EspnDetailMapper`; this test asserts that the running fake panel still
produces exactly their shape (every key and JSON type, recursively), so a change to `sports_nfl.summary` that the app
could not read fails here. Times differ run to run, so the values are not compared.

Regenerate after a deliberate change (then run the Kotlin test):

    FAKEPANEL_WRITE_GOLDEN=1 python3 -I -m unittest test_nfl_summary_golden   (from tools/fakepanel)
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sports_nfl  # noqa: E402
from test_sports_nfl import NflCase, SUMMARY  # noqa: E402

GOLDEN_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src",
                                           "testFixtures", "resources", "sports", "nfl", "hub"))
ORIGIN = "http://fakepanel.invalid"


def shape(v):
    """Keys and JSON types, recursively; a list by its first element (ESPN lists are uniform)."""
    if isinstance(v, dict):
        return {k: shape(x) for k, x in v.items()}
    if isinstance(v, list):
        return [shape(v[0])] if v else []
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "float"
    if v is None:
        return "null"
    return "str"


class TestSummaryGolden(NflCase):
    def sample(self, eid):
        self.panel.cfg["sports_nfl_summary_pad_kb"] = 0
        st, _, body = self.raw(SUMMARY + eid)
        self.assertEqual(200, st, body[:200])
        text = body.decode().replace("http://127.0.0.1:%d" % self.port, ORIGIN)
        self.assertNotIn("127.0.0.1", text)
        return json.loads(text)

    def check(self, name, doc):
        path = os.path.join(GOLDEN_DIR, "fakepanel-summary-%s.json" % name)
        if os.environ.get("FAKEPANEL_WRITE_GOLDEN"):
            with open(path, "w") as f:
                json.dump(doc, f, indent=1, sort_keys=False)
                f.write("\n")
        with open(path) as f:
            golden = json.load(f)
        self.assertEqual(shape(golden), shape(doc), "%s: the fake panel's summary no longer has the pinned shape" % name)
        self.assertEqual(list(golden.keys()), list(doc.keys()), "%s: key order (header first or last by event)" % name)
        text = json.dumps(golden)
        self.assertNotIn("127.0.0.1", text)
        for url in [u for u in text.split('"') if u.startswith("http")]:
            self.assertTrue(url.startswith(ORIGIN) or ".invalid/" in url, url)
        self.assertLess(len(text), 40_000, "keep the samples small (no drives padding)")
        return golden

    def test_pre_game(self):
        g = self.check("pre", self.sample("401899002"))
        self.assertEqual("pre", g["header"]["competitions"][0]["status"]["type"]["state"])
        self.assertIn("lastFiveGames", g)

    def test_live(self):
        self.at("q3 KC ball", 3)
        g = self.check("live", self.sample(sports_nfl.FEATURED_ID))
        self.assertEqual("in", g["header"]["competitions"][0]["status"]["type"]["state"])
        self.assertTrue(g["scoringPlays"])

    def test_final_with_clips(self):
        g = self.check("final", self.sample("401899003"))
        self.assertEqual("post", g["header"]["competitions"][0]["status"]["type"]["state"])
        self.assertEqual(["Final Game Highlight", "OnePlay", "OnePlay", "OnePlay"],
                         [v["tracking"]["coverageType"] for v in g["videos"]])
        self.assertTrue(g["videos"][0]["links"]["source"]["HLS"]["href"].startswith(ORIGIN + "/espn-media/"))


if __name__ == "__main__":
    unittest.main()
