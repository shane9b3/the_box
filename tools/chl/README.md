# CHL tools

Scripts for the CHL leagues (OHL, WHL, QMJHL); the design is `docs/design/m6-chl-leagues.md`. Python 3, standard library
only; run them with `python3 -I` from the repo root.

| Script | What it does |
|---|---|
| `pp_replay.py` | The reference model of design §5: the goal replay, the duration audit, probes. `live <folder>` is a quick Python check of a recording, **not** the release gate. |
| `pp_parity.py` | Probe parity between the reference and `ChlPowerPlay`. |
| `logo_audit.py` | The 61-logo outcome table (§3.5). |
| `record_live.py` | Records one live game's seven Firebase nodes every 5 s for the S8 gate (§7.4). |

## The S8 live power-play gate on a game night

The gate runs the **shipped Kotlin code**: `ChlLiveGateTest` (`:core` tests) rebuilds every recorded poll, maps it with
`HockeyTechMapper.followed` (which runs `ChlPowerPlay`), and compares the bar it would show with the §5 model on the game's
final data. Gates per league:

| Metric | Gate |
|---|---|
| wrong-bar seconds (a bar of the wrong side or kind, or a bar where the model has none) | ≤ 5 % of the final model's power-play seconds |
| longest wrong stretch | ≤ 30 s |
| hidden + missing seconds (unknown, or nothing shown, while the model has a power play) | ≤ 25 % of the final model's power-play seconds |
| shown seconds within ±5 s of the model, of the seconds the bar matches | ≥ 95 % |

Record at least **one full live game per league** before a release.

1. **The key.** `record_live.py` needs the league's `lscluster` key only for the board facts (`board-start.json` and
   `board-end.json`: team codes, start time, period lengths, the playoff flag). The gate cannot run without them. The key
   is the league site's public key: the same value as the app's `HockeyTechKeys`, the one source file that holds it.
   - Supply it **only** through the environment (`HOCKEYTECH_KEY`) or a private file (`--key-file`, one line). Never pass it
     on the command line, never commit it, and never put it in a doc, fixture or recording.
   - Example of a private file: `install -m 600 /dev/null ~/.config/thebox/ohl.key`, then paste the key into it.
   - The recorder writes the boards with the key replaced by `KEY_REDACTED`, and never prints it.
2. **Find the game.** The game id is HockeyTech's `game_id` (in the app's debug log, or on the league site's game-centre
   URL). Use the game's date `YYYY-MM-DD` as the league lists it.
3. **Record** from before puck drop until it stops by itself (final for three polls, or `--max-hours`, default 4):

   ```
   python3 -I tools/chl/record_live.py --league whl --game 1023288 --date 2026-11-13 \
       --key-file ~/.config/thebox/whl.key --out tools/chl/recordings/whl-1023288
   ```

   - It polls every 5 s, the app's floor; it refuses a shorter `--interval`.
   - It sends the default urllib request with a generic User-Agent and nothing about the user.
   - `tools/chl/recordings/` holds no secret. Keep recordings out of git unless the lead asks for one as a fixture.
4. **Run the gate** on one recording, or on a folder of them (each league is judged on its games together):

   ```
   CHL_LIVE_RECORDINGS=$PWD/tools/chl/recordings ./gradlew :core:test --tests '*ChlLiveGateTest*' -i | grep -E 'polls|PASS|FAIL'
   ```

   - Each game prints one line: polls, in-play seconds, the final model's power-play seconds, wrong (and the longest wrong
     stretch), hidden + missing, the seconds agreement, and the seconds not judged (H9 windows).
   - Then one PASS or FAIL line per league. A FAIL fails the test.
   - Without `CHL_LIVE_RECORDINGS` that case is skipped. The fake panel's recordings (`core/src/test/resources/sports/chl/live-gate/`)
     always run.
5. **Quick check (optional):** `python3 -I tools/chl/pp_replay.py live tools/chl/recordings/whl-1023288` runs the Python
   model only. Use it for a fast look, not as the gate.
6. **Classify the misses** against the league's own game centre (design §5.7) and report the numbers to the lead with the
   recording folder.

### A dry run against the fake panel

```
python3 -I tools/fakepanel/fakepanel.py --port 8080 --no-ffmpeg --sports --sports-chl --chl-timeline 200 &
DAY=$(python3 -I -c "import json,urllib.request;print(json.load(urllib.request.urlopen('http://127.0.0.1:8080/admin/sports-chl'))['leagues']['ohl']['day'])")
HOCKEYTECH_KEY=fakepanel-ohl python3 -I tools/chl/record_live.py --league ohl --game 900001 --date $DAY \
    --firebase http://127.0.0.1:8080/firebase/svf/ --feed http://127.0.0.1:8080/hockeytech/feed/index.php --out /tmp/fake-ohl
CHL_LIVE_RECORDINGS=/tmp/fake-ohl ./gradlew :core:test --tests '*ChlLiveGateTest*' -i | grep -E 'polls|PASS|FAIL'
```

`fakepanel-ohl` is the fake panel's dummy key, not a HockeyTech key.
