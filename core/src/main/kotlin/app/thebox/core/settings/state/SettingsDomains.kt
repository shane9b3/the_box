package app.thebox.core.settings.state

import app.thebox.core.full.PlayerSettings
import app.thebox.core.net.NetworkSettings
import app.thebox.core.settings.DataPrefs
import app.thebox.core.settings.Domains
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.PlaybackPrefs
import app.thebox.core.settings.SettingsDomain

/**
 * WP-A's settings domains (docs/design/m5-setup-settings.md §2.2, §4.1): one `app_setting` document each. The store keys
 * them by [SettingsDomain.name]; every reader and writer uses these objects so the serializer and default live in one
 * place. [SetupDomain] (device-bound) is in its own file because WP-B and WP-C compile against it.
 */
val GuideDomain: SettingsDomain<GuidePrefs> = SettingsDomain(Domains.GUIDE, GuidePrefs.serializer(), GuidePrefs())

/** `core.full.PlayerSettings` stored as is (§2.12 item 4). */
val PlayerDomain: SettingsDomain<PlayerSettings> = SettingsDomain(Domains.PLAYER, PlayerSettings.serializer(), PlayerSettings())

/** The user-settable part of `:player`'s `PlaybackSettings` (`PersistedPlaybackSettings` in `:app` maps it). */
val PlaybackDomain: SettingsDomain<PlaybackPrefs> = SettingsDomain(Domains.PLAYBACK, PlaybackPrefs.serializer(), PlaybackPrefs())

/** The user-settable part of `:data`'s `DataSettingsSnapshot` (`PersistedDataSettings` maps it, S12). */
val DataDomain: SettingsDomain<DataPrefs> = SettingsDomain(Domains.DATA, DataPrefs.serializer(), DataPrefs())

/** Secure DNS, IPv4 preference and timeouts (§2.11, §3.13); read by `BoxDns` at lookup time. */
val NetworkDomain: SettingsDomain<NetworkSettings> = SettingsDomain(Domains.NETWORK, NetworkSettings.serializer(), NetworkSettings())

/** Every domain WP-A defines, in load order (the store decodes them first; other WPs' domains register on first use). */
val WP_A_DOMAINS: List<SettingsDomain<*>> = listOf(GuideDomain, PlayerDomain, PlaybackDomain, DataDomain, NetworkDomain, SetupDomain)

/** Domains of §2.2 `Domains` that are device-bound or learned, so never exported even before their owner registers them. */
val NOT_BACKED_UP_DOMAINS: Set<String> = setOf(Domains.BACKUP, Domains.UPDATE, Domains.SETUP)
