package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.QuickItem
import app.thebox.core.full.QuickItem.*
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.full.state.PlayerWorld.Companion.archive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithArchive
import app.thebox.core.full.state.PlayerWorld.Companion.liveWithoutArchive
import app.thebox.core.full.state.PlayerWorld.Companion.timeShift
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.BACK
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.LEFT
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.sports.DayState
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §3.1.6 five-key rule in FULL (review B4/F6 as adapted to the owner ruling of 2026-10-15): a breadth-first search with
 * UP/DOWN/LEFT/RIGHT/SELECT/BACK only (no CONTEXT_MENU, no media keys) from four start states — live with archive, live
 * without archive, archive (`playProgramme`) and time-shift (`pauseLive` + resume) — once per [UpDownInPlayer] value.
 * **Within each start state no state of another kind is expanded** (a zap, a jump to live or a start of the archive
 * ends the path; the effect that left is still counted): every [PlayerLayer], every [QuickItem] that applies there
 * (reached *and* selected), every [PickerKind], every [ScrubFocus], the Previous row and the kind's effects are reached.
 * Every [ErrorButton] that applies is reached and selected on a scripted ERROR per code.
 *
 * Stated exclusions: the channel menu (long OK; favourite is in the quick bar), number entry (digits), the ERROR layer
 * in the graph search (scripted per code below), and in archive/time-shift the INFO layer (by design the bottom bar is
 * the scrub bar there, §0.8). States are replayed from scratch (the machine is not cloneable); a coarse signature
 * dedupes them.
 */
class PlayerFiveKeyReachabilityTest {
    private val keys = listOf(UP, DOWN, LEFT, RIGHT, SELECT, BACK)

    /**
     * The four §3.1.6 start states, plus (M6 time-shift, docs/design/m6-timeshift.md §11.1) a live channel without
     * catch-up that the device's buffer can pause and rewind, and that channel's LOCAL time-shift (pause + resume).
     */
    enum class Start { LIVE_WITH_ARCHIVE, LIVE_WITHOUT_ARCHIVE, ARCHIVE, TIME_SHIFT, LIVE_BUFFERED, LOCAL_TIME_SHIFT, PAUSED_LOCAL }

    /** (M5 integration) Two servers, so the Sources item and the SERVER picker are offered (§3.6.2). */
    private val tracks = PlayerFixtures.trackMenu().copy(servers = listOf("panel.example:8080", "backup.example:8080"))

    /** (Score bug WP-4b) Every start state has today's board with one live game, so the score sheet offers a game to follow. */
    private fun world(start: Start, s: PlayerSettings): PlayerWorld = baseWorld(start, s).also { w ->
        val g = SportsFixtures.game()
        w.player.scores(ScoreFacts(day = DayState.Board(SportsFixtures.board(g), fetchedAtMono = 0L, failing = false), style = app.thebox.core.sports.NhlBugStyle.PILL))
    }

    private fun baseWorld(start: Start, s: PlayerSettings): PlayerWorld = when (start) {
        Start.LIVE_WITH_ARCHIVE -> liveWithArchive(s, tracks)
        Start.LIVE_WITHOUT_ARCHIVE -> liveWithoutArchive(s, tracks)
        Start.ARCHIVE -> archive(s, tracks)
        Start.TIME_SHIFT -> timeShift(s, tracks)
        Start.LIVE_BUFFERED -> liveWithoutArchive(s, tracks).buffer()
        Start.LOCAL_TIME_SHIFT -> liveWithoutArchive(s, tracks).buffer().also { w ->
            w.press(BoxAction.PLAY_PAUSE)
            w.advance(5_000)
            w.press(BoxAction.PLAY_PAUSE)
            check(w.status.phase == ArchivePhase.PLAYING && w.status.source == app.thebox.core.catchup.archive.TimelineSource.LOCAL) { "LOCAL time-shift: ${w.status}" }
        }
        // (Fix pass, UX review S10) Paused with the bar closed, where B1 lived: the quick bar's Pause (QUICK_MENU opens
        // it under every Up/Down setting; under the default it is ↑), OK, then Back.
        Start.PAUSED_LOCAL -> liveWithoutArchive(s, tracks).buffer().also { w ->
            w.press(BoxAction.QUICK_MENU)
            w.press(SELECT)
            w.press(BACK)
            check(w.status.phase == ArchivePhase.PAUSED && w.status.source == app.thebox.core.catchup.archive.TimelineSource.LOCAL && w.ui.layer == PlayerLayer.NONE && w.ui.scrub == null) { "paused, bar closed: ${w.status} ${w.ui.layer}" }
        }
    }

