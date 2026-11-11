package app.thebox.core.epg.store

/** epg.db DDL, pragmas and meta keys (docs/design/m1-data-layer.md §2.3). */
internal object EpgSchema {
    const val SCHEMA_VERSION = 1

    /**
     * Build `programme_by_text` before the bulk load instead of after it (no sort, ~20 % slower inserts).
     * Switch only if the device run (I-5) shows the native sorter peak above 64 MB (review S1).
     *
     * (2026-11-10, performance review H5) On: `EpgIndexMemoryTest` at 1.96M programmes (bundled SQLite, JVM, x86-64
     * macOS) measured the build's RSS peak at 108–115 MB over the pre-build baseline with the post-load sort (the
     * index/FTS/ANALYZE phase alone +66–72 MB) and 56–57 MB with the index built during the load (that phase +13 MB),
     * for ~20 % more build time (38–40 s → 45–46 s) and +2 % file size. The device run (I-5) should still confirm.
     */
    const val INDEX_BEFORE_LOAD = true

    /** Longest stored programme row; longer programmes are split (§3.5.5). */
    const val MAX_ROW_MIN = 360

    /** Stop inferred for the last programme of a channel (§3.5.5). */
    const val LAST_INFERRED_MIN = 60

    const val REBUILD_BATCH_ROWS = 10_000
    const val PATCH_BATCH_ROWS = 5_000
    const val REPAIR_BATCH_CHANNELS = 500

    /** `PRAGMA analysis_limit` before `ANALYZE`, so the 1M-row build does not scan every index fully. */
    const val ANALYSIS_LIMIT = 1_000

    val TABLES: List<String> = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
        """CREATE TABLE source_stat (
            source_id INTEGER PRIMARY KEY, channels INTEGER NOT NULL, programmes_seen INTEGER NOT NULL,
            programmes_kept INTEGER NOT NULL, bytes INTEGER NOT NULL, imported_at_ms INTEGER NOT NULL)""",
        """CREATE TABLE epg_channel (
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            xmltv_id TEXT NOT NULL,
            display_name TEXT NOT NULL,
            icon_url TEXT,
            imported INTEGER NOT NULL DEFAULT 0,
            programme_count INTEGER NOT NULL DEFAULT 0,
            max_len_min INTEGER NOT NULL DEFAULT 360,
            first_start_min INTEGER,
            last_end_min INTEGER,
            UNIQUE (source_id, xmltv_id))""",
        """CREATE TABLE epg_channel_name (
            epg_channel_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            norm TEXT NOT NULL,
            lang TEXT,
            PRIMARY KEY (epg_channel_id, name)) WITHOUT ROWID""",
        """CREATE TABLE epg_text (
            id INTEGER PRIMARY KEY,
            hash INTEGER NOT NULL,
            title TEXT NOT NULL,
            subtitle TEXT,
            description TEXT,
            categories TEXT,
            episode_label TEXT,
            season INTEGER,
            episode INTEGER,
            icon_url TEXT,
            rating TEXT,
            star_rating TEXT,
            year INTEGER,
            credits TEXT,
            series_ref TEXT)""",
        """CREATE TABLE programme (
            epg_channel_id INTEGER NOT NULL,
            start_min INTEGER NOT NULL,
            end_min INTEGER NOT NULL,
            text_id INTEGER NOT NULL,
            flags INTEGER NOT NULL DEFAULT 0,
            catchup_id TEXT,
            PRIMARY KEY (epg_channel_id, start_min)) WITHOUT ROWID""",
    )

    const val PROGRAMME_BY_TEXT = "CREATE INDEX programme_by_text ON programme(text_id, start_min)"

    /** Created after the bulk load (§2.5.2 step 4). */
    val POST_LOAD_INDEXES: List<String> = listOfNotNull(
        if (INDEX_BEFORE_LOAD) null else PROGRAMME_BY_TEXT,
        "CREATE INDEX epg_text_hash ON epg_text(hash)",
        "CREATE INDEX epg_channel_ci ON epg_channel(xmltv_id COLLATE NOCASE)",
        "CREATE INDEX epg_channel_name_norm ON epg_channel_name(norm)",
    )

    /** Build connection on a new `.building` file. `page_size` must run before the first table. */
    val BUILD_PRAGMAS: List<String> = listOf(
        "PRAGMA page_size=4096",
        "PRAGMA journal_mode=OFF",
        "PRAGMA synchronous=OFF",
        "PRAGMA locking_mode=EXCLUSIVE",
        "PRAGMA secure_delete=OFF",
        "PRAGMA cache_size=-16000",
    )

    val READER_PRAGMAS: List<String> = listOf(
        "PRAGMA query_only=1",
        "PRAGMA cache_size=-4000",
        "PRAGMA mmap_size=67108864",
        "PRAGMA busy_timeout=250",
    )

    /** Patch / in-place writer on the current file. */
    val WRITER_PRAGMAS: List<String> = listOf(
        "PRAGMA journal_mode=DELETE",
        "PRAGMA synchronous=NORMAL",
        "PRAGMA secure_delete=OFF",
        "PRAGMA busy_timeout=2000",
        "PRAGMA cache_size=-8000",
    )

    object Meta {
        const val SCHEMA_VERSION = "schema_version"
        const val GENERATION = "generation"
        const val PATCH_SEQ = "patch_seq"
        const val BUILT_AT_MS = "built_at_ms"
        const val COMPLETE = "complete"
        const val FTS = "fts"
        const val WINDOW_FROM_MIN = "window_from_min"
        const val WINDOW_TO_MIN = "window_to_min"
        const val PROGRAMME_COUNT = "programme_count"
        const val CHANNEL_COUNT = "channel_count"
        const val APP_VERSION = "app_version"
    }
}
