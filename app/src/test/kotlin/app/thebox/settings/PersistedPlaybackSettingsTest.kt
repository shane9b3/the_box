package app.thebox.settings

import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.settings.PlaybackPrefs
import app.thebox.core.settings.SubtitleMode
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.PlaybackDomain
import app.thebox.data.net.HttpModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `PersistedPlaybackSettings` (docs/design/m5-setup-settings.md §2.2, §6.1): mapping onto `PlaybackSettings`, test hooks unchanged. */
class PersistedPlaybackSettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = FakeSettingsStore()
    private var installs = 0
    private val settings = PersistedPlaybackSettings(store, scope) { installs++ }

    @After fun tearDown() = scope.cancel()

    @Test fun defaultsAndTheUserAgentSeedNeverEnterTheStore() {
        assertEquals(HttpModule.DEFAULT_USER_AGENT, settings.current.defaultUserAgent)
        assertEquals(PlaybackPrefs(), store.flow(PlaybackDomain).value)
        assertEquals(1, installs)
    }

    @Test fun storeValuesMapOntoPlaybackSettings() {
        store.update(PlaybackDomain) { it.copy(bufferMode = BufferMode.LARGE, audioOutput = AudioOutputMode.FORCE_PASSTHROUGH, subtitles = SubtitleMode.ON, reconnectAttempts = -1) }
        val s = settings.current
        assertEquals(BufferMode.LARGE, s.bufferMode)
        assertEquals(AudioOutputMode.FORCE_PASSTHROUGH, s.audioOutput)
        assertTrue(s.subtitlesEnabled)
        assertEquals(-1, s.reconnectAttempts)
        store.update(PlaybackDomain) { it.copy(subtitles = SubtitleMode.FORCED_ONLY) }
        assertTrue("Forced only behaves as On until F21", settings.current.subtitlesEnabled)
    }

    @Test fun testHooksStayInMemoryAndOnlyChangedFieldsAreWritten() {
        store.update(PlaybackDomain) { it.copy(tunneling = true) }
        settings.update { it.copy(lifecycleTimeScale = 0.1f, streamSwitchTimeoutMs = 3_000, bufferMode = BufferMode.SMALL) }
        assertEquals(0.1f, settings.current.lifecycleTimeScale)
        assertEquals(3_000L, settings.current.streamSwitchTimeoutMs)
        assertEquals(PlaybackPrefs(tunneling = true, bufferMode = BufferMode.SMALL), store.flow(PlaybackDomain).value)
        store.update(PlaybackDomain) { it.copy(tunneling = false) }
        assertEquals("a store change keeps the hooks", 0.1f, settings.current.lifecycleTimeScale)
        assertEquals(false, settings.current.tunneling)
    }
}
