# The ladder turns tunneled playback off for the visit, not for good (2026-11-25)

Owner ruling: "make it try everytime we come back to the channel, it is making some of channel play strange when it
turns it off and it hard to find out whats happening to that channel".

## Before

When tunneled playback stalled or its decoder failed (ladder rows L27/L28, `DisableTunnelingAndReprepare`), the player
turned tunneling off and saved that in the channel's memory (`ChannelPlaybackMemory.tunnelingOff`, bit `0b1000` of
`channel_prefs.playback_flags`). Every later tune of that channel played untunneled, silently and for ever. The same bit
was also what Channel options › "Tunneled playback: Off for this channel" writes, so nobody could tell a one-off stall
from the viewer's choice.

## Decision

- **The ladder's switch-off lasts for the visit and is never saved.** A *visit* is the run of cycles on one channel with
  no user tune in between: the ladder's own re-prepares and restarts, Retry from the error card, a backup-source
  restart, the resume after leaving the app. Inside a visit tunneling stays off once the ladder turned it off, so there
  is no on → stall → off → restart → on loop (the ladder also sees `tunnelingActive = false`, so L27/L28 cannot fire
  again). The visit ends with any user tune — a tune from the guide or a list, a zap, the previous-channel key, a
  catch-up request — even of the same channel, and with a clip or a change between live and catch-up. The next tune
  follows the settings again.
- **The channel option is the user's alone.** It moves to its own bit, `0b100000` (`RoomChannelMemory.TUNNELING_OFF_BIT`);
  the ladder never writes it. It still persists, and it still wins: a channel set Off by the viewer never tunnels.
- **One-time clear of the old flags.** The old bit `0b1000` cannot say who set it, and almost every one was set by the
  ladder, so it is retired: never read again and never written again (`LEGACY_TUNNELING_OFF_BIT`). That is the clear:
  no migration, no upgrade step, no schema change; the stale bit is dropped the next time that row's flags are
  written. A viewer who had picked "Off for this channel" by hand before this version picks it again. The bit is
  reserved and must not be reused.
- **Saying so.** The toast now reads "Tunneling is off until you leave this channel (the picture stalled)", one line at
  Default and at Largest text (`TunnelingNoticeFitTest`). The stats overlay's Tunneling line shows `on`,
  `off (setting)` (the Playback setting or the channel option), `off for now (stalled)` (this visit), and since the code
  review the other reasons below (B3). Two info log
  lines: "tunneling: off until the viewer leaves this channel (<signal>); not saved" and, on the next tune of that
  channel, "tunneling: trying it again on this channel".
- Clips never tunnel, as before; a clip in between ends the visit.

## Cost

A channel whose tunneled decoder really cannot play stalls once per visit before the ladder turns tunneling off
(the existing L27/L28 path with its own switch window, as on the first occurrence today). The owner prefers that to a
silent permanent change. Since the code review this is capped at two visits in a row per app run (B2 below).

## Code review changes (2026-11-26): damping, for the lead to confirm with the owner

Review: `docs/review/2026-11-26-playback-fixes-code-review.md` §B (B2, B3, B4).

**This refines the owner's ruling "try every time we come back", it does not reverse it, and the lead should confirm it
with the owner in these words:** *a channel whose picture stalled with tunneling on, and which tunneling was switched
off for, on two visits in a row, starts its next visits with tunneling off until the app is restarted; after a restart
it tries again. Nothing is saved.*

- **B2, damping (implemented).** In memory only, for the life of the process, per channel (and live vs catch-up): the
  number of consecutive visits that ended with the ladder's switch-off. After `PlaybackControllerImpl.TUNNELING_STALLED_VISITS`
  (= 2, one constant) such visits, the next visits of that channel start untunneled: no stall first, no toast, and the
  stats overlay says `off for now (stalled before)`. A visit that played tunneled without a switch-off ends the run (the
  count goes back to 0). A damped visit tests nothing, so the channel stays damped until the process ends. Nothing is
  persisted, so the ruling's point (no silent permanent change) holds: an app restart, which happens at least at every
  update or reboot, tries tunneling again. Log line: `tunneling: off for this visit without trying (stalled on the last 2
  visits; tried again after an app restart)`. Tests `TunnelingVisitTest.twoStalledVisitsInARowStartTheNextOnesUntunneled`,
  `aGoodVisitInBetweenResetsTheRun`.
  - Why 2: one stall can be a one-off (a bad segment, a decoder hiccup); two visits in a row is the case the retired
    bit was added for (Sony/MediaTek HEVC, every visit). Each extra stall costs the viewer a stall of up to the L27/L28
    detection time plus a re-prepare and the toast.
- **B3, the stats line (fixed).** The Tunneling line now says why it is off: `off (setting)` (the Playback setting or the
  channel's option), `off for now (stalled)` (this visit), `off for now (stalled before)` (B2), `not used for replays`
  (a Sports Hub clip), `not supported for this stream/device` (asked for, the track selector did not tunnel), and plain
  `off` before first media (not decided yet). `StatsFacts.tunnelingOff` / `StreamStats.tunnelingOff` (`TunnelingOff`)
  replaces the boolean.
- **B4, the user bits and backup (documented, not changed).** Retiring bit `0b1000` drops any "Off for this channel" a
  viewer chose by hand before this version (accepted above). The user-only bits `0b100000` (tunneling off) and
  `AFR_ALWAYS_BIT` live in `channel_prefs.playback_flags`, which backup treats as a learned column and leaves out
  (`BackupRestorer`), so a restore on a new device loses those two choices. Recorded as a known gap for the backup design
  (export and restore the two user bits there); no change in this branch, because the backup format is shared with
  another branch in flight.

## Rejected

- **Keep saving it, with an expiry.** Still invisible and still "strange" until it expires.
- **A separate learned column or a schema change.** `playback_flags` has free bits; the visit needs no storage at all.
- **Clearing the flag on Retry from the card.** Retry is the same visit: it would turn tunneling back on into the
  stall the viewer just saw.
- **Keeping the old bit as the user's choice.** It would keep every channel the ladder ever switched off untunneled.
