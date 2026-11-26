package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Market
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.player.api.ClipTune
import app.thebox.player.api.PlaybackController
import app.thebox.ui.shelltest.FakePlaybackController
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (NFL hub P3, §8.3) Goldens for the NFL hub: the panel in every phase and state the design lists, the Scoring sheet's
 * four states, the Box score's NFL tab, the NFL Matchup and the clip bar's ESPN sub-line. Written to
 * `ui/build/sports-tab/nfl-hub-*.png`, which `sportsHashGate` checks against `sports-hashes.txt`. Clock, time zone and
 * locale pinned as in `HubScreenshotTest`; no image loader (logos show their codes, posters their backdrop).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NflHubScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("build/sports-tab").apply { mkdirs() }
    private var zone: TimeZone? = null
    private var locale: Locale? = null

    @Before fun pin() {
        zone = TimeZone.getDefault(); locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)
    }

    @After fun unpin() {
        UiTextScale.running = TextSize.DEFAULT
        zone?.let { TimeZone.setDefault(it) }
        locale?.let { Locale.setDefault(it) }
    }

    private val now = NflHubTestKit.AFTER_FINAL
    private val ended = NflHubTestKit.MNF_START + SportsConstants.HUB_NFL_GAME_LENGTH_MS

    private val espn = ChannelCandidate(
        GuideFixtures.channel(1, name = "ESPN"), "Main", MatchConfidence.LIKELY,
        listOf(MatchReason.Broadcaster("ESPN", Market.NATIONAL, "US")), emptyList(), null,
    )
    private val catchUp = espn.copy(airing = GuideFixtures.info(100, 280))

    private fun model(
        g: GameSnapshot, d: GameDetail?, kind: DetailKind = if (d != null) DetailKind.READY else DetailKind.LOADING,
        failure: FailKind? = null, at: Long = now, match: MatchState = MatchState.Done(listOf(espn)), videoOff: Boolean = false,
    ): HubPanelModel {
        val base = HubPanelModel(g, detail = d, detailKind = kind, failure = failure, nowMs = at, match = match, videoOff = videoOff, imminent = g.phase != GamePhase.PRE)
        return base.copy(slots = HubSlots.default(HubSlots.Ctx(g, false, d, failure, match, videoOff, at)))
    }

    private fun save(name: String, tag: String) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name.png", File(out, "$name.png").length() > 2_000)
    }

    @Test
    fun theNflPanelInEveryPhase() {
        val model = mutableStateOf(model(NflHubTestKit.pre(), null))
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Box(Modifier.testTag(SHOT).background(DesignTokens.Colors.Canvas).padding(16.dp)) {
                        HubPanel(model.value, clock24 = false, slotRequesters = remember { List(3) { FocusRequester() } }, onSlot = {}, onSlotFocus = {}, onExit = {}, entered = false)
                    }
                }
            }
        }
        val pre = NflHubTestKit.pre()
        val preAt = NflHubTestKit.PRE_START - 7_200_000L
        val live = NflHubTestKit.mnfLive()
        val half = NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME, period = 2, clockSecs = 0)
        val endQ = NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD, period = 1, clockSecs = 0)
        val delayed = NflHubTestKit.mnfLive(delayed = true)
        val fin = NflHubTestKit.mnfFinal()
        val expired = NflHubTestKit.highlight()
        val cases = listOf(
            Triple("pre", TextSize.DEFAULT, model(pre, NflHubTestKit.preDetail(pre), at = preAt)),
            Triple("pre", TextSize.LARGEST, model(pre, NflHubTestKit.preDetail(pre), at = preAt)),
            Triple("live", TextSize.DEFAULT, model(live, NflHubTestKit.mnfLiveDetail(live))),
            Triple("live", TextSize.LARGEST, model(live, NflHubTestKit.mnfLiveDetail(live))),
            Triple("halftime", TextSize.DEFAULT, model(half, NflHubTestKit.mnfLiveDetail(half))),
            Triple("end-quarter", TextSize.DEFAULT, model(endQ, NflHubTestKit.mnfLiveDetail(endQ))),
            Triple("delayed", TextSize.DEFAULT, model(delayed, NflHubTestKit.mnfLiveDetail(delayed))),
            Triple("final-highlight", TextSize.DEFAULT, model(fin, NflHubTestKit.mnfFinalDetail(fin), match = MatchState.Done(listOf(catchUp)))),
            Triple("final-highlight", TextSize.LARGEST, model(fin, NflHubTestKit.mnfFinalDetail(fin), match = MatchState.Done(listOf(catchUp)))),
            Triple("final-expired", TextSize.DEFAULT, model(fin, NflHubTestKit.mnfFinalDetail(fin, clips = listOf(expired)), at = NflHubTestKit.offEnd(expired), match = MatchState.Done(emptyList()))),
            Triple("final-soon", TextSize.DEFAULT, model(fin, NflHubTestKit.mnfFinalDetail(fin, clips = emptyList()), at = ended + 600_000L, match = MatchState.Done(emptyList()))),
            Triple("failed", TextSize.DEFAULT, model(live, null, DetailKind.FAILED, FailKind.TIMEOUT)),
            Triple("video-off", TextSize.DEFAULT, model(fin, NflHubTestKit.mnfFinalDetail(fin), match = MatchState.Done(emptyList()), videoOff = true)),
            Triple("postponed", TextSize.DEFAULT, model(NflHubTestKit.pre(phase = GamePhase.POSTPONED), null, at = preAt, match = MatchState.Idle)),
        )
        for ((name, s, m) in cases) {
            size.value = s
            model.value = m
            save("nfl-hub-panel-$name-${if (s == TextSize.LARGEST) "largest" else "default"}", SHOT)
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        BoxTheme { Box(Modifier.fillMaxSize().testTag(SHOT).background(DesignTokens.Colors.Canvas)) { content() } }
    }

    @Test
    fun theScoringSheetsStates() {
        val fin = NflHubTestKit.mnfFinal()
        val live = NflHubTestKit.mnfLive()
        val state = mutableStateOf<Pair<GameSnapshot, GameDetail>>(fin to NflHubTestKit.mnfFinalDetail(fin))
        val videoOn = mutableStateOf(true)
        // The ESPN case opens on the clip just played (the second More videos row), so the video sections are on screen.
        val focusId = mutableStateOf<Long?>(50578740L)
        rule.setContent {
            Frame {
                key(state.value, videoOn.value, focusId.value) {
                    GoalsSheet(state.value.first, state.value.second, emptyMap(), emptyList(), focusId.value, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, videoOn = videoOn.value, nowMs = now)
                }
            }
        }
        save("nfl-hub-scoring-espn", SHOT)
        focusId.value = null
        state.value = fin to NflHubTestKit.mnfFinalDetail(fin, clips = listOf(NflHubTestKit.highlight()))
        save("nfl-hub-scoring-highlight-only", SHOT)
        state.value = fin to NflHubTestKit.mnfFinalDetail(fin, clips = emptyList(), plays = NflHubTestKit.MNF_PLAYS.take(3))
        videoOn.value = false
        save("nfl-hub-scoring-no-video", SHOT)
        videoOn.value = true
        state.value = live to NflHubTestKit.mnfLiveDetail(live, plays = emptyList(), clips = emptyList())
        save("nfl-hub-scoring-live-empty", SHOT)
    }

    @Test
    fun theBoxScoreAndMatchup() {
        val fin = NflHubTestKit.mnfFinal()
        val pre = NflHubTestKit.pre()
        val which = mutableStateOf(0)
        rule.setContent {
            Frame {
                if (which.value == 0) BoxScoreSheet(fin, Fetch.Ok(NflHubTestKit.mnfBox(), 0L, null, 0), onRetry = {}, onClose = {})
                else MatchupSheet(pre, NflHubTestKit.preDetail(pre), onClose = {})
            }
        }
        save("nfl-hub-box-score", SHOT)
        which.value = 1
        save("nfl-hub-matchup", SHOT)
    }

    @Test
    fun theClipBarNamesEspn() {
        val fin = NflHubTestKit.mnfFinal()
        val base = FakePlaybackController()
        val controller = object : PlaybackController by base {
            override fun playClip(tune: ClipTune): Long {
                // The clip connects (as the real controller reports it), so the session keeps it.
                base.state.value = app.thebox.player.api.PlaybackState.Connecting(
                    app.thebox.player.api.NowPlaying(100L, null, tune.title, isArchive = false, format = app.thebox.core.playback.StreamFormat.HLS, isClip = true),
                    app.thebox.player.api.ConnectPhase.CONNECTING, 0, 0,
                )
                return 100L
            }
        }
        val resolver = FakeClipResolver()
        val ref = ClipRef(50578740L, ClipKind.PLAY, fin.id)
        resolver.streams[ref.videoId] = FakeClipResolver.okStream(ref, title = "Jacory Croskey-Merritt finds pay dirt for a Commanders TD", durationMs = 18_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = ClipSession(controller, FakeArchivePlayback(), resolver, scope)
        val videos = NflHubUi.moreVideos(NflHubTestKit.mnfFinalDetail(fin), now)
        // Started before the layer composes, so the bar is up for good (resolving, then buffering: sticky).
        rule.runOnUiThread { session.play(videos.map { ClipRef(it.videoId, ClipKind.PLAY, fin.id) }, 1) }
        rule.setContent {
            BoxTheme {
                Box(Modifier.fillMaxSize().testTag(SHOT).background(DesignTokens.Colors.Canvas)) {
                    ClipPlayerLayer(session, onBackFreeChanged = {}, game = fin, videos = videos)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(CLIP_BAR_TAG).assertExists()
        save("nfl-hub-clip-bar", SHOT)
        scope.cancel()
    }

    private companion object { const val SHOT = "nfl_hub_shot" }
}
