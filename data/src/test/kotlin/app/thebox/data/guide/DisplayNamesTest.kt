package app.thebox.data.guide

import app.thebox.core.text.ChannelNamePrefix
import app.thebox.core.text.NamePrefixRule
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import app.thebox.data.testutil.strings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/** docs/design/m3-guide.md §5.2 `DisplayNamesTest` (§3.12.3): changed rows only, under ImportMutex, backfill, timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayNamesTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private lateinit var gg: GuideGraph

    @Before fun setUp() { db = TestDb.create(); g = TestGraph(db); gg = GuideGraph(g) }
    @After fun tearDown() { db.close() }

    @Test
    fun recomputeWritesOnlyChangedRows() = runBlocking {
        val p = db.newPlaylist()
        val sports = db.newGroup(p, "g:sports", "SPORTS")
        db.newChannel(p, "a", "UK: One", sortIndex = 0)
        db.newChannel(p, "b", "Plain", sortIndex = 1)
        db.newChannel(p, "c", "SPORTS: ESPN", groupId = sports, sortIndex = 2)
        db.newChannel(p, "d", "USA|CNN", sortIndex = 3)
        db.exec("UPDATE channel SET display_name = 'One' WHERE stable_key = 'a'") // already right
        val pass = gg.displayNames.recompute(p)!!
        assertEquals(4, pass.scanned)
        assertEquals(2, pass.changed) // c (group tag) and d
        assertEquals(listOf("One", "null", "ESPN", "CNN"), db.strings("SELECT display_name FROM channel ORDER BY sort_index"))
        assertEquals(ChannelNamePrefix.RULE_VERSION.toLong(), db.scalar("SELECT name_prefix_ver FROM playlist WHERE id = ?", p))
        // Off clears every stored name; previewCount reads without writing.
        assertEquals(3, gg.displayNames.previewCount(p, NamePrefixRule.Auto))
        assertEquals(1, gg.displayNames.previewCount(p, NamePrefixRule.Custom(regex = "UK: ")))
        assertEquals(3, gg.displayNames.setRule(p, NamePrefixRule.Off)!!.changed)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM channel WHERE display_name IS NOT NULL"))
        assertEquals("OFF", db.strings("SELECT name_prefix_mode FROM playlist").single())
    }

    @Test
    fun recomputeWaitsForImportMutex() = runBlocking {
        val p = db.newPlaylist()
        db.newChannel(p, "a", "UK: One")
        var mergeDone = false
        val holder = async {
            g.importMutex.withLock {
                delay(300)
                mergeDone = true
            }
        }
        delay(50)
        val pass = async { gg.displayNames.recompute(p); mergeDone }
        assertTrue("recompute ran only after the merge released ImportMutex", pass.await())
        holder.await()
    }

    @Test
    fun backfillRunsForPlaylistsBelowTheRuleVersion() = runBlocking {
        val p1 = db.newPlaylist("one")
        val p2 = db.newPlaylist("two")
        db.newChannel(p1, "a", "UK: One")
        db.newChannel(p2, "b", "DE: Zwei")
        db.exec("UPDATE playlist SET name_prefix_ver = ? WHERE id = ?", ChannelNamePrefix.RULE_VERSION, p2)
        val passes = gg.displayNames.backfill()
        assertEquals(listOf(p1), passes.map { it.playlistId })
        assertEquals(listOf("One", "null"), db.strings("SELECT display_name FROM channel ORDER BY playlist_id"))
        assertTrue(gg.displayNames.backfill().isEmpty())
    }

    @Test
    fun recompute20kAnd200kTiming() = runBlocking {
        for (n in listOf(20_000, 200_000)) {
            val p = db.newPlaylist("p$n")
            seed(p, n)
            val pass = gg.displayNames.recompute(p)!!
            println("DisplayNames: recompute of ${pass.scanned} channels, ${pass.changed} changed, in ${pass.ms} ms (Robolectric SQLite)")
            assertEquals(n, pass.scanned)
            assertEquals(n * 3 / 5, pass.changed)
            val again = gg.displayNames.recompute(p)!!
            println("DisplayNames: no-op recompute of ${again.scanned} channels in ${again.ms} ms")
            assertEquals(0, again.changed)
            assertFalse(pass.ms > if (n == 20_000) 10_000 else 100_000)
        }
    }

    private suspend fun seed(p: Long, n: Int) {
        db.writeTransaction {
            usePrepared(
                "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, is_adult, content_hash, " +
                    "missing_count, first_seen_at, updated_at) VALUES (?, ?, 'URL', 0, ?, '', ?, 'http://h', 0, 0, 0, 1, 1)",
            ) { st ->
                for (i in 0 until n) {
                    st.bindLong(1, p)
                    st.bindText(2, "k$i")
                    st.bindText(
                        3,
                        when (i % 5) {
                            0 -> "UK: Channel $i"
                            1 -> "[DE] Sender $i"
                            2 -> "Plain $i"
                            3 -> "Sky Sports: Match $i"
                            else -> "USA|News $i"
                        },
                    )
                    st.bindLong(4, i.toLong())
                    st.step()
                    st.reset()
                    st.clearBindings()
                }
            }
        }
    }
}
