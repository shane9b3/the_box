package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.QuickItem
import app.thebox.core.full.QuickItem.*
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.ErrorDetail
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackErrorCode.*
import app.thebox.core.playback.VideoDecoderMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3.5.2 / E21 step table and the pure scrub arithmetic. */
class ScrubModelTest {
    @ParameterizedTest
    @CsvSource("0,10000", "999,10000", "1000,30000", "2999,30000", "3000,120000", "9999,120000", "10000,300000", "60000,300000")
    fun stepByHeldMs(held: Long, step: Long) = assertEquals(step, ScrubModel.stepMs(held))

    @ParameterizedTest
    @CsvSource("0,0,30000", "0,5000,30000", "1,100,30000", "3,2000,30000", "5,4000,120000", "9,12000,300000")
    fun ffRwUsesTheTableFromItsSecondRow(repeat: Int, held: Long, step: Long) = assertEquals(step, ScrubModel.ffRwStepMs(repeat, held))

    @Test fun previewClampsAtBothEnds() {
        assertEquals(1_000L, ScrubModel.preview(null, 5_000, -10_000, 1_000, 100_000), "archive start")
        assertEquals(100_000L, ScrubModel.preview(null, 95_000, 10_000, 1_000, 100_000), "live edge")
        assertEquals(50_000L, ScrubModel.preview(60_000, 5_000, -10_000, 1_000, 100_000), "steps from the preview, not the playhead")
        assertEquals(15_000L, ScrubModel.preview(null, 5_000, 10_000, 1_000, 100_000))
    }

    @Test fun programmeContainmentAndChipTargets() {
        val p = GuideFixtures.info(1_000, 1_030)
        assertTrue(ScrubModel.contains(p, 1_000 * 60_000L))
        assertFalse(ScrubModel.contains(p, 1_030 * 60_000L))
        assertEquals(60_000_000L, ScrubModel.chipTarget(p, 70_000_000L))
        assertNull(ScrubModel.chipTarget(p, 60_000_000L), "starting at or beyond the live edge → live")
        assertEquals(1, ScrubModel.indexAt(listOf(GuideFixtures.info(970, 1_000), p), 1_010 * 60_000L))
    }

    @Test fun noEpgSpansThirtyMinutesEachSideClamped() {
        val b = ScrubModel.noEpgBounds(100 * 60_000L, 0, 110 * 60_000L)
        assertEquals(70 * 60_000L, b[0]); assertEquals(110 * 60_000L, b[1])
    }

    @Test fun mergeKeepsStartOrderAndReplaces() {
        val a = GuideFixtures.info(10, 20, "a"); val b = GuideFixtures.info(20, 30, "b"); val b2 = GuideFixtures.info(20, 30, "b2")
        assertEquals(listOf("a", "b2"), ScrubModel.merge(listOf(b), listOf(a, b2)).map { it.title })
    }
}

/** §3.3 / §3.1.4 cursor, holds, Previous row, pages. */
class ChannelListModelTest {
    @Test fun opensOnTheTunedRowAtVisibleRowThree() {
        val m = ChannelListModel()
        m.open(50, 20, hasPrevious = false)
        assertEquals(20, m.focused); assertEquals(17, m.top)
        m.open(50, 1, hasPrevious = false)
        assertEquals(0, m.top, "clamped at the top")
        m.open(50, 49, hasPrevious = false)
        assertEquals(40, m.top, "clamped at the end")
        m.open(0, 5, hasPrevious = true)
        assertEquals(-1, m.focused, "an empty list with a previous channel focuses the Previous row")
    }

    @ParameterizedTest
    @CsvSource("0,1", "7,1", "8,3", "19,3", "20,10", "40,10")
    fun holdAcceleration(repeat: Int, rows: Int) = assertEquals(rows, ChannelListModel.rowsPerEvent(repeat))

