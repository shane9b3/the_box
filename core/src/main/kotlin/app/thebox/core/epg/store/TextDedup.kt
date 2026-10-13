package app.thebox.core.epg.store

import androidx.collection.MutableLongIntMap
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.common.Hash64
import app.thebox.core.xmltv.XmltvProgramme

/**
 * De-duplicated programme text (§3.5.4): `Hash64` over every text column → `epg_text.id`.
 *
 * Rebuild: the map starts empty and ids are assigned here (no `last_insert_rowid()` round trip).
 * Patch / in-place ([seedFromDb]): a map miss is looked up through `epg_text_hash` before inserting,
 * and with [ftsKind] set every new text is also added to the FTS index (external content needs it).
 */
internal class TextDedup(
    private val conn: SQLiteConnection,
    firstId: Long,
    private val seedFromDb: Boolean,
    private val ftsKind: FtsKind? = null,
) {
    private val map = MutableLongIntMap(4096)
    private var nextId: Long = firstId
    private var insert: SQLiteStatement? = null
    private var lookup: SQLiteStatement? = null
    private var ftsInsert: SQLiteStatement? = null

    /** New epg_text rows written by this instance. */
    var inserted = 0
        private set

    fun idFor(p: XmltvProgramme): Int {
        val categories = p.categoriesJoined()
        val h = hash(p, categories)
        val known = map.getOrDefault(h, 0)
        if (known != 0) return known
        if (seedFromDb) {
            val st = lookup ?: conn.prepare("SELECT id FROM epg_text WHERE hash = ? LIMIT 1").also { lookup = it }
            st.bindLong(1, h)
            val found = try { if (st.step()) st.getLong(0).toInt() else 0 } finally { st.reset() }
            if (found != 0) {
                map[h] = found
                return found
            }
        }
        val id = nextId++
        val st = insert ?: conn.prepare(INSERT).also { insert = it }
        st.bindLong(1, id)
        st.bindLong(2, h)
        st.bindText(3, p.title)
        st.bindTextOrNull(4, p.subtitle)
        st.bindTextOrNull(5, p.description)
        st.bindTextOrNull(6, categories)
        st.bindTextOrNull(7, p.episodeLabel)
        st.bindIntOrNone(8, p.season)
        st.bindIntOrNone(9, p.episode)
        st.bindTextOrNull(10, p.iconUrl)
        st.bindTextOrNull(11, p.rating)
        st.bindTextOrNull(12, p.starRating)
        st.bindIntOrNone(13, p.year)
        st.bindTextOrNull(14, p.credits)
        st.bindTextOrNull(15, p.seriesRef)
        st.run()
        if (ftsKind != null) {
            val f = ftsInsert ?: conn.prepare(FtsSupport.insertSql(ftsKind)).also { ftsInsert = it }
            f.bindLong(1, id)
            f.bindText(2, p.title)
            f.bindTextOrNull(3, p.subtitle)
            f.run()
        }
        inserted++
        map[h] = id.toInt()
        return id.toInt()
    }

    fun close() {
        insert?.close()
        lookup?.close()
        ftsInsert?.close()
        insert = null
        lookup = null
        ftsInsert = null
    }

    companion object {
        private const val INSERT =
            "INSERT INTO epg_text(id, hash, title, subtitle, description, categories, episode_label, season, episode, " +
                "icon_url, rating, star_rating, year, credits, series_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

        // Same bytes as Hash64.int(h, v?) without boxing.
        private fun intOrNone(h: Long, v: Int): Long = if (v == XmltvProgramme.NONE) Hash64.str(h, null) else Hash64.long(h, v.toLong())

        fun hash(p: XmltvProgramme, categories: String?): Long {
            var h = Hash64.str(Hash64.OFFSET, p.title)
            h = Hash64.str(h, p.subtitle)
            h = Hash64.str(h, p.description)
            h = Hash64.str(h, categories)
            h = Hash64.str(h, p.episodeLabel)
            h = intOrNone(h, p.season)
            h = intOrNone(h, p.episode)
            h = Hash64.str(h, p.iconUrl)
            h = Hash64.str(h, p.rating)
            h = Hash64.str(h, p.starRating)
            h = intOrNone(h, p.year)
            h = Hash64.str(h, p.credits)
            h = Hash64.str(h, p.seriesRef)
            return h
        }
    }
}
