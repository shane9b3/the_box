package app.thebox.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Day-0 check 1 (docs/design/m1-data-layer.md §6.2): the JVM variant of sqlite-bundled loads on this host
 * (dev Mac x86_64, CI linux_x64) and is the FTS5 build that ships on the device. Regression guard.
 */
class BundledSqliteDay0Test {
    private fun SQLiteConnection.strings(sql: String): List<String> = prepare(sql).use { st ->
        buildList { while (st.step()) add(st.getText(0)) }
    }

    @Test
    fun bundledDriverOpensInMemoryWithFts5() {
        val conn = BundledSQLiteDriver().open(":memory:")
        try {
            val options = conn.strings("PRAGMA compile_options")
            println("sqlite_version=" + conn.strings("SELECT sqlite_version()").single() + " compile_options=" + options)
            assertContains(options, "ENABLE_FTS5")
            assertContains(options, "ENABLE_FTS4")
            assertContains(options, "TEMP_STORE=3")

            // The epg.db shapes the design relies on (§2.3): WITHOUT ROWID programme table, FTS5 external content.
            conn.execSQL("CREATE TABLE programme(epg_channel_id INTEGER NOT NULL, start_min INTEGER NOT NULL, text_id INTEGER NOT NULL, PRIMARY KEY(epg_channel_id, start_min)) WITHOUT ROWID")
            conn.execSQL("CREATE TABLE epg_text(id INTEGER PRIMARY KEY, title TEXT NOT NULL, subtitle TEXT)")
            conn.execSQL("CREATE VIRTUAL TABLE epg_fts USING fts5(title, subtitle, content='epg_text', content_rowid='id', tokenize='unicode61 remove_diacritics 2')")
            conn.execSQL("INSERT INTO epg_text(id, title) VALUES (1, 'Télé Matin'), (2, 'News at Ten')")
            conn.execSQL("INSERT INTO epg_fts(epg_fts) VALUES ('rebuild')")
            assertEquals(listOf("Télé Matin"), conn.strings("SELECT title FROM epg_fts WHERE epg_fts MATCH '\"tele\"*' ORDER BY bm25(epg_fts)"))
        } finally {
            conn.close()
        }
    }
}
