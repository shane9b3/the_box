# UX / product review: event channels, team channels, the guide horizon

Reviewer: UX (adversarial), 2026-11-25. Design under review: `docs/design/m6-event-channels.md` (proposed 2026-11-25).
Read against: the owner's request (verbatim, lead's facts file), `docs/01-design-reference.md`, `m6-sports-hub.md` §1–§5,
current code on `main` (`HubPanel.kt` channel line, `ChannelSheet.kt`, `strings_sports_hub.xml`, `FocusedPanel.kt`,
`SearchScreen.kt`) and the owner's `library.db` copy (read-only). The 864 px panel / hint-row change in progress is out of
scope.

IDs: **U-E-B** blocker, **U-E-S** should-fix, **U-E-N** nice-to-have.

---

## Summary

The design reads the owner correctly on three of four asks. It removes the regional guesses, adds team channels and
greys out Channels beyond the guide. The parser and the guide overlay are sound. Where it falls short is what the owner
sees on the couch:

- The raw provider name, the "strange format" the owner complained about, is still what the hub line, the sheet's name
  line, the search card and the guide's label column show.
- The sheet's caveat sentences are written for a line that is one line high in the code, so the part that matters is
  cut off.
- The team channels the owner called the place the game "always" is are ranked after guide listings, and the eight-row
  cap can drop them.
- The beyond-guide line is 84 characters on a one-line, 664 px slot.

None of this needs a redesign. All of it is fixable in commit 0's strings and contracts.

---

## 1. Does the replacement do what the owner meant?

**"remove that line and may[be] match with a real search".** The owner saw the hedge on **future** games ("not todays").
Today's games had guide listings. Future games had only alias guesses, so the sheet was all "Home team's local
channel…" rows and the "Regional channels may show…" footer. The design does three things:
- it removes regional-only candidates;
- it keeps a regional channel when its guide names the game (stage C);
- it greys out Channels beyond the guide.

Together these remove every source of that line. "A real search" is best read as "find it the way Search would":
guide titles, team channels and channel names. That is what stages B, T and E do. **Agreed.**

**Does it remove channels the owner used?** I checked the owner's copy. Every Canadian regional the owner is likely to
use has a `tvg_id` and therefore guide data: Sportsnet Ontario, East, West, Pacific and Oilers, the TSN 1–5 channels, and
the US regionals (SportsNet Pittsburgh, Detroit SportsNet, MSG Sportsnet). So for any game inside the guide they come
back as CONFIRMED (or as LIKELY on a sport listing). Lost are only the regional channels with no guide data:
`4K: TSN East` and `4K: TSN West` (they were never alias matches anyway) and any regional whose guide simply doesn't
list the game. The second case is exactly the "may differ" situation the owner asked to drop. `4K: Sportsnet` is a
national alias and stays. **No objection.**

**Each reason type, as designed and as it should read.** The full list of replacement strings is in U-E-B1, U-E-B2 and
U-E-B3.

| Reason | On TV line (designed) | Sheet row (designed) | Verdict |
|---|---|---|---|
| Guide listing | "On TV: Sportsnet West · in your guide · +7 more" | "In your guide: NHL Hockey: Golden Knights at Oilers, 9:00–11:30 PM" | fine |
| Team channel | live: "On TV: Edmonton Oilers · team channel"; **upcoming: "On TV at 8:00 PM: Edmonton Oilers"** | "Team channel. It carries every Oilers game." | the upcoming line reads as a team, not a channel (U-E-B3) |
| Event name | "On TV at 8:00 PM: Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:…" | name line = the raw name; reason "Channel name: Vegas @ Edmonton at 8:00 PM. It names cities only, so check it's this game." | raw name repeated, cut (U-E-B1, U-E-B2); "at … at" in titles that contain "at" |
| Sport listing | "· in your guide" | unchanged | fine |
| National broadcaster | "· national broadcaster" | "Sportsnet carries this game in Canada. Your guide doesn't list it on this channel." | fine (one hedge left, but a true one) |

---

## Blockers

### U-E-B1. The sheet's reason line is one line, so the caveats are cut, and check 8 tests something the code doesn't do

