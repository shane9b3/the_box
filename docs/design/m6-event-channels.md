# Event channels and team channels: names as listings, a stricter channel link, the guide horizon (design)

Status:
- **Proposed 2026-11-25** (proposer, Opus).
- **Reviewed 2026-11-25**: `docs/review/2026-11-25-event-channels-design-review-arch.md` (B1–B5, S1–S13, N1–N6) and
  `docs/review/2026-11-25-event-channels-design-review-ux.md` (U-E-B1–B4, U-E-S1–S10, U-E-N1–N6).
- **Revised 2026-11-25** (this version) on the lead's rulings and the owner's final rulings of 2026-11-25. Every finding
  is applied in place and marked with its id; §15 "Review responses" lists each one with what changed and where.
  Waiting for the lead's sign-off before commit 0.

Inputs:
- `CLAUDE.md`; `docs/00-master-plan.md`; `docs/01-design-reference.md` (1 design px = width ÷ 1920).
- `docs/design/m6-sports-hub.md` §3–§5, §8.1, §15. This design is written against the hub **after** the lead's
  in-flight change: no bottom hint row and no credit, panel 720 × 864 (`HubPanelPlan.PANEL_H = 864`, inner width 664),
  sheet frame 968 × 864, grid 732; and against `b37512c` (Today resolved in the morning by `SportsViewModel`). It
  specifies no footer hints (arch S11).
