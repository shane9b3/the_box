package app.thebox.core.guide.state

import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import app.thebox.core.input.BoxAction
import app.thebox.core.input.BoxAction.*
import app.thebox.core.playback.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Owner report 2026-11-14 (docs/decisions/2026-11-14-guide-opens-on-playing-channel.md): the guide entered from full
 * screen always shows and focuses the playing channel's row at now, in a group that holds it. Group rule: the shown
 * group when it holds the channel, else the group it was tuned from, else the startup source ("Open guide on"), else All
 * channels; none holds it: the shown group, as before.
 */
class GuideOpensOnPlayingTest {
    private val all = GuideSource.All(1)
    private val news = GuideSource.Group(1, "news")
    private val sport = GuideSource.Group(1, "sport")

    /** News holds channels 5..7, Sport 0..2; All (and Favourites, Recent) every channel. */
    private fun world(tunedRow: Int = 0, settings: GuideSettings = GuideSettings(), lastSourceHint: GuideSource? = null, members: MutableMap<GuideSource, Set<Int>> = defaultMembers()) =
        GuideWorld(tunedRow = tunedRow, settings = settings, lastSourceHint = lastSourceHint, members = members)

    private fun defaultMembers(): MutableMap<GuideSource, Set<Int>> = hashMapOf(news to setOf(5, 6, 7), sport to setOf(0, 1, 2))

    /** The window start the startup focus (at now) uses. */
    private val homeWs = GuideWorld().machine.frame.focus!!.windowStartMin

    /** The Groups panel → [src] → OK. */
    private fun GuideWorld.pick(src: GuideSource) {
        press(GROUPS_PANEL)
        val o = assertIs<Overlay.Groups>(ui.overlay)
        val target = o.shortcuts.size + o.entries.indexOfFirst { it.source == src }
        while ((ui.overlay as Overlay.Groups).focused < target) press(DOWN)
        while ((ui.overlay as Overlay.Groups).focused > target) press(UP)
        press(SELECT)
        assertNull(ui.overlay)
        assertEquals(src, currentSource)
    }

    /** Focus [ch]'s row in the shown lineup (Down from row 0). */
    private fun GuideWorld.focusChannel(ch: Int) {
        while (rowChannel() != channels[ch]) { val r = row(); press(DOWN); check(row() != r) { "channel $ch not in the lineup" } }
    }

    /** OK on [ch] in [src] at now: the mini player tunes it, the guide stays. */
    private fun GuideWorld.tuneFrom(src: GuideSource, ch: Int) {
        pick(src)
        focusChannel(ch)
        press(SELECT)
        assertEquals(ScreenMode.GUIDE, ui.mode)
        assertEquals(channels[ch].identity, ui.tuned.identity)
    }

    /** Hold Back in the guide (PLAY in the guide context), then [back] in FULL. */
    private fun GuideWorld.holdBackThenReturn(back: BoxAction = BACK) {
        press(PLAY)
        assertEquals(ScreenMode.FULL, ui.mode)
        press(back)
        assertEquals(ScreenMode.GUIDE, ui.mode)
        assertEquals(Zone.GRID, ui.zone)
    }

    private fun GuideWorld.row(): Int = machine.frame.focus!!.row
    private fun GuideWorld.rowChannel() = rows.channel(row())

    private fun GuideWorld.assertOn(src: GuideSource, ch: Int) {
        assertEquals(src, currentSource, "the group")
        assertEquals(channels[ch], rowChannel(), "the row")
        val f = machine.frame.focus!!
        assertEquals((nowMs / 60_000L).toInt(), f.anchorMin, "at now: $f")
        assertEquals(homeWs, f.windowStartMin, "the window placed for now: $f")
    }

    // ---- the owner's sequence

    /** (a) Another row of the same group. */
    @Test fun aDifferentRowOfTheSameGroup() {
        val w = world()
        w.tuneFrom(news, 6)
        w.focusChannel(7)
        w.holdBackThenReturn()
        w.assertOn(news, 6)
    }

