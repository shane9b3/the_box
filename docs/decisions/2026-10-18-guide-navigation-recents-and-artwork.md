# 2026-10-18 — Guide navigation, recents in the player, programme artwork (owner ruling)

Owner findings on the Sony and the emulator, ruled the same day. Supersedes the matching lines of
`docs/decisions/2026-10-15-player-keys-and-quick-bar.md` (Right in the player) and §3.4.6 of `docs/design/m3-guide.md`
(the back ladder).

## Guide: reaching Groups and the top bar
- **Superseded 2026-10-19** by ruling 1 of `docs/decisions/2026-10-19-guide-left-and-groups.md` (Left follows the M3
  watchable rule; the clipped-edge check is deleted; Back from home opens Groups instead). Original text:
  **Left on the cell at the window's left edge opens Groups at once.** It used to walk back through every catch-up day
  first ("it just scrolls the guide"). Holding Left still steps back in time; the rewind key does too.
- **Back walks outward:** overlay or zone → grid; scrolled grid → home; **home → the top bar** (Guide / Search /
  Settings, Left/Right to pick, OK to open); top bar → the exit step (hint, dialog or exit per the setting). A single
  Back never goes to the full-screen player any more; **Hold Back** does (ruling of 2026-10-17). `returnedFromFull` no
  longer gates anything in the ladder. **Amended 2026-10-19** by rulings 2 and 3 of
  `docs/decisions/2026-10-19-guide-left-and-groups.md`: home → **the Groups panel** (opened by Back) → the top bar with
  **Search** focused → the exit step. Groups opened any other way closes on Back to where it was opened from.
- Key table `core/src/test/resources/guide/keys.csv` rows exit/back/tabs updated; `GuideBehaviourTest.BackLadder`
  rewritten.

## Player: Right = recently watched
- **Right (live, no layer open) opens the channel list on the "Recently watched" source**, with what is on each
  channel now (the list already draws the programme and its progress). Without a recents source it opens the info bar
  as before. Down and the Info key keep the info bar; OK keeps the current group's list.
- `player_keys.csv` row for Right is unchanged (the table's world has no recents source); `RecentsListTest` covers
  both branches.

## Programme artwork beside the mini player
- `ProgrammeInfo.iconUrl` (the XMLTV `<icon src>`) now reaches the focused panel: `PanelState.imageUrl` → a 200 × 300
  card to the right of the title/description, fitted (posters and 16:9 stills both read).
- **This provider's EPG has no artwork at all** (198 248 programme texts, 0 icons on the emulator's build of it), so
  the card stayed empty on this playlist. Owner ruling the same day: posters are looked up on TMDB with the user's own
  key (`docs/decisions/2026-10-18-tmdb-programme-posters.md`).

## Settings pages
- Rows run to the card's bottom edge (clipped by the card's shape); the notice line, when there is one, overlays the
  bottom on the card's fill. The reserved 64 px band under the rows is gone.
