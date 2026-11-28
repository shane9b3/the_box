# CHL hub fixtures (docs/design/m6-chl-hub.md)

Captured 2026-11-27 between 16:07 and 16:14 local (21:07–21:14 UTC). The requests went to the public HockeyTech
`lscluster` feed with the app's own per-league keys and a generic User-Agent. Every file has had its key removed:
`GC.Parameters.key` and `SiteKit.Parameters.key` are deleted, and no file holds a key or a keyed URL. A check before
writing failed on any 16-hex-digit token. In the request column below, `key=<league key>&client_code=C` is left out.

The files are pretty-printed (indent 1), so their sizes are larger than the raw bodies. Raw bodies were 4–92 KB, and
gzip on the wire was 1.2–6.8 KB (the sizes are in the design note's §1). The `statviewfeed` files keep the feed's JSONP
wrapper `( … )`, because the mapper must strip it.

| File | League | Request (`/feed/index.php?…`) | Game / state | Trimmed |
|---|---|---|---|---|
| `gc-gamesummary-final-ohl-29239.json` | OHL | `feed=gc&tab=gamesummary&game_id=29239&fmt=json&lang_code=en` | OTT 5 @ BAR 3, Final (2026-11-26) | lineups: first 5 skaters per side (all goalies kept); penalties: first 3; on-ice officials: 2, off-ice: 1; coaches: 1 per side; team address/phone/fax removed; `*_notes` blanked |
| `gc-gamesummary-final-whl-1023337.json` | WHL | same, `game_id=1023337` | RD 4 @ CGY 3, Final (2026-11-25); `venue` carries the city; `totalShotsOn` 0 | same |
| `gc-gamesummary-final-ot-lhjmq-32952.json` | QMJHL | same, `game_id=32952` | Chi 5 @ NFL 4, Final OT1 (2026-11-26); hits tracked; period 4 | same |
| `gc-gamesummary-pre-ohl-29246.json` | OHL | same, `game_id=29246` | NIAG @ LDN, status 1 (before the game); `penalties` null, `mvps` `[null,null,null]` | lineups: 5 skaters |
| `gc-preview-pre-ohl-29246.json` | OHL | `feed=gc&tab=preview&game_id=29246&fmt=json&lang_code=en` | NIAG @ LDN, pre-game, first meeting (H2H all zero, `previous_meetings` []) | `last_season` removed; lineup players: 3; streak lists: 1 each |
| `gc-preview-pre-whl-1023348.json` | WHL | same, `game_id=1023348` | CGY @ MH, pre-game; 3 previous meetings; **`lineup` null**; no `lineup_pairing_report` | same |
| `gc-preview-pre-lhjmq-32953.json` | QMJHL | same, `game_id=32953` | Chi @ NFL, pre-game, the day after 32952 (1 previous meeting, OT win) | same |
| `gc-preview-after-final-ohl-29239.json` | OHL | same, `game_id=29239` | read after the final: the numbers are still **as of the game's start** (BAR losses 17, not 18) | same |
| `mk-topgoalies-ohl-t14.json` | OHL | `feed=modulekit&view=statviewtype&type=topgoalies&season_id=88&team_id=14&first=0&limit=10&fmt=json&lang=en` | London's 5 goalies | birth/home place, height, weight, link HTML removed |
| `mk-topgoalies-whl-t206.json` | WHL | same, `season_id=295&team_id=206` | Medicine Hat, 2 goalies | same |
| `mk-topgoalies-lhjmq-t2.json` | QMJHL | same, `season_id=214&team_id=2` | Newfoundland, 2 goalies; names "Last, First" | same |
| `svf-teams-ohl.json` | OHL | `feed=statviewfeed&view=teams&groupTeamsBy=league&context=overall&season=88&special=false&sort=points&lang=en` | league table, 20 teams (GF, GA, GP, streak, past_10) | none (JSONP kept) |
| `svf-teams-special-ohl.json` | OHL | same with `special=true&sort=power_play_pct` | PP%, PK% per team ("29.7%" strings) | none |
| `svf-teams-special-whl.json` | WHL | same, `season=295` | 23 teams | none |
| `svf-teams-lhjmq.json` | QMJHL | `special=false`, `season=214` | 18 teams | none |
| `gc-pxpverbose-excerpt-ohl-29239.json` | OHL | `feed=gc&tab=pxpverbose&game_id=29239&fmt=json&lang_code=en` | play by play, not used in v1 (shot x/y, shot type) | 12 of 147 events: the first of each kind and every goal |

Not captured, or captured and left out:
- `feed=statviewfeed&view=gameSummary`: the same facts as `gc` `gamesummary`, plus a headshot URL pattern
  (`https://assets.leaguestat.com/ohl/240x240/{player_id}.jpg`). The mapper does not read it, so it is not kept.
- `modulekit&view=statviewtype&type=standings&stat=all`: an empty list for the OHL (461 B). Not used.
- No shootout game. The shape of `shootoutDetail` is still unverified.

## Live captures (CHL hub fix pass, 2026-11-27 evening)

Captured during the OHL's Friday 7 PM ET slate with the app's OHL key and a generic User-Agent, written key-free the
same way as above (`GC.Parameters.key` deleted; a check before writing failed on the key, any 16-hex-digit token or
`key=`), trimmed as the finals above (5 skaters per side, all goalies; penalties 3; officials 2 + 1; coaches 1). Kept
only to record the live shape for §7; the app makes no live detail request. `ChlDetailMapperTest.capturedLiveBodiesMapAndAreLive`
maps each one.

| File | Request | Game / state |
|---|---|---|
| `gc-gamesummary-live-ohl-29246.json` | `feed=gc&tab=gamesummary&game_id=29246&fmt=json&lang_code=en` | NIAG @ LDN (the pre-game fixture's game), 1st period in progress: `meta.status` "2", `final` "0", `started` "1", `game_clock` "00:16:43", `status_value` "In Progress (16:43 remaining in 1st)", `periods` only "1", no goals yet, `goalsByPeriod` 0–0. The same top-level keys as the finals. |
| `gc-gamesummary-int-ohl-29241.json` | same, `game_id=29241` | NB @ BRAM in the 1st intermission: `meta.status` still "2", `period` "1", `game_clock` "00:00:00", `status_value` "In Progress (0:00 remaining in 1st)"; **no intermission flag** in `meta`; 0–0, so **`goals` is `null`** (not `[]`). |
| `gc-gamesummary-live-goals-ohl-29248.json` | same, `game_id=29248` | OSH @ SOO, 1st period 3:55 left, 1–2 with 3 goal rows (`period_id` "1"), `shotsByPeriod` 8–11. |

No live game was in a shootout, so `shootoutDetail` is still unverified. The intermission reads as status 2 with the
clock at 0:00; the mapper (no clock input) calls it LIVE.