    @Test fun holdsMoveFasterAndClamp() {
        val m = ChannelListModel()
        m.open(100, 0, hasPrevious = false)
        for (r in 0 until 8) m.move(1, r)
        assertEquals(8, m.focused)
        m.move(1, 8); assertEquals(11, m.focused)
        m.move(1, 20); assertEquals(21, m.focused)
        m.move(1, 200); m.move(1, 200); m.move(1, 200); m.move(1, 200); m.move(1, 200); m.move(1, 200); m.move(1, 200); m.move(1, 200)
        assertEquals(99, m.focused)
        assertTrue(m.focused in m.top until m.top + ChannelListModel.LIST_VISIBLE, "cursor stays visible")
    }

    @Test fun previousRowAboveRowZero() {
        val m = ChannelListModel()
        m.open(10, 0, hasPrevious = true)
        m.move(-1, 0); assertEquals(-1, m.focused, "UP from 0 → Previous")
        m.move(-1, 0); assertEquals(-1, m.focused, "nothing above Previous")
        m.move(1, 0); assertEquals(0, m.focused)
        val n = ChannelListModel()
        n.open(10, 0, hasPrevious = false)
        n.move(-1, 0); assertEquals(0, n.focused, "no Previous row without a previous channel")
        n.open(10, 2, hasPrevious = true)
        n.move(-1, 9); assertEquals(0, n.focused, "an accelerated hold (3 rows) clamps at row 0, not past it")
        n.move(-1, 9); assertEquals(-1, n.focused, "from row 0 UP reaches the Previous row")
    }

    @Test fun pagesTowardsLowerAndHigherNumbers() {
        val m = ChannelListModel()
        m.open(100, 50, hasPrevious = false)
        m.page(-1); assertEquals(40, m.focused)
        m.page(1); m.page(1); assertEquals(60, m.focused)
        m.open(100, 5, hasPrevious = false)
        m.page(-1); assertEquals(0, m.focused)
    }

    @Test fun resizeKeepsTheIndex() {
        val m = ChannelListModel()
        m.open(20, 15, hasPrevious = false)
        m.resize(10, hasPrevious = false)
        assertEquals(9, m.focused)
        assertEquals(0, m.top)
    }
}

/** §3.7 (S8/F5): every code × availability; no prepended Retry; no bare card. */
class ErrorCardModelTest {
    private val ch = GuideFixtures.channel(1)

    private fun narrowed(e: PlaybackError) = e.copy(actions = e.actions.filter { it != NextStep.TRY_BACKUP && it != NextStep.PREVIOUS_CHANNEL })

    @ParameterizedTest
    @EnumSource(PlaybackErrorCode::class)
    fun everyCodeHasAHeadlineAButtonAndAFirstStep(code: PlaybackErrorCode) {
        for (e in listOf(PlayerFixtures.error(code), narrowed(PlayerFixtures.error(code)))) {
            for (variantLeft in listOf(true, false)) for (decoder in listOf(null, VideoDecoderMode.SOFTWARE_PREFERRED)) {
                val c = ErrorCardModel.card(e, ch, decoder, variantLeft, archiveFailed = false)
                assertTrue(code.key.isNotEmpty(), "headline key")
                assertTrue(c.buttons.isNotEmpty(), "$code: at least one button")
                assertEquals(ErrorButton.GUIDE, c.buttons.last(), "$code: Guide always last")
                assertEquals(c.buttons.size, c.buttons.toSet().size, "$code: no duplicates")
                val first = e.actions.firstOrNull()
                if (first != null && first != NextStep.TRY_SOFTWARE_DECODER) {
                    val firstHasButton = c.buttons.size > 1 && buttonFor(first) in c.buttons
                    assertTrue(firstHasButton || c.textStep == first, "$code: the first action is a button or the text step ($c)")
                }
                // (M5 integration, §3.14) A route that edits the playlist is followed by Retry (it applies the fix).
                val fixRoute = c.route is app.thebox.core.settings.SettingsRoute.PlaylistSource || (c.route as? app.thebox.core.settings.SettingsRoute.Playlist)?.focus != null
                when {
                    NextStep.RETRY in e.actions -> assertTrue(ErrorButton.RETRY in c.buttons, "$code: Retry from its own actions")
                    fixRoute -> assertEquals(c.buttons.indexOf(ErrorButton.OPEN_SETTINGS) + 1, c.buttons.indexOf(ErrorButton.RETRY), "$code: Retry right after the route")
                    else -> assertFalse(ErrorButton.RETRY in c.buttons, "$code: no prepended Retry")
                }
                assertTrue(c.buttons.indexOf(ErrorButton.RETRY) <= 0 || e.actions.first() != NextStep.RETRY || c.buttons.first() == ErrorButton.RETRY)
            }
        }
    }

