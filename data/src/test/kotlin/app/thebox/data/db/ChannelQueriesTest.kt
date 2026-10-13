package app.thebox.data.db

import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgMatchEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import app.thebox.data.db.dao.MatchCounts
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The §2.1.4 queries with the parental predicate (architecture review C7) on browse, number, search and unmatched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelQueriesTest {
    private lateinit var db: LibraryDatabase
    private var p = 0L
    private var news = 0L
    private var adultGroup = 0L

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        p = db.newPlaylist()
        news = db.newGroup(p, "g:news", "News")
        adultGroup = db.newGroup(p, "g:xxx", "XXX", adult = true)
        db.newChannel(p, "bbc", "BBC One", news, sortIndex = 0, number = 101, tvgId = "bbc.uk")
        db.newChannel(p, "cnn", "CNN", news, sortIndex = 1, number = 102)
        db.newChannel(p, "tele", "Télé Matin", news, sortIndex = 2, number = 103)
        db.newChannel(p, "gone", "Gone TV", news, sortIndex = 3, number = 104, missingSince = 1)
        db.newChannel(p, "adultch", "Hot", news, sortIndex = 4, number = 105, adult = true)
        db.newChannel(p, "ingroup", "Late", adultGroup, sortIndex = 5, number = 106)
        Unit
    }

    @After fun tearDown() { db.close() }

    private suspend fun browse(unlocked: Boolean, group: Long = news) = db.channelDao().browseGroup(p, group, unlocked).first().map { it.channel.stableKey }

    @Test
    fun browseHidesMissingAdultAndHidden() = runBlocking {
        assertEquals(listOf("bbc", "cnn", "tele"), browse(false))
        assertEquals(listOf("bbc", "cnn", "tele", "adultch"), browse(true))
        assertEquals(emptyList<String>(), browse(false, adultGroup))
        assertEquals(listOf("ingroup"), browse(true, adultGroup))
        // Hidden channel: never shown, even unlocked.
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "cnn", hidden = true, updatedAt = 1))
        assertEquals(listOf("bbc", "tele", "adultch"), browse(true))
        // Explicit unlock of an adult channel; explicit lock of a normal one.
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "adultch", locked = false, updatedAt = 1))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "bbc", locked = true, updatedAt = 1))
        assertEquals(listOf("tele", "adultch"), browse(false))
        // Group lock overrides channel auto state; group hide hides everything.
        db.groupDao().upsertPrefs(GroupPrefsEntity(p, 0, "g:news", locked = true, updatedAt = 1))
        assertEquals(emptyList<String>(), browse(false))
        db.groupDao().upsertPrefs(GroupPrefsEntity(p, 0, "g:news", hidden = true, updatedAt = 1))
        assertEquals(emptyList<String>(), browse(true))
    }

    @Test
    fun customOrderComesFirstThenProviderOrder() = runBlocking {
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "tele", customSortIndex = 0, updatedAt = 1))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "cnn", customSortIndex = 1, updatedAt = 1))
        assertEquals(listOf("tele", "cnn", "bbc"), browse(false))
    }

    @Test
    fun numberLookupRespectsParentalAndCustomNumbers() = runBlocking {
        assertEquals("cnn", db.channelDao().byNumber(p, 102, false)!!.channel.stableKey)
        assertNull(db.channelDao().byNumber(p, 105, false))
        assertEquals("adultch", db.channelDao().byNumber(p, 105, true)!!.channel.stableKey)
        assertNull("missing channels have no number", db.channelDao().byNumber(p, 104, true))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "tele", customNumber = 7, updatedAt = 1))
        assertEquals("tele", db.channelDao().byNumber(p, 7, false)!!.channel.stableKey)
        assertNull("the provider number is replaced", db.channelDao().byNumber(p, 103, false))
    }

    @Test
    fun ftsSearchFoldsDiacriticsAndAppliesThePredicate() = runBlocking {
        fun q(t: String) = ChannelRepository.ftsQuery(t)!!
        assertEquals(listOf("tele"), db.channelDao().search(q("tele"), false, 10).map { it.channel.stableKey })
        assertEquals(listOf("tele"), db.channelDao().search(q("Télé Mat"), false, 10).map { it.channel.stableKey })
        assertEquals(listOf("bbc"), db.channelDao().search(q("bb"), false, 10).map { it.channel.stableKey })
        assertEquals(emptyList<String>(), db.channelDao().search(q("gone"), true, 10).map { it.channel.stableKey })
        assertEquals(emptyList<String>(), db.channelDao().search(q("hot"), false, 10).map { it.channel.stableKey })
        assertEquals(listOf("adultch"), db.channelDao().search(q("hot"), true, 10).map { it.channel.stableKey })
        assertNull(ChannelRepository.ftsQuery("  \"*  "))
    }

    @Test
    fun unmatchedMatchedWithoutDataAndCounts() = runBlocking {
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        db.epgMatchDao().insertAll(
            listOf(
                EpgMatchEntity(p, "bbc", s, "bbc.uk", 0, "ID_EXACT", 1f, hasData = true, inputsHash = 1, matchedAt = 1),
                EpgMatchEntity(p, "cnn", s, "cnn.us", 0, "DISPLAY_NAME", 0.97f, hasData = false, inputsHash = 1, matchedAt = 1),
                EpgMatchEntity(p, "tele", s, "tm.fr", 1, "FUZZY", 0.8f, inputsHash = 1, matchedAt = 1),
            ),
        )
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "ingroup", watchCount = 9, updatedAt = 1))
        val unmatched = db.channelDao().unmatched(unlocked = true).first()
        assertEquals(listOf("ingroup", "tele", "adultch"), unmatched.map { it.stableKey })
        assertEquals("$s\u001Ftm.fr\u001F0.8", unmatched[1].bestSuggestion?.let { it.substring(0, it.lastIndexOf('\u001F') + 4) })
        assertEquals(listOf("tele"), db.channelDao().unmatched(unlocked = false).first().map { it.stableKey })
        assertEquals(listOf("cnn"), db.channelDao().matchedWithoutData(unlocked = false).first().map { it.stableKey })
        // An explicit mapping takes a channel out of the unmatched list.
        db.epgMappingDao().upsert(EpgMappingEntity(p, "tele", s, "tm.fr", 1))
        assertEquals(listOf("ingroup", "adultch"), db.channelDao().unmatched(unlocked = true).first().map { it.stableKey })
        val counts = db.channelDao().matchCounts(p)
        assertEquals(MatchCounts(5, 3, 2), counts)
    }
}
