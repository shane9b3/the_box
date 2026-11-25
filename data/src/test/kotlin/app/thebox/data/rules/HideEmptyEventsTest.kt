package app.thebox.data.rules

import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalControls
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeParentalSession
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.ChannelsPages
import app.thebox.core.settings.tree.ChannelsRows
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.HideRuleEntity
import app.thebox.data.editor.ChannelEditorRepositoryImpl
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.strings
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-event-channels.md §5.5 and §11.1 `RuleHiddenPassTest` + the hidden-reason test (re-review E1, E2): the
 * built-in empty-slot matcher on and off, a slot that gains an event shows again, a favourite empty slot is hidden and
 * returns with its event; the Hidden channels page, the channel editor and the Hide rules summary tell the switch from a
 * user rule.
 *
 * Most cases use [emptySlot], a stand-in for `EventNameParser.isEmptySlot`, so they pin the pass and not the parser.
 * [theProductionEmptySlotTestHidesTheRealEmptyShapes] runs the pass with its production default over the real shapes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HideEmptyEventsTest {
    private lateinit var db: LibraryDatabase
    private var p = 0L
    private val clock = FakeClock()

    /** Test stand-in: "… NO EVENT", a bare "NN:" and "… | Offline" are empty slots. */
    private val emptySlot: (String) -> Boolean = { n -> n.endsWith("NO EVENT") || n.endsWith(":") || n.endsWith("| Offline") }

    @Before fun setUp() { db = TestDb.create(); p = runBlocking { db.newPlaylist() } }
    @After fun tearDown() { db.close() }

    private suspend fun pass() = db.writeTransaction { RuleHiddenPass.run(this, p, isEmptySlot = emptySlot) }
    private suspend fun hidden(): List<String> = db.strings("SELECT stable_key FROM channel WHERE rule_hidden = 1 ORDER BY stable_key")
    private suspend fun switch(on: Boolean) = db.playlistDao().setHideEmptyEvents(p, on)

    private suspend fun seed() {
        db.newChannel(p, "empty", "Sportsnet+ 11: NO EVENT")
        db.newChannel(p, "bare", "NHL 02:")
        db.newChannel(p, "offline", "NFL GP 02 | Offline")
        db.newChannel(p, "event", "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET")
        db.newChannel(p, "plain", "Edmonton Oilers")
    }

    @Test
    fun offByDefaultAndHidesOnlyEmptySlotsWhenOn() = runBlocking {
        seed()
        assertFalse(db.playlistDao().get(p)!!.hideEmptyEvents)
        pass()
        assertEquals(emptyList<String>(), hidden())
        switch(true)
        val r = pass()
        assertEquals(listOf("bare", "empty", "offline"), hidden())
        assertEquals(3, r.hiddenChannels)
        assertTrue(r.disabled.isEmpty())
        // Off again with no user rule: the clearAll path shows them all.
        switch(false)
        pass()
        assertEquals(emptyList<String>(), hidden())
    }

    @Test
    fun theProductionEmptySlotTestHidesTheRealEmptyShapes() = runBlocking {
        // Integration: RuleHiddenPass.run with its default seam (the real EventNameParser.isEmptySlot).
        seed()
        db.newChannel(p, "cbc", "(CA) (CBC 13) |  (2098-12-31 08:00:01)")
        db.newChannel(p, "snOne", "Sportsnet One")
        switch(true)
        db.writeTransaction { RuleHiddenPass.run(this, p) }
        assertEquals(listOf("bare", "cbc", "empty", "offline"), hidden())
        assertEquals(4, db.read { RuleHiddenPass.countHiddenByNoEvent(this, p) })
    }

    @Test
    fun aSlotThatGainsAnEventShowsAgainAtTheNextPass() = runBlocking {
        seed()
        switch(true)
        pass()
        assertTrue("empty" in hidden())
        // A refresh renames the slot; `finish` runs the pass.
        db.exec("UPDATE channel SET name = 'Sportsnet+ 11: Montreal @ Colorado @ 25 Nov 09:30 PM ET' WHERE stable_key = 'empty'")
        pass()
        assertFalse("empty" in hidden())
        // And back to empty: hidden again.
        db.exec("UPDATE channel SET name = 'Sportsnet+ 11: NO EVENT' WHERE stable_key = 'empty'")
        pass()
        assertTrue("empty" in hidden())
    }

    @Test
    fun theSwitchSitsBesideUserRules() = runBlocking {
        seed()
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = "Oilers", isRegex = false, createdAt = 1))
        switch(true)
        pass()
        assertEquals(listOf("bare", "empty", "offline", "plain"), hidden())
        switch(false)
        pass()
        assertEquals("only the user rule is left", listOf("plain"), hidden())
    }

    @Test
    fun aMissingRowIsNeverTouched() = runBlocking {
        db.newChannel(p, "gone", "Sportsnet+ 12: NO EVENT", missingSince = 5L)
        switch(true)
        pass()
        assertEquals(emptyList<String>(), hidden())
    }

    @Test
    fun aFavouriteEmptySlotIsHiddenAndReturnsWithItsEvent() = runBlocking {
        // Re-review E2: hide rules apply to every lineup, Favourites included (ChannelDao.HIDDEN).
        seed()
        val gg = GuideGraph(TestGraph(db))
        val list = db.favouriteDao().insertList(FavouriteListEntity(name = "Favourites", sortIndex = 0, createdAt = 1))
        db.favouriteDao().insert(FavouriteEntity(listId = list, playlistId = p, stableKey = "empty", sortIndex = 0, addedAt = 1))
        db.favouriteDao().insert(FavouriteEntity(listId = list, playlistId = p, stableKey = "event", sortIndex = 1, addedAt = 1))
        suspend fun favourites() = gg.queries.lineup(GuideSource.Favourites(list), ParentalAccess.OPEN).ids.size
        assertEquals(2, favourites())
        switch(true)
        pass()
        assertEquals(1, favourites())
        db.exec("UPDATE channel SET name = 'Sportsnet+ 11: Montreal @ Colorado @ 25 Nov 09:30 PM ET' WHERE stable_key = 'empty'")
        pass()
        assertEquals(2, favourites())
    }

    @Test
    fun hiddenByNoEventNeedsTheSwitchTheFlagAndAnEmptyName() {
        assertTrue(RuleHiddenPass.hiddenByNoEvent(true, true, "NHL 02:", emptySlot))
        assertFalse(RuleHiddenPass.hiddenByNoEvent(false, true, "NHL 02:", emptySlot))
        assertFalse(RuleHiddenPass.hiddenByNoEvent(true, false, "NHL 02:", emptySlot))
        assertFalse(RuleHiddenPass.hiddenByNoEvent(true, true, "Edmonton Oilers", emptySlot))
        assertFalse("a parser bug is not a hide", RuleHiddenPass.hiddenByNoEvent(true, true, "x") { error("bug") })
    }

    // ---------------------------------------------------------------- E1: the switch's own reason

    private val texts = BackupTexts { k, args -> if (args.isEmpty()) k.name else k.name + args.joinToString(",", "(", ")") }

    private fun pages() = ChannelsPagesImpl(db, HideRulesImpl(db, ImportMutex(), clock), texts, FakeSettingsStore(), FakeParentalSession(), clock)
        .also { it.isEmptySlot = emptySlot }

    @Test
    fun theHiddenPageShowsTheSwitchsReasonAndLinksToThePlaylistPage() = runBlocking {
        seed()
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = "Oilers", isRegex = false, createdAt = 1))
        db.channelPrefsDao().upsertHidden(p, "offline", true, 1) // hidden by the user too: the user's reason wins
        switch(true)
        pass()
        val route = SettingsRoute.HiddenChannels(p)
        val rows = pages().items(route, 0, 50).associateBy { it.id.key.removePrefix(ChannelsRows.HIDDEN_PREFIX) }
        for (k in listOf("empty", "bare")) {
            assertEquals(BackupText.HIDDEN_NO_EVENT.name, rows.getValue(k).description)
            assertEquals(SettingsRoute.Playlist(p, ChannelsPages.NO_EVENT_SWITCH), (rows.getValue(k).kind as RowKind.Item).route)
        }
        assertEquals("playlist.hide_empty_events", ChannelsPages.NO_EVENT_SWITCH.key)
        assertEquals("HIDDEN_BY_RULE(Oilers)", rows.getValue("plain").description)
        assertEquals(SettingsRoute.HideRules(p), (rows.getValue("plain").kind as RowKind.Item).route)
        assertEquals(BackupText.HIDDEN_BY_YOU.name, rows.getValue("offline").description)
        // Switch off (rows not yet recomputed): never the switch's reason.
        switch(false)
        val stale = pages().items(route, 0, 50).associateBy { it.id.key.removePrefix(ChannelsRows.HIDDEN_PREFIX) }
        assertEquals("HIDDEN_BY_RULE(Oilers)", stale.getValue("empty").description)
    }

    @Test
    fun theHideRulesSummaryCountsOnlyWhatAUserRuleHides() = runBlocking {
        seed()
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = "Oilers", isRegex = false, createdAt = 1))
        switch(true)
        pass()
        val section = pages().page(SettingsRoute.Section(SectionId.CHANNELS), advanced = true)
        assertEquals("CHANNELS_HIDE_RULES_VALUE(1,1)", section.rows.single { it.id == ChannelsRows.HIDE_RULES }.value)
        assertEquals("4", section.rows.single { it.id == ChannelsRows.HIDDEN }.value)
        assertEquals(3, db.read { RuleHiddenPass.countHiddenByNoEvent(this, p, emptySlot) })
        switch(false)
        assertEquals(0, db.read { RuleHiddenPass.countHiddenByNoEvent(this, p, emptySlot) })
    }

    @Test
    fun theEditorMarksTheSwitchsRowsAndKeepsTheUserRulePattern() = runBlocking {
        seed()
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = "Oilers", isRegex = false, createdAt = 1))
        // An empty slot a user rule also matches: the switch's reason wins (§5.5 "never shown as hidden by a user rule").
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = "NHL 02", isRegex = false, createdAt = 1))
        switch(true)
        pass()
        val repo = ChannelEditorRepositoryImpl(db, ParentalControls.NONE, clock, Dispatchers.IO).also { it.isEmptySlot = emptySlot }
        val ids = repo.channels(p, EditorList.All, EditorFilter.ALL, ParentalAccess.OPEN).ids
        val rows = repo.channelRows(ids, 0, ids.size).filterNotNull().associateBy { it.stableKey }
        for (k in listOf("empty", "bare", "offline")) {
            assertTrue(k, rows.getValue(k).ruleHidden)
            assertTrue(k, rows.getValue(k).noEvent)
            assertNull(k, rows.getValue(k).rulePattern)
        }
        assertFalse(rows.getValue("plain").noEvent)
        assertEquals("Oilers", rows.getValue("plain").rulePattern)
        assertFalse(rows.getValue("event").noEvent)
    }
}
