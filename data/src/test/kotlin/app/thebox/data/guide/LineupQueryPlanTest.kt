package app.thebox.data.guide

import app.thebox.core.guide.GuideSource
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hand-off numbers (docs/design/m3-guide.md §1.3): `EXPLAIN QUERY PLAN` and timings of the lineup and block queries on 20k channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LineupQueryPlanTest {
    @Test
    fun plansAndTimingsOn20kChannels() = runBlocking<Unit> {
        val db = TestDb.create()
        try {
            val gg = GuideGraph(TestGraph(db))
            val p = db.newPlaylist()
            val groups = List(50) { db.newGroup(p, "g:$it", "Group $it") }
            db.writeTransaction {
                usePrepared(
                    "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, group_id, url, is_adult, " +
                        "content_hash, missing_count, first_seen_at, updated_at) VALUES (?, ?, 'URL', 0, ?, '', ?, ?, 'http://h', 0, 0, 0, 1, 1)",
                ) { st ->
                    for (i in 0 until 20_000) {
                        st.bindLong(1, p); st.bindText(2, "k$i"); st.bindText(3, "Channel $i"); st.bindLong(4, i.toLong()); st.bindLong(5, groups[i % 50])
                        st.step(); st.reset(); st.clearBindings()
                    }
                }
            }
            val cases = listOf(
                Triple("All", GuideSource.All(p), listOf<Any>(0L, p)),
                Triple("Group", GuideSource.Group(p, "g:7"), listOf<Any>(0L, p, "g:7")),
                Triple("Favourites", GuideSource.Favourites(1), listOf<Any>(0L, 1L)),
                Triple("Recent", GuideSource.Recent, listOf<Any>(0L)),
            )
            for ((label, source, binds) in cases) {
                val plan = plan(db, gg.queries.lineupSql(source), binds)
                gg.queries.lineup(source, false) // warm
                val t0 = System.nanoTime()
                val n = gg.queries.lineup(source, false).ids.size
                println("Lineup $label: $n rows in ${"%.1f".format((System.nanoTime() - t0) / 1e6)} ms; plan: $plan")
                if (source is GuideSource.All) assertEquals(20_000, n)
                if (source is GuideSource.Group) assertEquals(400, n)
            }
            val ids = gg.queries.lineup(GuideSource.All(p), false).ids
            val blockPlan = plan(db, gg.queries.blockSql(32), ids.take(32).toList())
            gg.queries.channels(ids, 0, 32)
            val t0 = System.nanoTime()
            repeat(10) { gg.queries.channels(ids, 1_000 + it * 32, 1_032 + it * 32) }
            println("Block of 32: ${"%.2f".format((System.nanoTime() - t0) / 1e7)} ms avg; plan: $blockPlan")
            assertTrue(blockPlan.contains("PRIMARY KEY") || blockPlan.contains("INTEGER PRIMARY KEY") || blockPlan.contains("rowid"))
        } finally {
            db.close()
        }
    }

    /**
     * `EXPLAIN` cannot run through `AndroidSQLiteDriver` (it routes non-SELECT text to execSQL), so the plan is read with
     * the framework's `rawQuery` on the same file, with the (test) values inlined.
     */
    private suspend fun plan(db: app.thebox.data.db.LibraryDatabase, sql: String, binds: List<Any>): String {
        val path = db.read { usePrepared("PRAGMA database_list") { st -> st.step(); st.getText(2) } }
        var text = sql
        for (i in binds.indices.reversed()) text = text.replace("?${i + 1}", binds[i].let { if (it is String) "'$it'" else it.toString() })
        var k = 0
        text = Regex("\\?(?!\\d)").replace(text) { binds[k++].toString() }
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            raw.rawQuery("EXPLAIN QUERY PLAN $text", null).use { c ->
                return buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString(" | ")
            }
        } finally {
            raw.close()
        }
    }
}
