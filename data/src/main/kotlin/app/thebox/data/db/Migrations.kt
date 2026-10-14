package app.thebox.data.db

import androidx.room.migration.Migration

/**
 * library.db migrations (docs/design/m1-data-layer.md §2.1.3). Hand-written migrations only; none so far.
 * 1 → 2 is an `@AutoMigration` declared on [LibraryDatabase] (docs/design/m2-playback-core.md §2.10).
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
    val ALL: Array<Migration> = emptyArray()
}
