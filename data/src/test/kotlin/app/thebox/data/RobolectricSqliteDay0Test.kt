package app.thebox.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Day-0 check 5 (docs/design/m1-data-layer.md §6.2): Robolectric's SQLite, reached through
 * `AndroidSQLiteDriver`, handles the epg.db shapes, so `EpgBuildIT` (§5.6) can run `EpgStoreImpl` on the
 * FTS4 path: `WITHOUT ROWID`, FTS4 `tokenize=unicode61 "remove_diacritics=2"`, `PRAGMA journal_mode=OFF`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobolectricSqliteDay0Test {
    private fun SQLiteConnection.strings(sql: String): List<String> = prepare(sql).use { st ->
        buildList { while (st.step()) add(st.getText(0)) }
    }

    @Test
    fun withoutRowidFts4AndJournalOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("day0-epg.db").apply { parentFile?.mkdirs(); delete() }
        val conn = AndroidSQLiteDriver().open(file.path)
        try {
            println("robolectric sqlite_version=" + conn.strings("SELECT sqlite_version()").single())
            // AndroidSQLiteDriver routes `PRAGMA journal_mode=` through a special statement that throws
            // CursorIndexOutOfBoundsException on a second step(): step it exactly once (note for WP-B).
            val mode = conn.prepare("PRAGMA journal_mode=OFF").use { st -> check(st.step()); st.getText(0) }
            assertEquals("off", mode.lowercase())

            conn.execSQL(
                "CREATE TABLE programme(epg_channel_id INTEGER NOT NULL, start_min INTEGER NOT NULL, " +
                    "end_min INTEGER NOT NULL, text_id INTEGER NOT NULL, PRIMARY KEY(epg_channel_id, start_min)) WITHOUT ROWID",
            )
            conn.execSQL("INSERT INTO programme VALUES (1, 100, 130, 1), (1, 130, 160, 2), (2, 100, 200, 1)")
            assertEquals(
                listOf("100", "130"),
                conn.strings("SELECT start_min FROM programme WHERE epg_channel_id = 1 AND start_min >= 90 AND start_min < 200 ORDER BY start_min"),
            )

            conn.execSQL("CREATE TABLE epg_text(id INTEGER PRIMARY KEY, title TEXT NOT NULL, subtitle TEXT)")
            conn.execSQL(
                "CREATE VIRTUAL TABLE epg_fts USING fts4(content=\"epg_text\", title, subtitle, " +
                    "tokenize=unicode61 \"remove_diacritics=2\")",
            )
            conn.execSQL("INSERT INTO epg_text(id, title) VALUES (1, 'Télé Matin'), (2, 'News at Ten')")
            conn.execSQL("INSERT INTO epg_fts(epg_fts) VALUES ('rebuild')")
            assertEquals(listOf("Télé Matin"), conn.strings("SELECT title FROM epg_fts WHERE epg_fts MATCH 'tele*'"))
            assertEquals(listOf("News at Ten"), conn.strings("SELECT title FROM epg_fts WHERE epg_fts MATCH 'news'"))
        } finally {
            conn.close()
            file.delete()
        }
    }
}
