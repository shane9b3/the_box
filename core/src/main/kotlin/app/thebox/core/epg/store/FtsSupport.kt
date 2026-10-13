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
     * FTS5 stage 1: best texts first (?1 match, ?2 page size, ?3 offset). Ranking texts before joining
     * airings keeps the sort to the matching texts instead of every matching airing (perf: 170 → a few ms
     * for "news" on the 1M-programme database).
     */
    const val FTS5_TEXTS_SQL = "SELECT rowid FROM epg_fts WHERE epg_fts MATCH ?1 ORDER BY bm25(epg_fts) LIMIT ?2 OFFSET ?3"

    /** FTS5 stage 2: airings of one text still on air or upcoming, via `programme_by_text` (?1 text, ?2 now − 360, ?3 now, ?4 limit). */
    const val AIRINGS_SQL =
        "SELECT p.epg_channel_id, p.start_min, p.end_min, c.source_id, c.xmltv_id FROM programme p " +
            "JOIN epg_channel c ON c.id = p.epg_channel_id " +
            "WHERE p.text_id = ?1 AND p.start_min >= ?2 AND p.end_min > ?3 ORDER BY p.start_min LIMIT ?4"

    /** One-statement form (§2.4); used for FTS4, which has no bm25. ?1 match, ?2 now (raw), ?3 limit. */
    fun searchSql(kind: FtsKind): String {
        val order = if (kind == FtsKind.FTS5) "bm25(epg_fts), p.start_min" else "p.start_min"
        return "SELECT p.epg_channel_id, p.start_min, p.end_min, t.title, t.subtitle, c.source_id, c.xmltv_id " +
            "FROM epg_fts JOIN epg_text t ON t.id = epg_fts.rowid " +
            "JOIN programme p ON p.text_id = t.id " +
            "JOIN epg_channel c ON c.id = p.epg_channel_id " +
            "WHERE epg_fts MATCH ?1 AND p.end_min > ?2 " +
            "ORDER BY $order LIMIT ?3"
    }

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
