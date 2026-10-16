package app.thebox.ui.guide.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.fakes.GuideFixtures
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
 * U-B3 = P-S4 (V7): the mini player fed through the real [TunedStateMapper]. A live pause past M2's 10 s release keeps
 * the PAUSED pill (with or without an archive session) and never shows the stopped still; a real stop still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class MiniPlayerOverlayTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ch = GuideFixtures.channel(1, archiveDays = 7)
    private val live = NowPlaying(tuneId = 4, identity = ch.identity, title = "News", isArchive = false, format = StreamFormat.TS)
    private val pressOk = "Press OK to watch"

    private var current: androidx.compose.runtime.MutableState<Pair<PlaybackState, ArchiveStatus?>>? = null

    private fun show(state: PlaybackState, archive: ArchiveStatus?) {
        val holder = current
        if (holder != null) {
            holder.value = state to archive
        } else {
            val h = androidx.compose.runtime.mutableStateOf(state to archive)
            current = h
            rule.setContent {
                val (s, a) = h.value
                val tuned = TunedStateMapper.map(s, ch.rowId, still = false)
                BoxTheme { MiniPlayerOverlay(tuned, null, ch, clock24 = true, lowEnd = true, poster = false, archive = a) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun livePauseTimeoutWithASessionShowsThePausedPill() {
        val now = System.currentTimeMillis()
        val status = ArchiveStatus(
            ArchivePhase.PAUSED_LIVE, channel = ch, identityHash = ch.identityHash, wallMs = now - 12_000, atMs = now,
            liveEdgeMs = now, timeShift = true,
        )
        show(PlaybackState.Stopped(live, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true), status)
        rule.onAllNodesWithTag(MINI_ARCHIVE_PILL_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("PAUSED", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(pressOk, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun plainPauseWithoutASessionShowsThePausedPill() {
        show(PlaybackState.Playing(live, paused = true), null)
        rule.onAllNodesWithTag(MINI_ARCHIVE_PILL_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText(pressOk, useUnmergedTree = true).assertCountEquals(0)
        show(PlaybackState.Stopped(live, StopReason.LIVE_PAUSE_TIMEOUT, resumable = true), null)
        rule.onAllNodesWithTag(MINI_ARCHIVE_PILL_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText(pressOk, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aRealStopStillShowsTheStoppedStill() {
        show(PlaybackState.Stopped(live, StopReason.USER, resumable = false), null)
        assertEquals(MiniPhase.STOPPED, TunedStateMapper.map(PlaybackState.Stopped(live, StopReason.USER, false), 1, false).phase)
        rule.onAllNodesWithTag(MINI_ARCHIVE_PILL_TAG, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText(pressOk, useUnmergedTree = true).assertExists()
    }
}