**Scenario.** It's 7:55 PM. The owner opens Channels (9) and scrolls to the Sportsnet+ row. The reason line is
`Line(reason, sans(22), fillMaxWidth())`, and `Line` defaults to `maxLines = 1` with an ellipsis. The row is a fixed 96 px
(`ChannelSheet.kt`). The text area is about 770 px: 968 sheet − 56 padding − 96 logo − gaps. That holds roughly 58
characters at Default and 47 at Largest (0.55 em, the hub's own estimate). What the owner sees:

> Channel name: NHL on TNT: Golden Knights at Oilers at 7:3…
> Channel name: Vegas @ Edmonton at 8:00 PM. It names cities…

The caveat, which is the whole reason the string exists, never shows. Emulator check 8 expects the longest reason "wraps
within its row (two lines at 22)". It won't. Either the check fails, or someone grows the row and breaks the 96 px
pitch of the shared sheet frame.

**Change.** Keep the reason to one short line and put any caveat on the **third** line. That line already exists and
holds the playlist name and "Also: …" (`listOfNotNull(playlist, also, caveat)` joined by " · "). Put the time first, so
truncation eats the end of the title, never the time. Replace the §9.1 additions with these:

```xml
<!-- Sheet, event name. %1$s = the start time ("7:30 PM"), %2$s = the title as the name gives it. -->
<string name="sports_hub_reason_event">From the channel name: %1$s, %2$s</string>
<!-- Sheet, event name whose zone was assumed: no time shown (the panel already shows the game's time). %1$s = the title. -->
<string name="sports_hub_reason_event_untimed">From the channel name: %1$s</string>
<!-- Third line, cities-only event (only if Q4 keeps a caveat; see U-E-S7). -->
<string name="sports_hub_note_cities">Names cities only</string>
```

Delete `sports_hub_reason_event_cities` and `sports_hub_reason_event_zone` (reasons in U-E-S7). Change check 8 to:
"Largest: every sheet row keeps its time and title start visible; no caveat is cut, because none is longer than the
third line."

### U-E-B2. Event channels still show their raw "strange format" name everywhere a channel name is drawn

**Scenario.** The owner's complaint was that the Sportsnet+ names are "formatted strange". After this design the parsed
title appears as a programme, but `channel.displayName` stays the raw name, because `display_name` is NULL for these rows
in the owner's db and falls back to `name`. So:
- **Hub line**, upcoming game: "On TV at 8:00 PM: Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET · +6 more". At 22
  px on 664 px this cuts to "On TV at 8:00 PM: Sportsnet+ 09: Vegas @ Edmonton @ 24…" and the "+6 more" is lost.
- **Sheet**: the name line (26/600) repeats the event the reason line already says.
- **Search card** (520 px wide): the title "Vegas @ Edmonton", then the channel line "Sportsnet+ 09: Vegas @ Edmonton @
  24…".
- **Guide label column** (280 px): "Sportsnet+ 09: Vegas @ Ed…" next to a cell that says "Vegas @ Edmonton". After the
  event ends, the label still names yesterday's game until the next refresh. That is the stale name, in the most
  visible place.

**Change.** Add a **short name** to the contract. For `event_kind` EVENT or EMPTY, show the parsed **slot** ("Sportsnet+
09", "NHL 01", "CA (TSN+ 008)") as the channel's name in the guide label column, search cards, the hub line and the sheet
name line. Rules:
1. A user's custom name always wins. Never shorten a renamed channel.
2. Shape C with no slot (`flowrestling: …`) keeps the raw name; there is nothing better.
3. When two rows in one sheet would show the same short name ("NHL 01" in `Sports | NHL` and in `Sports | NHL (2)`),
   append the group to the third line: "Sports | NHL (2)".
4. The player's info bar and the channel list show the short name too. They read the event through `feed.at()` (the
   `PlayerViewModel` path), so the event title still shows as the programme.

Implementation: store `event_slot TEXT` (the parser already returns `slot`) or derive it on read. Expose it as
`GuideChannel.displayName` when no custom name is set. The architecture reviewer chooses where; UX needs only the
outcome. Add to §11.4: "The guide's Sportsnet+ rows read `Sportsnet+ 09` in the label column, and the hub line reads
`On TV at 8:00 PM: Sportsnet+ 09 · +6 more`."

### U-E-B3. Team channels rank after guide listings, can be capped out, and the upcoming line names them like a team

**Scenario.** The owner said "the game will always be on the teams channels". Tonight's Golden Knights at Oilers game,
8:00 PM, in the owner's playlist would collect:
- CONFIRMED guide listings: Sportsnet West, Sportsnet Oilers, TNT, truTV, and any of their variants that are not
  collapsed;
- CONFIRMED team channels: Edmonton Oilers and Vegas Golden Knights;
- CONFIRMED event names: NHL 01, NHL (2) 01 and MAX 002;
- a LIKELY event name: Sportsnet+ 09;
- national LIKELY channels.

The §8.6 order is confidence, then evidence class (guide before team), and the cap `HUB_MATCH_MAX` is 8. With five guide
listings, the two team channels come in 6th and 7th. Sportsnet+ 09, from the very group the owner named, is cut. With
seven guide listings the team channels are cut too. Emulator check 5 passes only because the fake XMLTV is small.

Separately, the **upcoming** form of the line, `sports_hub_on_tv_at`, has no reason. If a team channel tops the list,
the owner reads "On TV at 8:00 PM: Edmonton Oilers" in the panel of a Golden Knights vs. Oilers game. That reads as a
team, not a channel.

**Change.**
1. **Order within CONFIRMED: team channels first.** Among the two: the **followed** team's channel first (the owner's
   explicit signal on this page), then a favourite, then watch count, then **home before away**. The home broadcast is
   the default feed. "Away first" is a naming rule for headlines, not a viewing rule. Then guide listings, then event
   names.
