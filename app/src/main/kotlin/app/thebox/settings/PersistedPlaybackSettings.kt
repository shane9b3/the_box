package app.thebox.settings

import app.thebox.core.settings.PlaybackPrefs
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SubtitleMode
import app.thebox.core.settings.state.GuidePrefsMapping.pick
import app.thebox.core.settings.state.PlaybackDomain
import app.thebox.data.di.ApplicationScope
import app.thebox.data.net.HttpModule
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.PlaybackSettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:player`'s `PlaybackSettingsSource` over the `playback` domain (docs/design/m5-setup-settings.md §2.2, §1.4). The
 * user-settable part is [PlaybackPrefs]; `defaultUserAgent` (from `HttpModule.DEFAULT_USER_AGENT`),
 * `lifecycleTimeScale`, `streamSwitchTimeoutMs`, `autoUserAgentFallback` and `userAgentFallbackPreset` are not user
 * choices and stay in memory (S1: they never enter the store). [update] (the debug harness, tests) applies only the
 * fields its transform changed, so a transform queued before the store's read never overwrites stored choices.
 *
 * Creating it (with the controller, at the first engine use) also installs the ON_STOP flush of the store
 * ([SettingsLifecycle]).
 */
@Singleton
class PersistedPlaybackSettings internal constructor(
    private val store: SettingsStore,
    scope: CoroutineScope,
    install: () -> Unit,
) : PlaybackSettingsSource {
    @Inject constructor(store: SettingsStore, @ApplicationScope scope: CoroutineScope, lifecycle: SettingsLifecycle) :
        this(store, scope, lifecycle::install)

    @Volatile private var extras = PlaybackSettings(defaultUserAgent = HttpModule.DEFAULT_USER_AGENT)
    private val state = MutableStateFlow(toSettings(store.flow(PlaybackDomain).value, extras))
    override val settings: StateFlow<PlaybackSettings> = state.asStateFlow()

    init {
        scope.launch(Dispatchers.Unconfined) { store.flow(PlaybackDomain).collect { state.value = toSettings(it, extras) } }
        install()
    }

    override fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        val before = state.value
        val after = transform(before)
        extras = after
        store.update(PlaybackDomain) { prefsFrom(it, before, after) }
        state.value = toSettings(store.flow(PlaybackDomain).value, extras)
    }

    companion object {
        /** [PlaybackPrefs] onto [PlaybackSettings]; [extras] supplies the non-user fields. */
        fun toSettings(p: PlaybackPrefs, extras: PlaybackSettings): PlaybackSettings = extras.copy(
            bufferMode = p.bufferMode,
            videoDecoderMode = p.videoDecoderMode,
            softwareAudioFallback = p.softwareAudioFallback,
            preferSoftwareAudio = p.preferSoftwareAudio,
            audioOutput = p.audioOutput,
            tunneling = p.tunneling,
            afrMode = p.afrMode,
            hdrInPreview = p.hdrInPreview,
            preferredAudioLanguages = p.preferredAudioLanguages,
            // (§9 F21) FORCED_ONLY is not offered yet; a stored one behaves as On.
            subtitlesEnabled = p.subtitles != SubtitleMode.OFF,
            preferredTextLanguages = p.preferredTextLanguages,
            defaultAspect = p.defaultAspect,
            reconnectAttempts = p.reconnectAttempts,
            failoverToBackup = p.failoverToBackup,
            resumeAfterStandby = p.resumeAfterStandby,
            // (M6 time-shift, lead reconciliation R10) The controller reads these here, not from the store.
            timeshift = p.timeshift,
            timeshiftHold = p.timeshiftHold,
            storageProtection = p.storageProtection,
        )

        /** The stored form after an update changed [before] into [after] (changed fields only). */
        fun prefsFrom(old: PlaybackPrefs, before: PlaybackSettings, after: PlaybackSettings): PlaybackPrefs = old.copy(
            bufferMode = pick(old.bufferMode, before.bufferMode, after.bufferMode),
            videoDecoderMode = pick(old.videoDecoderMode, before.videoDecoderMode, after.videoDecoderMode),
            softwareAudioFallback = pick(old.softwareAudioFallback, before.softwareAudioFallback, after.softwareAudioFallback),
            preferSoftwareAudio = pick(old.preferSoftwareAudio, before.preferSoftwareAudio, after.preferSoftwareAudio),
            audioOutput = pick(old.audioOutput, before.audioOutput, after.audioOutput),
            tunneling = pick(old.tunneling, before.tunneling, after.tunneling),
            afrMode = pick(old.afrMode, before.afrMode, after.afrMode),
            hdrInPreview = pick(old.hdrInPreview, before.hdrInPreview, after.hdrInPreview),
            preferredAudioLanguages = pick(old.preferredAudioLanguages, before.preferredAudioLanguages, after.preferredAudioLanguages),
            subtitles = if (after.subtitlesEnabled == before.subtitlesEnabled) old.subtitles else if (after.subtitlesEnabled) SubtitleMode.ON else SubtitleMode.OFF,
            preferredTextLanguages = pick(old.preferredTextLanguages, before.preferredTextLanguages, after.preferredTextLanguages),
            defaultAspect = pick(old.defaultAspect, before.defaultAspect, after.defaultAspect),
            reconnectAttempts = pick(old.reconnectAttempts, before.reconnectAttempts, after.reconnectAttempts),
            failoverToBackup = pick(old.failoverToBackup, before.failoverToBackup, after.failoverToBackup),
            resumeAfterStandby = pick(old.resumeAfterStandby, before.resumeAfterStandby, after.resumeAfterStandby),
            timeshift = pick(old.timeshift, before.timeshift, after.timeshift),
            timeshiftHold = pick(old.timeshiftHold, before.timeshiftHold, after.timeshiftHold),
            storageProtection = pick(old.storageProtection, before.storageProtection, after.storageProtection),
        )
    }
}
