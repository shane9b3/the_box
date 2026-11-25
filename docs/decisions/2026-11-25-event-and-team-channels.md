# Event channels and team channels (2026-11-25)

Design: `docs/design/m6-event-channels.md` (signed off §16, integration §17, code review changes §18).
Reviews: `docs/review/2026-11-25-event-channels-design-review-*.md`, `...-code-review-data.md`, `...-code-review-ui.md`.

## Decisions

- **Events come from channel names, read at use time.** Many providers put the event and its start in the channel
  name and rename the channel at every playlist refresh. The name is parsed (no regex, never throws) into an in-memory
  index rebuilt per refresh; nothing parsed is stored except the slot's short display name. Reason: names change at
  every refresh, so stored events would always be stale.
- **A real guide listing wins over a name-derived event; provider placeholders ("No Event Today") do not.**
- **Team channels first.** A channel named exactly as a team in a "<league> Teams" group always carries that team's
  games (owner, 2026-11-25). It is listed first and is offered even for a game beyond the guide.
- **No cap on the hub's channel list** (owner ruling, 2026-11-25). Every matched channel is listed; feeds of one guide
  channel sit together and each stays selectable, because feeds differ in resolution and bandwidth.
- **Beyond the guide:** the Channels chip is grey only when there is no team channel to offer (owner ruling,
  2026-11-25; replaces "always grey").
- **A slot prefix or group label is never proof of the league** (the owner's "CHL" slots carry AHL games). Leagues
  without a team table are matched on the game's own team names, both sides.
- **"Hide channels with no event" is off until turned on** (owner ruling), per playlist, and uses `rule_hidden` with its
  own reason; a user's own hide or rule always wins.
- **Playlists with 50 or more event channels default to a 6 h refresh**, never over Manual or an interval the user chose.
- **The "local channel, may differ" line is removed.** A regional alias is offered only with a guide listing.
- **The guide's first page does not wait for the event index**; event cells arrive when the index is ready.

## Not verified on real hardware

Index build time, matcher time and refresh cost on a low-end Fire TV; the spoken reason on the grey chip with a screen
reader. See §18.8 for deferred review items.
