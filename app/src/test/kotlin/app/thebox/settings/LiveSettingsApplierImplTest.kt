package app.thebox.settings

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.PanelApplyResult
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.model.StableKey
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * `LiveSettingsApplierImpl.applyFromPanel` (docs/design/m6-player-settings-panel.md §4.2, stage A): what it calls and
 * answers from the controller's and archive's public state — idle, stopped, the error card (no call), archive playing,
 * archive paused (provider, local, at live), a clip, live paused, live playing / buffering.
 */
class LiveSettingsApplierImplTest {
    private class Rig(state: PlaybackState, phase: ArchivePhase = ArchivePhase.IDLE, source: TimelineSource = TimelineSource.PROVIDER) {
        var reapplies = 0
        val reapplied = ArrayList<Long>()
        val calls = ArrayList<String>()
        val controllerState = MutableStateFlow(state)
        val status = MutableStateFlow(ArchiveStatus(phase = phase, wallMs = 50_000, atMs = 1_000, advancing = phase != ArchivePhase.PAUSED && phase != ArchivePhase.PAUSED_LIVE, source = source))
        val controller: PlaybackController = proxy { name, _ ->
            when (name) {
                "getState" -> controllerState
                "reapplySettings" -> { reapplies++; null }
                else -> { calls += name; null }
            }
        }
        val archive: ArchivePlayback = proxy { name, args ->
            when (name) {
                "getStatus" -> status
                "reapply" -> { reapplied += args!![0] as Long; null }
                else -> { calls += "archive.$name"; null }
            }
        }
        val applier = LiveSettingsApplierImpl(controller, archive) { 3_000 }
    }

    private val live = NowPlaying(1, ChannelIdentity(1, StableKey("xc:1-1")), "Channel", isArchive = false, format = StreamFormat.TS)
    private val archived = live.copy(isArchive = true)
    private val clip = live.copy(identity = null, isClip = true)

    private fun check(r: Rig, expected: PanelApplyResult, reapplies: Int, reapplied: List<Long> = emptyList()) {
        assertEquals(expected, r.applier.applyFromPanel())
        assertEquals("reapplySettings calls", reapplies, r.reapplies)
        assertEquals("archive re-requests", reapplied, r.reapplied)
        assertEquals("nothing else (no retry, no seek)", emptyList<String>(), r.calls)
    }

    @Test fun idleIsNothingPlaying() = check(Rig(PlaybackState.Idle), PanelApplyResult.NOTHING_PLAYING, 0)

    @Test fun stoppedIsNothingPlaying() =
        check(Rig(PlaybackState.Stopped(live, StopReason.USER, resumable = true)), PanelApplyResult.NOTHING_PLAYING, 0)

    @Test fun theErrorCardCallsNothing() {
        check(Rig(PlaybackState.Error(live, PlaybackError(PlaybackErrorCode.STREAM_INTERRUPTED))), PanelApplyResult.CARD_SHOWN, 0)
        // Also during an archive session: the card wins, no re-request.
        check(Rig(PlaybackState.Error(archived, PlaybackError(PlaybackErrorCode.STREAM_INTERRUPTED)), ArchivePhase.PLAYING), PanelApplyResult.CARD_SHOWN, 0)
    }

    @Test fun archivePlayingIsReRequestedAtThePlayhead() {
        for (phase in listOf(ArchivePhase.PLAYING, ArchivePhase.CONTINUING, ArchivePhase.SEEKING, ArchivePhase.STARTING, ArchivePhase.PROBING)) {
            check(Rig(PlaybackState.Playing(archived), phase), PanelApplyResult.REPREPARING, 0, listOf(52_000L))
        }
    }

    @Test fun archivePausedAtTheProviderWaitsForResume() =
        check(Rig(PlaybackState.Playing(archived, paused = true), ArchivePhase.PAUSED), PanelApplyResult.WAITS_FOR_RESUME, 0)

    @Test fun pausedInTheLocalBufferRepreparesAndWaitsForResume() =
        check(Rig(PlaybackState.Playing(live, paused = true), ArchivePhase.PAUSED, TimelineSource.LOCAL), PanelApplyResult.WAITS_FOR_RESUME, 0, listOf(50_000L))

    @Test fun pausedAtLiveWaitsForResume() {
        for (source in TimelineSource.entries) {
            check(Rig(PlaybackState.Playing(live, paused = true), ArchivePhase.PAUSED_LIVE, source), PanelApplyResult.WAITS_FOR_RESUME, 0)
        }
    }

    @Test fun livePausedAsksTheControllerAndWaitsForResume() =
        check(Rig(PlaybackState.Playing(live, paused = true)), PanelApplyResult.WAITS_FOR_RESUME, 1)

    @Test fun livePlayingIsReprepared() {
        check(Rig(PlaybackState.Playing(live)), PanelApplyResult.REPREPARING, 1)
        check(Rig(PlaybackState.Buffering(live, app.thebox.player.api.BufferingCause.REBUFFER, null)), PanelApplyResult.REPREPARING, 1)
        check(Rig(PlaybackState.Playing(live), ArchivePhase.FAILED), PanelApplyResult.REPREPARING, 1)
    }

    @Test fun aClipOrAnArchiveTargetWithoutASessionClaimsNothing() {
        // reapplySettings is a no-op for both (cycle.isVod), so the panel must not say "Re-preparing".
        check(Rig(PlaybackState.Playing(clip)), PanelApplyResult.NOTHING_PLAYING, 0)
        check(Rig(PlaybackState.Playing(archived)), PanelApplyResult.NOTHING_PLAYING, 0)
    }

    @Test fun applyIsUnchanged() {
        val r = Rig(PlaybackState.Error(live, PlaybackError(PlaybackErrorCode.STREAM_INTERRUPTED)))
        r.applier.apply()
        assertEquals("leaving Settings on the card still asks the controller, as today", 1, r.reapplies)
    }

    private companion object {
        inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, m, args ->
                when (m.name) {
                    "toString" -> T::class.java.simpleName
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    else -> handler(m.name, args)
                }
            } as T
    }
}