2. **Never cap the team channels.** They are reserved; the cap applies to the rest. Also keep at least one event-name
   row when stage E found one, so the owner's Sportsnet+ row always appears. Simplest form: the cap is 8 **plus** the
   team channels, and the last slot of the 8 goes to the best event-name row when none made the cut.
3. Strings (nicknames from `TeamName.nicknames[0]`):

```xml
<!-- Channel line, a team channel on top. %1$s = the nickname ("Oilers"). -->
<string name="sports_hub_on_tv_team">On TV: %1$s team channel</string>
<!-- Upcoming. %1$s = the start time, %2$s = the nickname. -->
<string name="sports_hub_on_tv_team_at">On TV at %1$s: %2$s team channel</string>
<!-- Sheet, team channel. %1$s = the nickname. -->
<string name="sports_hub_reason_team">Team channel. Oilers games are always on it.</string>
```

The last string should be `%1$s games are always on it.` It uses the owner's own word ("always") and avoids "carries
every…", which sounds like a broadcast-rights claim. Delete `sports_hub_reason_short_team`: the reason is now part of
the name phrase. Worst case at Largest: "On TV at 10:30 PM: Golden Knights team channel · +8 more" is 55 characters.
That overflows. See U-E-N5 for keeping "+n more" uncut; the name part may ellipsize.

### U-E-B4. The beyond-guide line can't fit, and its date reads as "the guide covers the game day"

**Scenario.** The owner focuses a game six days out. Channels is greyed out, and the line reads "Your guide has listings
up to Thu, Nov 27. Check Channels again closer to the game." That is 84 characters on a one-line, 22 px, 664 px slot
(`Line`, `maxLines = 1`), which holds about 55 at Default and about 45 at Largest. It shows as "Your guide has listings
up to Thu, Nov 27. Check Chann…". Worse, `dataToMin` is a median last end, typically an early-morning time such as
Nov 27 at 4:00 AM. A game on Thu Nov 27 at 7 PM then shows "listings up to Thu, Nov 27" with a grey chip. The owner
reads that as a bug.

**Change.** Drop the date. The owner asked only to grey out the chip; the line just has to explain the grey. Use these
strings:

```xml
<!-- Channel line, game beyond the guide (§8.5). 38 characters: fits at Largest. -->
<string name="sports_hub_beyond_guide">Your guide doesn\'t cover this game yet</string>
<!-- Beyond the 12-hour look-ahead with no guide at all. 38 characters. -->
<string name="sports_hub_beyond_guide_none">Channels show 12 hours before the game</string>
```

"yet" carries the next step: the chip comes back on its own as the guide rolls forward. Also put the line into the
panel's content description. The disabled chip is skipped by focus and never announced (`sports_hub_desc_press_ok`
lists enabled slots only), so a TalkBack user would otherwise never learn why there is no Channels. With U-E-S1 below
(team channels beyond the guide), this line is replaced by the team line whenever team channels exist.

---

## Should-fix

### U-E-S1. Open question 1: name the team channels beyond the guide, and keep the chip grey

The owner said two things: "the game will always be on the teams channels" and "for games outside the epg download just
grey out the channels chip". Both hold if the **chip stays grey**, exactly as the owner asked, and the **line** names
the team channels. Stage T needs no guide data. The cost is one cached query per league.