    private fun buttonFor(s: NextStep): ErrorButton? = when (s) {
        NextStep.RETRY -> ErrorButton.RETRY
        NextStep.PREVIOUS_CHANNEL -> ErrorButton.PREVIOUS_CHANNEL
        NextStep.WATCH_LIVE -> ErrorButton.WATCH_LIVE
        NextStep.TRY_OTHER_CATCHUP_MODE -> ErrorButton.TRY_OTHER_CATCHUP_FORMAT
        NextStep.TRY_SOFTWARE_DECODER -> ErrorButton.TRY_SOFTWARE_DECODER
        NextStep.AUDIO_OPTIONS -> ErrorButton.AUDIO_OPTIONS
        NextStep.STOP_AND_WAIT -> ErrorButton.STOP
        // M5 (docs/design/m5-setup-settings.md §3.14)
        NextStep.ACCOUNT_DETAILS, NextStep.EDIT_LOGIN, NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS, NextStep.CHANGE_USER_AGENT -> ErrorButton.OPEN_SETTINGS
        NextStep.USE_SECURE_DNS -> ErrorButton.USE_SECURE_DNS
        NextStep.REFRESH_PLAYLIST -> ErrorButton.REFRESH_PLAYLIST
        NextStep.OPEN_EXTERNAL -> ErrorButton.OPEN_EXTERNAL
        else -> null
    }

    private fun card(code: PlaybackErrorCode, decoder: VideoDecoderMode? = null, variantLeft: Boolean = true, error: PlaybackError = PlayerFixtures.error(code)) =
        ErrorCardModel.card(error, ch, decoder, variantLeft, archiveFailed = false)

    /** The §3.7 per-code table (default actions), with M5's routes (§3.14): one route button per card. */
    @Test fun perCodeTable() {
        val b = ErrorButton.entries.associateBy { it.name }
        fun check(code: PlaybackErrorCode, buttons: String, text: NextStep?) {
            val c = card(code)
            assertEquals(buttons.split(' ').map { b.getValue(it) }, c.buttons, "$code buttons")
            assertEquals(text, c.textStep, "$code text step")
        }
        // (M5 integration, I-M5-21) Edit login is the button, the account the text line.
        check(AUTH_REJECTED, "OPEN_SETTINGS RETRY GUIDE", NextStep.ACCOUNT_DETAILS)
        check(ACCOUNT_EXPIRED, "OPEN_SETTINGS GUIDE", NextStep.ACCOUNT_DETAILS)
        check(ACCOUNT_DISABLED, "OPEN_SETTINGS GUIDE", NextStep.ACCOUNT_DETAILS)
        check(NEEDS_REAUTH, "OPEN_SETTINGS RETRY GUIDE", NextStep.EDIT_LOGIN)
        check(CREDENTIALS_UNAVAILABLE, "RETRY GUIDE", NextStep.RETRY) // P-S2
        check(CONNECTION_LIMIT, "RETRY STOP GUIDE", NextStep.STOP_AND_WAIT) // P-S2
        check(CONNECTION_LIMIT_LOCAL, "RETRY GUIDE", NextStep.RETRY) // P-S2
        check(FORBIDDEN, "RETRY OPEN_SETTINGS GUIDE", NextStep.DIAGNOSTICS)
        check(CHANNEL_OFFLINE, "PREVIOUS_CHANNEL RETRY GUIDE", NextStep.TRY_BACKUP)
        check(FORMAT_REFUSED, "OPEN_SETTINGS RETRY GUIDE", NextStep.DIAGNOSTICS)
        check(WEB_PAGE_INSTEAD, "OPEN_SETTINGS RETRY GUIDE", NextStep.ACCOUNT_DETAILS)
        for (c in listOf(EMPTY_RESPONSE, NO_DATA_TIMEOUT, STREAM_INTERRUPTED, CONNECT_FAILED, INTERNAL)) check(c, "RETRY OPEN_SETTINGS GUIDE", NextStep.DIAGNOSTICS)
        check(SERVER_ERROR, "RETRY GUIDE", NextStep.TRY_BACKUP)
        check(DNS_FAILURE, "USE_SECURE_DNS OPEN_SETTINGS GUIDE", NextStep.USE_SECURE_DNS)
        check(TLS_FAILURE, "OPEN_SETTINGS RETRY GUIDE", NextStep.DIAGNOSTICS)
        check(CODEC_UNSUPPORTED, "TRY_SOFTWARE_DECODER OPEN_EXTERNAL GUIDE", NextStep.TRY_SOFTWARE_DECODER)
        check(STREAM_UNDECODABLE, "RETRY OPEN_EXTERNAL GUIDE", NextStep.OPEN_EXTERNAL)
        check(AUDIO_OUTPUT_FAILED, "AUDIO_OPTIONS GUIDE", NextStep.AUDIO_OPTIONS) // P-S2
        check(UNSUPPORTED_SOURCE, "OPEN_EXTERNAL GUIDE", NextStep.OPEN_EXTERNAL)
        check(CATCHUP_NOT_IN_ARCHIVE, "WATCH_LIVE TRY_OTHER_CATCHUP_FORMAT GUIDE", NextStep.WATCH_LIVE) // P-S2
        check(CHANNEL_NOT_FOUND, "REFRESH_PLAYLIST GUIDE", NextStep.REFRESH_PLAYLIST)
    }

