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

    enum class Start { LIVE_WITH_ARCHIVE, LIVE_WITHOUT_ARCHIVE, ARCHIVE, TIME_SHIFT }

    private fun world(start: Start, s: PlayerSettings): PlayerWorld = when (start) {
        Start.LIVE_WITH_ARCHIVE -> liveWithArchive(s)
        Start.LIVE_WITHOUT_ARCHIVE -> liveWithoutArchive(s)
        Start.ARCHIVE -> archive(s)
        Start.TIME_SHIFT -> timeShift(s)
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
            ui.picker?.let { "${it.kind}${it.focused}${it.remember}${it.fromQuickBar}" }, ui.stats != null, w.status.phase, w.tunedRaw.phase,
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
        layers = PlayerLayer.entries - PlayerLayer.ERROR,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, TIME_SHIFT, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("startOver", "pauseLive", "playProgramme", "rewindLive"))

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun liveWithoutArchive(u: UpDownInPlayer) = assertReach(Start.LIVE_WITHOUT_ARCHIVE, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.SCRUB,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, SETTINGS),
        scrub = emptyList(), effects = emptyList())

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun archive(u: UpDownInPlayer) = assertReach(Start.ARCHIVE, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.INFO,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, JUMP_TO_LIVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("jumpToLive", "archiveSeek", "archivePause"))

    @ParameterizedTest
    @EnumSource(UpDownInPlayer::class)
    fun timeShift(u: UpDownInPlayer) = assertReach(Start.TIME_SHIFT, u,
        layers = PlayerLayer.entries - PlayerLayer.ERROR - PlayerLayer.INFO,
        items = listOf(AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, JUMP_TO_LIVE, PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, QuickItem.SCREEN_OFF, SETTINGS),
        scrub = ScrubFocus.entries, effects = listOf("jumpToLive", "archiveSeek", "archivePause"))

    private fun assertReach(start: Start, u: UpDownInPlayer, layers: List<PlayerLayer>, items: List<QuickItem>, scrub: List<ScrubFocus>, effects: List<String>) {
        val r = search(start, PlayerSettings(upDown = u))
        val missing = buildList {
            addAll(layers.filter { it !in r.layers })
            addAll(items.filter { it !in r.quickSelected }.map { "quick:$it" })
            addAll(PickerKind.entries.filter { it !in r.pickers }.map { "picker:$it" })
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
        return PlayerWorld(tunedIndex = 1).also { it.fail(PlayerFixtures.error(code)) }
    }

    private companion object {
        const val MAX_DEPTH = 18
    }
}
