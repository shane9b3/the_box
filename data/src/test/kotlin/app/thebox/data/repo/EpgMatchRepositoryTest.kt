package app.thebox.data.repo

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.epgChannel
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MatcherWhitelistResolver (§3.6.5): persistence, whitelist, has_data, patch refs, incremental matching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpgMatchRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private var p = 0L
    private var src = 0L

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        g = TestGraph(db)
        p = db.newPlaylist()
        db.newChannel(p, "bbc", "BBC One HD", tvgId = "BBCOne.uk", sortIndex = 0)
        db.newChannel(p, "cnn", "CNN International", sortIndex = 1)
        db.newChannel(p, "hbo", "HBO", sortIndex = 2)
        db.newChannel(p, "arte", "Arte", tvgId = "arte.fr@SD", sortIndex = 3)
        db.newChannel(p, "gone", "Gone", tvgId = "gone.uk", sortIndex = 4, missingSince = 1)
        src = db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        Unit
    }

    @After fun tearDown() { db.close() }

    private val catalogue get() = listOf(
        epgChannel(src, "bbcone.uk", "BBC One"),
        epgChannel(src, "cnn.int", "CNN International"),
        epgChannel(src, "hbo.east", "HBO (East)"),
        epgChannel(src, "hbo.west", "HBO (West)"),
        epgChannel(src, "Arte.fr@HD", "Arte HD"),
        epgChannel(src, "other", "Other"),
    )

    @Test
    fun whitelistDuringABuildKeepsThePublishedHasData() = runBlocking {
        // Code review M1 S-9: the building generation reports programme_count 0 for every channel (pre-repair).
        g.matches.whitelistFor(src, catalogue.asSequence())
        assertEquals(1L, db.scalar("SELECT has_data FROM epg_match WHERE stable_key = 'bbc'"))
        val building = catalogue.map { it.copy(programmeCount = 0) }
        g.matches.whitelistFor(src, building.asSequence())
        assertEquals("has_data survives until afterBuild", 1L, db.scalar("SELECT has_data FROM epg_match WHERE stable_key = 'bbc'"))
    }

    @Test
    fun whitelistPersistsMatchesAndAcceptsSubjectIds() = runBlocking {
        db.epgMappingDao().upsert(EpgMappingEntity(p, "hbo", src, "hbo.west", 1))
        val wl = g.matches.whitelistFor(src, catalogue.asSequence())
        // AUTO: bbc (case-insensitive id), cnn (display name), arte (iptv-org base id); hbo is an override.
        val rows = db.epgMatchDao().autos().associate { it.stableKey to (it.xmltvId to it.method) }
        assertEquals("bbcone.uk" to "ID_CASE_INSENSITIVE", rows["bbc"])
        assertEquals("cnn.int" to "DISPLAY_NAME", rows["cnn"])
        assertEquals("Arte.fr@HD" to "ID_BASE", rows["arte"])
        assertFalse("overrides are not cached in epg_match", "hbo" in rows)
        assertFalse("missing channels are not subjects", db.strings("SELECT stable_key FROM epg_match").contains("gone"))
        for (id in listOf("bbcone.uk", "cnn.int", "hbo.west", "Arte.fr@HD", "BBCOne.uk", "arte.fr@SD", "ARTE.FR@FHD")) assertTrue(id, wl.accepts(id))
        assertFalse(wl.accepts("hbo.east"))
        assertFalse(wl.accepts("other"))
        assertTrue(db.scalar("SELECT COUNT(DISTINCT inputs_hash) FROM epg_match") >= 1)
    }

    @Test
    fun ambiguousNamesAreSuggestionsOnly() = runBlocking {
        g.matches.whitelistFor(src, catalogue.asSequence())
        val hbo = g.matches.matches(ChannelIdentity(p, StableKey("hbo")))
        assertEquals(setOf("hbo.east", "hbo.west"), hbo.map { it.xmltvId }.toSet())
        assertTrue(hbo.all { it.status == 1 })
        assertEquals(listOf("hbo"), g.matches.unmatched(access = app.thebox.core.parental.ParentalAccess.OPEN).first().map { it.stableKey })
    }

    @Test
    fun unknownSourceGetsAnEmptyWhitelist() {
        assertFalse(g.matches.whitelistFor(999, catalogue.asSequence()).accepts("bbcone.uk"))
    }

    @Test
    fun afterBuildSetsHasDataAndReturnsPatchRefs() = runBlocking {
        g.store.catalogue[src] = catalogue.map { if (it.xmltvId == "cnn.int") it.copy(programmeCount = 0) else it }
        g.matches.whitelistFor(src, catalogue.asSequence())
        // A new mapping to a channel the generation did not import.
        db.epgMappingDao().upsert(EpgMappingEntity(p, "hbo", src, "hbo.east", 1))
        g.store.publish(mapOf(src to setOf("bbcone.uk", "cnn.int", "Arte.fr@HD")))
        val refs = g.matches.afterBuild(g.store)
        assertEquals(listOf(EpgChannelRef(src, "hbo.east")), refs)
        assertEquals(1L, db.scalar("SELECT has_data FROM epg_match WHERE stable_key = 'bbc'"))
        assertEquals(0L, db.scalar("SELECT has_data FROM epg_match WHERE stable_key = 'cnn'"))
        assertEquals(0, g.store.openReaders.get())
    }

    @Test
    fun overrideTriggersAPatchAndNoneDoesNot() = runBlocking {
        val c = ChannelIdentity(p, StableKey("hbo"))
        g.matches.setOverride(c, EpgChannelRef(src, "hbo.east"))
        assertEquals(1, g.requester.patches)
        g.matches.setOverride(c, null)
        assertEquals(1, g.requester.patches)
        assertEquals(null, g.matches.override(c)!!.sourceId)
        g.matches.clearOverride(c)
        assertEquals(null, g.matches.override(c))
    }

    @Test
    fun incrementalMatchesOnlyChangedSubjects() = runBlocking {
        g.store.catalogue[src] = catalogue
        g.store.publish(mapOf(src to setOf("bbcone.uk")))
        // First run: every subject is new.
        assertTrue("cnn.int is matched but not imported → patch", g.matches.matchIncremental(p, g.store))
        val stamp = db.scalar("SELECT matched_at FROM epg_match WHERE stable_key = 'bbc'")
        g.clock.advance(1000)
        g.matches.matchIncremental(p, g.store)
        assertEquals("unchanged subjects are not rematched", stamp, db.scalar("SELECT matched_at FROM epg_match WHERE stable_key = 'bbc'"))
        // A changed name changes inputs_hash.
        db.newChannel(p, "new", "Arte", tvgId = null, sortIndex = 9)
        g.matches.matchIncremental(p, g.store)
        assertEquals(listOf("Arte.fr@HD"), db.strings("SELECT xmltv_id FROM epg_match WHERE stable_key = 'new'"))
    }

    @Test
    fun suggestPickerUsesTheCurrentGeneration() = runBlocking {
        g.store.catalogue[src] = catalogue
        g.store.publish()
        val list = g.matches.suggest(ChannelIdentity(p, StableKey("hbo")), g.store, limit = 2)
        assertEquals(setOf("hbo.east", "hbo.west"), list.map { it.ref.xmltvId }.toSet())
    }
}