- The lead's facts file (session scratchpad `events/FACTS.md`) with the owner's request, a read-only copy of the
  owner's `library.db` (playlist refresh 14, Nov 25 02:55 UTC) and of the owner's newest `epg.db` (generation 10, built
  Nov 25 06:18 UTC). Every number here is measured on those copies by throw-away scripts in the session scratchpad
  (`evscripts/proto_live.py`, `evscripts/b1.py`; the reviewers' scripts are in `review/`). **Counts are over live rows
  only** (`missing_since IS NULL`, 13,109 rows; arch S9). The doc quotes channel names as format examples. It contains
  no URL, credential, id or bulk dump.

Words on screen: the existing set. New: *team channel*, *channel name*, *event*. Never "parse", "EPG", "regex", "slot",
"overlay", "horizon", "filler". Contractions and plain verbs, as in the hub (U-S11).

---

## 0. Decisions at a glance

| Topic | Decision |
|---|---|
| "Local channel, may differ" | **Removed**, with every regional guess. A regional (home / away) broadcaster alias is no longer a candidate on its own. It only seeds the guide window read (hub §5.2 C), so a regional channel appears **only when its guide names the game** (or the sport). The four strings, the generic "May carry" string and the sheet's footer note are deleted. |
| Team channels | A channel is a team channel when (1) its **name equals** a team's name: folded, with the country tag, quality words and an optional leading league word removed; and (2) either its **provider group** has the adjacent words "<league> Teams", with any prefix, or its `tvg_id` is `<TeamStem>.<league>` for the same team. Both teams' channels are CONFIRMED. They lead the list, the user's team first, and are **never capped** (U-E-B3). |
| Event channels | A name ending in a date and time (five shapes, §3) yields an **event**: title, start, estimated end and zone source. Events are **derived at read time** into an in-memory `EventIndex` that is rebuilt once per playlist refresh (§5; arch N1 adopted). |
| Short names (U-E-B2) | Event and empty-slot channels show their parsed **slot** ("Sportsnet+ 09") wherever a channel name is drawn. It is stored through the **existing `display_name` column** (time-independent; `DisplayNames` backfill, `RULE_VERSION` bump). A custom name always wins. Search still matches the raw text, because `channel_fts` indexes `name`. |
| Empty slots | Owner ruling: a per-playlist switch, **Hide channels with no event**, off by default. It is applied through the existing `rule_hidden` pass. In the guide, an empty slot keeps "No information" cells; its panel says "No event scheduled". |
| Guide | A read-time **overlay** in the guide pager. On an event channel, the event is drawn as a programme. Provider placeholder listings ("No Event Today", "Next Event: …", "Signing Off") are **clipped around it**. A real listing that overlaps the event wins (§6.1, measured in §2.3). Nothing is written to `epg.db`. |
| Search | Events come from the in-memory index (prefix match on title and subtitle; a team's nickname also finds its city). Placeholder listings are dropped from programme results. An event and a guide listing on the same channel within 15 min of each other are shown once, as the listing (§7). |
| Sports matcher | Two new stages: **T** (team channels) and **E** (events whose two sides name the game's two teams **by exact name**, starting 90 min before to 45 min after the game). Placeholder listings never count as guide evidence. Watch stays "Channels (n)", with the user's team channel focused first (owner ruling). |
| Outside the guide download | The Channels slot is **grey** and no channel search runs when the game starts at or after `max(guide data end, now + 12 h)`. The guide data end is the median last programme end over guide channels that hold at least one real listing. The line names the two team channels when they exist (owner ruling). |
| Zones | A zone word in the name wins. ISO-dated names use their country tag, and the US-college pipe shape is Eastern. Otherwise the playlist's **learned zone** applies: the most common explicit zone among its live event names, with hysteresis. Once learned, it never falls back to the panel's zone. No setting in v1. |
| Freshness | The **default** update interval of a playlist with 50 or more events is 6 h. It shows in the Update interval row and never overrides Manual or an interval the user picked. |

---

## 1. Scope and non-goals

In scope:
1. The regional wording and guesses go (§8.1, §9).
2. Team channels for the NHL and the NFL (§8.2).
3. The guide horizon for the hub's Channels slot (§8.5).
4. Event parsing (§3), the event index and short names (§5), the guide (§6), search (§7) and the matcher (§8.3, §8.4).
5. The hide switch and the refresh default (§5.5, §10).
6. Reminders on event cells that moved (§6.6).
7. Fake-panel support and the emulator states (§11.3, §11.4).

Non-goals (v1):
- Matcher tables for leagues other than NHL and NFL. The parser, guide and search cover every sport.
- The shapes listed as not handled in §3.6.
- Refreshing only the event categories (Xtream `get_live_streams&category_id`): v2.
- Catch-up on an event cell (the end is estimated and the zone may be assumed; §6.4).
- A setting for the assumed zone, or a "read events from names" switch (§15, arch N4).
- Any change to `RowCellsBuilder`, `GuideWindowData` or the drawing code. The renderer gains one flag test for catch-up
  (arch S6).

---

## 2. What the owner's data holds (live rows, refresh 14)

### 2.1 Names

| Kind | Channels | Notes |
|---|---:|---|
| Shape A `… @ D Mon hh:mm AM/PM zone` | 456 | Zone ET 288, London 108, none 60 (F1 TV 54, MonoMAX 3, Ligue 1 3). The `\|` slot separator (`MLS 03 \| …`) is shape A's (arch N6). |
| Shape B `… (M.D h:mm AM/PM ET)` | 430 | All ET. One has a 24 h clock and a second zone. |
| Shape C `… (YYYY-MM-DD HH:MM[:SS])` | 419 | Tagged CA 107, AU 100, UK 50. Untagged 162: Fanatiz 99, PDC 50, NBA LP 10, Setanta 2, NFL GP 1. |
| Shape D `… [Wkd] @ Mon D h:mmAM/PM zone` | 126 | ET 108, AEST 17, none 1. |
| Shape E `… \| M.D h:mm AM/PM \| NET` | 112 | No zone word. Eastern by cross-check. |
| **Events parsed** | **1,543** | Zone assumed for 223 (A 60, C 162, D 1). |
| Empty: `NO EVENT` | 1,955 | |
| Empty: bare `NN:` / `NN :` | 1,505 | |
| Empty: `… \| Offline` | 262 | |
| Empty: shape C with year 2098 or no title | 44 | |
| **Empty slots** | **3,766** | |
| A time but not handled (§3.6) | 85 | EFL stale (September) 35, EFL/UEFA current 23, TV2 "I dag" 17, QMJHL weekday + ordinal 9, "LIVE EVENT 02 - 7:45pm …" 1. |
| No event shape | 7,715 | Ordinary channels, including 32 NHL and 32 NFL team channels. |

1,099 more shape C rows (FloSports 999, Victory+ 100) are missing rows: their Nov 19 names are gone since Nov 24. Every
query, tally and index here excludes `missing_since` rows.

A SQL prefilter `name GLOB '*[0-9]:[0-5][0-9]*'` returns exactly 1,672 live rows: the 1,543 events, the 44 placeholders
and the 85 unhandled names. Every handled shape carries a clock, so the index reads only these rows (§5.2).

### 2.2 Zones

- Explicit zone words: **ET 826**, London 108, AEST 17.
- Tagged and Eastern-convention shapes check out exactly against the NHL schedule:
  - the UK-tagged `Blue Jackets @ Devils (2026-11-24 00:00:00)` is 00:00Z for all 7 games of Nov 23;
  - shape A and B ET names give VGK at EDM at Δ 0 (`NHL 01`, `Sportsnet+ 09`) and at Δ −30 (`NHL on TNT`);
  - the CA-tagged TSN+ shape C names equal their shape A ET twins.
- Untagged shape C names run on these clocks:
  - Fanatiz: Eastern (Ligue 1 at 14:45 is 20:45 CET);
  - PDC: Eastern (14:00 is the 19:00 UK evening session);
  - NBA LP and NFL GP: UTC.
- So the learned zone (Eastern) is right for 149 of the 162 untagged names and wrong for 11. The panel's zone
  (`Europe/London`) would be right for 11.

### 2.3 What the provider's guide holds for event channels (arch B1)

777 parsed events sit on channels with a status-0 guide mapping that has data. All 777 map to the provider's own
`BossSports.<group>.<slot>` guide channels. Another 84 are mapped but have no programmes, and 682 have no mapping.

The BossSports guide is almost all **placeholder listings**: 4 h blocks titled "No Event Today" (19,276 programmes),
"Signing Off" (721) and "Next Event: <title> at <h:mm>PM EST on <Mon D>" (~400). Real listings are rare (183 channels
have one).

**What overlaps the name's event**, measured over `[start, start + 180 min)` on each of the 777 channels:

| Over the event | Channels |
|---|---:|
| Only "No Event Today" | 540 |
| Only "Signing Off" or "Next Event: …" | 24 |
| Nothing | 59 |
| A real listing, alone or with placeholders | 154 |

**Who wins between the name and a real listing.** Of the 154 overlapping real listings:
- 146 start at **exactly** the name's start. 112 share the title words; 34 spell them differently, such as "FAU" against
  "Florida Atlantic" or "SIUE" against "SIU Edwardsville".
- 8 start 15–75 min away and name another event. These are DAZN UK slots the provider reshuffled between the playlist
  fetch (02:55Z) and the guide build (06:18Z).

**What the real listings look like.** They are fixed 4 h blocks (210 min once). The one for `MAX 002: NHL on TNT:
Golden Knights at Oilers` carries the `ProgrammeFlags` bit 11, and the next day's real listing on `BossSports.NHL V2.01`
("Devils at Blue Jackets") carries the LIVE flag and a description.

**What this means for the design.**
- The guide is **rebuilt over the past**. At 06:18Z the slot that carried VGK at EDM the evening before lists "Next
  Event: Devils at Blue Jackets …" for all of Nov 24: the provider's guide keeps no history for an event slot.
- **Stage B is affected.** The "Next Event:" blocks name both teams, so stage B would CONFIRM a channel with a 4 h
  placeholder at the wrong time ("Next Event: NHL on TNT: Golden Knights at Oilers at 07:30PM EST on Nov 24" × 5
  blocks).
- **Placeholders exist elsewhere too.** Outside BossSports there are 207 "Signing Off", 14 "Upcoming - …" and 11 "No
  Event Today" programmes.

### 2.4 Guide depth

The owner's guide (built Nov 25 06:18Z) reaches **Nov 26 06:00Z**, about 24 h ahead. That is the median last end over
the 4,575 guide channels that hold at least one real listing. Counting the 3,266 channels that hold only placeholders
moves the median to Nov 26 00:07Z, six hours earlier, because BossSports placeholder blocks stop at Nov 25 08:00Z. All
7,841 guide channels with programmes are mapped to a live library channel (the import whitelist), so no "mapped only"
filter is needed (arch N5, §8.5).

### 2.5 Refresh cost (refresh_log on the owner's device)

| Refresh | Renames | Additions | Duration | EPG patch afterwards |
|---|---:|---:|---:|---|
| A busy one | 1,553 | 0 | 29 s | **none**: the next EPG entry is a NOT_MODIFIED build check |
| One with additions | — | 639 | — | one patch (7 s, matching the 639 additions) |
| A quiet one | 24 | 0 | 9 s | none |

So renames alone did not queue an EPG patch in this log (arch S7; §10 keeps a gate anyway).

---

## 3. The parser (`EventNameParser`, `:core`, pure)

### 3.1 Rules

- **Pure and table-driven.** Plain string scanning, **no `java.util.regex`** (the hub's rule: Android's ICU regex differs
  from the JVM's).
- **Never throws.** Any exception inside returns `ParsedName.None`. A fuzz test feeds it 10,000 strings.
- **Right-anchored.** It reads the tail first and fails after one comparison on a name that cannot end a handled
  shape.
- **Input is the provider name** (`channel.name`), never the custom name or `display_name`.
- `VERSION = 1`. It keys the event index (§5.2) and `ChannelNamePrefix.RULE_VERSION` (§5.3). Bumping it rebuilds both.
- **Three entry points:**
  - `parse(name, ctx)` returns the full result;
  - `slotOf(name)` returns the slot of an event or empty-slot name. It needs no clock and no zone, so it can be stored in
    `display_name`;
  - `isEmptySlot(name)` needs no clock either, and feeds the hide switch.

### 3.2 Grammar

`ws` is one or more spaces or tabs. Words are case-insensitive. Brackets are literal.

```
name      := [ slot sep ] title tail                       (the slot is optional only for shape C)
slot      := label ws? num | "(" cc ")" ws "(" label num ")" | cc ws "(" label num ")" | "(" label num ")"
             label: no ':' and no '|'; num: 1–3 digits
sep       := ws? (":" | "|") ws?                            "Sportsnet+ 01:", "Sportsnet+ 29 :", "MLS 03 |", "NFL GP 01 |"
cc        := "CA" | "US" | "USA" | "UK" | "GB" | "AU"

tail A    := ws "@" ws [wkd ws] day ws mon ws clock [ws zone]
tail D    := ws [wkd ws] "@" ws mon ws day ws clock [ws? zone]
tail B    := ws? "(" n1 "." n2 ws clock [ws zone] [ws? "/" any] ")"
tail E    := ws? "|" ws? n1 "." n2 ws clock [ws zone] [ws? "|" ws? net]
tail C    := ws? "(" yyyy "-" mm "-" dd ws hh ":" mi [":" ss] ")"

clock     := h ":" mi [ws? ampm]          ampm := ("A" | "P" | "O") "M"   ("OM" reads as PM: O is next to P)
mon       := Jan … Dec, Sept, each with an optional "."
wkd       := Mon … Sun, optionally followed by "," or "."
zone      := a word in the §3.3 table
net       := text without "|"             (becomes the subtitle: "ESPN+", "SECN+")

empty     := slot sep ( "NO EVENT" | "" | "Offline" )       → Empty
           | tail C with yyyy = 2098 or an empty title       → Empty
```

**Validity rules.**
- Month 1–12; the day must exist in the chosen year (§3.4).
- With `ampm`, h is 1–12. Without it, h is 0–23 (a 24 h clock). mi is 0–59.
- Anything else is `None`, never a guessed fix.
- An empty title in shapes A, B, D or E is `Empty`.

**n1.n2 order (arch S12).** Shapes B and E read `n1.n2` as month.day. When the zone is European (London, UK, BST, CET,
CEST, GMT, UTC) and both readings are valid dates, the parser keeps the one nearer the refresh time.

**Title clean-up** (the display text; the matcher reads the same text):
- runs of spaces collapse to one;
- a trailing `@`, `-` or `|` is trimmed;
- the backtick in names like ``Women`s`` becomes `’`;
- a leading lower-case scraper id followed by `": "` (`flohockey: `, `uefa-champions-league: `) moves to the subtitle,
  unless shape E already set one.

### 3.3 Zones

| Source | Zone | `EventZoneSource` |
|---|---|---|
| `ET`, `EST`, `EDT` | `America/New_York` (rules by date; providers write EST in July) | EXPLICIT |
| `CT`/`CST`/`CDT`, `MT`/`MST`/`MDT`, `PT`/`PST`/`PDT` | `America/Chicago`, `America/Denver`, `America/Los_Angeles` | EXPLICIT |
| `UTC`, `GMT`, `Z` | UTC | EXPLICIT |
| `London`, `UK`, `BST` | `Europe/London` | EXPLICIT |
| `CET`, `CEST` | `Europe/Paris` | EXPLICIT |
| `AEST` / `AEDT` | fixed +10:00 / +11:00. Kayo writes AEST in November and means +10: `Wolves v Derby County @ Nov 25 5:52AM AEST` is 19:52 GMT for a 19:45 kick-off. | EXPLICIT |
| Shape C tag `CA` / `US`,`USA` / `UK`,`GB` / `AU` | `America/Toronto` / `America/New_York` / `Europe/London` / `Australia/Sydney` | CONVENTION |
| Shape E with no zone word | `America/New_York` | CONVENTION |
| none of the above | the playlist's learned zone | ASSUMED |

**Learned zone** (arch B2: built from the whole playlist, with hysteresis). It is computed in `EventIndexImpl` (§5.2)
from **all live rows** of the playlist, so a quiet refresh cannot move it.

1. **Tally.** In pass 1, count the EXPLICIT zone ids.
2. **Choose.**
   - With a stored zone `Z`, keep it unless the new majority `M ≠ Z` has at least 20 names **and** at least twice
     `Z`'s current count.
   - With no stored zone, take `M` once it has at least 20 names.
   - Until then, use the panel's zone (`playlist.server_zone`), else UTC. That fallback is never stored.
3. **Store.** Write `playlist.event_zone` and `event_zone_n` only when they change.
4. **Resolve.** In pass 2, resolve the ASSUMED names with the chosen zone.

Once a zone is learned, the panel's zone is never used again (owner ruling). For the owner, `M` is ET with 826 names.

### 3.4 Year (arch S3)

Shapes A, B, D and E carry no year. `now` is the playlist's `last_refresh_at`, the moment the names were fetched,
taken in the event's zone. The parser tries the years `y − 1`, `y` and `y + 1`, keeps the valid dates whose instant lies
in **[now − 300 days, now + 90 days]**, and takes the nearest to `now`. If none lies there, the result is `None`.

The window is lopsided on purpose:
- Live future listings reach +60 days (Fanatiz to Jan 23).
- Stale live names reach −155 days (`Sports | NCAA Baseball`) and must stay in the past, not jump to next year.

Examples:
- "31 Dec" read on Jan 1 gives the past Dec 31.
- "1 Jan" read on Dec 30 gives the next Jan 1.
- A June name read in November gives the past June.

Shape C uses its own year; the year 2098 means an empty slot.

### 3.5 Estimated end (`EventDuration`, `:core`)

| Title tokens | Minutes | Why |
|---|---:|---|
| A matchup (§3.7) and a football word (`nfl`, `ncaaf`, `college football`, `cfl`) | 210 | NFL broadcasts run 3 h 15 to 3 h 30. |
| A matchup and a soccer word (`uefa`, `champions league`, `premier league`, `epl`, `efl`, `la liga`, `ligue 1`, `bundesliga`, `serie a`, `mls`, `fc`, or the ` v ` separator) | 135 | 90 min, half-time, stoppage time and a short pre-game. |
| Any other matchup | 180 | An NHL broadcast with pre-game runs 2 h 45 to 3 h. |
| No matchup (a show) | 60 | A short block is less wrong than a long one. |

The guide shows the end as "about" (U-E-N2, §6.4).

### 3.6 Formats handled and not handled (live counts)

| # | Shape | Example (real names) | Live | Handled |
|---|---|---|---:|---|
| 1 | A, ET | `Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET` | 288 | yes |
| 2 | A, London | `UEFA Champions League 01: Galatasaray - Aston Villa @ 24 Nov 05:00 PM London` | 108 | yes |
| 3 | A, no zone | `MonoMAX 01: TAURON Liga Energa Trefl Gdańsk vs NEONET Norwid Częstochowa @ 24 Nov 02:50 PM` | 60 | yes, ASSUMED |
| 4 | A, "OM" | `Sportsnet+ 23: Montreal @ Colorado @ 25 Nov 09:30 OM ET` (owner's example, Nov 25) | 0 in the copy | yes (PM) |
| 5 | A, `\|` separator | `MLS 03 \| Nashville SC vs. FC Cincinnati (English) @ 22 Nov 05:10 PM ET` | 10 (counted in A) | yes |
| 6 | B | `NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)` | 430 | yes |
| 7 | B, 24 h and a second zone | `Pie Per View 1: Arsenal vs. Dortmund [AMZ] (11.24 14:00 ET / 19:00 UTC)` | 1 | yes (the first zone) |
| 8 | C, tagged | ``CA (TSN+ 008) \| NCAA Men`s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)`` | 257 | yes, CONVENTION |
| 9 | C, untagged | `NBA LP 01 \| Grizzlies @ Hornets (2026-11-24 00:00:00)` | 162 | yes, ASSUMED |
| 10 | C, placeholder | `(CA) (CBC 13) \|  (2098-12-31 08:00:01)` | 44 | Empty |
| 11 | D | `ESPN+ 26: Long Beach State vs. Arkansas State @ Nov 24 3:30PM ET` | 126 | yes |
| 12 | E | `NCAAB 081: 9 TEXAS TECH @ 23 LOUISVILLE (M) \| 11.24 8:00 PM \| ESPN` | 112 | yes, CONVENTION |
| 13 | NO EVENT | `Sportsnet+ 11: NO EVENT` | 1,955 | Empty |
| 14 | bare colon | `NHL 02:`, `Sportsnet+ 29 :` | 1,505 | Empty |
| 15 | Offline | `NFL GP 02 \| Offline` | 262 | Empty |
| 16 | "Home HH:MM Away DD/MM" | `EFL03: (CH) Cardiff 19:45 Preston 24/11` | 58 (35 stale) | **no**: owner default |
| 17 | relative day | `TV2 NO 19: UEFA Champions League Porto - Napoli I dag 20:00` | 17 | **no**: Norwegian "today" only |
| 18 | weekday + ordinal | `QMJHL 18: Charlottetown @ Rimouski - #529 \| Sat 8th 9:00PM` | 9 | **no**: no month (stale, Nov 8) |
| 19 | time only | `LIVE EVENT 02 - 7:45pm USAC Jason Leffler Memorial Kern` | 1 | **no**: no date |

### 3.7 Matchup sides (`EventMatchup`, `:core`; arch S1, S2)

1. Split the title into segments on `": "` **and** `" | "`. Take the **last** segment that contains a separator; if
   none does, use the whole title. Example: `NHL.TV | Kings @ Canadiens` gives `Kings @ Canadiens`.
2. Separators, the first one found from the left wins: `" @ "`, `" at "`, `" vs. "`, `" vs "`, `" v. "`, `" v "`,
   `" - "`, `" – "`, `" — "`. `@` and `at` are **ordered** (the away side comes first); the others are not.
3. If the segment holds two or more matchups (`•`, or `" / "` between two separators), there are no sides.
4. Each side is cleaned for matching:
   - bracketed parts are dropped;
   - `#n` ranks, a leading number token (`9 TEXAS TECH`) and a leading `<year>? week <n>` are dropped;
   - sport and league words (`nhl`, `hockey`, `nfl`, `football`, `the`) are dropped.

   The result is `Matchup.first` and `Matchup.second`, as folded token lists.

---

## 4. Contracts (commit 0)

### 4.1 `:core` — `core/src/main/kotlin/app/thebox/core/events/EventContracts.kt` (new)

```kotlin
package app.thebox.core.events

/** How an event's start zone was found (§3.3). */
enum class EventZoneSource { EXPLICIT, CONVENTION, ASSUMED }

/** Which tail matched (§3.2). Tests and logs only. */
enum class EventShape { AT_DAY_MONTH, AT_MONTH_DAY, PAREN_MONTH_DAY, PIPE_MONTH_DAY, ISO }

/** [nowUtcMs]: `playlist.last_refresh_at`; [assumedZone]: the learned zone (§3.3). */
data class EventParseContext(val nowUtcMs: Long, val assumedZone: java.time.ZoneId)

sealed interface ParsedName {
    data object None : ParsedName
    /** "NO EVENT", "NHL 02:", "Offline", the 2098 placeholder. */
    data class Empty(val slot: String?) : ParsedName
    data class Event(
        /** "Sportsnet+ 23", "CA (TSN+ 008)"; null for a slot-less shape C name. */
        val slot: String?,
        /** Cleaned (§3.2), never blank. */
        val title: String,
        /** Shape E's network or a moved scraper id. */
        val subtitle: String?,
        val startUtcMs: Long,
        /** startUtcMs + EventDuration (§3.5). */
        val endUtcMs: Long,
        val zone: EventZoneSource,
        /** The zone id used ("America/New_York", "+10:00"): the learned-zone tally reads it for EXPLICIT rows. */
        val zoneId: String,
        val shape: EventShape,
    ) : ParsedName
}

/** §3.7: folded, cleaned token lists. [ordered]: "@" / "at", so [first] is the away side. */
data class Matchup(val first: List<String>, val second: List<String>, val ordered: Boolean)

/**
 * What the guide, search and the matcher read for one channel. [startMin] / [endMin]: UTC epoch minutes, never shifted
 * by the channel's guide shift (a name carries a true instant).
 */
data class ChannelEvent(
    val kind: Kind,
    val title: String?,
    val subtitle: String?,
    val startMin: Int,
    val endMin: Int,
    val zone: EventZoneSource,
) {
    enum class Kind { EVENT, EMPTY }
    /** Part of the guide's row basis (§6.5, arch B5). */
    val basis: Long get() = app.thebox.core.common.Hash64.str(
        app.thebox.core.common.Hash64.long(app.thebox.core.common.Hash64.long(kind.ordinal.toLong(), startMin.toLong()), endMin.toLong()), title)
}

/** One indexed event (§5.2). [group]: the provider group's name. */
class EventEntry(
    val channelId: Long,
    val playlistId: Long,
    val slot: String?,
    val group: String?,
    val event: ChannelEvent,
)

/** An immutable index of every live event of the enabled playlists. */
interface EventSnapshot {
    /** Changes whenever any keying input changes (§5.2). */
    val version: Long
    fun of(channelId: Long): EventEntry?
    /** Entries whose start is in [fromMin, toMin], in start order (binary search). */
    fun startingIn(fromMin: Int, toMin: Int): List<EventEntry>
    /** Entries whose event ends after [nowMin] (search). */
    fun current(nowMin: Int): List<EventEntry>
    fun count(playlistId: Long): Int
}

interface EventIndex {
    /** Emits the snapshot version after every rebuild; the guide's rows cache reloads on it (§6.5). */
    val version: kotlinx.coroutines.flow.StateFlow<Long>
    /** The current snapshot, building it first if it is missing or stale (IO; under 100 ms on a Fire TV Stick, §5.2). */
    suspend fun current(): EventSnapshot
}
```

Function objects. Stub files in commit 0; WP-1 owns the bodies:

```kotlin
object EventNameParser {
    const val VERSION: Int = 1
    fun parse(name: String, ctx: EventParseContext): ParsedName
    /** The slot of an event or empty-slot name; null otherwise. No clock, no zone (§5.3). */
    fun slotOf(name: String): String?
    /** True for an empty slot (§3.2 `empty`). No clock, no zone (§5.5). */
    fun isEmptySlot(name: String): Boolean
}
object EventMatchup { fun of(title: String): Matchup? }
object EventDuration { fun minutes(title: String): Int }
/** §6.2: a provider placeholder listing. */
object EpgFiller { fun isFiller(title: String): Boolean }
```

### 4.2 `:core` — teams (`core/.../sports/TeamNames.kt`, `TeamChannelRule.kt`; stubs in commit 0, WP-1)

```kotlin
data class TeamName(
    val league: League,
    val abbrev: String,                 // the board's ("EDM"; NFL as ESPN gives it, "WSH")
    val fullNames: List<String>,        // current first, then former names of the same club: ["Utah Mammoth", "Utah Hockey Club"]
    val nicknames: List<String>,        // ["Oilers"], ["Golden Knights"]
    val places: List<String>,           // ["Edmonton"], ["Vegas", "Las Vegas"], ["St. Louis"], ["Montréal"], ["Los Angeles", "LA"]
    val tvgStems: List<String>,         // ["EdmontonOilers"], ["UtahMammoth", "UtahHockeyClub"]
)

object TeamNames {
    enum class Strength { FULL, NICKNAME, PLACE }
    fun of(league: League, abbrev: String): TeamName?
    fun all(league: League): List<TeamName>
    /** Folded tokens: "saint"/"st"/"st." → "st", "&" → "and", "l.a." → "la"; letters and digits only. */
    fun tokens(s: String): List<String>
    /** How [side] (cleaned tokens, §3.7) **equals** one of [team]'s names, or null (arch S2: equality, never containment). */
    fun sideMatch(side: List<String>, team: TeamName): Strength?
    /** True when [place] belongs to two clubs of [league] ("New York", "Los Angeles"). */
    fun placeShared(league: League, place: String): Boolean
}

object TeamChannelRule {
    fun groupIsTeams(league: League, group: String?): Boolean
    fun teamOf(league: League, name: String, group: String?, tvgId: String?): TeamName?
}
```

### 4.3 `:core` — the event rule and the horizon

```kotlin
// core/.../sports/hub/EventGameRule.kt (stub in commit 0, WP-1)
data class EventFacts(val name: String, val title: String, val group: String?, val startUtcMs: Long, val zone: EventZoneSource)

object EventGameRule {
    data class Grade(val confidence: MatchConfidence, val citiesOnly: Boolean, val zoneAssumed: Boolean)
    /** §8.4. [board]: the other games the hub has loaded for the day (the cities-only upgrade). Null: not this game. */
    fun grade(event: EventFacts, game: GameSnapshot, board: List<GameSnapshot>): Grade?
}

// core/.../sports/hub/ChannelHorizon.kt (complete in commit 0)
object ChannelHorizon {
    fun beyondGuide(startUtcMs: Long, guideEndMs: Long?, nowMs: Long): Boolean =
        startUtcMs >= maxOf(guideEndMs ?: Long.MIN_VALUE, nowMs + SportsConstants.HUB_LOOKAHEAD_MIN * 60_000L)
}
```

### 4.4 Amended contracts (commit 0)

- **`core/.../guide/GuideModel.kt`**
  - `GuideChannel` gains a last parameter, `val event: ChannelEvent? = null`, which is part of equality.
  - `CellFlags` gains **`const val EVENT_NAME: Int = 1 shl 27`**. This is a free bit: `ProgrammeFlags` uses 0–12,
    `COMPLEX` 28, `RTL` 29 and `SLIVER` 30 (arch B4).
  - **`epgBasisOf` folds in `ch.event?.basis`** (arch B5). The basis is the guide's invalidation key for a row, so a
    renamed event slot re-queries and redraws.
- **`core/.../epg/EpgContracts.kt`**: `EpgGenerationInfo.dataToMin: Int? = null` (§8.5).
  **`core/.../epg/store/EpgSchema.kt`**: `Meta.DATA_TO_MIN = "data_to_min"`.
- **`core/.../guide/ReminderContracts.kt`**: `ReminderAlert` gains `val movedFrom: String? = null` and
  `val eventGone: Boolean = false` (§6.6).
- **`core/.../sports/hub/HubContracts.kt`**:

```kotlin
sealed interface MatchReason {
    // Guide, SportListing, Broadcaster: unchanged.
    data class TeamChannel(val team: String, val nickname: String, val side: Side) : MatchReason
    data class EventName(val title: String, val startMs: Long, val citiesOnly: Boolean, val zoneAssumed: Boolean) : MatchReason
}
data class ChannelCandidate(
    /* existing fields unchanged */
    /** The provider group (duplicate short names, "Also in", §9.1). */
    val group: String? = null,
)
interface GameChannelMatcher {
    suspend fun match(game: GameSnapshot, nowMs: Long, access: ParentalAccess, board: List<GameSnapshot> = emptyList()): List<ChannelCandidate>
    fun clear()
    /** §8.5: the end of usable guide data (epoch ms), or null without a guide. */
    fun guideEndMs(): Long? = null
    /** §8.5: the two team channels of [game] only (stage T), for the beyond-guide line. */
    suspend fun teamChannels(game: GameSnapshot, access: ParentalAccess): List<ChannelCandidate> = emptyList()
}
```

- **`core/.../setup/SetupPorts.kt`**
  - The playlist settings snapshot gains `refreshIntervalAuto: Boolean`, `effectiveIntervalH: Int`, `eventCount: Int`
    and `hideEmptyEvents: Boolean`.
  - The admin port gains `suspend fun setHideEmptyEvents(playlistId: Long, on: Boolean)`.
  - `setRefreshInterval` also clears the auto flag (§10).
- **`core/.../setup/SetupText.kt`**: the §9.3 entries. **`core/.../guide/state/GuideTexts.kt`**: §9.2.
  **`core/.../sports/SportsConstants.kt`**: Appendix A.
- **`ui/.../sports/HubPanel.kt`**: `MatchState.BeyondGuide(val teams: List<ChannelCandidate>, val noGuide: Boolean)`.
  The stub branch disables Channels and draws no line.

### 4.5 Storage (Room v5 → v6, `MIGRATION_5_6`, hand-written, `ADD COLUMN` only)

`playlist` gains:

| Column | Type | Meaning |
|---|---|---|
| `hide_empty_events` | INTEGER NOT NULL DEFAULT 0 | the owner's switch (§5.5); backed up (`MergePlanner.PLAYLIST_COLUMNS`) |
| `refresh_interval_auto` | INTEGER NOT NULL DEFAULT 1 | true until the user picks an interval (§10). The migration sets it to 1 where `refresh_interval_h = 24` (the default) and 0 elsewhere. Backed up. |
| `event_zone` | TEXT | the learned zone (§3.3); not backed up (derived) |
| `event_zone_n` | INTEGER NOT NULL DEFAULT 0 | its sample count at the last change |
| `event_count` | INTEGER NOT NULL DEFAULT 0 | live events after the last index build; read by the scheduler at app start |

No `channel` column, no index and no event backfill: the arch N1 decision (§5.1).

---

## 5. Where events live

### 5.1 Decision: derived at read time, short names through `display_name` (arch N1, lead ruling 3)

| Need | Mechanism | Why |
|---|---|---|
| Event title, start, end, zone | In-memory `EventIndex` built from the 1,672 prefiltered rows | It changes every refresh and depends on `now` and the learned zone. Read-time derivation removes the B2 tally bug (the index always sees every live row), the B3 version race (no per-row version) and six columns, an index, a backfill and a `finish` re-parse. |
| Short name "Sportsnet+ 09" | The existing **`display_name` content column**: `ChannelNamePrefixImpl.strip` returns `EventNameParser.slotOf(name)` first | Every surface already reads `COALESCE(custom_name, display_name, name)`: guide labels, search cards, hub lines, the player's info bar, zap lists and the editor. The slot needs no clock and no zone, so storing it is safe. `RoomMergeSink.bindContent` already binds `display_name` on insert and update. `DisplayNames.backfill` (the call is already in `TheBoxApplication`, under `ImportMutex`, paged, writing only changed rows; only it raises the version) re-derives every row after the `RULE_VERSION` bump. |
| Hide empty slots | The existing **`rule_hidden` pass** with one built-in matcher | Hiding must happen in SQL (lineup predicate) for every surface. `RuleHiddenPass.run` already runs over the whole playlist in `finish` and on rule changes, under the merge's lock. |
| Matcher time window | `EventSnapshot.startingIn` (binary search over a sorted array) | Replaces a SQL index. |
| Search | `EventSnapshot.current` plus a prefix match | A full-text search on names returns thousands of "NO EVENT" rows for "nhl" (arch N3). Scanning ≤ 1,543 titles in memory is exact and cheap. |

**Cold-start cost** on a Fire TV Stick:
- one indexed read of `channel` with the GLOB prefilter: 1,672 of 13,109 rows, 0.12 s on the Mac including process
  start, estimated ≤ 40 ms on the device;
- ~1,700 parses at ~5–20 µs each: ≤ 35 ms;
- the tally and the sort.

That is under 100 ms, built eagerly after each refresh and at app start (§5.2), so a first guide, search or hub use
rarely waits.

**Rejected: the six stored columns.** They would add a migration of the 14k-row table, a backfill with its own version
race (B3), whole-playlist tallies in `finish` (B2) and an index, all for data that is rewritten every refresh and whose
year depends on `now`.

### 5.2 `EventIndexImpl` (`:data`, new; bound in `GuideDataModule`)

- **Key:** for each enabled playlist, `(id, refresh_seq, last_refresh_at, event_zone)`, plus `EventNameParser.VERSION`.
  The index is rebuilt when the key changes. `PlaylistDao` flows signal the change, and the refresh path calls
  `rebuild()` after `finish` (§10).
- **Read:**

  ```sql
  SELECT c.id, c.playlist_id, c.name, g0.name
  FROM channel c
  LEFT JOIN channel_group g0 ON g0.id = c.group_id
  WHERE c.missing_since IS NULL AND c.kind IN (0, 1) AND c.name GLOB '*[0-9]:[0-5][0-9]*'
  ```

  There is no lineup or parental predicate here. The index is metadata keyed by row id, and each consumer filters
  visibility in its own query (§7, §8).
- **Pass 1:** parse with a placeholder zone and tally the EXPLICIT zone ids per playlist.
- **Then** choose the learned zone (§3.3).
- **Pass 2:** re-parse only the ASSUMED names with the learned zone.
- **Build:**
  - sorted `IntArray` starts with parallel entry arrays, and a `LongObjectMap` by id;
  - write `event_count`, and `event_zone` when it changed;
  - emit `version`.
- **Concurrency:** builds are serialised (one `Mutex`). A build runs on IO and publishes an immutable snapshot. Readers
  never block on a build except the first one after process start.
- **Warm-up:** `TheBoxApplication` calls `eventIndex.current()` right after the display-name backfill (`:app`, arch S8).

### 5.3 Short names (U-E-B2)

`ChannelNamePrefixImpl.strip(name, group, rule)` returns `EventNameParser.slotOf(name)` when it is non-null, for every
prefix rule, including the "off" rule. Otherwise it applies the existing prefix logic. `ChannelNamePrefix.RULE_VERSION`
goes up by one.

| Case | Shown name |
|---|---|
| A custom name is set | the custom name (`COALESCE` order, unchanged) |
| Shape C with no slot (`flowrestling: …`) | the existing prefix logic, else the raw name |
| Two channels of one Channels sheet share a short name (`NHL 01` in `Sports \| NHL` and `Sports \| NHL (2)`) | the sheet's third line shows the group (§9.1) |
| Search by raw text ("Golden Knights", "Sportsnet+") | still matches, because `channel_fts` indexes `name` and `display_name` never touches it (`DisplayNames` header) |

### 5.4 `GuideChannel.event`

`GuideQueries.channels(rowIds, …)` adds `c.name` to `BLOCK_SELECT` and fills `event` as follows:
- with an index entry: `EventSnapshot.of(id)?.event`;
- else, when `EventNameParser.isEmptySlot(c.name)`: `ChannelEvent(EMPTY, …)`;
- else: null.

It calls `eventIndex.current()` once per page.

### 5.5 Hide channels with no event (owner ruling a)

- **Where:** a toggle in the playlist's settings page, `Hide channels with no event` (§9.3). It is off by default and
  shown on every playlist page. Its description says what it hides, so no per-page count query is needed. Showing it
  only when empty slots exist would need a count that the index does not hold (empty slots carry no clock, §2.1).
- **Toggling:** `setHideEmptyEvents` stores the column, then runs `RuleHiddenPass.run` under `ImportMutex`.
- **The built-in matcher:** when `hide_empty_events = 1`, `RuleHiddenPass` hides channels where
  `EventNameParser.isEmptySlot(name)`, next to the user's own name rules. The disable-on-slow logic does not apply: it
  is not a regex.
- **When a slot changes:** an empty slot that gets an event name at a refresh is recomputed in `finish` and shows
  again.
- **Favourites:** a favourite stays visible, because user data outranks rules, as for every hide rule.

---

## 6. Guide

### 6.1 Overlay in the pager (`GuideWindowPagerImpl.build`, `box-guide-epg` thread)

After `rd.window(...)`, or with **no reader** at all (arch S10: `build` no longer returns early for event rows):

1. **Fast path.** If no channel in the request has `event?.kind == EVENT`, nothing changes.
2. **Merge.** Otherwise, the request's rows are merged into a reused second `EpgWindow` (`merged`). For each channel
   with an event:
   1. **Clip placeholders.** Every guide row of that channel whose title is a placeholder (`EpgFiller.isFiller`) and
      that overlaps `[event.startMin, event.endMin)` is clipped to the parts outside the span. Pieces shorter than
      1 min are dropped. A split row's tail keeps its `rawStartMin`, so `info()` still finds it.
   2. **Real listing wins.** If any **real** guide row overlaps the span, the event is **not** inserted.
   3. **Insert.** Otherwise one row is inserted, in start order, with the event's own minutes (no guide shift),
      `rawStartMin = startMin`, `flags = CellFlags.EVENT_NAME`, `textId = EVENT_TEXT_ID` (a constant −2, arch N2;
      negative ids are never cached) and `titles[i]` = the title.
3. **Row status.** The row is never `noEpg` when an event was inserted. Its other time becomes GAP or placeholder cells
   as the provider sent them.

`EpgFiller.isFiller(title)`, on the folded title, is true for:
- `no event today`, `no event`, `no events`, `no events today`, `offline`, `signing off`, `sign off`, `off air`;
- titles starting with `next event:`;
- titles starting with `upcoming`.

The title is the only signal. Every measured placeholder is a 4 h block, the same length as the real listings, so the
"≥ 12 h" length test proposed in arch B1 would catch none (§2.3).

The placeholder test needs the window's titles, which `EpgReaderImpl.window` fills from its text cache. WP-3 confirms
this, or classifies by text id through a per-generation map.

**What the owner sees on a provider-linked event channel (BossSports), from §2.3:**

| Over the event span | Channels | Shown |
|---|---:|---|
| Only placeholders, or nothing | 623 of 777 | the **name's event**; placeholder blocks before and after it, as the provider sent them |
| A real listing starting at the same minute (the same event, even when spelled differently) | 146 | the **listing**: it carries the provider's description and flags |
| A real listing for another event (slot reshuffled between the two fetches) | 8 | the **listing**: the richer source; the name's version is still found by search (§7) |

The 84 mapped channels without programmes and the 682 unmapped ones always show the name's event.

**Rejected** "EPG wins if anything overlaps": it would hide the event on 564 channels that show only "No Event Today"
over it.

### 6.2 `info()` and `at()` (arch S10)

- **When they synthesise.**
  - `info(channel, rawStartMin)`: when `channel.event` is an EVENT whose `startMin == rawStartMin` and no guide detail
    exists there (or there is no reader).
  - `at(channel, atMin)`: the same inside the event span.
- **What they return:**

  ```
  ProgrammeInfo(startMin, startMin, endMin, title, subtitle,
                description = EVENT_FROM_NAME (+ " " + EVENT_ZONE_ASSUMED when ASSUMED),
                genre = null, episodeLabel = null, flags = 0, titleHash = Hash64.of(title))
  ```

- **Caching.** `InfoKey` gains the event's `basis`, so a renamed slot with the same start never shows the old title.
  The state machine's loaded-info reuse (`GuideStateMachineImpl` around line 2574) compares the title hash as well.

### 6.3 Empty slots and the time around an event (U-E-S3)

For a GAP or NO_EPG cell, the panel reads the channel's `event`:

| Channel's `event` | Cell | Panel title | Panel description |
|---|---|---|---|
| EMPTY | any | `NO_EVENT` | `NO_EVENT_NEXT` |
| EVENT, end in the past | at or after the end | `NO_EVENT` | `EVENT_MAY_BE_ON` up to 3 h after the estimated end, then `EVENT_ENDED` |
| EVENT, start in the future | before the start | `NO_INFORMATION` | `NEXT_EVENT` ("Next on this channel: Montreal @ Colorado at 9:30 PM.") |

The grid keeps "No information" cells (U-E-N1 left as is: it would need a per-row text in `RowCellsBuilder`).

### 6.4 Info panel and menu for an event cell (`GuideStateMachineImpl`, `GuideRendererImpl`; arch S6)

- **Panel.** The title is the event title. The time line reads `"8:00 PM – about 11:00 PM · From the channel name"`
  when `CellFlags.EVENT_NAME` is set (U-E-N2): the duration label gives way to `ABOUT` and `FROM_NAME`. The description
  comes from §6.2.
- **Menu.** Watch (live only), Remind me, favourites and hide.
- **Catch-up is gated on `EVENT_NAME` everywhere:**
  - the PAST_ARCHIVE "Watch" menu item;
  - "Watch from start";
  - the Start-over key (`GuideStateMachineImpl` around line 711);
  - the renderer's "catch-up available" mark (`GuideRendererImpl` around lines 255 and 527). This is one flag test,
    and the only renderer change.
- **Why gate it:** the end is estimated, the zone may be assumed, and a past event slot's archive may hold another
  event. A past event cell reads as "not in catch-up", like any past programme outside the archive.

### 6.5 Redraw after a refresh (arch B5)

- **Rows.** `GuideLibraryImpl` reloads loaded rows on a playlist change (around line 204), and now also on
  `EventIndex.version`, so rows built before the index rebuilt are rebuilt again.
- **Pager.** `epgBasisOf` includes `event.basis` (§4.4), so `rowsChanged` re-queries a renamed slot even when its guide
  ref and shift are unchanged.
- **State machine.** It accepts the snapshot by the same basis.

### 6.6 Reminders on event cells (arch S5, U-E-S9)

`ReminderSchedulerImpl` (`:app`) resolves the reminder's channel when a reminder becomes due. If that channel has an
`EventSnapshot` entry or had one (`event` not null):

1. **Same event.** The current event's folded title equals the reminder's title and its start is within ±30 min: fire
   as today.
2. **Moved.** Otherwise, look in the same playlist's snapshot for an event with the same folded title starting within
   ±30 min. If one is found, fire for **that** channel: `identity` = the found channel, and `movedFrom` = the old short
   name. The card reads "Now on Sportsnet+ 25". Auto-switch is allowed: the title and time match.
3. **Gone.** Otherwise, fire with `eventGone = true`: no auto-switch, and the card says
   `reminder_event_moved` (§9.4).

A reminder on a channel without an event is unchanged.

An M3U provider whose event slots carry `tvg-id`s uses the TVG key rule. That rule puts the name in the key
(`StableKeyDeriverImpl`, line 31), so a rename makes a new key, and the reminder is orphaned by the existing path. A
test pins this; it is stated, not fixed. Xtream (`xc:<stream id>`) and URL keys do not change.

### 6.7 Cost (Fire TV Stick)

- **Window build:** one `event != null` test per row. Pages with events copy the window's rows (fewer than 300), test
  the placeholders of those channels' rows and insert at most one row each: under 0.3 ms against today's 2–6 ms build.
- **Memory:** one extra `EpgWindow` (~20 KB). The index is ~1,543 entries × ~120 B ≈ 0.2 MB.

---

## 7. Search (`GuideSearchImpl`, `SearchScreen`)

1. **Programmes from the guide (existing).** Hits whose title is a placeholder (`EpgFiller`) are dropped, everywhere.
   This removes the five "Next Event: …" blocks per slot that "oilers" would otherwise list.
2. **Events (new; arch N3).**
   1. **Terms.** Fold the query into words. If a query word, or a run of words, equals an NHL or NFL team's nickname or
      full name (`TeamNames`), add that team's places as alternatives for that word (U-E-S5): "oilers" becomes
      oilers | edmonton.
   2. **Match.** Keep `EventSnapshot.current(nowMin)` entries where every query word (or one of its alternatives)
      prefixes a word of the title or the subtitle.
   3. **Filter.** Apply the lineup and parental filter through `queries.visible(ids, access)`, then load the channels.
   4. **Cap.** `EVENT_SEARCH_LIMIT = 60`, in start order.
3. **De-duplicate** (from §2.3). Drop an event hit when the same channel has a kept guide hit starting within
   **±15 min** of the event's start. In the data, every overlapping real listing for the same event starts at exactly
   the name's minute, even when the words differ ("FAU" against "Florida Atlantic"). A listing further away is a
   different event, and both are shown.
4. **Show.**
   - Each event hit is a `ProgrammeHit` with the §6.2 info, bucketed like guide hits (on now, upcoming, later).
   - Channel results are unchanged, except that empty slots rank last.
   - Cards show short names (§5.3).
5. **No guide (U-E-S5.2).** `SearchScreen` draws the programme row whenever it has hits (event hits included), and shows
   `search_needs_guide` only when the row is empty.

Cost: an in-memory scan of ≤ 1,543 entries (< 2 ms), plus one `visible` query and one `channels` page.

---

## 8. Channel link (`GameChannelMatcherImpl`, `GameListing`)

### 8.1 Regional guesses removed

- A channel whose only evidence is a regional alias (`AliasMatch.national == false`) is **not a candidate**. It is still
  read by stage C, so it can become CONFIRMED (its guide names the game) or LIKELY (a sport listing) on guide evidence
  alone.
- National aliases keep their levels and their network wording.
- `GameListing.classify` returns `NONE` for a placeholder title, so stages B and C never take a "Next Event: …" block as
  evidence (§2.3).

### 8.2 Stage T: team channels

- **Query.** Once per league per page session (dropped by `clear()`):
  `teamChannelCandidates(word, access, HUB_TEAM_QUERY_MAX)` (`GuideQueries`, new), with `word` = `nhl` or `nfl`.
  - **Match:** `g0.name LIKE '%' || word || ' team%'` OR `lower(c.tvg_id) LIKE '%.' || word`.
  - **Filters:** the lineup and parental predicates (`LINEUP_BASE`, `PARENTAL_RAW`), which exclude missing rows.
  - **Result:** `teamOf` in Kotlin gives a map from abbreviation to channel ids.
- **`teamOf`.** Both of these are required:
  1. **Name.** Take `BroadcastAliases.residue(name)`, drop a leading league word, and the tokens must **equal** one of
     the team's full names or nicknames.
     - Pass: "Edmonton Oilers", "CA| EDMONTON OILERS HD", "NHL: Oilers", "Montreal Canadiens", "St. Louis Blues", "Utah
       Mammoth", "Utah Hockey Club".
     - Fail: "Sportsnet Oilers", "Oilers TV", "Radio: Edmonton Oilers", "Edmonton".
  2. **Context.** Either `groupIsTeams`, meaning the group has the adjacent "nhl team(s)" or "nfl team(s)" and none of
     radio, replay(s), highlights or classic(s); or `tvg_id` is `<Stem>.<league>` with a stem of the same team.
- **Any prefix works** ("CA | NHL TEAMS", "NHL Teams (2)").
- **The owner's data:** all 64 owner team channels pass. Same-name clubs of other leagues sit in `NBA Teams` /
  `MLB Teams` and fail.
- **Per game:** the home and away teams' channels are CONFIRMED with the reason `TeamChannel(full name, nickname,
  side)`. They carry `airing` when stage B or C also found a listing on them.

### 8.3 Stage E: event channels

1. Read `EventSnapshot.startingIn(gameStartMin − 90, gameStartMin + 45)` (`HUB_EVENT_BEFORE_MIN = 90`,
   `HUB_EVENT_AFTER_MIN = 45`, arch S13).
2. Apply `queries.visible(ids, access)`.
3. Grade each hit with `EventGameRule.grade(EventFacts(name, title, group, start, zone), game, board)`.
4. A graded hit becomes a candidate with `EventName(title, startMs, citiesOnly, zoneAssumed)`. A channel that is also a
   stage B/C hit merges into one candidate (one `Acc` per channel id). Its class is its best evidence, and both reasons
   are kept.

### 8.4 `EventGameRule.grade`

1. **Time.** `Δ = eventStart − gameStart` must be in **[−90, +45] min**. The owner's
   `Sportsnet+ 23: Montreal @ Colorado @ 25 Nov 09:30 OM ET` against a 9:00 PM ET puck drop is +30 and is pinned as a
   fixture.
2. **Sides.** `EventMatchup.of(title)` must give two sides, and each must **equal** a name of one of the game's two
   teams (`TeamNames.sideMatch`; arch S2). The two teams must differ.
   - Equality rejects "Southern Utah", "Boston College", "Colorado College", "Washington State", "Sacramento Kings" and
     "Basketball NBA Dallas Mavericks", with no other-league table needed (arch S1, third point).
   - A PLACE side whose place `placeShared` (New York, Los Angeles) is unusable.
3. **Hints.** These are read over the **whole name and the group** (arch S1). Any negative word means no grade.
   - Negative: `nba`, `wnba`, `basketball`, `ncaa`, `ncaab`, `ncaaw`, `ncaaf`, `college`, `mlb`, `baseball`, `mls`,
     `soccer`, `ahl`, `echl`, `ohl`, `whl`, `qmjhl`, `chl`, `usports`, `u17`–`u21`, `junior`, `women`, `womens`,
     `(w)`, `(m)`, `cfl`. For the NFL add `nhl` and `hockey`; for the NHL add `nfl` and `football`.
   - Positive: for the NHL `nhl`, `hockey`, `lnh`, `nhl.tv`; for the NFL `nfl`.
4. **Order.** With `@` or `at`, the first side must be the away team; a reversed order means no grade.
5. **Level** (U-E-S7: no caveat sentence in the hub):

| Evidence | EXPLICIT / CONVENTION zone | ASSUMED zone |
|---|---|---|
| Both sides FULL or NICKNAME, no cross-league nickname (Jets, Panthers, Rangers, Kings, Giants, Cardinals, Senators, Hurricanes, …), or any positive hint | **CONFIRMED** | LIKELY |
| Both sides FULL or NICKNAME, a cross-league nickname, no positive hint | LIKELY | LIKELY |
| One side PLACE, the other FULL or NICKNAME | CONFIRMED with a positive hint, else LIKELY | LIKELY |
| Both sides PLACE, positive hint | CONFIRMED (`citiesOnly`) | LIKELY |
| Both sides PLACE, ordered and in the right order, **\|Δ\| ≤ 15 min, and no other game on `board` involving either place within ±3 h** | **CONFIRMED** (`citiesOnly`) | LIKELY |
| Both sides PLACE, ordered and in the right order, otherwise | LIKELY (`citiesOnly`) | no grade |
| Both sides PLACE, unordered, no hint | no grade | no grade |

`board` holds the loaded day's games of the shown league (`SportsViewModel` passes `repo.day`). Other leagues' boards
are not loaded on the page; the negative hints and the arena argument cover them.

Worked cases (Golden Knights at Oilers, 8:00 PM ET, Nov 24):

| Name | Result |
|---|---|
| `NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)` | CONFIRMED |
| `NHL 01: NHL on TNT: Golden Knights at Oilers @ 24 Nov 07:30 PM ET` | CONFIRMED (Δ −30; the "NHL on TNT" label is in the whole-name hint scan) |
| `MAX 002: NHL on TNT: Golden Knights at Oilers (11.24 07:30 PM ET)` | CONFIRMED |
| `Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET` | CONFIRMED (ordered, Δ 0, no other Vegas or Edmonton game on the board) |
| `Sky Sports + 19: Basketball NBA Boston Celtics @ Washington Wizards` against Bruins at Capitals | no grade (negative hint; sides not equal) |
| `NHL.TV \| Kings @ Canadiens` (DAZN UK) against LA at MTL | CONFIRMED (" \| " split; `nhl.tv` hint lifts the cross-league "Kings") |

### 8.5 The guide horizon (owner ruling c)

**`dataToMin`.** `EpgStoreImpl` writes it to meta at publish and after a patch:
- `ImportSession` marks the placeholder text ids as it inserts texts (one `isFiller` call per distinct title, ~175k).
- At publish, one grouped count over `programme_by_text` for those ids finds the channels with only placeholders
  (3,266 for the owner).
- `dataToMin` is the median `last_end_min` over the other channels with programmes, capped at `window_to_min`.
- Cost: under 1 s on the build thread inside a 26–70 s build.
- Older generations have no value; `guideEndMs()` then uses `window_to_min`.

**Arch N5 rejected.** Arch N5 proposed computing this at match time from the open reader. The placeholder scan over
`epg_text` measured 0.9 s on the Mac (about 3–5 s on the Stick) and would land on the first hub open. Its "mapped
channels only" restriction is already true: the import keeps programmes only for whitelisted channels, and all 7,841
are mapped (§2.4).

**Rule.**
- `SportsViewModel.startMatch`: if `ChannelHorizon.beyondGuide(start, m.guideEndMs(), now)`, set
  `MatchState.BeyondGuide(teams = m.teamChannels(game, access), noGuide = m.guideEndMs() == null)` and run no other
  stage. Slot 2 is a **disabled** "Channels" (the owner's grey chip).
- `now + 12 h` keeps tonight's games when the guide is stale or missing. Team channels and event names need no guide.
- For the owner (a guide about 24 h deep, §2.4), tonight's and tomorrow-night's games before about 1 AM ET of the day
  after tomorrow are matched; later games are grey until the next morning's guide build.

**Line** (§9.1):
- with team channels found: "On the Golden Knights and Oilers team channels";
- with none: "Your guide doesn't cover this game yet";
- with no guide at all: "Channels show 12 hours before the game".

The line is added to the panel's content description, because the disabled chip is never focused (U-E-B4).

### 8.6 Ranking, cap and the user's team (U-E-B3, owner ruling b)

1. **Team channels** (CONFIRMED).
   - **Reserved:** never counted in the cap.
   - **Order:** the **user's team** first, then the other.
   - **"The user's team":** the app has no team follow, so it is the side whose team channel is a **favourite**, else
     the one with the higher **watch count**, else the **home** side.
2. **CONFIRMED guide listings.**
3. **CONFIRMED event names.**
4. **LIKELY**: event names, sport listings, tagged national broadcasters.
5. **POSSIBLE**: untagged or unknown-code national broadcasters.

Within each class: favourite, watch count, national before regional, playlist order, provider order. Collapse works as
today (same playlist and same alias key or residue).

**Cap.** `HUB_MATCH_MAX = 8` rows besides the team channels. When stage E found any event and none made the 8, the 8th
row is the best event row.

**Watch slot.** Unchanged rule (exactly one CONFIRMED candidate and imminent). NHL and NFL games therefore show
"Channels (n)", and the sheet opens with row 0, the user's team channel, focused (owner ruling b).

**Cache.**
- The key gains the sum of enabled playlists' `refresh_seq` and `EventIndex.version`.
- `clear()` drops the team maps.

**Cost** (Fire TV Stick):
- stage T: one scan per league per page session (~15–30 ms, then cached);
- stage E: a binary search plus one `visible` query.

The hub's p95 ≤ 250 ms gate (hub §5.7) is re-measured by WP-4.

---

## 9. Text

### 9.1 `strings_sports_hub.xml` (the hub after the lead's in-flight change)

**Deleted, with their callers (WP-4):**
- `sports_hub_reason_short_local`;
- `sports_hub_reason_home_local`;
- `sports_hub_reason_away_local`;
- `sports_hub_regional_note`, with the sheet's footer item and `CHANNEL_SHEET_NOTE_TAG`;
- `sports_hub_reason_possible`.

**Added in commit 0** (channel line and sheet text at 22/500; nothing under 22 design px):

```xml
<!-- Channel line short reason (U-E-S8: the guide's phrase). -->
<string name="sports_hub_reason_short_event">from the channel name</string>
<!-- Channel line with a team channel on top. %1$s = the nickname ("Oilers"). -->
<string name="sports_hub_on_tv_team">On TV: %1$s team channel</string>
<!-- Upcoming. %1$s = the start time, %2$s = the nickname. -->
<string name="sports_hub_on_tv_team_at">On TV at %1$s: %2$s team channel</string>
<!-- The never-cut tail of the channel line (U-E-N5). %1$d = other candidates. -->
<plurals name="sports_hub_more"><item quantity="one">+%1$d more</item><item quantity="other">+%1$d more</item></plurals>
<!-- Sheet, team channel. %1$s = the nickname. -->
<string name="sports_hub_reason_team">Team channel. %1$s games are always on it.</string>
<!-- Sheet, event. %1$s = the start time, %2$s = the title (time first: an ellipsis eats the title's end, U-E-B1). -->
<string name="sports_hub_reason_event">From the channel name: %1$s, %2$s</string>
<!-- Sheet, event whose zone was assumed: no time (U-E-S7). %1$s = the title. -->
<string name="sports_hub_reason_event_untimed">From the channel name: %1$s</string>
<!-- Sheet, national broadcaster with no usable network name (U-E-N6). -->
<string name="sports_hub_reason_network_unnamed">A national broadcaster carries this game. Your guide doesn\'t list it on this channel.</string>
<!-- Sheet, third line, a quality variant or a backup group with the same name (U-E-N3). %1$s = the group. -->
<string name="sports_hub_also_in">Also in %1$s</string>
<!-- Channel line beyond the guide (§8.5). -->
<string name="sports_hub_beyond_guide_team">On the %1$s and %2$s team channels</string>
<string name="sports_hub_beyond_guide">Your guide doesn\'t cover this game yet</string>
<string name="sports_hub_beyond_guide_none">Channels show 12 hours before the game</string>
```

**Sheet rows** (row height stays 96; U-E-B1):
- **Line 1:** the short name, 26/600.
- **Line 2:** the one-line reason.
- **Line 3:** the playlist name when there are several, then "Also: …" or "Also in <group>", then the group when two
  rows share a short name. Joined by " · ". No caveat sentence anywhere.

**Reason mapping:**

| Evidence | Short reason (channel line) | Sheet reason |
|---|---|---|
| Guide listing / sport listing | "in your guide" | `reason_guide` / `reason_sport_listing` |
| Team channel | the line uses `on_tv_team` / `on_tv_team_at` | `reason_team` |
| Event name | "from the channel name" | `reason_event`; `reason_event_untimed` when `zoneAssumed` |
| Broadcaster | "national broadcaster" | `reason_network` with a country; `reason_network_any` with a network name of at least 3 characters that has a letter; else `reason_network_unnamed` |

**Channel line layout (U-E-N5).** Two texts in a row: the name part, which ellipsizes, and " · " + `sports_hub_more`,
which is never cut. The worst case at Largest is "On TV at 10:30 PM: Golden Knights team channel", which ellipsizes
before "+8 more".

### 9.2 `GuideTexts` (`:core`, English constants as today; frozen in commit 0)

```kotlin
const val FROM_NAME = "From the channel name"
const val ABOUT = "about "
const val EVENT_FROM_NAME = "From the channel name: your guide has no listing for this channel. The end time is estimated."
const val EVENT_ZONE_ASSUMED = "The name has no time zone, so the time may be off."
const val EVENT_MAY_BE_ON = "The end time was estimated from the channel name, so this event may still be on."
const val EVENT_ENDED = "This event has ended. The channel's next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
const val NO_EVENT = "No event scheduled"
const val NO_EVENT_NEXT = "Nothing is scheduled on this channel. Its next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
fun nextEvent(title: String, time: String) = "Next on this channel: $title at $time."
```

### 9.3 Settings (`SetupText` + `strings_setup.xml` + `AndroidSetupStrings`)

```xml
<string name="setup_row_hide_empty_events">Hide channels with no event</string>
<string name="setup_hide_empty_events_desc">Hides channels whose name says NO EVENT or lists nothing. They come back when your provider names an event.</string>
<string name="setup_interval_event_default">Recommended: this playlist\'s channel names list upcoming events.</string>
```

### 9.4 Reminder card

```xml
<!-- %1$s = the reminder's title. -->
<string name="reminder_event_moved">Your provider changed %1$s\'s channel. Search for it to find where it is now.</string>
<!-- %1$s = the new short name. -->
<string name="reminder_event_now_on">Now on %1$s</string>
```

---

## 10. Freshness and the refresh default (owner ruling d; arch S7; U-E-S6)

- **Assumption.** Names are true as of the last refresh. The provider rewrites about 1,550 names on a busy refresh
  (§2.5).
- **Default, not an override.**
  - While `refresh_interval_auto = 1`, the effective interval is **6 h** when `event_count ≥ EVENT_REFRESH_MIN = 50`,
    else `refresh_interval_h`.
  - Manual (`refresh_interval_h = 0`) is never auto: the migration sets `auto = 0` for it.
  - Picking any value in the Update interval row clears `auto`, so the user's choice stands.
- **Settings row.** While `auto` is set and the effective interval is 6 h, the row shows "Every 6 hours" selected, with
  `setup_interval_event_default` as its description. `PlaylistPages.kt` builds this row.
- **Reschedule hook.** `RefreshRepository`, after `finish`, rebuilds the index (§5.2). If the effective interval
  changed, it calls `RefreshScheduler.schedulePlaylist`. App start and settings changes reschedule as today.
- **Cost evidence (§2.5).**
  - Refreshes take 9–29 s on the owner's device.
  - 1,553 renames did not trigger an EPG patch; the only patch (7 s) followed 639 additions.
  - Gate: WP-2 measures four refreshes in a row on the Fire TV Stick (CPU time, bytes, any patch). The 6 h default
    ships if each costs under 60 s CPU and there is no patch on a rename-only refresh. Otherwise the default becomes
    12 h, a one-constant change.
- **Stale names.**
  - An ended event stays in the guide's past. The cells after it explain it (§6.3).
  - Search and the matcher's time window ignore it.
- **No other refresh trigger** in v1.

---

## 11. Tests

### 11.1 Unit (JVM)

**`EventNameParserTest`** (`:core`) is table-driven from `core/src/test/resources/events/names.tsv` (name, now, assumed
zone, expected kind, slot, title, subtitle, local start, zone id, source). `now = 2026-11-24T12:00Z` and the assumed zone
is `America/New_York` unless stated. Real names (format examples):

| # | Name | Expected |
|---|---|---|
| 1 | `Sportsnet+ 01: Sportsnet Central: Nov. 23 @ 24 Nov 05:00 AM ET` | Event, slot `Sportsnet+ 01`, title `Sportsnet Central: Nov. 23`, Nov 24 05:00 New_York, EXPLICIT |
| 2 | `Sportsnet+ 08: Detroit @ Orlando @ 24 Nov 07:00 PM ET` | Event, 19:00 ET |
| 3 | `Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET` | Event, 20:00 ET |
| 4 | `Sportsnet+ 10: Dallas @ Denver @ 24 Nov 10:00 PM ET` | Event, 22:00 ET |
| 5 | `NHL 01: NHL on TNT: Golden Knights at Oilers @ 24 Nov 07:30 PM ET` | Event, title whole, 19:30 ET |
| 6 | `UEFA Champions League 01: Galatasaray - Aston Villa @ 24 Nov 05:00 PM London` | Event, 17:00 Europe/London |
| 7 | `MonoMAX 01: TAURON Liga Energa Trefl Gdańsk vs NEONET Norwid Częstochowa @ 24 Nov 02:50 PM` | Event, 14:50 assumed, ASSUMED |
| 8 | `F1 TV 08: [4K] F1 LIVE @ 20 Nov 11:00 PM` | Event, title `[4K] F1 LIVE`, Nov 20 23:00, ASSUMED |
| 9 | `DAZN CA 13: Boston vs. Barrow @ 24 Nov 02:45 PM ET` | Event |
| 10 | `NBA 03: Mavericks vs. Nuggets @ 24 Nov 09:58 PM ET` | Event, 21:58 ET |
| 11 | `Paramount+ 05: UEFA Champions League: Bodø/Glimt vs. LASK @ 24 Nov 01:00 PM ET` | Event |
| 12 | `NCAAB 49: #5 Texas Tech vs. #25 Louisville (Quarterfinal #3) @ 24 Nov 08:00 PM ET` | Event |
| 13 | `Sky Sports + 20: Basketball NBA Dallas Mavericks @ Denver Nuggets @ 25 Nov 02:55 AM London` | Event, Nov 25 02:55 London |
| 14 | `Apple TV F1 27: AppleTV 27 \| Formula 1: Las Vegas: Race - Fernando Alonso (English) @ 21 Nov 10:45 PM ET` | Event, slot `Apple TV F1 27` |
| 15 | `MLS 03 \| Nashville SC vs. FC Cincinnati (English) @ 22 Nov 05:10 PM ET` | Event, slot `MLS 03` |
| 16 | `SEC+ / ACC extra 08: Northeastern vs. Boston College (ACCNX) @ 24 Nov 06:00 PM ET` | Event, slot `SEC+ / ACC extra 08` |
| 17 | `Sportsnet+ 23: Montreal @ Colorado @ 25 Nov 09:30 OM ET` | Event, Nov 25 21:30 ET |
| 18 | `Sportsnet+ 01: Sportsnet Central: Nov. 25 @ 25 Nov 09:00 AM ET` | Event |
| 19 | `MAX UK 06: UK Championship \| Qualifying: Luca Brecel - Luo Zetao @ 24 Nov 02:30 PM London` | Event |
| 20 | `TSN+ 08: NCAA Men's Hockey: Bentley vs. Harvard @ 24 Nov 07:00 PM ET` | Event |
| 21 | `NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)` | Event, PAREN_MONTH_DAY |
| 22 | `ESPN UNLTD 001: NFL: NFL Football: 2026 Week 11 Cincinnati Bengals vs. Washington Commanders (11.24 5:00 AM ET)` | Event, 05:00 ET |
| 23 | `ESPN UNLTD 002: SEC Network: Samford vs. Auburn (11.24 5:30 AM ET)` | Event |
| 24 | `MAX 002: NHL on TNT: Golden Knights at Oilers (11.24 07:30 PM ET)` | Event |
| 25 | `CHL 02: Tucson vs. Texas (11.24 8:00 PM ET)` | Event |
| 26 | `FLO HOCKEY 022: flohockey: Camrose vs Drayton Valley (11.24 08:00 PM ET)` | Event, subtitle `flohockey` |
| 27 | `PARAMOUNT + 006: uefa-champions-league: RB Leipzig vs. Lens (11.24 2:00 PM ET)` | Event, subtitle `uefa-champions-league` |
| 28 | `Pie Per View 1: Arsenal vs. Dortmund [AMZ] (11.24 14:00 ET / 19:00 UTC)` | Event, 14:00 ET |
| 29 | `NBA 01: Pistons vs. Magic (11.24 7:00 PM ET)` | Event |
| 30 | `TRILLER 001: India A vs UAE (11.24 01:00 AM ET)` | Event |
| 31 | `ESPN UNLTD 199: NCAA Men's Basketball: CLEM vs. WASH • SAM vs. GT (11.24 8:00 PM ET)` | Event; `EventMatchup.of` = null |
| 32 | ``CA (TSN+ 008) \| NCAA Men`s Hockey: Bentley vs. Harvard (2026-11-24 19:00:00)`` | Event, slot `CA (TSN+ 008)`, 19:00 Toronto, CONVENTION |
| 33 | `AU (STAN 01) \| Galatasaray v Aston Villa  UEFA Champions League Matchday 5 2026/2027 (2026-11-25 04:40:50)` | Event, Nov 25 04:40 Sydney |
| 34 | `(UK) (Dazn 028) \| NFL Game Pass \| Bengals @ Commanders (2026-11-24 01:15:00)` | Event, 01:15 London |
| 35 | `NFL GP 01 \| Bengals @ Commanders (2026-11-24 01:15:00)` | Event, ASSUMED (a known miss: really UTC) |
| 36 | `(PDC 37) \| 2026/27 World Championships \| Day 4 _ Evening Session: 2026/27 World Championships \| English (2026-12-14 14:00:00)` | Event, ASSUMED |
| 37 | `NBA LP 01 \| Grizzlies @ Hornets (2026-11-24 00:00:00)` | Event, ASSUMED |
| 38 | `(CA) (CBC 13) \|  (2098-12-31 08:00:01)` | Empty |
| 39 | `ESPN+ 26: Long Beach State vs. Arkansas State @ Nov 24 3:30PM ET` | Event, AT_MONTH_DAY |
| 40 | `BIG10+ 01: Basketball (M) Sacred Heart at Rutgers Tue @ Nov 24 06:00PM ET` | Event |
| 41 | `Kayo AU 03: Football Sky Bet Championship Wolves v Derby County @ Nov 25 5:52AM AEST` | Event, +10:00 |
| 42 | `ESPN+ 11: Tue, 11/24 - The Rich Eisen Show @ Nov 24 12:00PM ET` | Event |
| 43 | `NCAAB 081: 9 TEXAS TECH @ 23 LOUISVILLE (M) \| 11.24 8:00 PM \| ESPN` | Event, subtitle `ESPN`, CONVENTION |
| 44 | `NCAAB 098: SAN JOSÉ STATE @ ARIZONA STATE (W) \| 11.24 8:30 PM \| ESPN+` | Event |
| 45 | `Sportsnet+ 11: NO EVENT` | Empty, `slotOf` = `Sportsnet+ 11` |
| 46 | `NHL 02:` | Empty |
| 47 | `Sportsnet+ 29 :` | Empty |
| 48 | `NFL GP 02 \| Offline` | Empty |
| 49 | `Pie Per View 2:` | Empty |
| 50 | `EFL03: (CH) Cardiff 19:45 Preston 24/11` | None |
| 51 | `TV2 NO 19: UEFA Champions League Porto - Napoli I dag 20:00` | None |
| 52 | `QMJHL 18: Charlottetown @ Rimouski - #529 \| Sat 8th 9:00PM` | None |
| 53 | `LIVE EVENT 02 - 7:45pm USAC Jason Leffler Memorial Kern` | None |
| 54 | `QMJHL 01: Val-d'Or @ Gatineau - #426` | None |
| 55 | `Sportsnet Oilers` | None, `slotOf` = null |
| 56 | `Edmonton Oilers` | None |
| 57 | `CA\| SPORTSNET ONTARIO HD` | None |
| 58 | `◈ NHL Replay 1 ᴴᴰ` | None |
| 59 | `Radio: Detroit Pistons` | None |
| 60 | `DAZN UK 30: NHL.TV \| Kings @ Canadiens @ 23 Nov 07:00 PM ET` (DAZN UK shape) | Event; the matchup is `Kings @ Canadiens` |

**Synthetic rows:**

| Case | Name | `now` | Expected |
|---|---|---|---|
| Year wrap | `NHL 01: Kraken vs. Canucks (12.31 10:00 PM ET)` | 2027-01-01T05:00Z | 2026-12-31 |
| Year wrap | `Sportsnet+ 03: Oilers @ Flames @ 1 Jan 03:00 PM ET` | 2026-12-30 | 2027-01-01 |
| Stale name (arch S3) | `MLB 01: Yankees @ Red Sox @ 20 Jun 07:05 PM ET` | Nov 24 | June 20 **2026**; with `now` on Apr 30 2027, `None` (outside the window) |
| D.M order (arch S12) | `(5.11 20:00 CET)` | Nov 4 | Nov 5 |
| D.M order (arch S12) | `(5.11 20:00 ET)` | Nov 4 | May 11 when that is valid and in the window, else `None` |
| Invalid | `(2.30 …)`, `25:00 PM`, `13:00 PM` | — | None |
| Not an event | `Movie 1: Ocean's 11 (2001)` | — | None |
| DST | `@ 8 Mar 03:00 AM ET` | — | the later offset |
| Junk | an empty string, a 2,000-character string, `@@@@` | — | None |

A fuzz test covers the rest.

**`EventMatchupTest`**, **`EventDurationTest`**, **`EpgFillerTest`**:
- the filler test covers the placeholder titles measured in §2.3, `Upcoming - MLB Network Strike Zone`, and the
  negatives `Signing Day Special`, `Next Gen Stats`, `No Events Barred`.

**`TeamNamesTest`** / **`TeamChannelRuleTest`**:
- **Positives:** all 64 owner team channels by name and tvg_id (`MontrealCanadiens.nhl`, `StLouisBlues.nhl`,
  `UtahHockeyClub.nhl`, `WashingtonCommanders.nfl`, …).
- **Negatives:**
  - `Sportsnet Oilers` in `CA | Sports`;
  - `Radio: Detroit Pistons`;
  - `Detroit Pistons` in `Sports | NBA Teams` for the NHL;
  - `Edmonton Oilers` in `CA | Sports` with no tvg_id;
  - `Boston Bruins` in `Sports | NHL Teams Radio`;
  - `Oilers TV`;
  - `New York` alone.
- **Other prefixes:** `CA | NHL TEAMS`, `NHL Teams (2)`, and the tvg_id form with group `Sports | NHL`.
- **`sideMatch` equality cases:** Southern Utah, Boston College, Sacramento Kings, Basketball NBA Dallas Mavericks,
  `2026 Week 11 Cincinnati Bengals`.

**`EventGameRuleTest`**:
- every row of the §8.4 table and the worked cases;
- the Δ edges −90, −91, +45 and +46;
- the owner's `09:30 OM ET` against 9:00 PM ET;
- the reversed ordered separator;
- a New York place-only side;
- the cross-league pair;
- negative hints from the group only, from the slot only, and inside a side;
- the cities-only upgrade: with and without another game on the board, |Δ| = 15 and 16.

**`ChannelHorizonTest`**: no guide; a guide end before now; an end between now and now + 12 h; a start exactly at the
end.

**`:data` tests:**
- `MIGRATION_5_6` (MigrationTestHelper, `6.json`; auto set to 1 only for `refresh_interval_h = 24`).
- `EventIndexImplTest`:
  - the prefilter;
  - missing rows excluded;
  - a whole-playlist tally;
  - hysteresis: a quiet refresh does not flip; a 20-sample, 2× majority does flip; no panel fallback once learned;
  - ASSUMED rows re-resolved;
  - `startingIn` and `current`;
  - the version bump on `refresh_seq`.
- `ChannelNamePrefixImplTest`: slot first under every rule; no slot for plain names.
- `DisplayNames` backfill on a `RULE_VERSION` bump; a custom name still wins.
- `RuleHiddenPassTest`: the built-in empty-slot matcher on and off; a slot that gains an event shows again; a favourite
  stays visible.
- `RefreshSchedulerTest`: auto with 50 or more events gives 6 h; Manual stays manual; a user value stands; the
  reschedule hook.
- `GuideQueriesTeamTest`: the parental and hidden rules.
- `GuideSearchImplEventTest`:
  - placeholders dropped;
  - "oilers" finds `Vegas @ Edmonton`;
  - the ±15 min de-duplication with an `FAU` / `Florida Atlantic` pair;
  - ended events dropped;
  - "sportsnet" does not list an event;
  - empty slots last.
- `EpgStoreDataToMinTest`: the placeholder-only exclusion, the median, the cap, a patch.
- `GameChannelMatcherImplTest`:
  - team channels on both sides, the user's team first, never capped;
  - a regional alias without guide evidence is absent; with a listing it is CONFIRMED;
  - a "Next Event:" placeholder is not stage B evidence;
  - the event stage;
  - the ranking classes;
  - the reserved event row;
  - the cache key;
  - beyond the guide runs only `teamChannels`.

**`:core` guide tests:**
- `GuideWindowPagerEventTest`:
  - the overlay over placeholders (clipped, split tails keep their raw start);
  - a real listing wins;
  - no reader;
  - the −2 text id;
  - `info()` and `at()` with no reader;
  - `InfoKey` with the basis;
  - a 300-row window with 60 events in under 1 ms on the JVM.
- The `epgBasisOf` change re-queries a renamed row.
- `CellFlags` bits are disjoint (a test over every flag constant).
- Panel tests: the §6.3 table; the time line with "about"; catch-up gated on `EVENT_NAME` (menu, Start-over).
- A renderer test: no catch-up mark on an `EVENT_NAME` cell.

**Reminder and UI tests:**
- `ReminderSchedulerEventTest` (`:app`): same event, moved, gone; the M3U TVG key is orphaned.
- `HubSlotsTest`: BeyondGuide disables Channels and shows no "Finding".
- `HubPanel` line strings for each case, with the never-cut "+n more".
- `ChannelSheet`: the reasons, no footer, line 3 with the group for duplicate short names.
- `SearchScreen`: event hits with `guideDataMissing`.

### 11.2 Performance (Fire TV Stick)

- Matcher p95 ≤ 250 ms per game on the 20 k synthetic playlist with 1,700 event-shaped names and 64 team channels;
  stage T ≤ 40 ms cold.
- `EventIndex` cold build ≤ 150 ms.
- Pager build with the overlay: P90 within 0.5 ms of today on the `GuideScrollBenchmark` page with 30 % event rows.
- Search with an event hit ≤ 50 ms more than today.
- The §10 refresh gate.

### 11.3 Fake panel (`tools/fakepanel/events.py`, new; switch `--event-catalog`, config `event_catalog`; U-E §8)

Channels have ids 80–129. Names are **generated at request time** around the featured game's anchor (`sports.py`, VGK at
EDM at `HUB_ANCHOR`), so they always read "today".

**Team channels.**
- `Sports | NHL Teams`: `Vegas Golden Knights` (`VegasGoldenKnights.nhl`), `Edmonton Oilers` (`EdmontonOilers.nhl`),
  `Montreal Canadiens` (`MontrealCanadiens.nhl`), `Utah Mammoth` (`UtahHockeyClub.nhl`). They have no guide data.
- `Sports | NHL Teams (2)`: a second `Edmonton Oilers`, for the "Also in" line.
- `Sports | NFL Teams`: the two teams of the fake NFL board's first game.

**Guide-listed channels.** At least **7** for the featured game, each with "NHL Hockey: Golden Knights at Oilers" in
the XMLTV: `CA| SPORTSNET WEST`, `CA| SPORTSNET OILERS`, `CA| SPORTSNET PACIFIC`, `CA| SPORTSNET EAST`,
`CA| SPORTSNET ONE`, `US: TNT` (now with a tvg id) and `US: TRUTV`. Together with the hub catalog's Sportsnet Ontario,
they exercise the cap and the order.

**`Sports | Sportsnet+`** (shape A, ET):
- `Vegas @ Edmonton` at the anchor (cities-only CONFIRMED);
- `Detroit @ Orlando`, an NBA decoy;
- a morning show `Sportsnet Central: <Mon. D>`;
- `Sportsnet+ 11: NO EVENT` and `Sportsnet+ 12:`;
- a stale `Calgary @ Seattle` that ended 2 h ago (the §6.3 "may still be on" state), and one from yesterday (ended);
- the owner's `… OM ET` for a later game.

**`Sports | NHL`** and **`Sports | NHL (2)`**:
- shape B `NHL 01: Golden Knights vs. Oilers (M.D h:mm PM ET)`;
- shape A `NHL 01: NHL on TNT: Golden Knights at Oilers` 30 min early. Both read `NHL 01`, for the duplicate short name.

**A BossSports-style provider-linked event channel** (`Sports | ESPN+`, shape D, tvg_id `fake.boss.espnplus.01`). Its
XMLTV holds:
- 4 h "No Event Today" blocks across the day;
- one "Next Event: <title> at <h:mm>PM EST on <Mon D>" block;
- for a second slot (`fake.boss.espnplus.02`), one real 4 h listing starting at the name's minute with a different
  spelling of the title (search de-duplication).

**Other shapes:**
- `Sports | NCAA Basketball (2)`: shape E;
- `Sports | TSN+ (2)`: shape C `CA (…)`;
- `Sports | NBA League Pass`: untagged shape C (ASSUMED);
- `UK | Sky Sports+`: London, including `Basketball NBA Boston Celtics @ Washington Wizards`;
- `Sports | NBA`: `Mavericks vs. Nuggets`;
- `CA | Sports`: `Sportsnet Oilers` (never a team channel).

**Admin routes:**
- `POST /admin/events {"shift_min": n}` moves every event;
- `{"names": "next"}` swaps the slots to the next set (a refresh then shows new names, and `Sportsnet+ 09`'s event moves
  to `Sportsnet+ 10`, for the reminder "moved" state).

**`tools/fakepanel/test_events.py`:** every generated name matches the tail checks (a 20-line mirror, not a second
parser), the categories carry the documented counts, and the XMLTV placeholder blocks are 240 min long.

### 11.4 Emulator checklist (eight compact states; 1080p TV emulator; `--sports-hub-catalog --event-catalog`)

1. **Guide, `Sports | Sportsnet+`.** The label column reads `Sportsnet+ 09`. The `Vegas @ Edmonton` cell is shown, and
   its panel time line reads "8:00 PM – about 11:00 PM · From the channel name".
2. **Guide panels.** One screenshot of the NO EVENT row's panel ("No event scheduled"). One of the now-cell after the
   stale `Calgary @ Seattle` ("may still be on"). On the BossSports-style row, the event cell is drawn with "No Event
   Today" blocks around it.
3. **Search "oilers".** The event results include `NHL 01` (both), `NHL on TNT` **and** `Vegas @ Edmonton`. There are no
   "Next Event: …" rows, and the BossSports-style real listing appears once. Cards show short names.
4. **Sports, featured game.** OK, then Channels (n) opens with the Oilers team channel **focused**, then Golden Knights,
   then the guide listings, then the events. The Sportsnet+ row is present. There is no "local channel" text and no
   footer note.
5. **The same game's panel line, before the game.** "On TV at 8:00 PM: Oilers team channel · +n more", with "+n more"
   visible.
6. **A game 6 days ahead.** Channels is grey and skipped by Left / Right. The line reads "On the Golden Knights and Oilers
   team channels". With `event_catalog.team_channels = false` it reads "Your guide doesn't cover this game yet".
7. **Settings and refresh.**
   - The playlist page shows "Hide channels with no event" (off) and Update interval "Every 6 hours" with the
     recommendation line.
   - Turning the switch on hides `Sportsnet+ 11`.
   - Admin `names: next`, then a refresh: the new event in the guide without scrolling (arch B5).
8. **Largest text size.** The sheet's longest row keeps its time and title start visible. The panel's channel line
   keeps "+n more".

---

## 12. Work packages (file ownership disjoint after commit 0)

**Prerequisite.** The lead lands the in-flight hub change (864 px panel, no hint row; arch S11) before commit 0.
Branch `event-channels`.

### Commit 0 (lead, Opus)

Owns and lands:
- **`:core` contracts:**
  - `events/EventContracts.kt` (§4.1);
  - `sports/hub/ChannelHorizon.kt` (complete);
  - stubs with frozen signatures: `events/EventNameParser.kt`, `EventMatchup.kt`, `EventDuration.kt`, `EpgFiller.kt`,
    `sports/TeamNames.kt`, `TeamChannelRule.kt`, `sports/hub/EventGameRule.kt`.
- **`:core` amended files:**
  - `guide/GuideModel.kt` (`GuideChannel.event`, `CellFlags.EVENT_NAME = 1 shl 27`, `epgBasisOf` with the event basis);
  - `guide/ReminderContracts.kt`;
  - `epg/EpgContracts.kt`, `epg/store/EpgSchema.kt` (constant);
  - `sports/hub/HubContracts.kt`, `sports/SportsConstants.kt`;
  - `guide/state/GuideTexts.kt`;
  - `setup/SetupPorts.kt`, `setup/SetupText.kt`.
- **`:data`:**
  - `db/entity/PlaylistEntity.kt`, `db/Migrations.kt` (`MIGRATION_5_6`), `db/LibraryDatabase.kt` (v6),
    `data/schemas/…/6.json`;
  - `db/dao/PlaylistDao.kt` (the new columns' setters and getters);
  - `events/EventIndexImpl.kt` as a stub (an empty snapshot) plus its binding in `di/GuideDataModule.kt`;
  - `guide/GuideQueries.kt`: the `BLOCK_SELECT` `c.name` column, `channels()` filling `event` from the index (complete),
    and a `teamChannelCandidates` stub.
- **`:ui`:**
  - `sports/HubPanel.kt` (`MatchState.BeyondGuide`, stub branch);
  - `res/values/strings_sports_hub.xml` (§9.1 additions only);
  - `res/values/strings_setup.xml` with `ui/.../setup/AndroidSetupStrings.kt` (§9.3);
  - the reminder strings (§9.4) in the existing reminder strings file.
- **Tools and fakes:**
  - `tools/fakepanel/fakepanel.py` (switch, `DEFAULTS`, routes to `events.py`) and a stub `events.py`;
  - in `core/src/testFixtures`: `FakeEventIndex`, `FakeGameChannelMatcher` (`guideEndMs`, `teamChannels`).
- **Gate:** commit 0 passes `./gradlew :core:test :app:assembleDebug` and `lintAll` on its own.

### Packages

| WP | Owns (every file it touches) | Model | Depends on | Size |
|---|---|---|---|---|
| **WP-1** parser and rules (`:core`, pure) | `core/.../events/EventNameParser.kt`, `EventMatchup.kt`, `EventDuration.kt`, `EpgFiller.kt`; `core/.../sports/TeamNames.kt`, `TeamChannelRule.kt`; `core/.../sports/hub/EventGameRule.kt`; `core/src/test/resources/events/names.tsv`; their tests (§11.1 `:core` parser, matchup, duration, filler, team, rule) | Opus | C0 | ~1,000 + ~1,000 tests |
| **WP-2** library, index, names, hiding, refresh | `data/.../events/EventIndexImpl.kt`; `core/.../text/ChannelNamePrefixImpl.kt` and `core/.../text/ChannelNamePrefix.kt` (`RULE_VERSION`); `data/.../rules/RuleHiddenPass.kt`; `data/.../setup/PlaylistSettingsRepository.kt`; `data/.../work/RefreshScheduler.kt`; `data/.../repo/RefreshRepository.kt` (index rebuild and reschedule after `finish`); `core/.../setup/pages/PlaylistPages.kt` (the switch row, the interval row); `core/.../backup/MergePlanner.kt` (`PLAYLIST_COLUMNS` + 2); `data/.../guide/GuideQueries.kt` (`teamChannelCandidates` body); `app/.../TheBoxApplication.kt` (index warm-up after the display-name backfill); their tests; the §10 gate | Sonnet under this spec; Opus review | C0; WP-1 for real parses in integration tests (unit tests use a fake parser) | ~700 + ~900 tests |
| **WP-3** guide, search, reminders, guide end | `core/.../guide/feed/GuideWindowPagerImpl.kt`; `core/.../guide/state/GuideStateMachineImpl.kt`; `core/.../guide/render/GuideRendererImpl.kt` (one flag test); `data/.../guide/GuideLibraryImpl.kt` (reload on `EventIndex.version`); `core/.../epg/store/EpgStoreImpl.kt`, `core/.../epg/store/ImportSession.kt` (placeholder text ids, `dataToMin`); `data/.../guide/GuideSearchImpl.kt`; `ui/.../search/SearchScreen.kt`; `app/.../reminder/ReminderSchedulerImpl.kt`; `ui/.../reminder/ReminderCard.kt`; their tests; the `GuideScrollBenchmark` event page | Opus | C0; WP-1's `EpgFiller` for real data (stub returns false) | ~600 + ~700 tests |
| **WP-4** channel link and hub | `data/.../sports/GameChannelMatcherImpl.kt`; `core/.../sports/hub/BroadcastAliases.kt` (`GameListing` placeholder rule only); `ui/.../sports/SportsViewModel.kt` (`startMatch`, `board`, BeyondGuide); `ui/.../sports/HubPanel.kt` (slots, the two-part line, short reasons, content description); `ui/.../sports/ChannelSheet.kt` (reasons, line 3, footer removal); the §9.1 deletions in `strings_sports_hub.xml`; `tools/fakepanel/events.py`, `catalog.py` (category hook, tvg ids for TNT and truTV), `test_events.py`, `README.md`; the matcher benchmark; their tests | Opus (matcher), Sonnet (UI text) | C0; WP-1 merged before its matcher tests run against the real rules | ~800 + ~900 tests |
| **Integration and review** | merge; §11.4; Fire TV Stick timings (§11.2) and the §10 gate; code reviews (data, guide, UI); release gates (plan §6); decision note `docs/decisions/2026-11-25-event-channels.md` (owner rulings, the user's-team interpretation, the gate result); amend `m6-sports-hub.md` §5.3, §5.5, §8.1 and `tools/fakepanel/README.md` | Opus | all | — |

**Shared files.** No file has two owners after commit 0. `GuideQueries.kt` (commit 0, then WP-2),
`HubPanel.kt` and `strings_sports_hub.xml` (commit 0, then WP-4), and `events.py` (commit 0's stub, then WP-4) are
touched in sequence. `TheBoxApplication.kt` belongs to WP-2 only.

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| ASSUMED zone wrong for a sub-feed: the 11 UTC names (NBA LP, NFL GP) sit 5 h off in the guide. | The guide panel says so. The hub shows those rows without a time (U-E-S7) and caps them at LIKELY. A 5 h error can never fall inside the matcher's window. |
| The provider changes a format overnight. | Unknown shapes are `None` and the channel behaves as today. The fuzz test guards against crashes. Formats are table rows. |
| City-only names on another league's game. | Exact-name sides, negative hints over the whole name, the ordered separator, place sharing, and the CONFIRMED upgrade only at \|Δ\| ≤ 15 with a clean board. |
| A team channel shows something else (pre-season, a blackout). | This is the owner's provider rule. The reason says "team channel", not "in your guide", and it is opt-in by provider layout. |
| The provider's guide lists another event on a slot than its name does (8 of 154 in the sample). | The listing wins in the guide; search shows both; the matcher keeps both pieces of evidence on one candidate. |
| A shallow guide (about 24 h for the owner) greys Channels for games the day after tomorrow. | The owner's own rule. The line names the team channels; the next morning's guide build re-enables it. |
| The 6 h default costs data and CPU on a Fire TV Stick. | It is a default only, and the §10 gate falls back to 12 h if needed. |
| `EventIndex` stale between a refresh and its rebuild. | Rebuilt in the refresh path before the library signal settles. Rows reload again on `version` (§6.5). |
| Reminder on a reshuffled slot. | §6.6 (moved / gone). M3U TVG keys are orphaned, as today, and stated. |

---

## 14. Open questions for the owner

None. The owner's rulings of 2026-11-25 and the lead's defaults settle every earlier question (§15.3). One
interpretation is recorded for the lead in the decision note: "the followed team's channel first" uses the favourite
team channel, else the more-watched one, else home, because the app has no team follow (§8.6).

---

## 15. Review responses

### 15.1 Architecture review

| Id | Finding | Response | Where |
|---|---|---|---|
| B1 | Half the events already have guide data; the "EPG wins" and search rules were unknown | **Measured** on the owner's `epg.db`. 777 BossSports-linked events; 564 have only placeholders over the event and 59 nothing; 154 have a real listing (146 at the same minute). Rule: placeholders are clipped and the event drawn; a real listing wins. Search drops placeholders and de-duplicates at ±15 min. Stages B and C ignore placeholders. Premise sentences fixed. | §2.3, §6.1, §7, §8.1 |
| B2 | Zone and event count tallied from changed rows only | Gone by design: the index tallies every live row. Hysteresis (20 samples and 2×); no panel fallback once learned; `event_count` written from the whole index. | §3.3, §5.2 |
| B3 | Version stamped in `finish` | Gone: no per-row event data. The only stored derivation (the slot in `display_name`) uses `DisplayNames`, the only raiser of the version, under `ImportMutex`, paged. | §5.1, §5.3 |
| B4 | `EVENT_NAME` collides with `RTL` | Bit 27, with a disjointness test, in commit 0. | §4.4, §11.1 |
| B5 | Renamed rows not redrawn | `epgBasisOf` folds in the event basis, in commit 0. Rows also reload on `EventIndex.version`. | §4.4, §6.5 |
| S1 | Hint scope; `" \| "` split; NBA sides | Hints read over the whole name and group; segments split on `" \| "`. The other-league nickname table is replaced by exact side matching, which rejects "Boston Celtics" and "Basketball NBA Dallas Mavericks" without a table (S2). | §3.7, §8.4 |
| S2 | PLACE by containment | Every side match is now equality, after cleaning. | §4.2, §8.4 |
| S3 | Symmetric year window | [−300 d, +90 d], with a test. | §3.4, §11.1 |
| S4 | Watch slot lost | Owner ruling: keep "Channels (n)" with the user's team channel focused first. | §8.6 |
| S5 | Reminders bound to a slot; TVG keys | Fire-time check (same, moved, gone); the TVG orphaning is stated and tested. | §6.6, §9.4 |
| S6 | Catch-up gating | Gated on `EVENT_NAME`: menu Watch, Watch from start, Start-over and the renderer mark (one flag test). | §6.4 |
| S7 | Reschedule hook, owner, cost | Hook after `finish`; the interval is a default (auto flag); `PlaylistPages.kt` is in WP-2. Cost: the log shows renames queued no patch (§2.5), with a Fire TV gate and a 12 h fallback. | §10, §12 |
| S8 | Backfill placement | No event backfill. The index warm-up and the display-name backfill run in `TheBoxApplication` (WP-2), under `ImportMutex` where they write. | §5.2, §12 |
| S9 | Counts included 1,101 missing rows | Every count redone on live rows; every query excludes `missing_since`. | §2 |
| S10 | No-reader and cache paths | The overlay and synthesised info work without a reader; `InfoKey` and the panel's reuse compare the event basis and title hash. | §6.1, §6.2 |
| S11 | In-flight hub work | Written against the 864 px hub; the lead lands it before commit 0; no footer hints. | Inputs, §12 |
| S12 | D.M readings | Nearer reading for European zones when both are valid. | §3.2, §11.1 |
| S13 | Window edge | +45, with the owner's string as a fixture. | §8.3, §8.4 |
| N1 | Read-time derivation | **Adopted**, with the slot in `display_name` and hiding through `rule_hidden`. | §5.1 |
| N2 | Drop the title → id map | Taken: constant −2. | §6.1 |
| N3 | Search in memory | Taken. | §7 |
| N4 | "Read events from names" switch | **Not taken.** Unknown shapes already return `None`; a wrong-zone family is bounded by the LIKELY cap and the guide sentence; and the owner's hide switch covers the clutter. One more settings row is not worth it in v1. | — |
| N5 | Horizon at match time | **Not taken** (§8.5): the measured placeholder scan (0.9 s on the Mac) would land on the first hub open; computing it in the background build costs under 1 s, and the "mapped only" filter is already implied (7,841 of 7,841 mapped). | §8.5 |
| N6 | Editorial | Fixed (no column count; `\|` noted as shape A's). | §0, §2.1 |

### 15.2 UX review

| Id | Finding | Response | Where |
|---|---|---|---|
| U-E-B1 | Caveats cut on a one-line reason | Time-first one-line reasons; no caveat sentences; line 3 holds group and variants; check 8 rewritten. | §9.1, §11.4 |
| U-E-B2 | Raw names everywhere | Slot short names through `display_name`; a custom name wins; raw-text search kept. | §5.3 |
| U-E-B3 | Team channels ranked low, capped, named like a team | Team channels first (the user's team first), outside the cap; a reserved event row; `on_tv_team` strings. | §8.6, §9.1 |
| U-E-B4 | Beyond-guide line too long and misleading | No date; three short lines; the line goes into the content description. | §8.5, §9.1 |
| U-E-S1 | Team channels beyond the guide | Owner ruling c: grey chip, the line names the team channels. | §8.5 |
| U-E-S2 | One-press Watch | Owner ruling b: keep Channels (n) with the user's team focused. | §8.6 |
| U-E-S3 | Overtime and after-event cells | `EVENT_MAY_BE_ON` (3 h), `EVENT_ENDED`, `NEXT_EVENT`. | §6.3, §9.2 |
| U-E-S4 | Existing next-step wording | Taken verbatim. | §9.2 |
| U-E-S5 | "oilers" vs cities; no-guide hiding; short names on cards | Nickname → places expansion; the programme row is drawn with event hits; short names. | §7 |
| U-E-S6 | 6 h respects Manual and choices | Auto flag; the row shows it with the recommendation. | §10 |
| U-E-S7 | Drop caveat sentences | Dropped. ASSUMED rows show no time in the hub. Cities-only CONFIRMED under the \|Δ\| ≤ 15 and clean-board rule. | §8.4, §9.1 |
| U-E-S8 | Same phrase in hub and guide | "from the channel name". | §9.1 |
| U-E-S9 | Reminder on a moved slot | Fire-time check (moved, gone). | §6.6 |
| U-E-S10 | 3,766 empty slots | Owner ruling a: the per-playlist switch, off by default. | §5.5 |
| U-E-N1 | "No event" in grid cells | Left: it needs per-row text in `RowCellsBuilder`; the panel says it. | §6.3 |
| U-E-N2 | "about" end | Taken. | §6.4 |
| U-E-N3 | "Also in <group>" | Taken. | §9.1 |
| U-E-N4 | Shorter `EVENT_FROM_NAME` | Taken. | §9.2 |
| U-E-N5 | Never-cut "+n more" | Taken. | §9.1 |
| U-E-N6 | Unnamed network fallback | Taken. | §9.1 |

### 15.3 Earlier open questions, settled

| # | Question | Settled by |
|---|---|---|
| 1 | Team channels beyond the guide | owner ruling c: grey chip, the line names them |
| 2 | Names without a zone | lead default: learned zone, no setting |
| 3 | 6 h refresh | lead default: a default only, shown in the row |
| 4 | City-only names | lead default: no warning sentence; CONFIRMED under U-E-S7's rule |
| 5 | EFL / UEFA style | lead default: not in v1 |
| 6 | Empty slots | owner ruling a: switch, off by default |
| 7 | Team channels first | U-E-B3 (accepted) |
| 8 | One-press Watch | owner ruling b: keep Channels (n) |
| 9 | Short names | lead ruling 5 |

---

## Appendix A: constants

| Name | Value | Use |
|---|---|---|
| `HUB_EVENT_BEFORE_MIN` / `HUB_EVENT_AFTER_MIN` | 90 / 45 | event start window around the game (§8.4) |
| `HUB_CITIES_CONFIRM_MIN` | 15 | \|Δ\| for the cities-only CONFIRMED (§8.4) |
| `HUB_BOARD_CLASH_MIN` | 180 | other games on the board involving a place (§8.4) |
| `HUB_LOOKAHEAD_MIN` | 720 | the horizon's floor (§8.5) |
| `HUB_TEAM_QUERY_MAX` | 400 | stage T rows per league |
| `HUB_MATCH_MAX` | 8 | rows besides team channels (§8.6) |
| `EVENT_SEARCH_LIMIT` | 60 | search's event hits (§7) |
| `EVENT_SEARCH_DEDUP_MIN` | 15 | event vs listing on one channel (§7) |
| `EVENT_MAY_BE_ON_MIN` | 180 | after the estimated end (§6.3) |
| `EVENT_REFRESH_H` / `EVENT_REFRESH_MIN` | 6 / 50 | the default interval (§10) |
| `EVENT_ZONE_MIN_SAMPLES` / hysteresis factor | 20 / 2 | the learned zone (§3.3) |
| Year window | −300 d / +90 d | §3.4 |
| `EventDuration` | 210 / 135 / 180 / 60 | football / soccer / other matchup / show |
| `EVENT_TEXT_ID` | −2 | synthetic text id (§6.1) |