```xml
<!-- Channel line beyond the guide when team channels exist. %1$s / %2$s = away / home nicknames; the caller falls back to
     abbreviations ("VGK", "EDM") when the nickname line would not fit. -->
<string name="sports_hub_beyond_guide_team">On the %1$s and %2$s team channels</string>
```

"On the Golden Knights and Oilers team channels" is 46 characters; use the abbreviation fallback at Largest. Recommended
as the default answer to Q1. Ask the owner to confirm in one line.

### U-E-S2. The one-press Watch slot never appears again for NHL or NFL games

**Scenario.** Before this change, a game with exactly one CONFIRMED channel showed **Watch** in slot 2: one press tuned
it. Every NHL and NFL game now has at least two CONFIRMED team channels, so slot 2 is always "Channels (n)". Getting to
the game takes OK, OK, OK instead of OK, OK. The design notes this in one sentence (§8.6) but does not treat it as a
regression.

**Change (owner question).** The three fixed slots allow only one action in slot 2, so there are two honest choices:
- (a) keep "Channels (n)", with the followed team's channel first and focused in the sheet. That is still OK, OK, OK,
  but the right row is already focused.
- (b) "Watch" on the followed team's channel, when the game is imminent or live **and** the user follows exactly one of
  the two teams **and** that team's channel is CONFIRMED. The other channels can then no longer be reached from this
  slot.

Ask the owner. Default to (a), which needs only the U-E-B3 ordering.

### U-E-S3. Estimated ends mislabel a game in overtime as "ended", and the "now" cell after an event says nothing

**Scenario.** A hockey event estimated at 180 minutes, 8:00 to 11:00 PM, goes to a shootout and runs to 11:20. At
11:05 the event cell is in the past. The now cell is a GAP, "No information", and its panel shows the existing "No
guide data for this time." If the owner focuses the past cell, it says "This event has ended…" while the game is still
on. §6.3 covers only EMPTY slots. Nothing covers the now cell on a channel whose parsed event has passed. That is
the most common state of an event channel from late evening until the next refresh.

**Change.** Replace `EVENT_ENDED` with two constants:

```kotlin
// Within 3 h after the estimated end.
const val EVENT_MAY_BE_ON = "The end time was estimated from the channel name, so this event may still be on."
// Later than that.
const val EVENT_ENDED = "This event has ended. The channel's next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
```

For a GAP or NO_EPG cell on a channel with `event.kind == EVENT` whose event has ended, the panel title is `NO_EVENT`
("No event scheduled") and the description is the new `EVENT_ENDED`. For a gap **before** a future event, use
`NEXT_EVENT` (format, built in the panel builder):

```kotlin
const val NEXT_EVENT = "Next on this channel: %1\$s at %2\$s."   // "Next on this channel: Montreal @ Colorado at 9:30 PM."
```

### U-E-S4. Next steps should use the app's existing wording

`NO_EVENT_NEXT` says "update it now in Settings". The app elsewhere says "Refresh the playlist in Settings"
(`error_step_refresh_playlist`) and "Refresh it in Settings › Playlists" (editor). Replace it with:

```kotlin
const val NO_EVENT_NEXT = "Nothing is scheduled on this channel. Its next event appears when your playlist updates, or refresh the playlist in Settings › Playlists."
```

### U-E-S5. Search: "oilers" doesn't find the Sportsnet+ game, and with no guide the event results are hidden

1. Emulator check 4 expects `Vegas @ Edmonton` for the query "oilers". `channel_fts` prefix matching on "oilers" can't
   match "Vegas @ Edmonton". The owner's own example group (Sportsnet+) uses cities only, so the owner will search
   "oilers" and miss it. **Change.** When a query word equals a team's nickname or full name (`TeamNames`, NHL and
   NFL), add that team's places as alternative terms **for the event stage only**: "oilers" becomes "oilers OR
   edmonton". The §7 step-2 word filter then accepts either. Keep check 4, which becomes true.
2. `SearchScreen` shows `search_needs_guide` **instead of** the programme row whenever `guideDataMissing`. The design
   says event hits appear with no guide and "no UI change", but they won't be drawn. **Change.** Draw the programme
   row when it has event hits, even with `guideDataMissing`. Show the hint only when the row is empty.
3. Each search card's channel line follows U-E-B2 (the short name).

