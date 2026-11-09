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
 * 3 → 4 is hand-written too ([MIGRATION_3_4], docs/design/m5-setup-settings.md §4.2): `playlist` gains a unique index
 * and a backfilled `uid`, which an auto-migration would do by rebuilding the table.
 * 4 → 5 is hand-written ([MIGRATION_4_5], docs/design/m6-channel-editor.md §3.6): `channel_prefs.custom_group_key` and
 * its index, with no rebuild.
 *
 * Policy:
 * - Every schema change bumps `LibraryDatabase.VERSION`, commits the new exported JSON under
 *   `data/schemas/`, and ships an `@AutoMigration` or a hand-written [Migration] listed in [ALL], plus an
 *   instrumented `MigrationTestHelper` test.
 * - `fallbackToDestructiveMigration*` is forbidden: it would wipe favourites, prefs, mappings and reminders.
 * - `channel`, `channel_group`, `channel_fts` and `epg_match` are re-derivable. A migration may drop and
 *   refill them by setting `playlist.etag = NULL` and `refresh_seq = 0` (forces a full refresh). `refresh_seq = 0`
 *   also tells a restore that the playlist has no guide rows yet; resetting it makes a restore in that window create
 *   placeholder guides the next refresh prunes (harmless; docs/design/m5-restore-placeholder-guides.md). User-data
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

    /**
     * v3 → v4, the statements of docs/design/m5-setup-settings.md §4.2, verbatim: two CREATE TABLEs, four ADD COLUMNs, one
     * UPDATE (backfills `playlist.uid`), two CREATE INDEXes; no table rebuild. Existing rows get `rule_hidden = 0`,
     * `hide_rules_ver = 0` and a random 32-hex `uid`.
     */
    val SQL_3_4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `app_setting` (`domain` TEXT NOT NULL, `json` TEXT NOT NULL, `schema_ver` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`domain`))",
        "CREATE TABLE IF NOT EXISTS `hide_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlist_id` INTEGER NOT NULL, `target` INTEGER NOT NULL, `pattern` TEXT NOT NULL, `is_regex` INTEGER NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1, `disabled_reason` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS `index_hide_rule_playlist` ON `hide_rule` (`playlist_id`)",
        "ALTER TABLE `channel` ADD COLUMN `rule_hidden` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `channel_group` ADD COLUMN `rule_hidden` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `playlist` ADD COLUMN `uid` TEXT",
        "UPDATE `playlist` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` IS NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_uid` ON `playlist` (`uid`)",
        "ALTER TABLE `playlist` ADD COLUMN `hide_rules_ver` INTEGER NOT NULL DEFAULT 0",
    )

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            for (sql in SQL_3_4) connection.execSQL(sql)
        }
    }

    /**
     * v4 → v5 (docs/design/m6-channel-editor.md §3.6, owner ruling Q1): the channel editor's Move to group. One ADD COLUMN
     * (every existing row gets NULL: no behaviour change) and one CREATE INDEX; no table rebuild. No downgrade path: an
     * older APK over v5 fails to open, as for v4.
     */
    val SQL_4_5: List<String> = listOf(
        "ALTER TABLE `channel_prefs` ADD COLUMN `custom_group_key` TEXT",
        "CREATE INDEX IF NOT EXISTS `index_channel_prefs_group` ON `channel_prefs` (`playlist_id`, `custom_group_key`)",
    )

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            for (sql in SQL_4_5) connection.execSQL(sql)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