    /** (M5 §3.14) Every OPEN_SETTINGS button carries a non-null route, the route of the first route step. */
    @ParameterizedTest
    @EnumSource(PlaybackErrorCode::class)
    fun everyOpenSettingsButtonCarriesItsRoute(code: PlaybackErrorCode) {
        val e = PlayerFixtures.error(code)
        val c = card(code)
        if (ErrorButton.OPEN_SETTINGS in c.buttons) {
            val route = assertNotNull(c.route, "$code: OPEN_SETTINGS without a route")
            // (M5 integration) Edit login takes the card's one route before Account details.
            val routeSteps = e.actions.filter { buttonFor(it) == ErrorButton.OPEN_SETTINGS }
            val step = if (NextStep.EDIT_LOGIN in routeSteps) NextStep.EDIT_LOGIN else routeSteps.first()
            assertEquals(ErrorCardModel.routeOf(step, code, ch.identity.playlistId), route)
        } else {
            assertEquals(null, c.route, "$code: a route without its button")
        }
    }

    /** (M5 §3.14) No code is left with a route step as its text line unless a route button already took the card's one route. */
    @ParameterizedTest
    @EnumSource(PlaybackErrorCode::class)
    fun routeStepsAreButtonsWhereTheyCanBe(code: PlaybackErrorCode) {
        val c = card(code)
        val t = c.textStep
        val routeStep = t != null && buttonFor(t) == ErrorButton.OPEN_SETTINGS
        if (routeStep) assertTrue(ErrorButton.OPEN_SETTINGS in c.buttons, "$code: a route step as text while no route button exists")
        assertFalse(t == NextStep.TRY_BACKUP && NextStep.TRY_BACKUP !in PlayerFixtures.error(code).actions)
    }

    @Test fun routesPerStep() {
        val pid = ch.identity.playlistId
        assertEquals(app.thebox.core.settings.SettingsRoute.Playlist(pid), card(ACCOUNT_EXPIRED).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.PlaylistSource(pid), card(NEEDS_REAUTH).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.PlaylistSource(pid), card(AUTH_REJECTED).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.Playlist(pid, ErrorCardModel.ROW_STREAM_FORMAT), card(FORMAT_REFUSED).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.Playlist(pid, ErrorCardModel.ROW_IGNORE_TLS), card(TLS_FAILURE).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.Playlist(pid, ErrorCardModel.ROW_USER_AGENT), card(FORBIDDEN).route)
        assertEquals(app.thebox.core.settings.SettingsRoute.NetworkTest(pid), card(CONNECT_FAILED).route)
    }

