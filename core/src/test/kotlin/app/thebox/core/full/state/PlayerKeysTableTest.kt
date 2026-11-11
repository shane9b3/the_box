package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.BackInPlayer
import app.thebox.core.full.PlayerLayer
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.QuickItem
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.state.PlayerWorld.Companion.archive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithArchive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.full.state.PlayerWorld.Companion.timeShift
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.playback.timeshift.BufferOff
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Table-driven player key test (docs/design/m4-player-ux.md §5.1 `PlayerKeysTableTest`): `player_keys.csv` lists §3.1.2
 * (both columns, every `UpDownInPlayer` value), §3.1.3 (every layer) and §3.1.5 as (precondition, setting, action) →
 * (layer, consumed, effects, toast, check). Runs on the player machine alone, as the guide machine hands it the action
 * (§3.1.1 step 3); `consumed = F` is what the guide then handles.
 */
class PlayerKeysTableTest {
    data class Row(
        val line: Int, val section: String, val pre: String, val updown: String, val action: BoxAction, val repeat: Int, val held: Long,
        val layer: PlayerLayer, val consumed: Boolean, val effects: String, val toast: String, val check: String,
    )

    private fun rows(): List<Row> {
        val text = javaClass.getResource("/player_keys.csv")!!.readText()
        return text.lines().mapIndexedNotNull { i, raw ->
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("section,")) return@mapIndexedNotNull null
            val c = l.split(',')
            require(c.size == 11) { "player_keys.csv:${i + 1}: 11 columns expected: $l" }
            Row(i + 1, c[0], c[1], c[2], BoxAction.valueOf(c[3]), c[4].toInt(), c[5].toLong(), PlayerLayer.valueOf(c[6]), c[7] == "T", c[8], c[9], c[10])
        }
    }

    @TestFactory
    fun keyTable(): List<DynamicTest> = rows().map { r ->
        DynamicTest.dynamicTest("${r.line} ${r.section}: ${r.pre} [${r.updown}] + ${r.action}") { run(r) }
    }

    /** Every layer and every BoxAction on bare video appears in the table; every precondition is used. */
    @Test
    fun coverage() {
        val all = rows()
        val used = all.map { it.pre }.filter { !it.contains('@') }.toSet()
        assertEquals(PRE.keys, used, "unused or missing preconditions")
        for (l in PlayerLayer.entries) assertTrue(all.any { it.layer == l }, "a row ending in $l")
        val bare = all.filter { it.section == "bare" || it.section == "archive" }.map { it.action }.toSet()
        for (a in BoxAction.entries) assertTrue(a in bare, "a bare-video row for $a")
        for (u in listOf("D", "Z", "ZI", "L")) assertTrue(all.any { it.updown == u }, "Up/Down setting $u")
        for (s in listOf("bare", "archive", "quick", "picker", "scorebug", "info", "scrub", "list", "error", "screen", "back")) assertTrue(all.any { it.section == s }, "section $s")
    }

    private fun run(r: Row) {
        val w = world(r.pre)
        w.setSettings(w.settings.copy(upDown = UPDOWN.getValue(r.updown).first, invertChannelDirection = UPDOWN.getValue(r.updown).second))
        val mark = w.mark()
        w.press(r.action, repeat = r.repeat, heldMs = r.held, digit = if (r.action == DIGIT) 1 else -1)
        val ui = w.ui
        assertEquals(r.layer, ui.layer, "layer")
        assertEquals(r.consumed, w.lastConsumed, "consumed")
        val calls = w.callsSince(mark)
        val names = calls.filter { it.name != "toast" }.map { it.name }
        if (r.effects == "-") assertEquals(emptyList(), names, "effects")
        else for (e in r.effects.split(';')) {
            val (n, v) = e.split('=').let { it[0] to it.getOrNull(1) }
            assertTrue(calls.any { it.name == n && (v == null || it.args.firstOrNull().toString() == v) }, "effect $e in $calls")
        }
        val toasts = calls.filter { it.name == "toast" }.map { it.args[0].toString() }
        if (r.toast == "-") assertEquals(emptyList(), toasts, "toasts") else assertEquals(listOf(r.toast), toasts, "toast")
        if (r.check != "-") for (c in r.check.split(';')) check(w, c)
    }

    private fun check(w: PlayerWorld, c: String) {
        val ui = w.ui
        val (k, v) = c.split(':').let { it[0] to it.getOrNull(1) }
        when (k) {
            "quick" -> { val q = assertNotNull(ui.quick, "quick"); assertEquals(QuickItem.valueOf(v!!), q.items[q.focused], "quick focus") }
            "info" -> {
                val i = assertNotNull(ui.info, "info")
                val p = i.programmes.getOrNull(i.focused) ?: fail("no focused programme in $i")
                val now = w.nowMin()
                val cls = when { p.endMin <= now -> "past"; p.startMin > now -> "future"; else -> "now" }
                assertEquals(v, cls, "info focus")
            }
            "infoNum" -> assertEquals(v!!.toInt(), ui.info?.channel?.number, "info channel")
            "browsing" -> assertTrue(ui.info?.browsing == true, "browsing")
            "passive" -> { val i = assertNotNull(ui.info, "passive bar"); assertTrue(!i.active, "passive") }
            "column" -> assertEquals(v, ui.channels?.column?.name, "list column")
            "listFocus" -> assertEquals(v!!.toInt(), ui.channels?.focused, "list focus")
            "groupFocus" -> assertEquals(v!!.toInt(), ui.channels?.groupFocused, "group focus")
            "menu" -> if (v == "open") assertNotNull(ui.channels?.menu, "menu") else assertNull(ui.channels?.menu, "menu closed")
            "menuFocus" -> assertEquals(v!!.toInt(), ui.channels?.menuFocused)
            "byBack" -> assertTrue(ui.channels?.openedByBack == true, "openedByBack")
            "picker" -> assertEquals(v, ui.picker?.kind?.name, "picker")
            "pickerFocus" -> assertEquals(v!!.toInt(), ui.picker?.focused)
            "quickNot" -> assertTrue(QuickItem.valueOf(v!!) !in assertNotNull(ui.quick, "quick").items, "quick has no $v")
            "place" -> assertEquals(ScoreCorner.valueOf(v!!), ui.placeScore?.corner, "place corner")
            "placeSample" -> assertEquals(v!!.toBoolean(), ui.placeScore?.sample, "place sample")
            "pickerRows" -> assertEquals(v!!.toInt(), ui.picker?.choices?.size, "picker rows")
            "fromQuick" -> assertTrue(ui.picker?.fromQuickBar == true, "fromQuickBar")
            "remember" -> assertEquals(v!!.toBoolean(), ui.picker?.remember)
            "scrub" -> {
                val s = assertNotNull(ui.scrub, "scrub bar")
                if (v == "unfocused") assertTrue(!s.focused, "unfocused") else { assertTrue(s.focused, "focused"); assertEquals(ScrubFocus.valueOf(v!!), s.focus) }
            }
            "chip" -> assertEquals(v!!.toInt(), ui.scrub?.chipFocused)
            "atLive" -> assertTrue(ui.scrub?.atLive == true, "atLive")
            "preview" -> {
                val s = assertNotNull(ui.scrub, "scrub bar")
                if (v == "none") assertNull(s.previewMs, "preview") else {
                    val playhead = s.playheadMs + if (s.advancing) w.nowMs - s.atMs else 0L
                    assertEquals(v!!.toLong(), assertNotNull(s.previewMs, "preview") - playhead, "preview − playhead")
                }
            }
            "stats" -> if (v == "on") assertNotNull(ui.stats, "stats") else assertNull(ui.stats, "stats off")
            "button" -> assertEquals(v!!.toInt(), ui.error?.focused, "error focus")
            else -> fail("bad check $c")
        }
    }

    companion object {
        private val UPDOWN = mapOf(
            "D" to (UpDownInPlayer.QUICK_BAR_AND_INFO to false), "Z" to (UpDownInPlayer.ZAP to false),
            "ZI" to (UpDownInPlayer.ZAP to true), "L" to (UpDownInPlayer.CHANNEL_LIST to false),
        )

        private fun PlayerWorld.k(vararg a: BoxAction): PlayerWorld = keys(*a)

        private fun failed(w: PlayerWorld, code: PlaybackErrorCode): PlayerWorld = w.also { it.fail(PlayerFixtures.error(code)) }

        private fun archiveFailed(attempts: Int): PlayerWorld = archive().also { w ->
            val e = PlayerFixtures.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)
            w.setStatus(w.status.copy(phase = ArchivePhase.FAILED, failure = ArchiveFailure.NOT_IN_ARCHIVE, error = e, attempt = 1, attempts = attempts))
            w.fail(e, archive = true)
            check(w.ui.layer == PlayerLayer.ERROR) { "archive error card" }
        }

        private val G1 = SportsFixtures.game(id = "G1") // CGY @ EDM, LIVE: the followed game
        private val G2 = SportsFixtures.game(id = "G2", away = SportsFixtures.team("TOR", "Maple Leafs", 1), home = SportsFixtures.team("MTL", "Canadiens", 1), startUtcMs = 1_793_920_000_000L)
        private val G3 = SportsFixtures.game(id = "G3", phase = GamePhase.SCHEDULED, away = SportsFixtures.team("VAN", "Canucks"), home = SportsFixtures.team("SEA", "Kraken"), period = null, periodKind = null, clock = null)
        private val G4 = SportsFixtures.game(id = "G4", phase = GamePhase.FINAL, away = SportsFixtures.team("BOS", "Bruins", 3), home = SportsFixtures.team("NYR", "Rangers", 2), period = 3, clock = null, finalKind = PeriodKind.REG)

        /** Following G1 with G2 (live), G3 (upcoming) and G4 (final) also on the board. */
        private val FOLLOWING = ScoreFacts(followed = SportsFixtures.liveView(G1), game = SportsFixtures.followedOf(G1), day = DayState.Board(SportsFixtures.board(G4, G3, G2, G1), 0L, false))
        private val NOT_FOLLOWING = ScoreFacts(day = DayState.Board(SportsFixtures.board(G4, G3, G2), 0L, false))

        /** The live world with [facts] sent, the quick bar open on "Score bug", OK pressed (the sheet), then [after]. */
        private fun scoreSheet(facts: ScoreFacts, vararg after: BoxAction): PlayerWorld {
            val w = liveWithArchive()
            w.player.scores(facts)
            w.k(UP)
            repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != QuickItem.SCORE_BUG) w.press(RIGHT) }
            check(w.ui.quick!!.let { it.items[it.focused] } == QuickItem.SCORE_BUG) { "Score bug not in ${w.ui.quick!!.items}" }
            return w.k(SELECT).k(*after)
        }

        /** `quick@ITEM` / `quickA@ITEM`: the quick bar (live / archive) with ITEM focused. */
        fun world(pre: String): PlayerWorld {
            if ('@' in pre) {
                val (base, item) = pre.split('@')
                val w = if (base == "quickA") archive().k(UP) else liveWithArchive().k(UP)
                val target = QuickItem.valueOf(item)
                repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != target) w.press(RIGHT) }
                check(w.ui.quick!!.let { it.items[it.focused] } == target) { "quick item $item not in ${w.ui.quick!!.items}" }
                return w
            }
            return (PRE[pre] ?: fail("unknown precondition $pre"))()
        }

        val PRE: Map<String, () -> PlayerWorld> = linkedMapOf(
            "live" to { liveWithArchive() },
            "live2" to { PlayerWorld(tunedIndex = 2) },
            "plain" to { liveWithoutArchive() },
            "nofav" to { PlayerWorld(favouritesEntry = false) },
            "noprev" to { PlayerWorld(previousIndex = null) },
            "notracks" to { PlayerWorld(tracks = TrackMenu(audio = listOf(PlayerFixtures.audio(0, "en", selected = true)))) },
            "stopped" to { failed(liveWithoutArchive(), PlaybackErrorCode.CONNECTION_LIMIT).k(RIGHT, SELECT).also { check(it.tunedRaw.phase == app.thebox.core.guide.MiniPhase.STOPPED) } },
            "plain_paused" to { liveWithoutArchive().k(PLAY_PAUSE) },
            "live_paused" to { liveWithArchive().k(PLAY_PAUSE).also { check(it.status.phase == ArchivePhase.PAUSED_LIVE) } },
            "archive" to { archive() },
            "archive_paused" to { archive().k(PLAY_PAUSE).also { check(it.status.phase == ArchivePhase.PAUSED) } },
            "tshift" to { timeShift() },
            "live_backlist" to { PlayerWorld(settings = PlayerSettings(back = BackInPlayer.CHANNEL_LIST)) },
            "backlist_closed" to { PlayerWorld(settings = PlayerSettings(back = BackInPlayer.CHANNEL_LIST)).k(SELECT, BACK) },
            "quick" to { liveWithArchive().k(UP) },
            "quick_error" to { failed(liveWithoutArchive(), PlaybackErrorCode.STREAM_INTERRUPTED).k(UP) },
            "picker" to { liveWithArchive().k(ASPECT) },
            "picker_remember" to { liveWithArchive().k(ASPECT, DOWN, DOWN, DOWN, DOWN, DOWN) },
            // M6 §11.3: Pause is first; Audio is the second item.
            // Score bug (WP-4b, docs/design/m6-score-bug.md §6). Rows with a followed game: 0 game, 1 Corner, 2 Delay, 3 Stop,
            // 4 Today, 5 G2 (live), 6 G3 (upcoming), 7 G4 (final).
            "score_follow" to { scoreSheet(FOLLOWING) },
            "score_stop" to { scoreSheet(FOLLOWING, DOWN, DOWN, DOWN) },
            "score_corner" to { scoreSheet(FOLLOWING, DOWN) },
            "score_delay_row" to { scoreSheet(FOLLOWING, DOWN, DOWN) },
            "score_delay" to { scoreSheet(FOLLOWING, DOWN, DOWN, SELECT) },
            "score_delay1" to { scoreSheet(FOLLOWING, DOWN, DOWN, SELECT, DOWN) },
            "score_last" to { scoreSheet(FOLLOWING, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN, DOWN) },
            "score_nofollow" to { scoreSheet(NOT_FOLLOWING) },
            "score_loading" to { scoreSheet(ScoreFacts()) },
            "score_failed" to { scoreSheet(ScoreFacts(day = DayState.Failed(FailKind.IO)), DOWN, DOWN) },
            "score_place" to { scoreSheet(FOLLOWING, DOWN, SELECT) },
            // (Stacked §1.1; WP-3, the coverage WP-4 left) The Style row: the followed game, Corner, Delay, then Style.
            "score_style" to { scoreSheet(FOLLOWING.copy(style = app.thebox.core.sports.NhlBugStyle.PILL), DOWN, DOWN, DOWN) },
            "score_style_stacked" to { scoreSheet(FOLLOWING.copy(style = app.thebox.core.sports.NhlBugStyle.STACKED), DOWN, DOWN, DOWN) },
            "score_place_sample" to { scoreSheet(ScoreFacts(), SELECT) },
            "score_place_bl" to { scoreSheet(ScoreFacts(corner = ScoreCorner.BOTTOM_LEFT), SELECT) },
            "score_off" to { liveWithArchive().also { it.player.scores(ScoreFacts(enabled = false)) }.k(UP) },
            "picker_q" to { liveWithArchive().k(UP, RIGHT, SELECT) },
            "picker_q2" to { liveWithArchive().k(UP, RIGHT, SELECT, DOWN) },
            "info" to { liveWithArchive().k(DOWN) },
            "info_plain" to { liveWithoutArchive().k(DOWN) },
            "info_past" to { liveWithArchive().k(DOWN, UP) },
            "info_future" to { liveWithArchive().k(DOWN, DOWN) },
            "info_browse" to { liveWithArchive().k(DOWN, RIGHT) },
            "scrub" to { archive().k(INFO_BAR) },
            "scrub_preview" to { archive().k(INFO_BAR, LEFT) },
            "scrub_paused" to { archive().k(INFO_BAR, SELECT) },
            "scrub_chips" to { archive().k(INFO_BAR, DOWN) },
            "scrub_live" to { liveWithArchive().k(TIME_SHIFT) },
            "scrub_live_preview" to { liveWithArchive().k(TIME_SHIFT, LEFT) },
            "list" to { liveWithArchive().k(SELECT) },
            "noprev_list" to { PlayerWorld(previousIndex = null).k(SELECT) },
            "list_row1" to { liveWithArchive().k(SELECT, DOWN) },
            "list_prev" to { liveWithArchive().k(SELECT, UP) },
            "list_byback" to { PlayerWorld(settings = PlayerSettings(back = BackInPlayer.CHANNEL_LIST)).k(BACK) },
            "list_groups" to { liveWithArchive().k(LEFT) },
            "list_groups_news" to { liveWithArchive().k(LEFT, DOWN) },
            "list_menu" to { liveWithArchive().k(SELECT, CONTEXT_MENU) },
            "list_menu_fav" to { liveWithArchive().k(SELECT, CONTEXT_MENU, DOWN) },
            "error" to { failed(liveWithoutArchive(), PlaybackErrorCode.STREAM_INTERRUPTED) },
            // M5 (§3.14): Retry, Test the connection (OPEN_SETTINGS), Guide.
            "error_guide" to { failed(liveWithoutArchive(), PlaybackErrorCode.STREAM_INTERRUPTED).k(RIGHT).k(RIGHT) },
            "error_stop" to { failed(liveWithoutArchive(), PlaybackErrorCode.CONNECTION_LIMIT).k(RIGHT) },
            "error_audio" to { failed(liveWithoutArchive(), PlaybackErrorCode.AUDIO_OUTPUT_FAILED) },
            "error_archive" to { archiveFailed(attempts = 1) },
            "error_archive_next" to { archiveFailed(attempts = 3).k(RIGHT) },
            "error_backlist" to { failed(PlayerWorld(tunedIndex = 1, settings = PlayerSettings(back = BackInPlayer.CHANNEL_LIST)), PlaybackErrorCode.STREAM_INTERRUPTED) },
            "screen_off" to { liveWithArchive().k(SCREEN_OFF) },
            "stats" to { liveWithArchive().k(STATS_OVERLAY) },
            "stats_info" to { liveWithArchive().k(STATS_OVERLAY, DOWN) },
            // M6 time-shift (docs/design/m6-timeshift.md §11.1): the bufferCapable axis and the LOCAL session
            "buf" to { liveWithoutArchive().buffer() },
            "buf_arch" to { liveWithArchive().buffer() },
            "hls" to { liveWithoutArchive().buffer(available = false, hls = true, off = BufferOff.NOT_TS) },
            "buf_off" to { liveWithoutArchive().buffer(available = false, off = BufferOff.SETTING_OFF) },
            "buf_space" to { liveWithoutArchive().buffer(available = false, off = BufferOff.NO_SPACE) },
            /** 20 s saved: a 30 s rewind lands on the oldest point (REWIND_START); no programme start is covered. */
            "buf_young" to { liveWithoutArchive().also { it.advance(10_000) }.buffer(savedMs = 20_000) },
            /** Just tuned: within REWIND_NOTHING_YET_MS. */
            "buf_new" to { liveWithoutArchive().buffer(savedMs = 2_000) },
            "buf_quick" to { liveWithoutArchive().buffer().k(UP) },
            "hls_quick" to { liveWithoutArchive().buffer(available = false, hls = true, off = BufferOff.NOT_TS).k(UP) },
            "hls_paused_quick" to { liveWithoutArchive().buffer(available = false, hls = true, off = BufferOff.NOT_TS).k(PLAY_PAUSE, UP) },
            "buf_paused" to { liveWithoutArchive().buffer().k(PLAY_PAUSE).also { check(it.status.source == TimelineSource.LOCAL && it.status.phase == ArchivePhase.PAUSED) } },
            "buf_tshift" to { liveWithoutArchive().buffer().k(PLAY_PAUSE).also { it.advance(5_000) }.k(PLAY_PAUSE).also { check(it.status.phase == ArchivePhase.PLAYING) } },
            "buf_scrub" to { liveWithoutArchive().buffer().k(PLAY_PAUSE, INFO_BAR) },
            "buf_scrub_live" to { liveWithoutArchive().buffer().k(TIME_SHIFT) },
            "buf_dim" to { liveWithoutArchive().buffer().k(PLAY_PAUSE).also { it.advance(130_000) } },
            "buf_paused_long" to { pausedLong() },
            "buf_confirm" to { pausedLong().k(ZAP_UP) },
            "buf_confirm_late" to { pausedLong().k(ZAP_UP).also { it.advance(3_500) } },
            // (Fix pass B1) OK on a paused time-shift continues, so the list opens with the channel-list key.
            "buf_paused_long_list" to { pausedLong().k(CHANNEL_LIST) },
            "buf_paused_long_list1" to { pausedLong().k(CHANNEL_LIST, DOWN) },
            // Fix pass (UX review B1, S7, missing key-table columns)
            "buf_paused_closed" to { liveWithoutArchive().buffer().k(UP, SELECT, BACK).also { check(it.ui.layer == PlayerLayer.NONE && it.ui.scrub == null && it.status.phase == ArchivePhase.PAUSED) } },
            "buf_arch_quick" to { liveWithArchive().buffer().k(UP) },
            "buf_notts" to { liveWithoutArchive().buffer(available = false, off = BufferOff.NOT_TS) },
            "buf_storage" to { liveWithoutArchive().buffer(available = false, off = BufferOff.STORAGE_ERROR) },
        )

        /** A LOCAL pause of 130 s with a key 30 s ago (so the frame is not dimmed): the zap confirm applies. */
        private fun pausedLong(): PlayerWorld = liveWithoutArchive().buffer().k(PLAY_PAUSE).also { w ->
            w.advance(100_000)
            w.press(REMIND)
            w.advance(30_000)
        }
    }
}