    private class Reached {
        val layers = HashSet<PlayerLayer>(); val pickers = HashSet<PickerKind>(); val scrub = HashSet<ScrubFocus>()
        val quickSelected = HashSet<QuickItem>(); var previousRow = false; val effects = HashSet<String>()
    }

    private fun observe(w: PlayerWorld, r: Reached) {
        val ui = w.ui
        r.layers += ui.layer
        ui.picker?.let { r.pickers += it.kind }
        ui.scrub?.takeIf { it.focused }?.let { r.scrub += it.focus }
        if (ui.channels?.focused == -1) r.previousRow = true
    }

    private fun signature(w: PlayerWorld): String {
        val ui = w.ui
        val info = ui.info?.let { i ->
            val p = i.programmes.getOrNull(i.focused)
            val now = w.nowMin()
            "${i.active}${i.channel?.number}${i.browsing}" + when { p == null -> "x"; p.endMin <= now -> "p${if (p.rawStartMin >= now - 7 * 1440) "a" else ""}"; p.startMin > now -> "f"; else -> "n" }
        }
        return listOf(
            ui.layer, info, ui.scrub?.let { "${it.focused}${it.focus}${it.chipFocused}${it.previewMs != null}${it.atLive}" },
            ui.channels?.let { "${it.column}${it.focused}${it.groupFocused}${it.source}" }, ui.quick?.let { "${it.items}${it.focused}${it.statsOn}" },
            ui.picker?.let { "${it.kind}${it.focused}${it.remember}${it.fromQuickBar}" }, ui.placeScore, ui.stats != null, w.status.phase, w.tunedRaw.phase,
            w.favourites.size, w.video, w.tracks.audio.indexOfFirst { it.selected }, w.tracks.textEnabled,
        ).joinToString("|")
    }