    /** Without a channel there is no playlist to open: the route steps and Refresh stay text (M4 behaviour). */
    @Test fun noChannelNoRoutes() {
        val c = ErrorCardModel.card(PlayerFixtures.error(AUTH_REJECTED), null, null, variantLeft = true, archiveFailed = false)
        assertEquals(listOf(ErrorButton.GUIDE), c.buttons)
        assertEquals(NextStep.EDIT_LOGIN, c.textStep) // (M5 integration) Edit login ordered first
        assertEquals(null, c.route)
        val r = ErrorCardModel.card(PlayerFixtures.error(CHANNEL_NOT_FOUND), null, null, variantLeft = true, archiveFailed = false)
        assertEquals(listOf(ErrorButton.GUIDE), r.buttons)
    }

    @Test fun ladderNarrowedActions() {
        val offline = PlayerFixtures.error(CHANNEL_OFFLINE).copy(actions = listOf(NextStep.RETRY))
        assertEquals(listOf(ErrorButton.RETRY, ErrorButton.GUIDE), card(CHANNEL_OFFLINE, error = offline).buttons, "no previous channel: the ladder filtered it")
        assertEquals(NextStep.RETRY, card(CHANNEL_OFFLINE, error = offline).textStep, "P-S2: only Retry left, its sentence")
        val server = PlayerFixtures.error(SERVER_ERROR).copy(actions = listOf(NextStep.RETRY))
        assertEquals(NextStep.RETRY, card(SERVER_ERROR, error = server).textStep, "P-S2: no backup, the Retry sentence")
    }

    /** P-S2 = U-S4: every code × {defaults, ladder-narrowed} × variantLeft × decoder carries a next-step line. */
    @ParameterizedTest
    @EnumSource(PlaybackErrorCode::class)
    fun everyCardCarriesANextStepLine(code: PlaybackErrorCode) {
        val bases = listOf(PlayerFixtures.error(code), narrowed(PlayerFixtures.error(code)), PlayerFixtures.error(code).copy(actions = listOf(NextStep.RETRY)))
        for (e in bases) for (variantLeft in listOf(true, false)) for (decoder in listOf(null, VideoDecoderMode.SOFTWARE_PREFERRED)) {
            val c = ErrorCardModel.card(e, ch, decoder, variantLeft, archiveFailed = false)
            val step = assertNotNull(c.textStep, "$code ${e.actions} variantLeft=$variantLeft decoder=$decoder: a next-step line")
            if (step == NextStep.RETRY) {
                assertTrue(e.actions.none { it != NextStep.RETRY && buttonFor(it)?.let { b -> b in c.buttons } == true },
                    "$code: the Retry sentence only when no other action became a button ($c)")
            }
        }
    }

    @Test fun connectionLimitShowsStopAndWaitAsText() {
        val c = card(CONNECTION_LIMIT)
        assertEquals(NextStep.STOP_AND_WAIT, c.textStep, "P-S2: the instruction about the other device is drawn")
        assertEquals(listOf(ErrorButton.RETRY, ErrorButton.STOP, ErrorButton.GUIDE), c.buttons)
    }

    /**
     * WP-C finding (2026-10-15): the only card shown while retrying is the switch-timeout NO_DATA_TIMEOUT with
     * RetryInfo(maxAttempts = 0 = unlimited); STREAM_INTERRUPTED is terminal (retry = null). The card's buttons never
     * depend on `retry` (the retry line is WP-D's drawing of `error.retry`).
     */
    @Test fun retriedCardIsNoDataTimeoutAndButtonsIgnoreRetryInfo() {
        val retried = PlaybackError(NO_DATA_TIMEOUT, ErrorDetail(host = PlayerFixtures.HOST), retry = app.thebox.core.playback.RetryInfo(4, 0, 15_000))
        val c = card(NO_DATA_TIMEOUT, error = retried)
        assertEquals(listOf(ErrorButton.RETRY, ErrorButton.OPEN_SETTINGS, ErrorButton.GUIDE), c.buttons)
        assertEquals(NextStep.DIAGNOSTICS, c.textStep)
        assertEquals(retried.retry, c.error.retry, "the retry info reaches the card unchanged")
        val terminal = PlayerFixtures.error(STREAM_INTERRUPTED).copy(retry = null)
        assertEquals(card(STREAM_INTERRUPTED).buttons, card(STREAM_INTERRUPTED, error = terminal).buttons)
    }

