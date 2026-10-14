package app.thebox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.data.db.entity.ReminderEntity
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
 * library.db v1 → v2 → v3 (docs/design/m2-playback-core.md §2.10, review S10, day-0 check 7; m3-guide.md §2.9): the exported schemas are
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

    /**
     * library.db v2 → v3 (docs/design/m3-guide.md §2.9): user rows survive, exactly the five columns arrive with their
     * defaults (so the startup backfill sees `name_prefix_ver = 0` and NULL `display_name`s), the two reverse-lookup
     * indexes exist, no table is rebuilt, and the file opens through the production builder.
     */
    @Test
    fun migrate2To3KeepsRowsAndAddsColumnsAndIndexes() {
        val helper = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), LibraryDatabase::class)
        helper.createDatabase(2).closing { c ->
            c.execSQL(
                "INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, xtream_output_learned, created_at) " +
                    "VALUES (7, 'P', 'XTREAM', 'r', 'h', 0, 'TS', 1)",
            )
            c.execSQL(
                "INSERT INTO channel(id, playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, url, content_hash, first_seen_at, updated_at) " +
                    "VALUES (11, 7, 'xc:1', 'XTREAM', 0, 'UK: BBC One', 'uk bbc one', 0, 'u', 9, 1, 1)",
            )
            c.execSQL(
                "INSERT INTO reminder(id, playlist_id, stable_key, start_min, end_min, title_hash, title, channel_name, lead_sec, state, created_at) " +
                    "VALUES (3, 7, 'xc:1', 1000, 1030, 42, 'News', 'BBC One', 90, 1, 2)",
            )
            c.execSQL(
                "INSERT INTO epg_source(id, name, origin, credential_ref, url_hash, display_source, priority, created_at) " +
                    "VALUES (5, 'S', 'USER', 'r2', 1, 'h', 0, 1)",
            )
            c.execSQL("INSERT INTO epg_mapping(playlist_id, stable_key, source_id, xmltv_id, created_at) VALUES (7, 'xc:1', 5, 'bbc1.uk', 3)")
            c.execSQL("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, custom_name, watch_count, updated_at, playback_flags) VALUES (7, 'xc:1', 0, 'Beeb', 2, 4, 3)")
            v2TableSql = c.tableSql()
        }
        helper.runMigrationsAndValidate(3, Migrations.ALL.toList()).closing { c ->
            // No table rebuild (§2.9.1): every table not gaining a column keeps its CREATE statement byte for byte.
            val v3TableSql = c.tableSql()
            for (t in listOf("epg_match", "epg_mapping", "channel_prefs", "favourite", "group_prefs")) {
                assertEquals(t, v2TableSql[t], v3TableSql[t])
            }
            assertEquals(listOf("display_name"), c.columns("channel") - V2_CHANNEL_COLUMNS)
            assertEquals(listOf("name_prefix_mode", "name_prefix_custom", "name_prefix_ver"), c.columns("playlist") - (V1_PLAYLIST_COLUMNS + "xtream_output_learned"))
            assertEquals(listOf("shift_min"), c.columns("reminder") - V2_REMINDER_COLUMNS)
            c.prepare("SELECT name, display_name FROM channel WHERE id = 11").use { st ->
                assertTrue(st.step())
                assertEquals("UK: BBC One", st.getText(0))
                assertTrue(st.isNull(1))
            }
            c.prepare("SELECT xtream_output_learned, name_prefix_mode, name_prefix_custom, name_prefix_ver FROM playlist WHERE id = 7").use { st ->
                assertTrue(st.step())
                assertEquals("TS", st.getText(0))
                assertEquals("AUTO", st.getText(1))
                assertTrue(st.isNull(2))
                assertEquals(0L, st.getLong(3))
            }
            c.prepare("SELECT start_min, end_min, title, lead_sec, state, shift_min FROM reminder WHERE id = 3").use { st ->
                assertTrue(st.step())
                assertEquals(1000L, st.getLong(0))
                assertEquals(1030L, st.getLong(1))
                assertEquals("News", st.getText(2))
                assertEquals(90L, st.getLong(3))
                assertEquals(1L, st.getLong(4))
                assertEquals(0L, st.getLong(5))
            }
            c.prepare("SELECT xmltv_id FROM epg_mapping WHERE playlist_id = 7 AND stable_key = 'xc:1'").use { st ->
                assertTrue(st.step())
                assertEquals("bbc1.uk", st.getText(0))
            }
            c.prepare("SELECT custom_name, playback_flags FROM channel_prefs").use { st ->
                assertTrue(st.step())
                assertEquals("Beeb", st.getText(0))
                assertEquals(3L, st.getLong(1))
            }
            for ((index, table) in listOf("index_epg_match_ref" to "epg_match", "index_epg_mapping_ref" to "epg_mapping")) {
                val sql = c.prepare("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '$index'").use { st ->
                    assertTrue(index, st.step()); st.getText(0)
                }
                assertTrue(sql, sql.contains("ON `$table` (`source_id`, `xmltv_id`)"))
            }
            // No table rebuild: the reminder identity index and the cascading FK are unchanged.
            val sql = c.prepare("SELECT sql FROM sqlite_master WHERE name = 'reminder'").use { it.step(); it.getText(0) }
            assertTrue(sql, sql.contains("ON DELETE CASCADE"))
        }

        val db = LibraryDatabase.build(context, Dispatchers.IO, AndroidSQLiteDriver(), file.name)
        try {
            runBlocking {
                val p = db.playlistDao().get(7)!!
                assertEquals("AUTO", p.namePrefixMode)
                assertEquals(0, p.namePrefixVer)
                assertNull(db.channelDao().byId(11)!!.displayName)
                val r = db.reminderDao().all().single()
                assertEquals(0, r.shiftMin)
                assertEquals(ReminderEntity.STATE_FIRED, r.state)
                assertEquals(0, db.reminderDao().setStateIfPending(3, 2))
                assertEquals(1, db.reminderDao().deleteIfNotPending(7, "xc:1", 1000))
            }
        } finally {
            db.close()
        }
    }

    private var v2TableSql: Map<String, String> = emptyMap()

    private fun SQLiteConnection.tableSql(): Map<String, String> =
        prepare("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND sql IS NOT NULL").use { st ->
            buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
        }

    private inline fun <R> SQLiteConnection.closing(block: (SQLiteConnection) -> R): R = try {
        block(this)
    } finally {
        close()
    }

    private fun SQLiteConnection.columns(table: String): List<String> =
        prepare("SELECT name FROM pragma_table_info('$table')").use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    private companion object {
        val V2_CHANNEL_COLUMNS = listOf(
            "id", "playlist_id", "stable_key", "key_rule", "kind", "name", "norm_name", "number", "sort_index", "group_id",
            "logo_url", "tvg_id", "tvg_name", "url", "headers_json", "xtream_stream_id", "container_ext", "epg_shift_min",
            "catchup_type", "catchup_source", "catchup_days", "catchup_correction_h", "timeshift_days", "tvg_rec_days",
            "catchup_time_sec", "tv_archive", "tv_archive_days", "is_adult", "extras_json", "content_hash", "missing_since",
            "missing_count", "first_seen_at", "updated_at",
        )
        val V2_REMINDER_COLUMNS = listOf(
            "id", "playlist_id", "stable_key", "start_min", "end_min", "title_hash", "title", "channel_name", "lead_sec", "state",
            "created_at",
        )
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
