package app.thebox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import java.io.File

/**
 * library.db v5 → v6 (docs/design/m6-event-channels.md §4.5): exactly five `playlist` columns with their defaults,
 * `refresh_interval_auto` set only where the interval is the default 24 h (Manual and every other chosen interval stay
 * the user's, §10), no other table touched, and the migrated file opens through the production builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration5To6Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File

    @Before fun setUp() {
        file = context.getDatabasePath("m56-${System.nanoTime()}.db")
        file.parentFile?.mkdirs()
        file.delete()
    }

    @After fun tearDown() { context.deleteDatabase(file.name) }

    @Test
    fun addsTheFivePlaylistColumnsAndKeepsChosenIntervals() {
        val helper = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), LibraryDatabase::class)
        var v5TableSql: Map<String, String> = emptyMap()
        var v5PlaylistColumns: List<String> = emptyList()
        helper.createDatabase(5).closing { c ->
            // 24 h (the default), Manual (0) and a chosen 12 h.
            for ((id, h) in listOf(1 to 24, 2 to 0, 3 to 12)) {
                c.execSQL(
                    "INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, created_at, uid, refresh_interval_h, refresh_seq, server_zone) " +
                        "VALUES ($id, 'P$id', 'XTREAM', 'r$id', 'h', $id, 1, 'u$id', $h, 7, 'Europe/London')",
                )
            }
            c.execSQL("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, custom_name, watch_count, updated_at) VALUES (1, 'xc:9', 0, 'Mine', 2, 4)")
            v5TableSql = c.tableSql()
            v5PlaylistColumns = c.columns("playlist")
        }
        helper.runMigrationsAndValidate(6, Migrations.ALL.toList()).closing { c ->
            val v6TableSql = c.tableSql()
            for (t in v5TableSql.keys - "playlist") assertEquals(t, v5TableSql[t], v6TableSql[t])
            assertEquals(
                listOf("hide_empty_events", "refresh_interval_auto", "event_zone", "event_zone_n", "event_count"),
                c.columns("playlist") - v5PlaylistColumns.toSet(),
            )
            c.prepare("SELECT id, hide_empty_events, refresh_interval_auto, event_zone, event_zone_n, event_count FROM playlist ORDER BY id").use { st ->
                val auto = buildMap {
                    while (st.step()) {
                        assertEquals(0L, st.getLong(1))
                        assertTrue(st.isNull(3))
                        assertEquals(0L, st.getLong(4))
                        assertEquals(0L, st.getLong(5))
                        put(st.getLong(0), st.getLong(2))
                    }
                }
                assertEquals("auto only for the default 24 h", mapOf(1L to 1L, 2L to 0L, 3L to 0L), auto)
            }
            c.prepare("SELECT custom_name FROM channel_prefs WHERE stable_key = 'xc:9'").use { st -> assertTrue(st.step()); assertEquals("Mine", st.getText(0)) }
        }
        val db = LibraryDatabase.build(context, Dispatchers.IO, AndroidSQLiteDriver(), file.name)
        try {
            runBlocking {
                val dao = db.playlistDao()
                val p1 = dao.get(1)!!
                assertTrue(p1.refreshIntervalAuto)
                assertFalse(p1.hideEmptyEvents)
                assertNull(p1.eventZone)
                assertFalse(dao.get(3)!!.refreshIntervalAuto)
                // The new setters, one column each; the settings row's interval write clears auto.
                dao.setHideEmptyEvents(1, true)
                dao.setEventZone(1, "America/New_York", 826)
                dao.setEventCount(1, 1_543)
                dao.setRefreshInterval(1, 24)
                val keys = dao.eventKeys().single { it.id == 1L }
                assertTrue(keys.hideEmptyEvents)
                assertFalse(keys.refreshIntervalAuto)
                assertEquals("America/New_York", keys.eventZone)
                assertEquals(826, keys.eventZoneN)
                assertEquals(1_543, keys.eventCount)
                assertEquals(7L, keys.refreshSeq)
                assertEquals("Europe/London", keys.serverZone)
                dao.setRefreshIntervalAuto(1, true)
                assertTrue(dao.get(1)!!.refreshIntervalAuto)
                dao.setEnabled(2, false)
                assertEquals(listOf(1L, 3L), dao.observeEventKeys().first().map { it.id })
            }
        } finally {
            db.close()
        }
    }

    private fun SQLiteConnection.tableSql(): Map<String, String> =
        prepare("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND sql IS NOT NULL").use { st ->
            buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
        }

    private fun SQLiteConnection.columns(table: String): List<String> =
        prepare("SELECT name FROM pragma_table_info('$table')").use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    private inline fun <R> SQLiteConnection.closing(block: (SQLiteConnection) -> R): R = try {
        block(this)
    } finally {
        close()
    }
}
