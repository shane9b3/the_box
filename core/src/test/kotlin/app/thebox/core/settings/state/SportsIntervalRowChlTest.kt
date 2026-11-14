package app.thebox.core.settings.state

import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.SportsRows
import app.thebox.core.sports.CHL_LEAGUES
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** (CHL WP-2, docs/design/m6-chl-leagues.md §1.6 #21-27, §6.5) The Sports interval row for the CHL. */
class SportsIntervalRowChlTest {
    private val store = FakeSettingsStore()
    private val pages = WpaPages(store, SettingsTexts.KEYS, FakePageEnv())

    private fun row(): SettingsRow =
        pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = true).rows.first { it.id == SportsRows.INTERVAL }

    private fun options(r: SettingsRow) = (r.kind as RowKind.Choice).options

    @Test
    fun theChlRowIsFromCompletionTitledByTheGroupWithItsOwnList() {
        for (league in CHL_LEAGUES) {
            store.update(SportsDomain) { it.copy(league = league) }
            val r = row()
            assertEquals("SPORTS_INTERVAL(CHL)", r.label, "$league: the group's name, not the league's")
            assertEquals("SPORTS_INTERVAL_AUTO_AFTER(5)", r.value)
            assertEquals(listOf("0", "5", "10", "15", "20", "30"), options(r).map { it.id })
            assertEquals("SPORTS_INTERVAL_RECOMMENDED(SPORTS_INTERVAL_AFTER(5))", options(r)[1].label)
            assertTrue(!r.description!!.contains("SPORTS_INTERVAL_NHL_FLOOR"), r.description)
            assertEquals("SPORTS_INTERVAL_DESC_UNMEASURED(5,1.4) SPORTS_INTERVAL_LEAGUE(CHL)", r.description)
        }
    }

    @Test
    fun aSlowedChlLeagueSaysSoWithItsOwnName() {
        store.update(SportsDomain) { it.copy(league = League.WHL) }
        store.update(SportsFollowDomain) { it.copy(meters = mapOf(League.WHL to PollMeasure(slowed = true))) }
        val d = row().description!!
        assertTrue(d.contains("SPORTS_INTERVAL_SLOWED_LEAGUE(WHL)"), d)
        assertTrue(!d.contains("SPORTS_INTERVAL_LEAGUE"), d)
    }

    @Test
    fun theMeterIsTheFollowedGamesLeagueWhenItIsInTheShownGroup() {
        store.update(SportsDomain) { it.copy(league = League.OHL) }
        val followed = SportsFixtures.followedOf(SportsFixtures.chlGame(league = League.QMJHL))
        store.update(SportsFollowDomain) { it.copy(game = followed, meters = mapOf(League.QMJHL to PollMeasure(slowed = true))) }
        assertTrue(row().description!!.contains("SPORTS_INTERVAL_SLOWED_LEAGUE(QMJHL)"), row().description)
        // A followed NHL game while the CHL is shown: the shown league's meter.
        val nhl = SportsFixtures.followedOf(SportsFixtures.game())
        store.update(SportsFollowDomain) {
            it.copy(game = nhl, meters = mapOf(League.NHL to PollMeasure(slowed = true), League.OHL to PollMeasure(slowed = true)))
        }
        assertTrue(row().description!!.contains("SPORTS_INTERVAL_SLOWED_LEAGUE(OHL)"), row().description)
    }

    @Test
    fun theIntentWritesTheChlInterval() {
        store.update(SportsDomain) { it.copy(league = League.QMJHL) }
        assertTrue(pages.apply(RowIntent.Choose(SportsRows.INTERVAL, "15")))
        val p = store.flow(SportsDomain).value
        assertEquals(15, p.chlIntervalS)
        assertEquals(0, p.nhlIntervalS)
        assertEquals(0, p.nflIntervalS)
    }
}
