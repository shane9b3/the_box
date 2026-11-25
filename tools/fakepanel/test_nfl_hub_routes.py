"""Self-tests for the NFL hub's commit-0 routes in fakepanel.py (docs/design/m6-nfl-hub.md section 8.4). Run from the repo
root:

    python3 -I -m unittest discover -s tools/fakepanel -p 'test_nfl_hub*.py'
"""
import copy
import http.client
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fakepanel  # noqa: E402

SUMMARY = "/apis/site/v2/sports/football/nfl/summary?event=401873078"
RECORD = "/espn-core/v1/video/clips/50578630"


class NflHubRoutesCase(unittest.TestCase):
    def serve(self, overrides):
        cfg = copy.deepcopy(fakepanel.DEFAULTS)
        cfg.update({"pace": False})
        cfg.update(overrides)
        panel = fakepanel.Panel(cfg, use_ffmpeg=False, quiet=True)
        main, lb = fakepanel.start_servers(panel, "127.0.0.1", 0)
        self.addCleanup(fakepanel.stop_servers, panel, main, lb)
        return panel

    def get(self, panel, path):
        c = http.client.HTTPConnection("127.0.0.1", panel.main_port, timeout=10)
        c.request("GET", path)
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, body

    def test_defaults_are_known_config_keys(self):
        self.assertEqual(400, fakepanel.DEFAULTS["sports_nfl_summary_pad_kb"])
        self.assertEqual("espn", fakepanel.DEFAULTS["sports_nfl_clips"])
        panel = self.serve({})
        panel.update_config({"sports_nfl_clips": "expired", "sports_nfl_summary_pad_kb": 2100})
        for bad in ({"sports_nfl_clips": "later"}, {"sports_nfl_summary_pad_kb": -1}, {"sports_nfl_summary_pad_kb": True}):
            with self.assertRaises(ValueError):
                panel.update_config(bad)

    def test_routes_are_off_without_the_nfl(self):
        panel = self.serve({"sports": True})
        for path in (SUMMARY, RECORD, "/espn-media/50578630/master.m3u8"):
            st, body = self.get(panel, path)
            self.assertEqual(404, st, path)
            self.assertIn(b"off", body)

    def test_routes_reach_the_package_handlers(self):
        panel = self.serve({"sports": True, "sports_nfl": True})
        for path in (SUMMARY, RECORD):
            st, body = self.get(panel, path)
            # Until P2 adds sports_nfl.handle_summary / sports_video.handle_espn, a plain 404 names the package.
            if body.startswith(b"not served yet"):
                self.assertEqual(404, st, path)
            else:
                self.assertNotIn(b"off", body, path)
        # The board is unchanged.
        st, _ = self.get(panel, "/apis/site/v2/sports/football/nfl/scoreboard")
        self.assertEqual(200, st)


if __name__ == "__main__":
    unittest.main()
