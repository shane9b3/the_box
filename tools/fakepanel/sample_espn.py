"""Records ESPN's NFL scoreboard for one event (docs/design/m6-nfl.md section 1.6: the device items still to sample).

    python3 -I tools/fakepanel/sample_espn.py --event 401873038 --out /tmp/espn-sample [--interval 15] [--hours 4]
    python3 -I tools/fakepanel/sample_espn.py --event 401899001 --base http://127.0.0.1:8080 --out /tmp/fake-sample

Every `--interval` seconds it GETs the undated scoreboard (this week), finds the event, appends one line to `log.jsonl`
(time, HTTP status, the status name, period, clock, scores, the situation's down / distance / yardLine / possession /
possessionText / isRedZone and the last play's type and score value), and keeps the raw body of every answer whose
(status name, scores, down, possession, last play id) changed as `body-NNNN.json`. Read it for: STATUS_HALFTIME and its
situation, a missed XP or FG, a safety and a two-point try (down / distance), the Final-after-OT name, yardLine on a second
game, possessionText at the 50, a defensive TD's play type id, and the size of a busy Sunday board against the 1 MiB cap.
It sends no key and no cookie, one request per interval, and stops at --hours or when the event is final.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

ESPN = "https://site.api.espn.com"
PATH = "/apis/site/v2/sports/football/nfl/scoreboard"


def summarise(event):
    comp = event["competitions"][0]
    st = comp["status"]
    sit = comp.get("situation") or {}
    lp = sit.get("lastPlay") or {}
    scores = {c.get("homeAway"): c.get("score") for c in comp.get("competitors", [])}
    return {
        "name": st["type"]["name"], "period": st.get("period"), "clock": st.get("displayClock"), "scores": scores,
        "down": sit.get("down"), "distance": sit.get("distance"), "yardLine": sit.get("yardLine"),
        "possession": sit.get("possession"), "possessionText": sit.get("possessionText"), "isRedZone": sit.get("isRedZone"),
        "play": (lp.get("type") or {}).get("id"), "playText": (lp.get("type") or {}).get("text"),
        "playScore": lp.get("scoreValue"), "playId": lp.get("id"), "playTeam": (lp.get("team") or {}).get("id"),
    }


def key(s):
    return (s["name"], json.dumps(s["scores"], sort_keys=True), s["down"], s["possession"], s["playId"])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--event", required=True, help="the ESPN event id")
    ap.add_argument("--out", required=True, help="the directory for log.jsonl and the kept bodies")
    ap.add_argument("--base", default=ESPN, help="origin (default ESPN; the fake panel: http://127.0.0.1:8080)")
    ap.add_argument("--interval", type=float, default=15.0)
    ap.add_argument("--hours", type=float, default=4.0)
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    last, kept, end = None, 0, time.time() + a.hours * 3600
    with open(os.path.join(a.out, "log.jsonl"), "a") as log:
        while time.time() < end:
            t0 = time.time()
            row = {"unix": round(t0, 1)}
            try:
                with urllib.request.urlopen(urllib.request.Request(a.base + PATH, headers={"Accept-Encoding": "identity"}), timeout=10) as r:
                    body = r.read()
                    row.update(http=r.status, bytes=len(body))
                ev = next((e for e in json.loads(body).get("events", []) if e.get("id") == a.event), None)
                if ev is None:
                    row["error"] = "event not on this week's board"
                else:
                    s = summarise(ev)
                    row.update(s)
                    if key(s) != last:
                        last, kept = key(s), kept + 1
                        with open(os.path.join(a.out, f"body-{kept:04d}.json"), "wb") as f:
                            f.write(body)
                        row["kept"] = kept
                    if s["name"] == "STATUS_FINAL":
                        log.write(json.dumps(row) + "\n")
                        break
            except Exception as ex:  # noqa: BLE001 - a recorder keeps going through a bad answer
                row["error"] = f"{type(ex).__name__}: {ex}"
            log.write(json.dumps(row) + "\n")
            log.flush()
            time.sleep(max(0.0, a.interval - (time.time() - t0)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
