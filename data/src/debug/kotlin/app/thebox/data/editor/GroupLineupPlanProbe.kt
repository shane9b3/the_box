package app.thebox.data.editor

import android.content.Context
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.GuideQueries
import app.thebox.data.repo.writeTransaction
import kotlinx.coroutines.Dispatchers

/**
 * (docs/design/m6-channel-editor.md §3.6 "Plan assertions") `EXPLAIN QUERY PLAN` of the group lineup on the production
 * (bundled) driver, for `app/src/androidTest/.../editor/GroupLineupPlanTest`: a scratch `library.db` with 20,000
 * channels in 600 groups and some moves, then the plan lines of half 1, half 2 and the whole statement. Strings only, so
 * the `:app` test needs no Room or SQLite types. Debug source set only: it never ships in a release APK.
 */
object GroupLineupPlanProbe {
    class Plans(val half1: List<String>, val half2: List<String>, val whole: List<String>)

    suspend fun run(context: Context): Plans {
        val name = "plan-probe-${System.nanoTime()}.db"
        context.getDatabasePath(name).delete()
        val db = LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), name)
        try {
            // No ANALYZE: production never runs it, so the plan checked is the one users get (data review 2026-10-18).
            val pid = seed(db)
            val members = GuideQueries.groupMembersSql("c.id AS id, ${GuideQueries.GROUP_ORDER_COLUMNS}", "${GuideQueries.LINEUP_BASE} ${GuideQueries.PARENTAL_RAW}")
            val (h1, h2) = members.split("UNION ALL\n").let { it[0] to it[1] }
            return Plans(explain(db, h1, pid), explain(db, h2, pid), explain(db, GuideQueries.GROUP_LINEUP_SQL, pid))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** 20,000 channels in 600 groups, 100 of them moved into `g:7`; returns the playlist id. */
    suspend fun seed(db: LibraryDatabase): Long = db.writeTransaction {
        usePrepared("INSERT INTO playlist(name, type, credential_ref, display_source, sort_index, created_at, uid) VALUES ('P', 'M3U_URL', 'r', 'h', 0, 1, 'probe')") { it.step() }
        val pid = usePrepared("SELECT last_insert_rowid()") { st -> st.step(); st.getLong(0) }
        usePrepared("INSERT INTO channel_group(playlist_id, kind, group_key, name, sort_index, is_adult) VALUES (?, 0, ?, ?, ?, 0)") { st ->
            for (i in 0 until 600) {
                st.bindLong(1, pid); st.bindText(2, "g:$i"); st.bindText(3, "Group $i"); st.bindLong(4, i.toLong())
                st.step(); st.reset(); st.clearBindings()
            }
        }
        usePrepared(
            "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, group_id, url, is_adult, content_hash, missing_count, first_seen_at, updated_at) " +
                "SELECT ?1, 'k' || x.n, 'URL', 0, 'Channel ' || x.n, '', x.n, (SELECT id FROM channel_group WHERE playlist_id = ?1 AND group_key = 'g:' || (x.n % 600)), 'http://h', 0, 0, 0, 1, 1 " +
                "FROM (WITH RECURSIVE r(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM r WHERE n < 19999) SELECT n FROM r) x",
        ) { st -> st.bindLong(1, pid); st.step() }
        usePrepared(
            "INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_group_key) " +
                "SELECT ?1, 'k' || (n * 7), 0, 0, 1, 'g:7' FROM (WITH RECURSIVE r(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM r WHERE n < 99) SELECT n FROM r)",
        ) { st -> st.bindLong(1, pid); st.step() }
        pid
    }

    private suspend fun explain(db: LibraryDatabase, sql: String, pid: Long): List<String> = db.useReaderConnection { c ->
        c.usePrepared("EXPLAIN QUERY PLAN $sql") { st ->
            st.bindLong(1, 1L); st.bindLong(2, pid); st.bindText(3, "g:7")
            buildList { while (st.step()) add(st.getText(3)) }
        }
    }
}
