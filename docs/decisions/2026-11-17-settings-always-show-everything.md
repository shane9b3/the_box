# Settings: no advanced toggle, new section order, readable focused rows (2026-11-17)

Owner rulings, made while using the app on a TV. They replace the M5 design's simple/advanced split
(`docs/design/m5-setup-settings.md` §3.2.3, §3.3.13).

## Decisions

1. **"Show advanced settings" is removed.** Every section and every row is always shown. The pinned toggle at the
   bottom of the sections pane is gone, and the section cursor stops on the last section.
2. **Section order**: Playlists, TV guide, Playback, Channels and groups, Interface, Sports, Remote control, Parental
   controls, Backup and restore, Network, About. Sports sits under Interface; About is last.
3. **Secondary text on a focused row** (description, value, count, chevron) is at least `.85` of the dark text colour.
   At the old `.45`-`.65` it was the same mid grey as the unfocused text and hard to read on the light fill.

## What this means in the code

- `SettingsMachineImpl.SECTION_ORDER` is the sections pane's order. `SectionId` keeps its declaration order.
- `SettingsEvent.Advanced`, `SettingsUiState.advanced`, `ADVANCED_ROW` and the machine's simple-view filter are gone.
- `GuidePrefs.advancedSettings` stays as an unused field so stored prefs and backups still read.
- `Visibility.ADVANCED` and the page builders' `advanced` argument remain, and the app always passes `true`. They are
  now labels only. Removing them is a mechanical follow-up.
- The 36 simple-row budget (§3.3.13) no longer applies; `SimpleRowCountTest` was deleted with it.
- `GuideUi.secondary(text, focused, rest)` is the one rule for secondary text; settings rows, option lists, the channel
  editor and the player quick bar use it.

## Update check on every app start (same day)

Owner ruling: the app checks for an update each time it starts, not only once every 24 h.

- `UpdateCheckSchedulerImpl.scheduleCheck()` runs `UpdaterImpl.runStartupCheck()` 2 s after the first frame
  (`UpdateTimings.UPDATE_STARTUP_CHECK_DELAY_MS`), while "Check for updates automatically" is on and the build has an
  update endpoint. This replaces the M5 rule "60 s after the first frame, and only when the last check is over 24 h old"
  (§3.12.2) for the start check; the daily worker is unchanged.
- It is an automatic check: silent on failure, and it honours a skipped version, the rollout bucket and `withdrawn`.
- It does not use up the process's one automatic check, so the daily worker still runs when due.
- The same check runs each time the app returns to the foreground (the process's ON_START after an ON_STOP), 2 s
  after it is back. A download or install in progress is not interrupted.
