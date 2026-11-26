# Auto stream format tries MPEG-TS again after learning HLS (2026-11-25)

Owner ruling: "yes make auto retry ts after a while".

## Why

An Xtream playlist on Stream format **Auto** asks for MPEG-TS. When the server answers a TS request with 405/406 the
ladder flips the tune to HLS (L9) and the playlist remembers it (`playlist.xtream_output_learned = HLS`, M2 §2.10).
Until now it never asked for TS again, even after a one-off refusal. The emulator shows exactly that: one
`row=L9 signal=Http -> FlipXtreamOutput(to=HLS)` on 2026-11-17, every tune HLS since. It matters for this owner: the
provider retires its HLS edge server every 30 minutes, which shows as a visible reconnect on HLS, while TS reconnects
invisibly through the provider's address.

## Decision

Only for an Xtream playlist whose setting is Auto and whose learned value is HLS. Manual MPEG-TS and manual HLS are
untouched, and so is an account whose `allowed_output_formats` is known and lacks TS (`ts`/`mpegts`): it never retries.

- **When.** At the first session of a user tune (a tune from the guide or the app's start-up tune, a zap, the previous
  channel key). Never in the middle of playback, never on a ladder re-resolve or restart, never on Retry from the error
  card or the resume after leaving the app.
- **How often.** The first retry is due 24 h after HLS was learned. Each refusal re-learns HLS and backs off:
  24 h → 3 days → 7 days, then 7 days for good. A value stored before this change (plain `HLS`, no time) counts as
  learned long ago, so the emulator's playlist retries at its next tune and backs off to 3 days if refused.
- **Refused** (405/406 on the TS request, the ladder's L9 flip): the same tune goes on with HLS through the existing
  flip path. No error, and no "switched format" notice either: the viewer only waits for one extra answer.
- **Confirmed**: first media on TS and 30 s more in the same tune without any failure. The learned value is cleared, so
  the playlist is plain Auto (TS) again and the back-off starts over if it is ever learned again. 30 s because a format
  refusal comes at connection time, a server that accepts a request and then cuts it does so within seconds, and it is
  the same window the TS-compat raise uses (`COMPAT_PERSIST_HEALTHY_MS`). Zapping away before 30 s settles nothing; the
  next tune tries again (it costs nothing when TS works).
- **Inconclusive**: any other failure of the retry (network, 403, 404, stall, timeout), or no picture after 8 s. It does
  not count as a refusal: the learned HLS and its step stay, and no retry starts for 1 h (so a dead channel does not
  cost an extra attempt on every zap). Before first media the tune falls back to HLS at once, with a fresh stream-switch
  window and the flip counted like L9 (a 405 on HLS then ends at L10 instead of flipping back); the normal ladder then
  runs on HLS. After first media TS has played, so the ladder's own decision stands.

## The numbers

| | |
|---|---|
| Back-off after HLS is (re)learned | 24 h, 3 days, 7 days (cap) (`AutoTsRetry.BACKOFF_MS`) |
| Confirmation | first media + 30 s without a failure (`CONFIRM_MS`) |
| Hold-off after an inconclusive retry | 1 h (`HOLD_OFF_MS`) |
| No-picture budget | 8 s, the TS data source's first-byte timeout (`FIRST_MEDIA_BUDGET_MS`) |

**Cost to the viewer**, on the one tune that retries: one extra local resolve (database reads, no request) on every
retry, and at most one extra upstream request. A refusal costs one round trip to the panel (typically well under half a
second) before the HLS open. The worst case is a TS request that neither fails nor plays: 8 s before the HLS open.
With the back-off that is at most once a day at first, then once every 3 and 7 days.

## Where the state lives

In the column that already holds the learned value, behind the output name: `HLS;t=<learned at>;n=<step>;h=<hold-off
until>` (wall-clock epoch ms; `LearnedOutput` in `core/playback/resolve/AutoTsRetry.kt` is the only reader and writer).
No `library.db` schema change (it is at v6 with another branch in flight). The state shares the learned value's life
exactly: Room keeps it across restarts; removing the playlist removes the row; any Stream format choice clears it
(`PlaylistDao.setXtreamOutput`), and picking **Auto again** while HLS is learned now does that too (the option is
re-selectable there, with the detail "Pick again to try TS at the next channel"); a backup never carries it
(`MergePlanner.PLAYLIST_COLUMNS` leaves the column out, as before). Old readers that compared the column with `"HLS"`
now parse it (`DataPlaybackSourceProvider.toInfo`, the setup UA probe, the playlist page). The read-modify-writes are
serialised in `data/player/LearnedOutputStore`.

The Stream format row says when TS is tried next, as a calendar day in the device zone: "Auto (using HLS: the server
refused TS; will try TS again tomorrow)", "… in 3 days", "… later today", "… at the next channel"; the old text stays
for an account without TS. Every form fits the row's one line at Largest text (`StreamFormatRowFitTest`).

Four info log lines, playlist id and numbers only: retry attempted; refused (next retry in N days); confirmed (learned
HLS cleared); inconclusive (why, next retry in 60 min at the earliest).

## Rejected

- **New columns (`learned_at`, `backoff_step`) in a v7 migration.** The same life as the existing column, a migration
  and a backup review for nothing, and a schema clash with the branch in flight.
- **A preferences entry keyed by playlist id.** Needs its own clean-up on removal and its own backup exclusion.
- **A background probe of TS** (a request outside a tune). It would take one of the account's connections behind the
  viewer's back and bypass `StreamConnectionBroker`.
- **Switching a playing HLS tune to TS.** A visible re-tune in the middle of playback; the ruling is about tunes.
- **Retrying on a ladder re-resolve.** Mixes a format experiment into error recovery.
- **Counting every failure of the retry as a refusal.** A dead channel or a network blip would push TS back by days.
- **Retrying on every tune, or on a fixed interval with no back-off.** A panel that really refuses TS would cost a
  round trip on every zap, or every day forever.
