package app.thebox.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * library.db migrations (docs/design/m1-data-layer.md §2.1.3).
 * 1 → 2 is an `@AutoMigration` declared on [LibraryDatabase] (docs/design/m2-playback-core.md §2.10).
 * 2 → 3 is hand-written ([MIGRATION_2_3], docs/design/m3-guide.md §2.9, §2.12 item 1): Room's auto-migration
 * rebuilds `epg_match` and `epg_mapping` (create `_new_…`, copy, drop, rename) whenever an index is added, and the
 * design requires exactly five `ALTER TABLE … ADD COLUMN`s and two `CREATE INDEX`es with no table rebuild.
 *
 * Policy:
 * - Every schema change bumps `LibraryDatabase.VERSION`, commits the new exported JSON under
 *   `data/schemas/`, and ships an `@AutoMigration` or a hand-written [Migration] listed in [ALL], plus an
 *   instrumented `MigrationTestHelper` test.
 * - `fallbackToDestructiveMigration*` is forbidden: it would wipe favourites, prefs, mappings and reminders.
 * - `channel`, `channel_group`, `channel_fts` and `epg_match` are re-derivable. A migration may drop and
 *   refill them by setting `playlist.etag = NULL` and `refresh_seq = 0` (forces a full refresh). User-data
 *   tables (`favourite*`, `channel_prefs`, `group_prefs`, `epg_mapping`, `reminder`, `stream_source`,
 *   `purged_key`, `credential`) must always be migrated.
 */
object Migrations {
    /** The statements of docs/design/m3-guide.md §2.9.1, verbatim. Existing rows get NULL / 'AUTO' / 0. */
    val SQL_2_3: List<String> = listOf(
        "ALTER TABLE `channel` ADD COLUMN `display_name` TEXT",
        "ALTER TABLE `playlist` ADD COLUMN `name_prefix_mode` TEXT NOT NULL DEFAULT 'AUTO'",
        "ALTER TABLE `playlist` ADD COLUMN `name_prefix_custom` TEXT",
        "ALTER TABLE `playlist` ADD COLUMN `name_prefix_ver` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `reminder` ADD COLUMN `shift_min` INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS `index_epg_match_ref` ON `epg_match` (`source_id`, `xmltv_id`)",
        "CREATE INDEX IF NOT EXISTS `index_epg_mapping_ref` ON `epg_mapping` (`source_id`, `xmltv_id`)",
    )

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            for (sql in SQL_2_3) connection.execSQL(sql)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
