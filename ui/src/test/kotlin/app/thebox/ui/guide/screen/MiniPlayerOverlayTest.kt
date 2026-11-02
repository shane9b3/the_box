package app.thebox.ui.guide.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.ui.guide.vm.TunedStateMapper
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (owner ruling 2026-11-01, docs/decisions/2026-11-01-guide-panel-and-mini-player.md) The mini player is video only:
 * no text over the hole while playing, paused (with or without an archive session), connecting or reconnecting; the
 * still (a real stop, the Still setting, the HDR poster) is the logo alone; a playback error still shows the compact
 * error card with its reason and next step. Fed through the real [TunedStateMapper] where a [PlaybackState] applies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class MiniPlayerOverlayTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ch = GuideFixtures.channel(1, archiveDays = 7)
    private val live = NowPlaying(tuneId = 4, identity = ch.identity, title = "News", isArchive = false, format = StreamFormat.TS)
    private val anyText = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private var current: androidx.compose.runtime.MutableState<Triple<TunedState, GuideChannel?, Boolean>>? = null

    private fun show(tuned: TunedState, channel: GuideChannel? = ch, poster: Boolean = false) {
        val holder = current
        if (holder != null) {
            holder.value = Triple(tuned, channel, poster)
        } else {
            val h = androidx.compose.runtime.mutableStateOf(Triple(tuned, channel, poster))
            current = h
            rule.setContent {
                val (t, c, p) = h.value
                BoxTheme { MiniPlayerOverlay(t, c, p) }
            }
        }
        rule.waitForIdle()
    }

    private fun show(state: PlaybackState) = show(TunedStateMapper.map(state, ch.rowId, still = false))

    private fun assertNoText() = rule.onAllNodes(anyText, useUnmergedTree = true).assertCountEquals(0)

    @Test
    fun playingPausedAndReconnectingDrawNoText() {
        show(PlaybackState.Playing(live))
        assertNoText()
        show(PlaybackState.Playing(live, paused = true))
        assertNoText()
        show(PlaybackState.Stopped(live, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true))
        assertNoText()
        rule.onAllNodesWithTag(MINI_STILL_TAG, useUnmergedTree = true).assertCountEquals(0) // a live-pause release is no still
        show(TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.CONNECTING))
        assertNoText()
        show(TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.RECONNECTING, reconnectAttempt = 2, reconnectMax = 5))
        assertNoText()
        show(TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.PLAYING, isArchive = true))
        assertNoText()
    }

    @Test
    fun theStillIsTheLogoAloneWithNoPrompt() {
        show(PlaybackState.Stopped(live, StopReason.USER, resumable = false))
        assertEquals(MiniPhase.STOPPED, TunedStateMapper.map(PlaybackState.Stopped(live, StopReason.USER, false), 1, false).phase)
        rule.onNodeWithTag(MINI_STILL_TAG, useUnmergedTree = true).assertExists()
        assertNoText() // no title, no "Press OK to watch"; no logo loader in tests, and the still never falls back to the name
        show(TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.PLAYING), poster = true)
        rule.onNodeWithTag(MINI_STILL_TAG, useUnmergedTree = true).assertExists()
        assertNoText() // no "HDR · Press OK for full screen"
        show(TunedState(), channel = null)
        assertNoText() // nothing tuned: no "Nothing playing" line
    }

    @Test
    fun aPlaybackErrorKeepsTheErrorCard() {
        show(TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.ERROR, error = PlaybackErrorCode.SERVER_ERROR))
        rule.onNodeWithTag(ERROR_CARD_TAG, useUnmergedTree = true).assertExists()
        // The reason and the next step (and the channel name when there is no logo) are the only text left.
        rule.onAllNodes(anyText, useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(3, it.size) }
    }
}