    /** (b) Another group that does not hold the playing channel: back to the group it was tuned from. */
    @Test fun aDifferentGroup() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(DOWN)
        w.holdBackThenReturn()
        w.assertOn(news, 6)
    }

    /** (b) through the Guide key in FULL. */
    @Test fun aDifferentGroupThenTheGuideKey() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.holdBackThenReturn(OPEN_GUIDE)
        w.assertOn(news, 6)
    }

    /** (c) Scrolled in time, same group and another group. */
    @Test fun scrolledInTime() {
        val w = world()
        w.tuneFrom(news, 6)
        repeat(4) { w.press(RIGHT) }
        w.holdBackThenReturn()
        w.assertOn(news, 6)

        w.pick(sport)
        repeat(4) { w.press(RIGHT) }
        w.press(DOWN)
        w.holdBackThenReturn()
        w.assertOn(news, 6)
    }

    // ---- the group rule

    /** The group shown holds the playing channel: it stays, the row is the channel's. */
    @Test fun theShownGroupIsKeptWhenItHoldsTheChannel() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(all)
        w.press(DOWN)
        w.holdBackThenReturn()
        w.assertOn(all, 6)
    }

    /** Not tuned from a known group (the startup tune): the startup source, "Open guide on" = All. */
    @Test fun unknownTunedFromFallsBackToTheStartupSource() {
        val w = world(tunedRow = 6)
        assertEquals(all, w.currentSource)
        w.pick(sport)
        w.holdBackThenReturn()
        w.assertOn(all, 6)
    }

    /** "Open guide on" = Last with News remembered: the startup source is News. */
    @Test fun unknownTunedFromFallsBackToTheLastSource() {
        val w = world(tunedRow = 6, lastSourceHint = news)
        assertEquals(news, w.currentSource)
        w.pick(sport)
        w.holdBackThenReturn()
        w.assertOn(news, 6)
    }

    /** The tuned-from group lost the channel since: the next candidate (the startup source) holds it. */
    @Test fun aTunedFromGroupThatNoLongerHoldsTheChannelIsSkipped() {
        val members = defaultMembers()
        val w = world(members = members)
        w.tuneFrom(news, 6)
        w.pick(sport)
        members[news] = setOf(5, 7)
        w.holdBackThenReturn()
        w.assertOn(all, 6)
    }

    /** "Open guide on" = Favourites with nothing favourited: All, the default the startup uses. */
    @Test fun favouritesWithoutFavouritesFallsBackToAll() {
        val w = world(tunedRow = 6, settings = GuideSettings(openGuideOn = OpenGuideOn.FAVOURITES))
        w.pick(sport)
        w.holdBackThenReturn()
        w.assertOn(all, 6)
    }

    /** The playing channel is in no listed lineup (hidden since): the shown group and its row at now, as before. */
    @Test fun aChannelInNoLineupKeepsTheShownGroup() {
        val w = world(tunedRow = 6)
        w.hidden += w.channels[6].identityHash
        w.pick(sport)
        w.press(DOWN)
        w.holdBackThenReturn()
        w.assertOn(sport, 1)
    }

    // ---- other entries from FULL

    @Test fun backOutOfSearchOpenedFromFull() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(PLAY)
        w.press(OPEN_SEARCH)
        assertEquals(ScreenMode.SEARCH, w.ui.mode)
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.assertOn(news, 6)
    }

    @Test fun theGuideKeyInSettingsOpenedFromFull() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(PLAY)
        w.press(OPEN_SETTINGS)
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        w.press(OPEN_GUIDE)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.assertOn(news, 6)
    }

    // ---- what does not change

    /** Screens and overlays over the guide (Groups, menu, Days, Search, Settings) return to where the focus was. */
    @Test fun overlaysOverTheGuideDoNotRefocus() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(DOWN)
        repeat(2) { w.press(RIGHT) }
        val before = w.machine.frame.focus
        for (open in listOf(GROUPS_PANEL, CONTEXT_MENU, DAY_STRIP, OPEN_SEARCH, OPEN_SETTINGS)) {
            w.press(open)
            w.press(BACK)
            assertEquals(ScreenMode.GUIDE, w.ui.mode, "$open closed")
            assertNull(w.ui.overlay, "$open closed")
            assertEquals(sport, w.currentSource, "$open: the group")
            assertEquals(before, w.machine.frame.focus, "$open: the focus")
        }
    }

    /** OK in the guide plays in the mini player: the focus stays on the tuned row, the group stays. */
    @Test fun tuningInTheGuideKeepsTheFocus() {
        val w = world()
        w.tuneFrom(sport, 2)
        assertEquals(sport, w.currentSource)
        assertEquals(w.channels[2], w.rowChannel())
    }

    /** Stopped in FULL (nothing playing): Back keeps today's home (the shown group, its row at now), no lookup. */
    @Test fun nothingPlayingKeepsTheShownGroup() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(DOWN)
        w.press(PLAY)
        w.tuneTo(w.channels[6], MiniPhase.STOPPED)
        w.flush()
        val mark = w.calls.calls.size
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.assertOn(sport, 1)
        assertEquals(listOf("setFullScreen"), w.effectsSince(mark))
    }

    /** The error card (Back, or its Guide button): the failed channel counts as current, by the same rule. */
    @Test fun theErrorCardOpensTheGuideOnTheFailedChannel() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(DOWN)
        w.press(PLAY)
        w.tuneTo(w.channels[6], MiniPhase.ERROR, error = PlaybackErrorCode.STREAM_INTERRUPTED)
        w.flush()
        w.press(BACK)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.assertOn(news, 6)
    }

    // ---- the off-screen lookup (review 2026-11-15): one answer, one applySource, nothing in between

    private fun GuideWorld.held(): Int {
        deferHolding = true
        press(PLAY)
        val mark = calls.calls.size
        press(BACK)
        assertEquals(ScreenMode.FULL, ui.mode, "the guide's first frame is held for the answer")
        return mark
    }

    /** (c) The normal case: one lookup, one load of the answer, no candidate touches the zap list, label or last source. */
    @Test fun anotherGroupIsOneLookupAndOneLoad() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        val label = w.ui.sourceLabel
        val mark = w.held()
        assertEquals(listOf("findSourceHolding"), w.effectsSince(mark))
        assertEquals(listOf(news, all), w.calls.last("findSourceHolding")!!.args[1], "tuned-from, then the startup source (All)")
        assertEquals(label, w.ui.sourceLabel, "the header is untouched while the lookup is pending")
        w.answerHolding()
        assertEquals(listOf("findSourceHolding", "loadLineup", "persistSource", "setFullScreen"), w.effectsSince(mark))
        assertEquals(news, w.calls.last("loadLineup")!!.args[0])
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.assertOn(news, 6)
    }

    /** (a) A tuned-from group that lost the channel: the lookup skips it; only All is loaded. */
    @Test fun aStaleTunedFromGroupIsNeverLoaded() {
        val members = defaultMembers()
        val w = world(members = members)
        w.tuneFrom(news, 6)
        w.pick(sport)
        members[news] = setOf(5, 7)
        val mark = w.calls.calls.size
        w.holdBackThenReturn()
        assertEquals(listOf("setFullScreen", "findSourceHolding", "loadLineup", "persistSource", "setFullScreen"), w.effectsSince(mark))
        assertEquals(listOf(all), w.calls.calls.drop(mark).filter { it.name == "loadLineup" }.map { it.args[0] })
        w.assertOn(all, 6)
    }

    /** (b) A hidden playing channel: the lookup answers none; nothing loads and the guide stays on its group. */
    @Test fun aHiddenChannelLoadsNothing() {
        val w = world(tunedRow = 6)
        w.hidden += w.channels[6].identityHash
        w.pick(sport)
        w.press(DOWN)
        val mark = w.calls.calls.size
        w.holdBackThenReturn()
        assertEquals(listOf("setFullScreen", "findSourceHolding", "setFullScreen"), w.effectsSince(mark))
        w.assertOn(sport, 1)
    }

    /** A key while the lookup is pending: the guide shows at once (its home) and the key acts there; the answer is dropped. */
    @Test fun aKeyDuringThePendingLookupWins() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.held()
        w.press(DOWN)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(sport, w.currentSource)
        assertEquals(w.channels[1], w.rowChannel(), "Down from home (row 0)")
        val mark = w.calls.calls.size
        w.answerHolding()
        assertEquals(emptyList(), w.effectsSince(mark), "the late answer changes nothing")
        assertEquals(sport, w.currentSource)
        assertEquals(w.channels[1], w.rowChannel())
    }

    /** Another source change while pending (the playlist went away): it wins; the answer is dropped. */
    @Test fun anotherSourceDuringThePendingLookupWins() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.held()
        val other = GuideSource.All(2)
        w.send(app.thebox.core.guide.GuideEvent.Sources(listOf(app.thebox.core.guide.GuideSourceEntry(other, "Other", 10, app.thebox.core.guide.SourceSection.ALL, null))))
        assertEquals(other, w.currentSource)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        val mark = w.calls.calls.size
        w.answerHolding()
        assertEquals(emptyList(), w.effectsSince(mark))
        assertEquals(other, w.currentSource)
    }

    /** The hold times out: the guide shows where it was; the answer, with no key since, still moves it. A key then drops it. */
    @Test fun theHoldTimesOut() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.held()
        w.advance(GuideStateMachineImpl.ENTRY_HOLD_MS)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(sport, w.currentSource)
        w.answerHolding()
        w.assertOn(news, 6)

        w.pick(sport)
        w.held()
        w.advance(GuideStateMachineImpl.ENTRY_HOLD_MS)
        w.press(DOWN)
        w.answerHolding()
        assertEquals(sport, w.currentSource, "a key after the timeout drops the answer")
    }

    /** Leaving while pending (back to FULL): the answer is dropped. */
    @Test fun leavingDuringThePendingLookupDropsTheAnswer() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(PLAY)
        w.press(OPEN_SEARCH)
        w.deferHolding = true
        w.press(BACK) // Search opened from FULL: held in Search
        assertEquals(ScreenMode.SEARCH, w.ui.mode)
        w.send(app.thebox.core.guide.GuideEvent.SearchClosed(w.channels[2], tuned = true)) // the search screen tuned and left
        val mark = w.calls.calls.size
        w.answerHolding()
        assertEquals(emptyList(), w.effectsSince(mark).filter { it == "loadLineup" })
    }

    // ---- tuned-from is bound to the channel

    /** Previous channel to another News channel: not tuned from News (cleared), so the startup source. */
    @Test fun previousChannelClearsTheTunedFromGroup() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(PREVIOUS_CHANNEL)
        w.tuneTo(w.channels[7]); w.flush() // the controller's previous channel
        w.holdBackThenReturn()
        w.assertOn(all, 7)
    }

    /** A reminder's switch: not tuned from a group. */
    @Test fun aReminderClearsTheTunedFromGroup() {
        val w = world()
        w.tuneFrom(sport, 2)
        w.pick(news)
        w.alert(autoSwitch = false)
        w.advance(600)
        w.press(SELECT) // "Switch now" (channel 1, also in Sport): FULL on it
        assertEquals(w.channels[1].identity, w.ui.tuned.identity)
        assertEquals(ScreenMode.FULL, w.ui.mode)
        w.press(BACK)
        w.assertOn(all, 1)
    }

    /** Number entry in FULL: not tuned from a group (the shown lineup is only its zap list). */
    @Test fun aNumberTuneInFullClearsTheTunedFromGroup() {
        val w = world()
        w.tuneFrom(news, 6)
        w.pick(sport)
        w.press(PLAY)
        for (d in w.channels[7].number.toString()) w.press(DIGIT, digit = d - '0')
        w.advance(5_000)
        assertEquals(w.channels[7].identity, w.ui.tuned.identity)
        assertEquals(ScreenMode.FULL, w.ui.mode)
        w.press(BACK)
        w.assertOn(all, 7)
    }

    /** Favourites, then a search that finds nothing: Green still returns to the user's last group (no candidate touched it). */
    @Test fun greenReturnsToTheLastGroupAfterASearch() {
        val w = world(tunedRow = 6)
        w.hidden += w.channels[6].identityHash
        w.pick(sport)
        w.press(FAVOURITES)
        assertIs<GuideSource.Favourites>(w.currentSource)
        w.holdBackThenReturn()
        assertIs<GuideSource.Favourites>(w.currentSource)
        w.press(FAVOURITES)
        assertEquals(sport, w.currentSource)
    }

    /** Nothing tuned: hold Back does nothing (unchanged). */
    @Test fun nothingTunedStaysInTheGuide() {
        val w = GuideWorld(startTuned = false, members = defaultMembers())
        w.pick(sport)
        w.press(PLAY)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(sport, w.currentSource)
    }
}
