package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §6.3, §6.5, §6.6, lead ruling L7) The Sports tab with the CHL: the chip row
 * `NHL · NFL · CHL | OHL · WHL · QMJHL` on one row, the D-pad and focus rules, nothing moving vertically, the credit line per
 * league ("LHJMQ" for the QMJHL), the empty-day, failed and refused texts, the cards' nickname and "Time TBD", and the
 * spoken text (P10).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ChlSportsTabTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private val store = FakeSettingsStore()

    /** The tab's writes go to the store; mirror them into the repository's prefs as the real repository does. */
    private fun wire(prefs: SportsPrefs) {
        store.update(SportsDomain) { prefs }
        repo.prefs.value = prefs
        CoroutineScope(Dispatchers.Unconfined).launch { store.flow(SportsDomain).collect { repo.prefs.value = it } }
    }

    private fun board(vararg g: app.thebox.core.sports.GameSnapshot, league: League) {
        repo.day.value = DayState.Board(SportsFixtures.board(*g, dayKey = "2026-11-13", league = league), android.os.SystemClock.elapsedRealtime(), false)
    }

    private fun show() {
        val vm = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }, easternToday = { LocalDate.of(2026, 11, 13) })
        rule.setContent { BoxTheme { SportsScreen(vm, clock24 = false) } }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        for (k in keys) rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg a: Any) = rule.activity.getString(id, *a)
    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    // ------------------------------------------------------------------ the chips

    @Test fun withoutCHLTheRowIsTodaysAndHasNoSubChips() {
        wire(SportsPrefs(league = League.NHL))
        board(SportsFixtures.game(id = "H1"), league = League.NHL)
        show()
        for (g in listOf("NHL", "NFL", "CHL")) rule.onNodeWithTag("sports_league:$g").assertExists()
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) rule.onNodeWithTag(sportsChlTag(l)).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_CHL_DIVIDER_TAG).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.sports_credit)).assertExists()
    }

    @Test fun okOnCHLShowsTheRememberedSubLeagueAndMovesFocusToItsChip() {
        wire(SportsPrefs(league = League.NHL, chlLeague = League.WHL))
        board(SportsFixtures.game(id = "H1"), league = League.NHL)
        show()
        // Up, Up reaches the NHL chip (the day chip first); Right, Right to CHL.
        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight))
        rule.onNodeWithTag(sportsGroupTag(app.thebox.core.sports.LeagueGroup.CHL)).assertIsFocused()
        press(listOf(Key.DirectionCenter))
        val p = store.flow(SportsDomain).value
        assertEquals("the CHL chip writes league = the remembered sub-league", League.WHL, p.league)
        assertEquals(League.WHL, p.chlLeague)
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) rule.onNodeWithTag(sportsChlTag(l)).assertExists()
        rule.onNodeWithTag(sportsChlTag(League.WHL)).assertIsFocused()
    }

    @Test fun okOnASubChipWritesLeagueAndChlLeagueAndFocusStaysAndLeftRightWalkTheWholeRow() {
        wire(SportsPrefs(league = League.OHL, chlLeague = League.OHL))
        board(SportsFixtures.chlGame(), league = League.OHL)
        show()
        press(listOf(Key.DirectionUp, Key.DirectionUp))
        // Up from the day chips lands on the sub-chip of the shown league.
        rule.onNodeWithTag(sportsChlTag(League.OHL)).assertIsFocused()
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertEquals(League.WHL, store.flow(SportsDomain).value.league)
        assertEquals(League.WHL, store.flow(SportsDomain).value.chlLeague)
        rule.onNodeWithTag(sportsChlTag(League.WHL)).assertIsFocused()
        // Right to QMJHL, then on to the Corner chip: the row is one walk.
        press(listOf(Key.DirectionRight))
        rule.onNodeWithTag(sportsChlTag(League.QMJHL)).assertIsFocused()
        press(listOf(Key.DirectionRight))
        rule.onNodeWithTag(SPORTS_CORNER_TAG).assertIsFocused()
        // Left walks back through the sub-chips to CHL and on to NFL.
        press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft))
        rule.onNodeWithTag(sportsGroupTag(app.thebox.core.sports.LeagueGroup.CHL)).assertIsFocused()
        press(listOf(Key.DirectionLeft))
        rule.onNodeWithTag(sportsGroupTag(app.thebox.core.sports.LeagueGroup.NFL)).assertIsFocused()
        // Down from any chip goes to the day chips; Up from the day chips returns to the sub-chip.
        press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionDown))
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
        press(listOf(Key.DirectionUp))
        rule.onNodeWithTag(sportsChlTag(League.WHL)).assertIsFocused()
    }

    @Test fun nhlAndNflLeaveTheRememberedSubLeagueAlone() {
        wire(SportsPrefs(league = League.QMJHL, chlLeague = League.QMJHL))
        board(SportsFixtures.chlGame(league = League.QMJHL), league = League.QMJHL)
        show()
        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft))
        rule.onNodeWithTag(sportsGroupTag(app.thebox.core.sports.LeagueGroup.NHL)).assertIsFocused()
        press(listOf(Key.DirectionCenter))
        val p = store.flow(SportsDomain).value
        assertEquals(League.NHL, p.league)
        assertEquals("the sub-league is remembered", League.QMJHL, p.chlLeague)
        rule.onNodeWithTag(sportsChlTag(League.OHL)).assertDoesNotExist()
    }

    private fun assertNothingMovesVerticallyAndTheRowFits() {
        wire(SportsPrefs(league = League.NHL))
        board(SportsFixtures.game(id = "H1"), league = League.NHL)
        show()
        val dayTop = bounds(sportsDayTag(SportsDay.TODAY)).top
        val gridTop = bounds(SPORTS_GRID_TAG).top
        val chipTop = bounds("sports_league:NHL").top
        store.update(SportsDomain) { it.copy(league = League.QMJHL, chlLeague = League.QMJHL) }
        board(SportsFixtures.chlGame(league = League.QMJHL), league = League.QMJHL)
        rule.waitForIdle()
        assertEquals("the day chips stay put", dayTop, bounds(sportsDayTag(SportsDay.TODAY)).top, 0.5f)
        assertEquals("the grid stays put", gridTop, bounds(SPORTS_GRID_TAG).top, 0.5f)
        for (l in listOf(League.OHL, League.WHL, League.QMJHL)) {
            assertEquals("$l sits on the chips' row", chipTop, bounds(sportsChlTag(l)).top, 0.5f)
        }
        val corner = bounds(SPORTS_CORNER_TAG)
        assertTrue("the last sub-chip ends before Corner (${bounds(sportsChlTag(League.QMJHL)).right} vs ${corner.left})", bounds(sportsChlTag(League.QMJHL)).right < corner.left)
        assertTrue("the divider sits between CHL and OHL", bounds(SPORTS_CHL_DIVIDER_TAG).left > bounds("sports_league:CHL").right && bounds(SPORTS_CHL_DIVIDER_TAG).right < bounds(sportsChlTag(League.OHL)).left)
    }

    @Test fun nothingMovesVerticallyAndTheRowEndsBeforeCornerAt1920() = assertNothingMovesVerticallyAndTheRowFits()

    @Config(sdk = [35], qualifiers = "w640dp-h360dp-xhdpi")
    @Test fun nothingMovesVerticallyAndTheRowEndsBeforeCornerAt1280() = assertNothingMovesVerticallyAndTheRowFits()

    @Test fun backOnASubChipChangesNothingAndLeavesFocusAndTheRouterFree() {
        wire(SportsPrefs(league = League.OHL, chlLeague = League.OHL))
        board(SportsFixtures.chlGame(), league = League.OHL)
        val backFree = ArrayList<Boolean>()
        val vm = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }, easternToday = { LocalDate.of(2026, 11, 13) })
        rule.setContent { BoxTheme { SportsScreen(vm, clock24 = false, onBackFreeChanged = { backFree += it }) } }
        rule.waitForIdle()
        press(listOf(Key.DirectionUp, Key.DirectionUp))
        rule.onNodeWithTag(sportsChlTag(League.OHL)).assertIsFocused()
        press(listOf(Key.Back))
        // The screen does not consume Back on a chip: the ladder (the router's) takes it. Nothing here changed.
        rule.onNodeWithTag(sportsChlTag(League.OHL)).assertIsFocused()
        assertEquals(League.OHL, store.flow(SportsDomain).value.league)
        assertTrue(backFree.last())
    }

    @Test fun aKeyBeforeTheSubSwitchAppearsCancelsTheFocusPull() {
        // (Review L6) The store answers late (nothing mirrors the write); a Left before it lands must not be undone.
        store.update(SportsDomain) { SportsPrefs(league = League.NHL, chlLeague = League.OHL) }
        repo.prefs.value = SportsPrefs(league = League.NHL, chlLeague = League.OHL)
        board(SportsFixtures.game(id = "H1"), league = League.NHL)
        show()
        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight, Key.DirectionCenter))
        press(listOf(Key.DirectionLeft)) // NFL; the repository's prefs still say NHL, so no sub-chips yet
        repo.prefs.value = store.flow(SportsDomain).value // the write lands now
        rule.waitForIdle()
        rule.onNodeWithTag(sportsGroupTag(app.thebox.core.sports.LeagueGroup.NFL)).assertIsFocused()
    }

    @Test fun rightToLeftMirrorsTheRowAndKeepsItOnOneLine() {
        wire(SportsPrefs(league = League.WHL, chlLeague = League.WHL))
        board(SportsFixtures.chlGame(league = League.WHL), league = League.WHL)
        val vm = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }, easternToday = { LocalDate.of(2026, 11, 13) })
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                BoxTheme { SportsScreen(vm, clock24 = false) }
            }
        }
        rule.waitForIdle()
        val order = listOf("sports_league:NHL", "sports_league:NFL", "sports_league:CHL", sportsChlTag(League.OHL), sportsChlTag(League.WHL), sportsChlTag(League.QMJHL))
        val lefts = order.map { bounds(it).left }
        assertEquals("the row reads right to left", lefts.sortedDescending(), lefts)
        val tops = order.map { bounds(it).top }
        assertTrue("one line", tops.all { Math.abs(it - tops[0]) < 0.5f })
    }

    // ------------------------------------------------------------------ credit, empty, failed, refused

    @Test fun theCreditNamesTheLeagueAndTheQmjhlKeepsLhjmqVerbatim() {
        wire(SportsPrefs(league = League.OHL))
        board(SportsFixtures.chlGame(), league = League.OHL)
        show()
        rule.onNodeWithText("Official statistics provided by Ontario Hockey League · Powered by HockeyTech.com").assertExists()
        for ((league, who) in listOf(League.WHL to "Western Hockey League", League.QMJHL to "LHJMQ")) {
            store.update(SportsDomain) { it.copy(league = league, chlLeague = league) }
            board(SportsFixtures.chlGame(league = league), league = league)
            rule.waitForIdle()
            rule.onNodeWithText("Official statistics provided by $who · Powered by HockeyTech.com").assertExists()
        }
    }

    @Test fun anEmptyCHLDayAndTheFailuresNameTheLeagueAndTheNextStep() {
        wire(SportsPrefs(league = League.WHL, chlLeague = League.WHL))
        repo.day.value = DayState.Board(SportsFixtures.board(dayKey = "2026-11-13", league = League.WHL), 0L, false)
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No WHL games today. Pick another day above.")
        repo.day.value = DayState.Failed(FailKind.IO)
        rule.waitForIdle()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("WHL scores did not answer. Press OK to try again; scores may be down for a few minutes.")
        repo.day.value = DayState.Failed(FailKind.REFUSED)
        rule.waitForIdle()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("WHL scores stopped letting this app in. CHL scores need an app update; NHL and NFL scores still work.")
        repo.day.value = DayState.Failed(FailKind.OFFLINE)
        rule.waitForIdle()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals(str(R.string.sports_no_network))
    }

    // ------------------------------------------------------------------ cards

    @Test fun cardsUseTheNicknameAndTimeTbdAndTheSpokenTextIsTheNickname() {
        wire(SportsPrefs(league = League.OHL))
        val live = SportsFixtures.chlGame(
            id = "1", away = SportsFixtures.chlTeam("LDN", "Knights", 1), home = SportsFixtures.chlTeam("KIT", "Rangers", 2),
        )
        val tbd = SportsFixtures.chlGame(
            id = "2", phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, startTbd = true,
            away = SportsFixtures.chlTeam("BRAM", "Steelheads", teamId = null), home = SportsFixtures.chlTeam("FLNT", "Firebirds", teamId = null),
        )
        board(live, tbd, league = League.OHL)
        show()
        rule.onNodeWithText("Knights").assertExists()
        rule.onNodeWithText("Steelheads").assertExists()
        rule.onNodeWithText("Time TBD").assertExists()
        // The plate shows the 4-letter code until a logo loads.
        rule.onNodeWithText("BRAM").assertExists()
        val desc = rule.onNodeWithTag(sportsCardTag("2")).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(desc, desc.contains("Steelheads") && desc.contains("Time TBD"))
    }

    @Test fun theBugsSpokenTextUsesCityAndNickname() {
        val g = SportsFixtures.chlGame()
        val text = Describe(rule.activity.resources, SportsFixtures.liveView(g)).text()
        assertEquals("London Knights 1, Kitchener Rangers 2, 10:22 left in the 2nd", text)
    }
}
