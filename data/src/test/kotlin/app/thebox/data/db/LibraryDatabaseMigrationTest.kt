package app.thebox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
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
import java.io.File

/**
 * library.db v1 → v2 (docs/design/m2-playback-core.md §2.10, review S10, day-0 check 7): the exported schemas are
 * visible as assets, the auto-migration keeps user rows and adds exactly the three columns with their defaults, and the
 * migrated file opens through the production builder and reads back. The instrumented twin is
 * `data/src/androidTest/.../I1LibraryDatabaseTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File

    @Before fun setUp() {
        file = context.getDatabasePath("migration-${System.nanoTime()}.db")
        file.parentFile?.mkdirs()
        file.delete()
    }

    @After fun tearDown() {
        context.deleteDatabase(file.name)
    }

    @Test
    fun exportedSchemasAreTestAssets() {
        for (v in 1..LibraryDatabase.VERSION) {
            val json = context.assets.open("app.thebox.data.db.LibraryDatabase/$v.json").bufferedReader().use { it.readText() }
            assertTrue("$v.json", json.contains("\"version\": $v"))
        }
    }

    @Test
    fun migrate1To2KeepsRowsAndAddsColumns() {
        val helper = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), LibraryDatabase::class)
        helper.createDatabase(1).closing { c ->
            c.execSQL(
                "INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, xtream_output, created_at) " +
                    "VALUES (7, 'P', 'XTREAM', 'r', 'h', 0, 'HLS', 1)",
            )
            c.execSQL(
                "INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, audio_lang, watch_count, updated_at) " +
                    "VALUES (7, 'xc:1', 1, 1, 'deu', 3, 5)",
            )
        }
        helper.runMigrationsAndValidate(2, emptyList()).closing { c ->
            assertEquals(listOf("xtream_output_learned"), c.columns("playlist") - V1_PLAYLIST_COLUMNS)
            assertEquals(listOf("playback_flags", "fps_milli"), c.columns("channel_prefs") - V1_PREFS_COLUMNS)
            c.prepare("SELECT hidden, locked, audio_lang, watch_count, updated_at, playback_flags, fps_milli FROM channel_prefs").use { st ->
                assertTrue(st.step())
                assertEquals(1L, st.getLong(0))
                assertEquals(1L, st.getLong(1))
                assertEquals("deu", st.getText(2))
                assertEquals(3L, st.getLong(3))
                assertEquals(5L, st.getLong(4))
                assertEquals(0L, st.getLong(5))
                assertTrue(st.isNull(6))
            }
            c.prepare("SELECT xtream_output, xtream_output_learned FROM playlist WHERE id = 7").use { st ->
                assertTrue(st.step())
                assertEquals("HLS", st.getText(0))
                assertTrue(st.isNull(1))
            }
            // No table rebuild: the PK and the cascading FK are unchanged.
            val sql = c.prepare("SELECT sql FROM sqlite_master WHERE name = 'channel_prefs'").use { it.step(); it.getText(0) }
            assertTrue(sql, sql.contains("PRIMARY KEY(`playlist_id`, `stable_key`)") && sql.contains("ON DELETE CASCADE"))
        }

        // Open-and-read through the production builder (M1 §2.1.3 policy: no destructive fallback).
        val db = LibraryDatabase.build(context, Dispatchers.IO, AndroidSQLiteDriver(), file.name)
        try {
            runBlocking {
                val prefs = db.channelPrefsDao().get(7, "xc:1")!!
                assertEquals(true, prefs.hidden)
                assertEquals("deu", prefs.audioLang)
                assertEquals(0, prefs.playbackFlags)
                assertNull(prefs.fpsMilli)
                assertNull(db.playlistDao().get(7)!!.xtreamOutputLearned)
            }
        } finally {
            db.close()
        }
    }

    private inline fun <R> SQLiteConnection.closing(block: (SQLiteConnection) -> R): R = try {
        block(this)
    } finally {
        close()
    }

    private fun SQLiteConnection.columns(table: String): List<String> =
        prepare("SELECT name FROM pragma_table_info('$table')").use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    private companion object {
        val V1_PREFS_COLUMNS = listOf(
            "playlist_id", "stable_key", "hidden", "locked", "custom_name", "custom_number", "custom_logo", "custom_sort_index",
            "epg_shift_min", "aspect_mode", "audio_lang", "subtitle_lang", "video_decoder", "buffer_profile", "last_watched_at",
            "watch_count", "updated_at",
        )
        val V1_PLAYLIST_COLUMNS = listOf(
            "id", "name", "type", "credential_ref", "display_source", "sort_index", "enabled", "needs_reauth", "user_agent",
            "extra_headers_json", "use_playlist_epg", "update_on_start", "refresh_interval_h", "numbering_mode", "order_mode",
            "xtream_output", "content_types", "extgrp_sticky", "charset_override", "catchup_mode_setting", "catchup_days_override",
            "catchup_probe_winner", "token_zone_setting", "server_zone", "server_offset_sec", "clock_skew_sec", "catchup_correction_h",
            "padding_before_sec", "padding_after_sec", "max_connections_override", "ignore_tls_errors", "active_base_index",
            "header_json", "etag", "last_modified", "refresh_seq", "live_count", "last_refresh_at", "last_success_at", "last_status",
            "created_at",
        )
    }
}
