package app.thebox.errors

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.model.StableKey
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ExternalHandOff
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Fix pass A1 (Back to Auto restarts from the card) and A5 (the external hand-off stops our stream only when it can open). */
class ErrorRouteLogicTest {
    private class Rig(state: PlaybackState, archivePhase: ArchivePhase = ArchivePhase.IDLE) {
        val calls = ArrayList<String>()
        val flow = MutableStateFlow(state)
        private val handOff = ExternalHandOff(url = "http://panel.example:8080/live/u/p/1.ts", headers = emptyMap(), title = "One")
        val controller: PlaybackController = proxy { name ->
            when (name) {
                "getState" -> flow
                "peekExternalHandOff" -> handOff
                "handOffExternal" -> { calls += "stop"; handOff }
                else -> { calls += name; null }
            }
        }
        val status = MutableStateFlow(ArchiveStatus(phase = archivePhase))
        val archive: ArchivePlayback = proxy { name -> if (name == "getStatus") status else { calls += "archive.$name"; null } }
        val applier = LiveSettingsApplier { calls += "apply" }

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
                answer(m.name) ?: if (m.returnType == Boolean::class.javaPrimitiveType) false else if (m.returnType == Void.TYPE) Unit else null
            } as T
    }

    private fun np(archive: Boolean) = NowPlaying(1, CH, null, archive, StreamFormat.TS)
    private fun card(archive: Boolean = false) = PlaybackState.Error(np(archive), PlaybackError(PlaybackErrorCode.AUDIO_OUTPUT_FAILED))

    @Test fun backToAutoOnTheCardRetries() {
        val r = Rig(card())
        applyBackToAuto(r.controller, r.archive, r.applier, archiveFailed = false)
        assertEquals(listOf("retry"), r.calls)
    }

    @Test fun backToAutoOnAnArchiveCardResumesTheArchive() {
        val r = Rig(card(archive = true), ArchivePhase.PLAYING)
        applyBackToAuto(r.controller, r.archive, r.applier, archiveFailed = false)
        assertEquals(listOf("archive.resume"), r.calls)
    }

    @Test fun backToAutoWhilePlayingReprepares() {
        val r = Rig(PlaybackState.Playing(np(false)))
        applyBackToAuto(r.controller, r.archive, r.applier, archiveFailed = false)
        assertEquals(listOf("apply"), r.calls)
    }

    @Test fun aPlayerThatCannotOpenTheStreamLeavesPlaybackAlone() {
        val r = Rig(card())
        val out = handOffExternal("org.videolan.vlc", r.controller, { _, _ -> false }, { _, _ -> error("not launched") })
        assertEquals(ExternalOutcome.FAILED, out)
        assertEquals("our stream is not stopped", emptyList<String>(), r.calls)
    }

    @Test fun aPlayerThatCanOpenItGetsTheStreamAfterOursStops() {
        val r = Rig(card())
        val out = handOffExternal("org.videolan.vlc", r.controller, { _, _ -> true }, { _, _ -> r.calls += "launch"; true })
        assertEquals(ExternalOutcome.LAUNCHED, out)
        assertEquals(listOf("stop", "launch"), r.calls)
    }

    @Test fun noPlayerChosen() {
        val r = Rig(card())
        assertEquals(ExternalOutcome.NO_PLAYER, handOffExternal(null, r.controller, { _, _ -> true }, { _, _ -> true }))
        assertEquals(emptyList<String>(), r.calls)
    }

    private companion object {
        val CH = ChannelIdentity(1L, StableKey("ch1"))
    }
}