### U-E-S6. The 6-hour refresh must respect Manual, and the owner must be able to see and change it

**Scenario.** §10 uses `min(refresh_interval_h, 6)`. A user who set **Manual** (`setup_interval_manual`), or a longer
interval to save data, is silently overridden. **Change.**
- Manual stays manual.
- Any interval the user picked after the playlist was found to have event channels stays as picked.
- The 6 h is the **default** for event playlists, not a ceiling.
- In the playlist's Update interval row, show the setting as `Every 6 hours`, with the subtitle: "Recommended: this
  playlist's channel names list upcoming events."

Keep "Refresh now" in the playlist menu as the manual next step that every message above points to.

### U-E-S7. Drop both caveat sentences from the hub (Open questions 2 and 4 in the hub's context)

- **Zone assumed.** An event only reaches the sheet if its parsed start is within −90 to +30 minutes of the board's
  start, so a matching time partly *confirms* the assumed zone. A five-hour error can never match. A one-hour error
  (Central vs. Eastern) can, and then the shown time is wrong by an hour. **Change.** For ASSUMED rows, don't show the
  event time in the sheet (`sports_hub_reason_event_untimed`). The panel already shows the game time. Drop the
  sentence. The guide keeps `EVENT_ZONE_ASSUMED`, where it is useful.
- **Cities only.** On the owner's playlist every Sportsnet+ game row is cities-only, so the owner would read "check it's
  this game" every night and learn to ignore it. The protections are already strong: negative hints, the ordered
  separator, place-sharing, and the fact that two leagues can't play the same city pair at the same time in one arena.
  **Change (Q4).** Show the row as LIKELY with no sentence. Upgrade it to CONFIRMED when the separator is ordered and the
  order is right **and** |Δ| ≤ 15 min **and** no other game on the loaded boards (NHL, NFL) involves either place within
  the window. A nickname collision without a positive hint (Jets vs. Panthers) stays LIKELY, also with no sentence. LIKELY
  rows already sort below CONFIRMED, and that is enough signal.

### U-E-S8. Use the same phrase for an event in the hub and in the guide

The guide says "From the channel name"; the hub short reason says "in the channel name". **Change.**
`sports_hub_reason_short_event` becomes `from the channel name`, giving "On TV: NHL 01 · from the channel name · +5
more".

### U-E-S9. Reminders on event cells can fire on the wrong game

The design keys reminders by `(playlist_id, stable_key, rawStartMin, titleHash)`. That is correct under the rules, but
the stable key is the **slot** (`xc:<stream id>`), and providers reuse and reshuffle slots. If the owner sets "Remind
me" on Sportsnet+ 23 "Montreal @ Colorado 9:30 PM" and the 3 PM refresh moves the game to Sportsnet+ 25, the reminder
fires at 9:30 for whatever is on 23. **Change.** At fire time, if the channel's current event title hash differs from
the reminder's, look for an event channel in the same playlist with the same title hash and a start within ±30 minutes.
If one is found, offer that channel. Otherwise show:

```xml
<string name="toast_reminder_event_moved">Your provider changed %1$s\'s channel. Search for it to find where it is now.</string>
```

Add one emulator step only if cheap (admin `names: next` already exists).

### U-E-S10. 3,866 empty slots crowd the guide (add as Open question 6)

The owner's copy has 1,955 NO EVENT, 1,506 bare-colon, 262 Offline and 143 placeholder slots. They sit in their own
groups (FloSports 329, ESPN+ 300, ESPN Unlimited 254, …), but they fill "All channels" and channel search with rows that
say "No information". Search already sinks them. **Proposed question for the owner**, with this default: a per-playlist
switch "Hide channels with no event", default **off**, applied as the existing `rule_hidden` at import. That works
because rows keep their ids, and a favourite stays visible because user data wins. With the switch on, a slot reappears
when its next refresh names an event. At minimum, sink empty slots to the end of their group in the guide's All view.

---

## Nice-to-have

- **U-E-N1.** Empty-slot grid cells say "No information" while their panel says "No event scheduled". If `RowCellsBuilder`
  stays untouched, accept this. If a later pass can pass a per-row text id for NO_EPG cells, use "No event".
- **U-E-N2.** The time line "8:00 PM – 11:00 PM · From the channel name" shows the end as fact. Change the format to
  "8:00 PM – about 11:00 PM · From the channel name". It fits mono 24 on one line.