    @Test fun dnsFailureHasNoRetry() = assertFalse(ErrorButton.RETRY in card(DNS_FAILURE).buttons)

    @Test fun softwareDecoderHiddenWhenAlreadySoftwareOrAudio() {
        val sw = card(CODEC_UNSUPPORTED, decoder = VideoDecoderMode.SOFTWARE_PREFERRED)
        assertFalse(ErrorButton.TRY_SOFTWARE_DECODER in sw.buttons)
        assertEquals(NextStep.OPEN_EXTERNAL, sw.textStep, "the step is dropped, not shown as text")
        val audio = card(CODEC_UNSUPPORTED, error = PlayerFixtures.error(CODEC_UNSUPPORTED).copy(detail = ErrorDetail(codec = "audio/eac3")))
        assertFalse(ErrorButton.TRY_SOFTWARE_DECODER in audio.buttons)
        assertTrue(ErrorButton.TRY_SOFTWARE_DECODER in card(CODEC_UNSUPPORTED, decoder = VideoDecoderMode.HARDWARE_ONLY).buttons)
    }

    @Test fun otherCatchupFormatOnlyWhileAVariantIsLeft() {
        val none = card(CATCHUP_NOT_IN_ARCHIVE, variantLeft = false)
        assertEquals(listOf(ErrorButton.WATCH_LIVE, ErrorButton.GUIDE), none.buttons)
        assertEquals(NextStep.TRY_OTHER_CATCHUP_MODE, none.textStep)
    }

    @Test fun archiveFailureCards() {
        for (f in ArchiveFailure.entries) {
            val e = ErrorCardModel.archiveFailure(f)
            val c = ErrorCardModel.card(e, ch, null, variantLeft = false, archiveFailed = true)
            assertTrue(c.archiveFailed)
            assertTrue(ErrorButton.WATCH_LIVE in c.buttons, "$f: Watch live")
            assertEquals(ErrorButton.GUIDE, c.buttons.last())
        }
        assertEquals(CATCHUP_NOT_IN_ARCHIVE, ErrorCardModel.archiveFailure(ArchiveFailure.NOT_IN_ARCHIVE).code)
    }
}

/** §3.2.3 pill table (B1/F3), extrapolation, and the UI debounce (PILL_DEBOUNCE_MS, M2 E-L8). */
class PillRulesTest {
    private val ch = GuideFixtures.channel(0, archiveDays = 7)
    private fun tuned(phase: MiniPhase, archive: Boolean = false, handoff: Boolean = false) =
        TunedState(ch.identity, ch.rowId, ch.identityHash, phase, archive, reconnectAttempt = 2, reconnectMax = 5, handoff = handoff)
    private fun status(phase: ArchivePhase, timeShift: Boolean, wall: Long = 1_000_000, live: Long = 1_090_000, at: Long = 2_000_000, advancing: Boolean = phase == ArchivePhase.PLAYING, hash: Long = ch.identityHash) =
        ArchiveStatus(phase = phase, identityHash = hash, wallMs = wall, liveEdgeMs = live, atMs = at, advancing = advancing, timeShift = timeShift)