    private fun search(start: Start, s: PlayerSettings): Reached {
        val r = Reached()
        val seen = HashSet<String>()
        val startHash = world(start, s).also { observe(it, r); seen += signature(it) }.tunedRaw.identityHash
        val startKind = world(start, s).kind(startHash)
        var frontier = listOf(emptyList<BoxAction>())
        for (depth in 1..MAX_DEPTH) {
            val next = ArrayList<List<BoxAction>>()
            for (seq in frontier) for (k in keys) {
                val w = world(start, s)
                for (a in seq) w.press(a)
                val quickFocus = if (k == SELECT) w.ui.quick?.let { it.items[it.focused] } else null
                val mark = w.mark()
                w.press(k)
                r.effects += w.effectsSince(mark)
                quickFocus?.let { r.quickSelected += it }
                if (w.kind(startHash) != startKind) continue // another kind of state: counted, not expanded
                observe(w, r)
                if (seen.add(signature(w))) next += seq + k
            }
            frontier = next
            if (frontier.isEmpty() || seen.size > 20_000) break
        }
        return r
    }

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun liveWithArchive(u: UpDownInPlayer) = assertReach(Start.LIVE_WITH_ARCHIVE, u,
        // M6 §11.3: the quick bar's Pause pauses at once (the scrub bar then belongs to the time-shift start state), so
        // the at-live scrub bar is reached by TIME_SHIFT / SEEK_BACK only, which are not among the five keys.
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.SCRUB,
        items = listOf(TIME_SHIFT, AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = emptyList(), effects = listOf("startOver", "pauseLive", "playProgramme", "followGame", "setScoreCorner", "setScoreDelay", "setScoreStyle", "scorePicker"))

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun liveWithoutArchive(u: UpDownInPlayer) = assertReach(Start.LIVE_WITHOUT_ARCHIVE, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.SCRUB,
        // M6 §11.3 (U-B3): Pause on every live channel; without catch-up or a buffer it freezes (M2) with the reason.
        items = listOf(TIME_SHIFT, AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = emptyList(), effects = listOf("pausePlain", "resumePlain"))

    /** M6 §11.1: a buffered live channel without catch-up: Pause (LOCAL) and Start over (the buffer covers it). */
    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun liveBuffered(u: UpDownInPlayer) {
        // (Fix pass S10) The Pause reached with five keys lands in a LOCAL state, not a plain freeze.
        val w = liveWithoutArchive(PlayerSettings(upDown = u), tracks).buffer()
        w.press(BoxAction.QUICK_MENU) // the quick bar under every setting; the five-key route to it is checked below
        w.press(SELECT)
        assertTrue(w.status.source == app.thebox.core.catchup.archive.TimelineSource.LOCAL && w.status.phase == ArchivePhase.PAUSED, "pauseLive from LIVE_BUFFERED is LOCAL: ${w.status}")
        liveBufferedReach(u)
    }

    private fun liveBufferedReach(u: UpDownInPlayer) = assertReach(Start.LIVE_BUFFERED, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.SCRUB,
        items = listOf(TIME_SHIFT, AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = emptyList(), effects = listOf("pauseLive", "startOver"))

    /** M6 §11.1: the LOCAL time-shift of that channel: the scrub bar over the rewind window, Back to live, pause. */
    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun localTimeShift(u: UpDownInPlayer) = assertReach(Start.LOCAL_TIME_SHIFT, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.INFO,
        items = listOf(JUMP_TO_LIVE, AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("jumpToLive", "archiveSeek", "archivePause"))

    /**
     * (Fix pass, UX review S10) Paused LOCAL with the bar closed: OK continues (B1) and Back to live is reachable, under
     * every Up/Down setting (↑ quick bar, or ← scrub bar then ↑).
     */
    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun pausedLocal(u: UpDownInPlayer) {
        val r = search(Start.PAUSED_LOCAL, PlayerSettings(upDown = u))
        val missing = listOf("archiveResume", "jumpToLive").filter { it !in r.effects }
        assertTrue(missing.isEmpty(), "PAUSED_LOCAL/$u: not reachable with five keys: $missing")
        // The very first OK resumes.
        val w = world(Start.PAUSED_LOCAL, PlayerSettings(upDown = u))
        val mark = w.mark()
        w.press(SELECT)
        assertTrue(w.effectsSince(mark) == listOf("archiveResume"), "OK resumes: ${w.effectsSince(mark)}")
    }

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun archive(u: UpDownInPlayer) = assertReach(Start.ARCHIVE, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.INFO,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, JUMP_TO_LIVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("jumpToLive", "archiveSeek", "archivePause"))

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun timeShift(u: UpDownInPlayer) = assertReach(Start.TIME_SHIFT, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.INFO,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, JUMP_TO_LIVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SOURCES, QuickItem.SCORE_BUG, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("jumpToLive", "archiveSeek", "archivePause"))

    private fun assertReach(start: Start, u: UpDownInPlayer, layers: List<PlayerLayer>, items: List<QuickItem>, scrub: List<ScrubFocus>, effects: List<String>) {
        val r = search(start, PlayerSettings(upDown = u))
        val missing = buildList {
            addAll(layers.filter { it !in r.layers })
            addAll(items.filter { it !in r.quickSelected }.map { "quick:$it" })
            addAll(PickerKind.entries.filter { it !in r.pickers }.map { "picker:$it" }) // M5 integration: SERVER is offered (Sources)
            addAll(scrub.filter { it !in r.scrub }.map { "scrub:$it" })
            if (!r.previousRow) add("Previous row")
            addAll(effects.filter { it !in r.effects }.map { "effect:$it" })
        }
        assertTrue(missing.isEmpty(), "$start/$u: not reachable with five keys: $missing")
    }

    /** Every ErrorButton that applies, per code: reached with LEFT/RIGHT, and SELECT does what it says (§3.7). */
    @Test
    fun everyErrorButtonPerCode() {
        val effectOf = mapOf(
            ErrorButton.RETRY to setOf("retry", "archiveResume"), ErrorButton.PREVIOUS_CHANNEL to setOf("previousChannel"),
            ErrorButton.WATCH_LIVE to setOf("jumpToLive"), ErrorButton.TRY_OTHER_CATCHUP_FORMAT to setOf("tryNextCatchupFormat"),
            ErrorButton.TRY_SOFTWARE_DECODER to setOf("setDecoder"), ErrorButton.AUDIO_OPTIONS to setOf("picker"),
            ErrorButton.STOP to setOf("stopPlayback"), ErrorButton.GUIDE to setOf("unconsumed"),
            // M5 (§3.14)
            ErrorButton.OPEN_SETTINGS to setOf("openSettingsRoute"), ErrorButton.USE_SECURE_DNS to setOf("errorAction"),
            ErrorButton.REFRESH_PLAYLIST to setOf("errorAction"), ErrorButton.OPEN_EXTERNAL to setOf("errorAction"),
            // M5 integration (B2)
            ErrorButton.BACK_TO_AUTO to setOf("errorAction"),
        )
        val seen = HashSet<ErrorButton>()
        val wrong = ArrayList<String>()
        for (code in PlaybackErrorCode.entries) {
            val buttons = errorWorld(code).ui.error!!.buttons
            for ((i, b) in buttons.withIndex()) {
                val w = errorWorld(code)
                repeat(i) { w.press(RIGHT) }
                if (w.ui.error?.focused != i) { wrong += "$code: $b not reachable"; continue }
                val mark = w.mark()
                w.press(SELECT)
                val did = w.effectsSince(mark).toMutableSet()
                if (!w.lastConsumed) did += "unconsumed"
                if (w.ui.picker != null) did += "picker"
                if (did.none { it in effectOf.getValue(b) }) wrong += "$code: $b did $did"
                seen += b
            }
        }
        assertTrue(wrong.isEmpty(), "error buttons: $wrong")
        assertTrue(seen.containsAll(ErrorButton.entries), "every ErrorButton occurs for some code: missing ${ErrorButton.entries - seen}")
    }

    /** A live error for most codes; CATCHUP_NOT_IN_ARCHIVE as a FAILED archive session with a variant left (§3.7). */
    private fun errorWorld(code: PlaybackErrorCode): PlayerWorld {
        if (code == PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE) return archive().also { w ->
            val e = PlayerFixtures.error(code)
            w.setStatus(w.status.copy(phase = ArchivePhase.FAILED, failure = ArchiveFailure.NOT_IN_ARCHIVE, error = e, attempt = 1, attempts = 2))
            w.fail(e, archive = true)
        }
        // (M5 integration, B2) AUDIO_OUTPUT_FAILED as the controller reports it under "Always passthrough".
        val e = PlayerFixtures.error(code).let { if (code == PlaybackErrorCode.AUDIO_OUTPUT_FAILED) it.copy(actions = listOf(app.thebox.core.playback.NextStep.BACK_TO_AUTO) + it.actions) else it }
        return PlayerWorld(tunedIndex = 1).also { it.fail(e) }
    }

    private companion object {
        /**
         * (Stacked WP-3) 19, was 18. The Score bug sheet's Style row (Stacked §1.1) sits between Delay and the games, and
         * the sheet opens on the first game, so its Corner row is one more Up away: the shortest five-key path to
         * `setScoreCorner` grew from 17 to 18 keys under the default Up/Down setting (Up, Right × 11 to Score bug, OK,
         * Up × 3, OK, OK) and from 18 to 19 under ZAP and CHANNEL_LIST. The search depth, not a reachability gap.
         */
        const val MAX_DEPTH = 19
    }
}