- **U-E-N3.** Backup groups: with "Edmonton Oilers" in both `NHL Teams` and `NHL Teams (2)`, `alsoLine` finds no extra
  words and prints "Also: Edmonton Oilers". **Change:** when a variant's name is identical, print the group:
  `<string name="sports_hub_also_in">Also in %1$s</string>` ("Also in Sports | NHL Teams (2)").
- **U-E-N4.** `EVENT_FROM_NAME` is good. Make it a little shorter so it doesn't push the rest of the description down:
  "From the channel name: your guide has no listing for this channel. The end time is estimated."
- **U-E-N5.** Lay out the hub channel line as two texts: the name part (ellipsizes) and " · +n more" (never cut). Today
  one `Line` cuts the count first.
- **U-E-N6.** `sports_hub_reason_network_any` now also covers unknown codes: "SNP carries this game." Show the code only
  when it is at least 3 characters and has letters. Otherwise use "A national broadcaster carries this game. Your guide
  doesn't list it on this channel."

---

## Open questions: recommended answers

| # | Question | Recommendation |
|---|---|---|
| 1 | Team channels beyond the guide | Chip **grey** as asked; the line names them (U-E-S1). |
| 2 | Names without a zone | Learned zone as designed; no setting in v1; guide keeps the "may be off" sentence; hub hides the time for ASSUMED rows (U-E-S7). Revisit if NBA LP / NFL GP (UTC) matter to the owner. |
| 3 | Refresh every 6 h | Yes as the **default**, never over Manual or a user-chosen interval, visible in the playlist row (U-E-S6). |
| 4 | City-only names | Show, no sentence; CONFIRMED under the stricter rule in U-E-S7. |
| 5 | EFL "Cardiff 19:45 Preston 24/11" | Agree: not in v1. |

Questions the proposer should have asked:
- **6.** Should empty event slots be hidden or sunk (U-E-S10)?
- **7.** Should team channels lead the Channels list, and should the followed team's channel be first (U-E-B3)?
- **8.** One-press Watch for the followed team, or keep Channels (n) (U-E-S2)?
- **9.** Should event channels be shown by their short slot name, "Sportsnet+ 09" (U-E-B2)? This is not really optional,
  but the owner should see it.

---

## Emulator checklist (§11.4): right idea, two checks are wrong, two states missing

Keep it compact. Amend it to these eight, adding no full walks:
1. Guide, `Sports | Sportsnet+`: the label column reads `Sportsnet+ 09`; the `Vegas @ Edmonton` cell; the panel time
   line "… · From the channel name" (merges the old check 1 with U-E-B2).
2. One screenshot of the NO EVENT row's panel, and one of the **now** cell on the stale row: "No event scheduled" plus
   the `EVENT_ENDED` sentence (merges the old checks 2 and 3 with U-E-S3).
3. Search "oilers": event results include NHL 01, NHL on TNT **and Vegas @ Edmonton** (needs U-E-S5.1); channel results
   include `Edmonton Oilers`; cards show short names.
4. Sports, the featured game, OK → Channels (n): **team channels first**, then guide, then events; the Sportsnet+ row is
   present; no "local channel" text, no footer.
5. The same game's panel line before the game: "On TV at 8:00 PM: Oilers team channel · +n more".
6. A game 6 days ahead: Channels greyed and **skipped by Left / Right**; the line "On the Golden Knights and Oilers team
   channels" (or "Your guide doesn't cover this game yet" with team channels off in the catalog).
7. Admin `names: next` + refresh: the new event in the guide (unchanged).
8. Largest: the sheet's longest row and the panel's channel line keep their time and "+n more" visible (replaces the
   wrapping check, U-E-B1).

Fake panel additions:
- Enough guide-listed channels for the featured game (at least 7) so the cap and ordering of U-E-B3 are really
  exercised.
- An `NHL Teams (2)` duplicate of one team channel (U-E-N3).
- A second `NHL 01` in `Sports | NHL (2)` already exists; keep it, since it tests the duplicate short name.

---

## Verdict

**Ready after the listed changes.** The four blockers are wording, presentation and ranking: short names, the sheet's
line budget, team channels first and never capped, and the beyond-guide line. They change commit 0's frozen strings
and two contract details (a short-name field, the cap and ordering rule). Fix them before commit 0. The should-fixes can
land in WP-3 and WP-4 under the lead, except U-E-S3 and U-E-S4: those change `GuideTexts`, which commit 0 freezes.
