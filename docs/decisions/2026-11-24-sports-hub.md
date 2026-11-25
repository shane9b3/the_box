# Sports Hub: the new Sports page, NHL game detail, video and the channel link (2026-11-24)

Status: built on branch `sports-hub` (WP-1 … WP-5, integration pass 2026-11-25). Design
`docs/design/m6-sports-hub.md` (signed off by the lead, §13); reviews `docs/review/2026-11-24-sports-hub-design-review-{arch,ux}.md`
and their re-review. This note records the owner's answers and the few behaviours the design asked to be written down.

## Request

The owner's mock-ups (`nhl.jpeg`, `nfl.jpeg`, 2026-11-24): turn the Sports tab into a hub. A detail panel for the focused
game (score, team stats, a summary and a picture), the day's games as cards on the right, a strip of days, the game's
goals and recap as videos, and a way to get from a game to the channel showing it.

## Owner rulings (2026-11-24)

1. **Video in the app, with the web player's key read at run time; risk accepted.** Clips play through the app's own
   player (the one `ExoPlayer` on the root `SurfaceView`, through the transport and the `StreamConnectionBroker` under a
   `clip:<host>` key). The key is the one nhl.com's public web player ships; the app reads it from that script when it
   needs it, keeps it in memory only and registers it with the log redaction. It is never written to the repo, a test, a
   tool, a fixture or a log (tests and the fake panel use `FAKE-KEY-FOR-TESTS*` literals). The owner accepts that the NHL
   can rotate the key (handled: one re-read) or block non-browser use (handled: scores and stats keep working and the page
   says videos are unavailable). Rejected: a WebView of the embed page (a second video pipeline beside the one surface,
   heavy on a Fire TV Stick, web focus fights the D-pad) and no video at all.
2. **Picking a channel tunes only.** Watch / a Channels row tunes and goes full screen; it does not start following the
   game.
3. **OK on a card enters the panel's three fixed buttons.** OK or Menu on any card (either column, every league and
   phase), or Left from the left column, moves focus to the first enabled button. The three slots never reorder and are
   frozen while focus is in the panel; Back, or Right from the last enabled slot, returns to the same card. No long press,
   no actions list.
4. **Defaults accepted:**
   - the Corner and Delay chips leave the page (Settings › Sports and the quick bar keep them);
   - days −7 … +7 (15 chips; NFL keeps Last / This / Next week);
   - the next goal auto-plays ("Up next" for 1.5 s);
   - captions follow the player's subtitles setting (Menu switches them for one clip);
   - guessed regional channels are shown, with a warning in their wording;
   - English clips only;
   - no reminders.
5. **Local affiliates are matched through guide listings only.** ABC / CBS / FOX / NBC / CTV stations and the CBC
   regionals never match by name; a station appears only when its guide names the game in the game's window.

## Recorded behaviours (design §11.1)

- **Back after Watch (U-N3).** Watch tunes and closes the page into full screen; Back from there is the player's normal
  Back (the guide), not the Sports page. The page is one Back from the guide again.
- **Local time-shift under a clip (§6.6).** A clip discards the device's own pause-and-rewind buffer (the clip uses the
  one player). After the clip the channel resumes **at live**, not at the rewound position. A provider catch-up that was
  playing resumes at its playhead through `ArchivePlayback`.
- **Home or standby during a clip:** nothing plays on return (neither the clip nor the channel under it). Owner question 4
  of the design offers the alternative.
- **Follow moved.** Follow is slot 1 of the panel (it was OK on the card); a finished game offers no Follow.

## Integration pass decisions (2026-11-25)

- **Captions of HLS clips come from the master.** The lead's recording of a real goal clip's master lists its own English
  `SUBTITLES` rendition with `DEFAULT=NO` and `CLOSED-CAPTIONS=NONE`, up to 1920 × 1080. HLS clips use that rendition
  (`MediaSourceBuilder.CLIP_HLS_MASTER_CARRIES_CAPTIONS = true`); the controller adds English to a clip's preferred text
  languages so the subtitles setting selects it although it is not flagged default. The MP4 fallback keeps the
  side-loaded WebVTT.
- **A final game whose board carries the recap keeps Watch recap in slot 1 when its stats fail.** The panel body says
  the stats retry on their own and names Watch recap as the next step. Retry takes slot 1 only when no recap can play.
- **One browse rest, not two.** The page rests 600 ms, then shows the game to the detail repository twice (its contract:
  a second show of the resting game skips its own rest).
- **The resumed channel waits for the page's viewport.** After a clip the guide view model tunes only once the root has
  given the controller the page's viewport back (at most 500 ms), so the channel never prepares at full screen under
  the page and no display-mode switch happens there.

## Not done in this version

NFL and CHL have no video and no box score. The "Live now" chip, loading the picture only on Play, and a different
headline wording are open owner questions (design §14).
