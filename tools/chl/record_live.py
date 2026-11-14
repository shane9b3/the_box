#!/usr/bin/env python3
"""Records one live CHL game's Firebase nodes for the S8 live power-play gate (docs/design/m6-chl-leagues.md §7.4).

Every --interval seconds (default 5) it reads all seven nodes of the game (runningclock, publishedclock, goalssummary,
shotssummary, penalties, goals, shootouts) with `X-Firebase-ETag: true` and `If-None-Match`, as the app does, and appends
one JSON line per poll to <out>/polls.jsonl:

    {"unix": 1794621785.2, "status": {"runningclock": 200, "penalties": 304, ...}, "nodes": {"runningclock": {...}, ...}}

`nodes` holds only the nodes whose body changed since the previous poll (a 304, or the same body, is left out; a failed
request too; its code is in `status`). `pp_replay.py live <out>` rebuilds every poll from that and compares the derivation
poll by poll with the model on the game's final data.

It stops once the game is final (publishedclock `Final` or status 4) for three polls in a row, or after --max-hours.

Board facts (needed by the Kotlin gate, `ChlLiveGateTest`): with a key it also saves that day's `gamesbydate` once at the
start and once at the end as board-start.json / board-end.json (team codes, start, period lengths, the playoff flag), with
the key replaced by KEY_REDACTED before writing. The key comes **only** from the HOCKEYTECH_KEY environment variable or
from a file (`--key-file`, one line); never from the command line (it would show in `ps` and the shell history, review
L8) and never hard-coded here. It is never printed. See tools/chl/README.md for the game-night procedure.

Usage (from the repo root):
  python3 -I tools/chl/record_live.py --league whl --game 1023288 --out tools/chl/recordings/whl-1023288
  python3 -I tools/chl/record_live.py --league ohl --game 29191 --date 2026-11-13 --key-file ~/.config/thebox/ohl.key --out ...
  HOCKEYTECH_KEY=fakepanel-ohl python3 -I tools/chl/record_live.py --league ohl --game 900001 --date <day> \
      --firebase http://127.0.0.1:8080/firebase/svf/ --feed http://127.0.0.1:8080/hockeytech/feed/index.php \
      --out /tmp/fake-ohl   # a dry run against the fake panel (its key is a dummy)

Requests carry a generic User-Agent and nothing about the user. Standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

FIREBASE = "https://leaguestat-b9523.firebaseio.com/svf/"
FEED = "https://lscluster.hockeytech.com/feed/index.php"
CLIENTS = {"ohl": "ohl", "whl": "whl", "qmjhl": "lhjmq", "lhjmq": "lhjmq"}
NODES = ("runningclock", "publishedclock", "goalssummary", "shotssummary", "penalties", "goals", "shootouts")
UA = "Mozilla/5.0 (compatible; recorder/1.0)"
MAX_BODY = 1 << 20


def node_url(base, client, node, gid):
    mid = "" if node == "runningclock" else "/1"
    return f"{base}{client}/{node}{mid}/games/{gid}.json"


def get(url, headers, timeout=10):
    """(status, headers dict, body bytes); status 0 = no response (the exception's class name in the body)."""
    req = urllib.request.Request(url, headers=dict(headers, **{"User-Agent": UA}))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read(MAX_BODY + 1)
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in (e.headers or {}).items()}, b""
    except Exception as e:  # noqa: BLE001 - a recorder keeps going; never print a URL (it may carry a key)
        return 0, {}, e.__class__.__name__.encode()


def redact(text, key):
    return text.replace(key, "KEY_REDACTED") if key else text


def save_board(args, client, key, name):
    if not key or not args.date:
        return
    q = urllib.parse.urlencode({"feed": "modulekit", "view": "gamesbydate", "fetch_date": args.date, "key": key,
                                "client_code": client, "fmt": "json", "lang": "en"})
    st, _, body = get(args.feed + "?" + q, {})
    if st != 200:
        print(f"board {name}: HTTP {st}", file=sys.stderr)
        return
    with open(os.path.join(args.out, name), "w") as f:
        f.write(redact(body.decode("utf-8", "replace"), key))


def is_final(nodes):
    pc = nodes.get("publishedclock") or {}
    rc = nodes.get("runningclock") or {}
    return pc.get("Final") is True or pc.get("StatusId") == 4 or rc.get("status_id") == 4


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--league", required=True, choices=sorted(CLIENTS))
    ap.add_argument("--game", required=True, help="HockeyTech game id (digits)")
    ap.add_argument("--out", required=True, help="output folder (created)")
    ap.add_argument("--interval", type=float, default=5.0)
    ap.add_argument("--max-hours", type=float, default=4.0)
    ap.add_argument("--date", help="the game's date YYYY-MM-DD (for the optional board facts)")
    ap.add_argument("--key-file", help="a file whose first line is the lscluster key (else HOCKEYTECH_KEY; else no board)")
    ap.add_argument("--firebase", default=FIREBASE, help="Firebase base ending in /svf/ (the fake panel's for a dry run)")
    ap.add_argument("--feed", default=FEED)
    args = ap.parse_args(argv)
    if not args.game.isdigit():
        ap.error("--game must be digits")
    if args.interval < 5.0:
        ap.error("--interval below 5 s is not allowed (the app's floor; do not hammer the service)")
    client = CLIENTS[args.league]
    key = None
    if args.key_file:
        with open(os.path.expanduser(args.key_file)) as f:
            key = f.readline().strip() or None
    key = key or os.environ.get("HOCKEYTECH_KEY") or None
    if not key or not args.date:
        print("no key or no --date: recording without board facts (the Kotlin gate needs them)", file=sys.stderr)
    os.makedirs(args.out, exist_ok=True)
    meta = {"league": args.league, "client": client, "game": args.game, "interval_s": args.interval,
            "date": args.date, "started_unix": time.time()}
    with open(os.path.join(args.out, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    save_board(args, client, key, "board-start.json")

    tags, last = {}, {}
    finals, polls, deadline = 0, 0, time.time() + args.max_hours * 3600
    with open(os.path.join(args.out, "polls.jsonl"), "a") as out:
        while time.time() < deadline:
            t0 = time.time()
            status, changed = {}, {}
            for n in NODES:
                h = {"X-Firebase-ETag": "true"}
                if n in tags:
                    h["If-None-Match"] = tags[n]
                st, hd, body = get(node_url(args.firebase, client, n, args.game), h)
                status[n] = st
                if st == 200 and len(body) <= MAX_BODY:
                    try:
                        value = json.loads(body)
                    except ValueError:
                        status[n] = -1  # not JSON
                        continue
                    if "etag" in hd:
                        tags[n] = hd["etag"]
                    if n not in last or last[n] != value:
                        changed[n] = value
                        last[n] = value
            out.write(json.dumps({"unix": round(t0, 3), "status": status, "nodes": changed}) + "\n")
            out.flush()
            polls += 1
            finals = finals + 1 if is_final(last) else 0
            if polls % 60 == 1:
                rc = (last.get("runningclock") or {}).get("Clock") or {}
                print(f"poll {polls}: P{rc.get('period')} {rc.get('Minutes')}:{rc.get('Seconds')}", file=sys.stderr)
            if finals >= 3:
                break
            time.sleep(max(0.0, args.interval - (time.time() - t0)))
    save_board(args, client, key, "board-end.json")
    print(f"{polls} polls written to {os.path.join(args.out, 'polls.jsonl')}", file=sys.stderr)


if __name__ == "__main__":
    main()
