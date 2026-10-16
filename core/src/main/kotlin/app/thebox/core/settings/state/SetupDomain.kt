package app.thebox.core.settings.state

import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SetupState

/**
 * (M5 commit 0, lead amendment; docs/design/m5-setup-settings.md §3.4.1, §4.1) The `setup` domain, seeded here so WP-B
 * (import stage 3 / restore set `completed`, `lastAddedPlaylistId`, `pinOfferedFor`) and the [app.thebox.core.settings.FirstRunDecider]
 * implementation (WP-A) compile against one object while the WPs run in parallel. WP-A owns this file and may fold it
 * into its domain registry, keeping the name `SetupDomain`. Device-bound, never backed up (§3.10.2).
 */
val SetupDomain: SettingsDomain<SetupState> = SettingsDomain(
    name = Domains.SETUP,
    serializer = SetupState.serializer(),
    default = SetupState(),
    backedUp = false,
)
