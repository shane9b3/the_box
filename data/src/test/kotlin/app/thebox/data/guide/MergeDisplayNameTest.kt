package app.thebox.data.guide

import app.thebox.core.common.Outcome
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.refresh.PlaylistDifferImpl
import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.NamePrefixRule
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.repo.Trigger
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m3-guide.md §5.2 `MergeDisplayNameTest` (§3.12.3, review S4): the real parser, differ and RoomMergeSink.
 * A prefix-rule change is not a channel change: no merge event, no key or FTS churn, only the recompute UPDATE pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeDisplayNameTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph
    private val url = "http://lb1.example:8080/list.m3u"

    @Before fun setUp() { db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g) }
    @After fun tearDown() { db.close() }

    private fun m3u(vararg entries: Triple<String, String, String>) = buildString {
        append("#EXTM3U\n")
        for ((name, group, id) in entries) append("#EXTINF:-1 tvg-id=\"$id\" group-title=\"$group\",$name\nhttp://h.example/live/$id.ts\n")
    }

    private val v1 = m3u(
        Triple("UK: BBC One HD", "UK Entertainment", "bbc1"),
        Triple("Plain Channel", "UK Entertainment", "plain"),
        Triple("Sky Sports: F1", "Sky Sports", "skyf1"),
        Triple("SKY SPORTS: F2", "Sky Sports", "skyf2"),
        Triple("TV: Live Events", "Events", "tv"),
        Triple("[US] CNN", "News", "cnn"),
    )

    private suspend fun setUpPlaylist(): Long {
        val id = (g.playlists.addM3uUrl("p", url) as Outcome.Ok).value
        g.fetcher.bodies[url] = v1
        assertNull(refresh().error)
        return id
    }

    private fun refresh() = runBlocking { g.refresh(differ = PlaylistDifferImpl(StableKeyDeriverImpl()), parser = M3uParserImpl()).refresh(db.playlistDao().all().single().id, Trigger.MANUAL) }

    private suspend fun names(): Map<String, String> =
        db.strings("SELECT name || '=' || COALESCE(display_name, 'NULL') FROM channel WHERE missing_since IS NULL ORDER BY sort_index").associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun mergeBindsDisplayNameAndLeavesTheVersionToTheBackfill() = runBlocking {
        val id = setUpPlaylist()
        assertEquals(
            mapOf(
                "UK: BBC One HD" to "BBC One HD", "Plain Channel" to "NULL", "Sky Sports: F1" to "NULL",
                "SKY SPORTS: F2" to "F2", "TV: Live Events" to "NULL", "[US] CNN" to "CNN",
            ),
            names(),
        )
        assertEquals("FTS keeps the raw name", 1L, db.scalar("SELECT COUNT(*) FROM channel_fts WHERE channel_fts MATCH 'uk'"))
        // S4: the merge never raises name_prefix_ver; the backfill still runs for this playlist.
        assertEquals(0L, db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", id))
        val passes = gg.displayNames.backfill()
        assertEquals(listOf(id), passes.map { it.playlistId })
        assertEquals("the merge already used Auto: nothing to rewrite", 0, passes.single().changed)
        assertEquals(ChannelNamePrefix.RULE_VERSION.toLong(), db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", id))
        // A refresh after the backfill keeps the stamp (already current) and stays consistent.
        assertNull(refresh().error)
        assertEquals(ChannelNamePrefix.RULE_VERSION.toLong(), db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", id))
        assertTrue(gg.displayNames.backfill().isEmpty())
    }

    @Test
    fun upgradeOrderMergeBeforeBackfillStillBackfills() = runBlocking {
        // A v2 → v3 upgrade: rows exist with display_name NULL and ver 0; a start-up refresh wins ImportMutex first.
        val id = setUpPlaylist()
        db.exec("UPDATE channel SET display_name = NULL")
        db.exec("UPDATE playlist SET name_prefix_ver = 0")
        assertNull(refresh().error) // identical content: every row is a touch, nothing recomputed
        assertEquals("NULL", names()["UK: BBC One HD"])
        assertEquals(0L, db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", id))
        val pass = gg.displayNames.backfill().single()
        assertEquals(3, pass.changed)
        assertEquals("BBC One HD", names()["UK: BBC One HD"])
    }

    @Test
    fun groupRenameChangesTheDisplayName() = runBlocking {
        setUpPlaylist()
        assertEquals("F2", names()["SKY SPORTS: F2"])
        g.fetcher.bodies[url] = v1.replace("group-title=\"Sky Sports\",SKY SPORTS: F2", "group-title=\"Football\",SKY SPORTS: F2")
        assertNull(refresh().error)
        assertEquals("NULL", names()["SKY SPORTS: F2"])
    }

    @Test
    fun ruleChangeIsNotAChannelChange() = runBlocking {
        val id = setUpPlaylist()
        gg.displayNames.backfill()
        db.exec("CREATE TABLE upd_log(id INTEGER, dn_changed INTEGER, other_changed INTEGER)")
        db.exec("CREATE TABLE ins_log(id INTEGER)")
        db.exec(
            """CREATE TRIGGER t_upd AFTER UPDATE ON channel BEGIN INSERT INTO upd_log VALUES (new.id,
               old.display_name IS NOT new.display_name,
               old.name IS NOT new.name OR old.stable_key IS NOT new.stable_key OR old.content_hash IS NOT new.content_hash
               OR old.updated_at IS NOT new.updated_at OR old.url IS NOT new.url OR old.group_id IS NOT new.group_id); END""",
        )
        db.exec("CREATE TRIGGER t_ins AFTER INSERT ON channel BEGIN INSERT INTO ins_log VALUES (new.id); END")
        db.exec("CREATE TRIGGER t_del AFTER DELETE ON channel BEGIN INSERT INTO ins_log VALUES (-old.id); END")
        val keysBefore = db.strings("SELECT id || ':' || stable_key FROM channel ORDER BY id")
        val ftsBefore = db.strings("SELECT rowid || ':' || name FROM channel_fts ORDER BY rowid")

        // The rule change: one targeted UPDATE per changed row, nothing else.
        val pass = gg.displayNames.setRule(id, NamePrefixRule.Custom(literals = listOf("Plain ")))!!
        assertEquals(4, pass.changed) // BBC, F2, CNN lose their stripped names; Plain gains one
        assertEquals(4L, db.scalar("SELECT COUNT(*) FROM upd_log"))
        assertEquals(4L, db.scalar("SELECT COUNT(*) FROM upd_log WHERE dn_changed = 1 AND other_changed = 0"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM ins_log"))
        assertEquals("Channel", names()["Plain Channel"])
        assertEquals("NULL", names()["UK: BBC One HD"])

        // The next refresh of the same playlist sees no change at all: zero merge events.
        db.exec("DELETE FROM upd_log")
        val r = refresh()
        assertNull(r.error)
        val report = r.report!!
        assertEquals(0, report.added)
        assertEquals(0, report.updated)
        assertEquals(0, report.renamed)
        assertEquals(0, report.urlChanged)
        assertEquals(0, report.newlyMissing)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM upd_log WHERE dn_changed = 1 OR other_changed = 1"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM ins_log"))
        assertEquals(keysBefore, db.strings("SELECT id || ':' || stable_key FROM channel ORDER BY id"))
        assertEquals(ftsBefore, db.strings("SELECT rowid || ':' || name FROM channel_fts ORDER BY rowid"))
        assertEquals("Channel", names()["Plain Channel"])
    }
}