    @Test fun table() {
        val now = 2_010_000L
        assertEquals(PillKind.LIVE, PillRules.pill(tuned(MiniPhase.PLAYING), null, now).kind)
        assertEquals(PillKind.CONNECTING, PillRules.pill(tuned(MiniPhase.CONNECTING), null, now).kind)
        assertEquals(PillKind.CONNECTING, PillRules.pill(tuned(MiniPhase.BUFFERING), null, now).kind)
        val rc = PillRules.pill(tuned(MiniPhase.RECONNECTING), null, now)
        assertEquals(PillKind.RECONNECTING, rc.kind); assertEquals(2, rc.reconnectAttempt); assertEquals(5, rc.reconnectMax)
        assertEquals(PillKind.PAUSED, PillRules.pill(tuned(MiniPhase.PAUSED), null, now).kind, "plain pause")
        assertFalse(PillRules.pill(tuned(MiniPhase.PAUSED), null, now).behindGrows)
        val pl = PillRules.pill(tuned(MiniPhase.PAUSED), status(ArchivePhase.PAUSED_LIVE, true), now)
        assertEquals(PillKind.PAUSED, pl.kind); assertTrue(pl.behindGrows)
        assertEquals(1_090_000L + 10_000 - 1_000_000, pl.behindMs, "live − wall at now: grows 1:1 while paused")
        assertEquals(now, pl.atMs)
        val behind = PillRules.pill(tuned(MiniPhase.PLAYING, archive = true), status(ArchivePhase.PLAYING, true), now)
        assertEquals(PillKind.BEHIND, behind.kind); assertEquals(90_000L, behind.behindMs); assertFalse(behind.behindGrows)
        for (p in listOf(ArchivePhase.STARTING, ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)) {
            assertEquals(PillKind.BEHIND, PillRules.pill(tuned(MiniPhase.PLAYING, archive = true), status(p, true), now).kind, "$p time-shift")
            assertEquals(PillKind.CATCH_UP, PillRules.pill(tuned(MiniPhase.PLAYING, archive = true), status(p, false), now).kind, "$p catch-up")
        }
        assertEquals(PillKind.CATCH_UP, PillRules.pill(tuned(MiniPhase.PLAYING, archive = true), null, now).kind, "archive tune without a session")
        assertEquals(PillKind.LIVE, PillRules.pill(tuned(MiniPhase.PLAYING), status(ArchivePhase.FAILED, true), now).kind, "FAILED is no session")
        assertEquals(PillKind.LIVE, PillRules.pill(tuned(MiniPhase.PLAYING), status(ArchivePhase.PLAYING, true, hash = 42), now).kind, "another channel's session")
    }

    /** B1: an owned hand-off never reads CONNECTING; it keeps the session's pill. */
    @Test fun handoffKeepsTheSessionPill() {
        val now = 2_010_000L
        assertEquals(PillKind.BEHIND, PillRules.pill(tuned(MiniPhase.PLAYING, true, handoff = true), status(ArchivePhase.SEEKING, true), now).kind)
        assertEquals(PillKind.CATCH_UP, PillRules.pill(tuned(MiniPhase.PLAYING, true, handoff = true), status(ArchivePhase.CONTINUING, false), now).kind)
    }

    @Test fun reconnectFlickerIsDebounced() {
        val d = PillRules.Debouncer()
        val live = PillState(PillKind.LIVE); val rc = PillState(PillKind.RECONNECTING, reconnectAttempt = 1)
        assertEquals(live, d.offer(live, 0))
        assertEquals(live, d.offer(rc, 100), "a 300 ms flicker never shows")
        assertEquals(500L, d.nextChangeAtMs)
        assertEquals(live, d.offer(live, 400))
        assertEquals(Long.MAX_VALUE, d.nextChangeAtMs)
        assertEquals(live, d.offer(rc, 1_000))
        assertEquals(live, d.offer(rc.copy(reconnectAttempt = 2), 1_399))
        assertEquals(PillKind.RECONNECTING, d.offer(rc.copy(reconnectAttempt = 2), 1_400).kind, "stable for 400 ms → shown")
        assertEquals(3, d.offer(rc.copy(reconnectAttempt = 3), 1_500).reconnectAttempt, "same kind: numbers update at once")
    }
}

/** §3.4.1 item order and hiding (owner ruling 2026-10-15), with M6's Pause / Back to live first (m6-timeshift §11.3). */
class QuickBarModelTest {
    private val menu = PlayerFixtures.trackMenu()
    private val prev = GuideFixtures.channel(9)

