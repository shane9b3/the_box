package app.thebox.data.repo

import app.thebox.core.common.Hash64
import app.thebox.core.refresh.EntryProducer
import app.thebox.core.refresh.FinishStats
import app.thebox.core.refresh.GroupRow
import app.thebox.core.refresh.KeyRemap
import app.thebox.core.refresh.MergeBatch
import app.thebox.core.refresh.MergeContext
import app.thebox.core.refresh.MergeFinish
import app.thebox.core.refresh.MergeSink
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.EpgMappingEntity
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.db.entity.ReminderEntity
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.pc
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RoomMergeSink + RoomExistingChannels driven by a §3.3-conformant FakeDiffer (WP-A's real differ: M3uToRoomIT). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMergeSinkTest {
    private lateinit var db: LibraryDatabase
    private var p = 0L
    private var seq = 0L
    private val day = 24L * 3600 * 1000
    private var now = 1_760_000_000_000L

    @Before fun setUp() = runBlocking { db = TestDb.create(); p = db.newPlaylist(); Unit }
    @After fun tearDown() { db.close() }

    private fun run(
        entries: List<ParsedChannel>,
        differ: FakeDiffer = FakeDiffer(),
        etag: String? = null,
        wrap: (RoomMergeSink) -> MergeSink = { it },
    ): Pair<RefreshReport, RoomMergeSink> {
        seq++
        val sink = RoomMergeSink(db, p, now, seq, etag = etag)
        val ctx = MergeContext(p, PlaylistType.M3U_URL, seq, now, previousLiveCount = 0)
        val report = differ.merge(EntryProducer { c -> entries.forEach(c) }, RoomExistingChannels(db, p), wrap(sink), ctx)
        return report to sink
    }

    private fun list(vararg names: String) = names.mapIndexed { i, n -> pc(n, position = i) }

    @Test
    fun existingChannelsCarryKindAndGroupKey() {
        // (amended 2026-10-13) ExistingChannel.kind/groupKey feed S18, KeyRemap.sameGroup and the group counts.
        run(
            listOf(
                pc("BBC", position = 0, group = "News"),
                pc("Radio 1", position = 1, group = "Music", kind = EntryKind.RADIO),
                pc("Film", url = "http://h/v/1.mkv", position = 2, group = "News", kind = EntryKind.MOVIE),
            ),
        )
        val rows = ArrayList<Triple<String, EntryKind, String?>>()
        RoomExistingChannels(db, p).forEach { rows += Triple(it.stableKey, it.kind, it.groupKey) }
        assertEquals(
            setOf(EntryKind.LIVE to "g:news", EntryKind.RADIO to "g:music", EntryKind.MOVIE to "g:news"),
            rows.map { it.second to it.third }.toSet(),
        )
    }

    @Test
    fun batchesOf2000AndFtsRows() = runBlocking {
        val differ = FakeDiffer()
        val entries = (0 until 5_001).map { pc("Ch $it", position = it, group = "G${it % 3}") }
        val (report, sink) = run(entries, differ)
        assertEquals(listOf(2_000, 2_000, 1_001), differ.lastBatchSizes)
        assertEquals(3, sink.batches)
        assertEquals(5_001, report.added)
        assertEquals(5_001L, db.scalar("SELECT COUNT(*) FROM channel"))
        assertEquals(5_001L, db.scalar("SELECT COUNT(*) FROM channel_fts"))
        assertEquals(3L, db.scalar("SELECT COUNT(*) FROM channel_group"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE group_id IS NULL"))
        assertEquals(5_001L, db.scalar("SELECT live_count FROM playlist WHERE id = ?", p))
    }

    @Test
    fun missingOnlyInTheFinalTransactionAndEtagOnlyAtFinish() = runBlocking {
        run(list("a", "b", "c"))
        now += day
        val seen = ArrayList<Long>()
        run(list("a", "c"), etag = "\"v2\"") { real ->
            object : MergeSink by real {
                override fun apply(batch: MergeBatch) {
                    real.apply(batch)
                    runBlocking {
                        seen += db.scalar("SELECT COUNT(*) FROM channel WHERE missing_since IS NOT NULL")
                        seen += if (db.strings("SELECT etag FROM playlist").single() == "null") 0 else 1
                    }
                }
            }
        }
        assertTrue("nothing missing and no etag before finish: $seen", seen.isNotEmpty() && seen.all { it == 0L })
        assertEquals(listOf("b"), db.strings("SELECT name FROM channel WHERE missing_since IS NOT NULL"))
        assertEquals(listOf("\"v2\""), db.strings("SELECT etag FROM playlist"))
        assertEquals(2L, db.scalar("SELECT live_count FROM playlist"))
        assertEquals(2L, db.scalar("SELECT refresh_seq FROM playlist"))
    }

    @Test
    fun graceThenPurgeKeepsUserDataAndTombstoneThenRestore() = runBlocking {
        run(list("a", "b", "c"))
        val bKey = db.strings("SELECT stable_key FROM channel WHERE name = 'b'").single()
        val listId = db.favouriteDao().insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(listId, p, bKey, 0, 0))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, bKey, customName = "Mine", updatedAt = 0))

        now += day
        val (r2, _) = run(list("a", "c"))
        assertEquals(1, r2.newlyMissing)
        assertEquals(0, r2.favouritesKept)
        assertEquals(1, r2.favouritesOrphaned)
        assertEquals(listOf("b"), r2.removedSample)

        now += 3 * day // 4 days missing: still in grace
        run(list("a", "c"))
        assertEquals(2L, db.scalar("SELECT missing_count FROM channel WHERE stable_key = ?", bKey))

        now += 4 * day // 8 days: purge
        val (r4, _) = run(list("a", "c"))
        assertEquals(1, r4.purged)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE stable_key = ?", bKey))
        assertEquals(2L, db.scalar("SELECT COUNT(*) FROM channel_fts"))
        assertEquals(now, db.scalar("SELECT purged_at FROM purged_key WHERE stable_key = ?", bKey))
        assertEquals("user data survives the purge", 1L, db.scalar("SELECT COUNT(*) FROM favourite"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM channel_prefs"))

        now += day
        val (r5, _) = run(list("a", "b", "c"))
        assertEquals(1, r5.added)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM purged_key"))
        assertEquals(1, r5.favouritesKept)
        assertEquals(listOf(bKey), db.favouriteDao().channels(listId, false).first().map { it.channel.stableKey })
        assertEquals(listOf("Mine"), db.favouriteDao().channels(listId, false).first().map { it.customName })
    }

    @Test
    fun restoreBeforePurgeIsATouch() = runBlocking {
        run(list("a", "b"))
        now += day
        run(list("a"))
        now += day
        val (r, sink) = run(list("a", "b"))
        assertEquals(1, r.restored)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE missing_since IS NOT NULL OR missing_count > 0"))
        assertEquals(0, sink.ftsWrites)
    }

    @Test
    fun reorderOnlyWritesNoFtsRows() = runBlocking {
        // S6
        run(list("a", "b", "c"))
        val (r, sink) = run(list("c", "b", "a"))
        assertEquals(0, r.added + r.updated)
        assertEquals(0, sink.ftsWrites)
        assertEquals(listOf("c", "b", "a"), db.strings("SELECT name FROM channel ORDER BY sort_index"))
    }

    @Test
    fun renameRewritesTheFtsRow() = runBlocking {
        run(listOf(pc("Alpha", url = "http://h/live/1.ts")))
        val (r, sink) = run(listOf(pc("Omega", url = "http://h/live/1.ts")))
        assertEquals(1, r.updated)
        assertEquals(1, sink.ftsWrites)
        assertEquals(listOf("Omega"), db.strings("SELECT name FROM channel_fts WHERE channel_fts MATCH 'omega'"))
        assertEquals(emptyList<String>(), db.strings("SELECT name FROM channel_fts WHERE channel_fts MATCH 'alpha'"))
    }

    @Test
    fun autoRemapsMoveUserDataInTheFinishTransaction() = runBlocking {
        // S7: the provider added a tvg-id, so the key moved from url: to tvg:.
        run(listOf(pc("BBC", url = "http://h/live/bbc.ts")))
        val old = db.strings("SELECT stable_key FROM channel").single()
        val listId = db.favouriteDao().insertList(FavouriteListEntity(name = "F", sortIndex = 0, createdAt = 0))
        db.favouriteDao().insert(FavouriteEntity(listId, p, old, 0, 0))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, old, epgShiftMin = 60, updatedAt = 0))
        val s = db.epgSourceDao().insert(EpgSourceEntity(name = "u", origin = "USER", credentialRef = "r", urlHash = 1, displaySource = "h", priority = 0, createdAt = 1))
        db.epgMappingDao().upsert(EpgMappingEntity(p, old, s, "bbc.uk", 0))
        db.reminderDao().insert(ReminderEntity(playlistId = p, stableKey = old, startMin = 5, endMin = 6, titleHash = 1, title = "t", channelName = "BBC", createdAt = 0))

        val differ = FakeDiffer(autoRemaps = listOf(KeyRemap(old, "tvg:bbc.uk", sameGroup = true)))
        val (r, _) = run(listOf(pc("BBC", url = "http://h/live/bbc.ts", tvgId = "bbc.uk")), differ)
        assertEquals(1, r.keysMigrated)
        for (t in listOf("favourite", "channel_prefs", "epg_mapping", "reminder")) {
            assertEquals(t, listOf("tvg:bbc.uk"), db.strings("SELECT stable_key FROM $t"))
        }
        assertEquals(1, r.favouritesKept)
    }

    @Test
    fun remapWhereTheNewKeyAlreadyHasDataKeepsTheCurrentRow() = runBlocking {
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "old", customName = "Old", updatedAt = 0))
        db.channelPrefsDao().upsert(ChannelPrefsEntity(p, "new", customName = "Current", updatedAt = 0))
        db.writeTransaction { UserDataRemapper.apply(this, p, listOf(KeyRemap("old", "new", true))) }
        assertEquals(listOf("Current"), db.strings("SELECT custom_name FROM channel_prefs"))
    }

    @Test
    fun unseenGroupsGetMissingSinceAndAreDeletedAfterGraceWhenEmpty() = runBlocking {
        run(listOf(pc("a", group = "News", position = 0), pc("b", group = "Sport", position = 1)))
        now += day
        run(listOf(pc("a", group = "News")))
        assertEquals(listOf("g:sport"), db.strings("SELECT group_key FROM channel_group WHERE missing_since IS NOT NULL"))
        now += 8 * day
        run(listOf(pc("a", group = "News"))) // b purged → Sport empty and past grace → deleted
        assertEquals(listOf("g:news"), db.strings("SELECT group_key FROM channel_group"))
    }

    @Test
    fun detectedCredentialsReachTheCallbackAndUrlsAreTemplated() = runBlocking {
        val d = DetectedCredentials("http://panel:8080", "alice", "s3cret")
        var stored: DetectedCredentials? = null
        seq++
        val sink = RoomMergeSink(db, p, now, seq, onDetected = { stored = it })
        FakeDiffer(detected = d).merge(
            EntryProducer { c -> c(pc("One", url = "http://panel:8080/live/alice/s3cret/1.ts")) },
            RoomExistingChannels(db, p), sink, MergeContext(p, PlaylistType.M3U_URL, seq, now, 0),
        )
        assertEquals(d, stored)
        assertEquals(d, sink.detected)
        assertEquals(listOf("http://panel:8080/live/{{u}}/{{p}}/1.ts"), db.strings("SELECT url FROM channel"))
    }

    @Test
    fun existingChannelsStreamComputedHashes() = runBlocking {
        run(listOf(pc("Name", url = "http://h/x.ts")))
        val rows = ArrayList<app.thebox.core.refresh.ExistingChannel>()
        RoomExistingChannels(db, p).forEach { rows += it }
        assertEquals(1, rows.size)
        assertEquals(Hash64.of("http://h/x.ts"), rows[0].urlHash)
        assertEquals(Hash64.of("Name"), rows[0].nameHash)
        assertNull(rows[0].missingSince)
    }

    @Test
    fun abortLeavesAppliedBatchesAndMarksNothingMissing() = runBlocking {
        run(list("a", "b"))
        var aborted: Throwable? = null
        var pass = 0
        seq++
        val sink = RoomMergeSink(db, p, now, seq)
        try {
            FakeDiffer().merge(
                EntryProducer { c ->
                    pass++
                    c(pc("z", position = 0))
                    if (pass == 2) error("boom")
                },
                RoomExistingChannels(db, p),
                object : MergeSink by sink {
                    override fun abort(error: Throwable) { aborted = error; sink.abort(error) }
                    override fun finish(finish: MergeFinish): FinishStats = throw AssertionError("no finish")
                    override fun begin(groups: List<GroupRow>, detected: DetectedCredentials?) = sink.begin(groups, detected)
                },
                MergeContext(p, PlaylistType.M3U_URL, seq, now, 0, batchSize = 1),
            )
            fail("must throw")
        } catch (e: IllegalStateException) { /* expected */ }
        assertTrue(aborted is IllegalStateException)
        assertEquals("the applied insert stays", 3L, db.scalar("SELECT COUNT(*) FROM channel"))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE missing_since IS NOT NULL"))
    }
}
