package app.thebox.core.epg.store

import androidx.sqlite.SQLiteConnection
import java.text.Normalizer

/** Full-text kind stored in `meta.fts`. */
internal enum class FtsKind(val id: String) {
    FTS5("fts5"),
    FTS4("fts4"),
    ;

    companion object {
        fun of(id: String?): FtsKind = if (id == FTS4.id) FTS4 else FTS5
    }
}

/** FTS5 vs FTS4 detection, DDL and query building (§2.3, §2.4). Title + subtitle only (B3.2). */
internal object FtsSupport {
    fun detect(conn: SQLiteConnection, forceFts4: Boolean): FtsKind {
        if (forceFts4) return FtsKind.FTS4
        val fts5 = try {
            conn.longOrNull("SELECT sqlite_compileoption_used('ENABLE_FTS5')") == 1L
        } catch (_: Throwable) {
            false
        }
        return if (fts5) FtsKind.FTS5 else FtsKind.FTS4
    }

    fun createSql(kind: FtsKind): String = when (kind) {
        FtsKind.FTS5 ->
            "CREATE VIRTUAL TABLE epg_fts USING fts5(title, subtitle, content='epg_text', content_rowid='id', " +
                "tokenize='unicode61 remove_diacritics 2')"
        FtsKind.FTS4 ->
            "CREATE VIRTUAL TABLE epg_fts USING fts4(content=\"epg_text\", title, subtitle, tokenize=unicode61 \"remove_diacritics=2\")"
    }

    const val REBUILD_SQL = "INSERT INTO epg_fts(epg_fts) VALUES('rebuild')"

    fun insertSql(kind: FtsKind): String = when (kind) {
        FtsKind.FTS5 -> "INSERT INTO epg_fts(rowid, title, subtitle) VALUES (?, ?, ?)"
        FtsKind.FTS4 -> "INSERT INTO epg_fts(docid, title, subtitle) VALUES (?, ?, ?)"
    }

    /**
     * Stage 1, FTS5: best texts first (?1 match, ?2 page size, ?3 offset). Ranking texts before joining airings keeps
     * the sort to the matching texts instead of every matching airing (perf: 170 → a few ms for "news" on the
     * 1M-programme database).
     */
    const val FTS5_TEXTS_SQL = "SELECT rowid FROM epg_fts WHERE epg_fts MATCH ?1 ORDER BY bm25(epg_fts) LIMIT ?2 OFFSET ?3"

    /** Stage 1, FTS4 (no ranking function): matching texts in docid order. */
    const val FTS4_TEXTS_SQL = "SELECT docid FROM epg_fts WHERE epg_fts MATCH ?1 ORDER BY docid LIMIT ?2 OFFSET ?3"

    fun textsSql(kind: FtsKind): String = if (kind == FtsKind.FTS5) FTS5_TEXTS_SQL else FTS4_TEXTS_SQL

    /** `programme.flags` bits the search never returns: CONTINUATION (M1 code review Later 10). */
    private const val NOT_CONTINUATION = "(p.flags & 32) = 0"

    /** Past search also skips CONTINUED first segments: their stored end is the 6 h cut (§3.10.2 item 3, N18). */
    private const val NOT_SPLIT = "(p.flags & 96) = 0"

    private const val AIRING_COLUMNS =
        "SELECT p.epg_channel_id, p.start_min, p.end_min, c.source_id, c.xmltv_id FROM programme p JOIN epg_channel c ON c.id = p.epg_channel_id "

    /** Stage 2, on now: ?1 text, ?2 now − 360, ?3 now, ?4 limit; a `programme_by_text` range scan in start order. */
    const val AIRINGS_NOW_SQL =
        AIRING_COLUMNS + "WHERE p.text_id = ?1 AND p.start_min >= ?2 AND p.start_min <= ?3 AND p.end_min > ?3 AND $NOT_CONTINUATION " +
            "ORDER BY p.start_min LIMIT ?4"

    /** Stage 2, upcoming: ?1 text, ?2 now, ?3 limit. */
    const val AIRINGS_NEXT_SQL =
        AIRING_COLUMNS + "WHERE p.text_id = ?1 AND p.start_min > ?2 AND $NOT_CONTINUATION ORDER BY p.start_min LIMIT ?3"

    /**
     * Stage 2 for `searchPast` (review B6): ?1 text, ?2 past-from, ?3 now, ?4 limit; end in (past-from, now], newest
     * first. The start bound keeps it a `programme_by_text` range scan.
     */
    const val PAST_AIRINGS_SQL =
        AIRING_COLUMNS + "WHERE p.text_id = ?1 AND p.start_min >= ?2 - ${EpgSchema.MAX_ROW_MIN} AND p.start_min < ?3 " +
            "AND p.end_min > ?2 AND p.end_min <= ?3 AND $NOT_SPLIT ORDER BY p.start_min DESC LIMIT ?4"

    /**
     * Builds the MATCH expression from user text: split on non-letter/digit, drop empties, double `"`,
     * quote each token and make it a prefix (`"bbc"* "new"*` for FTS5, `"bbc*" "new*"` for FTS4),
     * implicit AND. Null when no token is left.
     */
    fun matchExpression(text: String, kind: FtsKind): String? {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        val sb = StringBuilder()
        var i = 0
        val token = StringBuilder()
        fun flush() {
            if (token.isEmpty()) return
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append('"')
            for (ch in token) {
                if (ch == '"') sb.append("\"\"") else sb.append(ch)
            }
            if (kind == FtsKind.FTS5) sb.append("\"*") else sb.append("*\"")
            token.setLength(0)
        }
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                token.appendCodePoint(cp)
            } else {
                flush()
            }
            i += Character.charCount(cp)
        }
        flush()
        return if (sb.isEmpty()) null else sb.toString()
    }
}
