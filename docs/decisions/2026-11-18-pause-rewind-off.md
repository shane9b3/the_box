# Pause and rewind switched off, shown as "Coming soon" (2026-11-18)

Owner ruling. A tester on a BuzzTV box reports the picture stuttering about every 4 seconds; toggling tunnelling and
hardware/software decoding changes nothing, and other players are fine on the same provider. Logs have not arrived.

## Why the buffer is the suspect

- The time-shift ring is the one thing The Box does while watching that other players do not: every live TS channel is
  written to storage through a RAM pool of 64 blocks (4 MiB). At a typical 8 Mbit/s that pool is about 4 s of stream.
- If the box's storage writes slowly, the pool fills and the pump waits, which would starve the player at about that
  rhythm. This is a hypothesis, not a finding: there is no such device to test on.

## Decision

- Pause and rewind is off in every build until it can be tested on that class of hardware:
  `TimeshiftFeature.SHIPPED = false` (`core/settings/SettingsStore.kt`).
- Nothing is saved while watching, whatever the stored setting says. Playback and the SMB keeper read
  `PlaybackPrefs.timeshiftInUse`, which is Off while the feature is unavailable, so an older install or a restored
  backup holding "Auto" is covered. The stored choice is kept for when the feature returns.
- The default of `PlaybackPrefs.timeshift` (and `PlaybackSettings.timeshift`) is Off: all recording is off by default.
- Settings › Playback › "Pause and rewind" stays in place, greyed out, with the value "Coming soon". Its sub-pages
  (rewind length, Save to, locations) cannot be opened from there.
- Pause on live TV still freezes the picture and Play returns to live (the existing Off behaviour); the toast's next
  step now reads "Pause and rewind is coming in a later version."
- Ring files left by an earlier version are deleted by the existing start-up sweep and by the Off path of the first
  tune.

## To bring it back

Set `TimeshiftFeature.SHIPPED = true` and restore the `player_step_rewind_off` string. The default length stays Off
(`TimeshiftFeature.DEFAULT`) unless the owner rules otherwise.

The unit-test suites still exercise the feature: the Gradle test tasks set the system property
`thebox.timeshift.tests`, under which the feature is available and the default is Auto, as those tests were written.
`TimeshiftFeatureOffTest` covers the switched-off state.