    @Test fun liveWithArchiveFullOrder() {
        assertEquals(
            listOf(TIME_SHIFT, AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, START_OVER, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SETTINGS),
            QuickBarModel.items(menu, startOverCapable = true, inArchive = false, nowProgramme = true, favourite = false, previous = prev),
        )
    }

    /** M6 §11.3 (U-B3): Pause first on every live channel, whatever it can do (HLS, buffer off, no space). */
    @Test fun pauseFirstOnEveryLiveChannel() {
        for (capable in listOf(true, false)) {
            val items = QuickBarModel.items(menu, startOverCapable = capable, inArchive = false, nowProgramme = true, favourite = null, previous = null)
            assertEquals(TIME_SHIFT, items.first(), "capable=$capable")
            assertFalse(JUMP_TO_LIVE in items)
        }
        assertFalse(TIME_SHIFT in QuickBarModel.items(menu, false, false, false, null, null, live = false), "nothing tuned: no Pause")
    }

    /** M6 §11.3: Start over is offered when the buffer covers the programme's start (the caller's startOverCapable). */
    @Test fun startOverWithBufferCoverage() {
        assertTrue(START_OVER in QuickBarModel.items(menu, startOverCapable = true, inArchive = false, nowProgramme = true, favourite = null, previous = null))
        assertFalse(START_OVER in QuickBarModel.items(menu, startOverCapable = false, inArchive = false, nowProgramme = true, favourite = null, previous = null))
    }

    /** (M5 integration, §3.6.2) Sources shows only with two or more servers, just before Settings. */
    @Test fun sourcesWithBackupServers() {
        val one = QuickBarModel.items(menu.copy(servers = listOf("a:80")), startOverCapable = false, inArchive = false, nowProgramme = false, favourite = null, previous = null)
        assertTrue(app.thebox.core.full.QuickItem.SOURCES !in one)
        val two = QuickBarModel.items(menu.copy(servers = listOf("a:80", "b:80")), startOverCapable = false, inArchive = false, nowProgramme = false, favourite = null, previous = null)
        assertEquals(listOf(app.thebox.core.full.QuickItem.SOURCES, SETTINGS), two.takeLast(2))
    }

    /** M6 §11.3: behind live (time-shift or catch-up) Back to live comes first; no Start over, no Pause. */
    @Test fun behindLiveBackToLiveFirst() {
        val items = QuickBarModel.items(menu, startOverCapable = true, inArchive = true, nowProgramme = true, favourite = true, previous = prev)
        assertEquals(listOf(JUMP_TO_LIVE, AUDIO, ASPECT, SUBTITLES, FAVOURITE_REMOVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SETTINGS), items)
    }

    @Test fun hiding() {
        val bare = QuickBarModel.items(TrackMenu(), startOverCapable = false, inArchive = false, nowProgramme = true, favourite = null, previous = null)
        assertEquals(listOf(TIME_SHIFT, ASPECT, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SETTINGS), bare, "favourite hidden until the answer; no audio/subtitles")
        val oneUnsupported = TrackMenu(audio = listOf(PlayerFixtures.audio(0, "en", selected = true, supported = false)))
        assertTrue(AUDIO in QuickBarModel.items(oneUnsupported, false, false, false, null, null), "a single unsupported track still offers Audio")
        assertFalse(START_OVER in QuickBarModel.items(menu, true, false, nowProgramme = false, favourite = null, previous = null), "no programme on now")
        for (s in listOf(true, false)) for (a in listOf(true, false)) {
            val items = QuickBarModel.items(menu, a, s, true, null, prev)
            assertEquals(SETTINGS, items.last(), "Settings always last")
            assertEquals(if (s) JUMP_TO_LIVE else TIME_SHIFT, items.first(), "Back to live / Pause first")
            assertEquals(items.drop(1).sortedBy { it.ordinal }, items.drop(1), "after the first item, the enum order")
        }
    }

    @Test fun quickItemOrderIsTheContractOrder() =
        assertEquals(listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, JUMP_TO_LIVE, TIME_SHIFT, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SOURCES, SETTINGS, QuickItem.SCORE_BUG), QuickItem.entries.toList()) // score bug commit 0: appended

    @Suppress("unused") private fun unused() = assertNotNull(prev)
}
