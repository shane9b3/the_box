package app.thebox.core.epg.store

import androidx.collection.MutableIntIntMap
import androidx.sqlite.SQLiteStatement
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeDetail
import app.thebox.core.text.NameNormalizer
import app.thebox.core.xmltv.XmltvProgramme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read API on one generation (§2.4). Times are stored raw; the shift is applied here.
 * Not thread-safe per instance (the guide owns one); the shared connections are locked per call.
 */
internal class EpgReaderImpl(private val handle: GenerationHandle) : EpgReader {
    private val closed = AtomicBoolean(false)
    private val titles = IntLru(TITLE_CACHE)
    /** `max_len_min` per epg_channel id, -1 = not loaded. Reloaded after a patch (S13). */
    private var maxLen = IntArray(0)
    private var seenPatchSeq = handle.info.patchSeq

    override val info: EpgGenerationInfo get() = handle.info

    override fun resolve(refs: List<EpgChannelRef>, caseInsensitive: Boolean): IntArray {
        checkOpen()
        val out = IntArray(refs.size) { -1 }
        handle.guide.use { s ->
            val exact = s.statement("SELECT id FROM epg_channel WHERE source_id = ? AND xmltv_id = ?")
            val ci = s.statement("SELECT id FROM epg_channel WHERE xmltv_id = ? COLLATE NOCASE AND source_id = ? ORDER BY id LIMIT 1")
            for (i in refs.indices) {
                val r = refs[i]
                exact.bindLong(1, r.sourceId)
                exact.bindText(2, r.xmltvId)
                out[i] = firstInt(exact)
                if (out[i] < 0 && caseInsensitive) {
                    ci.bindText(1, r.xmltvId)
                    ci.bindLong(2, r.sourceId)
                    out[i] = firstInt(ci)
                }
            }
        }
        return out
    }

    override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) {
        checkOpen()
        out.clear()
        handle.guide.use { s ->
            refreshIfPatched()
            val st = s.statement(WINDOW_SQL)
            try {
                for (i in channels.indices) {
                    val ch = channels[i]
                    if (ch < 0) continue
                    val shift = if (i < shiftMin.size) shiftMin[i] else 0
                    val rawFrom = fromMin - shift
                    val rawTo = toMin - shift
                    st.bindLong(1, ch.toLong())
                    st.bindLong(2, rawFrom.toLong())
                    st.bindLong(3, rawTo.toLong())
                    st.bindLong(4, maxLenOf(s, ch).toLong())
                    while (st.step()) {
                        val k = out.size
                        out.ensureCapacity(k + 1)
                        val rawStart = st.getLong(0).toInt()
                        out.channelIndex[k] = i
                        out.rawStartMin[k] = rawStart
                        out.startMin[k] = rawStart + shift
                        out.endMin[k] = st.getLong(1).toInt() + shift
                        out.textId[k] = st.getLong(2).toInt()
                        out.flags[k] = st.getLong(3).toInt()
                        out.size = k + 1
                    }
                    st.reset()
                }
            } finally {
                st.reset()
            }
            val titleSt = s.statement("SELECT title FROM epg_text WHERE id = ?")
            for (k in 0 until out.size) out.titles[k] = titleOf(titleSt, out.textId[k])
        }
    }

    override fun detail(channel: Int, rawStartMin: Int, shiftMin: Int): ProgrammeDetail? {
        checkOpen()
        if (channel < 0) return null
        return handle.guide.use { s ->
            val st = s.statement(DETAIL_SQL)
            st.bindLong(1, channel.toLong())
            st.bindLong(2, rawStartMin.toLong())
            try { if (st.step()) toDetail(st, shiftMin) else null } finally { st.reset() }
        }
    }

    override fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail? {
        checkOpen()
        if (channel < 0) return null
        return handle.guide.use { s ->
            refreshIfPatched()
            val st = s.statement(AT_SQL)
            st.bindLong(1, channel.toLong())
            st.bindLong(2, (atMin - shiftMin).toLong())
            st.bindLong(3, maxLenOf(s, channel).toLong())
            try { if (st.step()) toDetail(st, shiftMin) else null } finally { st.reset() }
        }
    }

    /**
     * M3 (docs/design/m3-guide.md §2.7, §3.10.2, review B6): on-now and upcoming airings, best text first, at most
     * [perText] per text in start order, CONTINUATION segments never returned (M1 code review Later 10).
     *
     * The per-text cap is shared between on-now and upcoming airings: a text airing on many channels at once (an hourly
     * "News" on 20 channels) keeps at most half its slots for on-now copies when it also has upcoming ones, so the next
     * airing is never starved by simultaneous copies. Unused slots go to the other kind.
     */
    override fun search(query: String, nowMin: Int, limit: Int, perText: Int): List<EpgSearchHit> {
        checkOpen()
        val kind = handle.fts
        val match = FtsSupport.matchExpression(query, kind) ?: return emptyList()
        if (limit <= 0 || perText <= 0) return emptyList()
        return handle.aux.use { s ->
            val onNow = s.statement(FtsSupport.AIRINGS_NOW_SQL)
            val next = s.statement(FtsSupport.AIRINGS_NEXT_SQL)
            val cap = if (perText == Int.MAX_VALUE) Int.MAX_VALUE else perText
            searchByText(s, match, kind, limit) { textId, room ->
                val per = minOf(cap, room)
                val now = airings(onNow, textId, (nowMin - EpgSchema.MAX_ROW_MIN).toLong(), nowMin.toLong(), per)
                val up = airings(next, textId, nowMin.toLong(), null, per)
                val keepNow = minOf(now.size, maxOf(per - up.size, (per + 1) / 2))
                now.subList(0, keepNow) + up.subList(0, minOf(up.size, per - keepNow))
            }
        }
    }

    /**
     * Catch-up search (B6): airings with end_min in (pastFromMin, nowMin], best text first, newest first within a text,
     * at most [perText] per text. CONTINUATION rows and CONTINUED first segments (a split programme whose stored end is
     * the 6 h cut, not its real end; §3.10.2 item 3, N18) are never returned.
     */
    override fun searchPast(query: String, nowMin: Int, pastFromMin: Int, limit: Int, perText: Int): List<EpgSearchHit> {
        checkOpen()
        val kind = handle.fts
        val match = FtsSupport.matchExpression(query, kind) ?: return emptyList()
        if (limit <= 0 || perText <= 0 || pastFromMin >= nowMin) return emptyList()
        return handle.aux.use { s ->
            val past = s.statement(FtsSupport.PAST_AIRINGS_SQL)
            searchByText(s, match, kind, limit) { textId, room ->
                airings(past, textId, pastFromMin.toLong(), nowMin.toLong(), minOf(perText, room))
            }
        }
    }

    private class Airing(val channel: Int, val start: Int, val end: Int, val sourceId: Long, val xmltvId: String)

    /** Runs one per-text airings statement: ?1 text, ?2 [a], ?3 [b] (when not null), last param the limit. */
    private fun airings(st: SQLiteStatement, textId: Int, a: Long, b: Long?, limit: Int): List<Airing> {
        if (limit <= 0) return emptyList()
        st.bindLong(1, textId.toLong())
        st.bindLong(2, a)
        if (b != null) st.bindLong(3, b)
        st.bindLong(if (b != null) 4 else 3, limit.toLong())
        val out = ArrayList<Airing>(minOf(limit, 16))
        try {
            while (st.step()) out += Airing(st.getLong(0).toInt(), st.getLong(1).toInt(), st.getLong(2).toInt(), st.getLong(3), st.getText(4))
        } finally {
            st.reset()
        }
        return out
    }

    /**
     * Texts in rank order (bm25 on FTS5; docid order on FTS4, which has no ranking function), then [airingsOf] per text
     * with the room left under [limit]. Ranking texts before joining airings keeps the sort to the matching texts
     * (M1 perf note: 170 → a few ms for "news" on the 1M-programme database).
     */
    private fun searchByText(
        s: ReaderConnection.Scope, match: String, kind: FtsKind, limit: Int, airingsOf: (textId: Int, room: Int) -> List<Airing>,
    ): List<EpgSearchHit> {
        val hits = ArrayList<EpgSearchHit>()
        val textsSt = s.statement(FtsSupport.textsSql(kind))
        val textSt = s.statement("SELECT title, subtitle FROM epg_text WHERE id = ?")
        val page = IntArray(SEARCH_TEXT_PAGE)
        var offset = 0
        while (hits.size < limit) {
            var n = 0
            textsSt.bindText(1, match)
            textsSt.bindLong(2, SEARCH_TEXT_PAGE.toLong())
            textsSt.bindLong(3, offset.toLong())
            try {
                while (textsSt.step()) page[n++] = textsSt.getLong(0).toInt()
            } finally {
                textsSt.reset()
            }
            for (k in 0 until n) {
                if (hits.size >= limit) break
                val list = airingsOf(page[k], limit - hits.size)
                if (list.isEmpty()) continue
                textSt.bindLong(1, page[k].toLong())
                var title = ""
                var subtitle: String? = null
                try {
                    if (textSt.step()) {
                        title = textSt.getText(0)
                        subtitle = textSt.textOrNull(1)
                    }
                } finally {
                    textSt.reset()
                }
                for (a in list) hits += EpgSearchHit(a.channel, EpgChannelRef(a.sourceId, a.xmltvId), a.start, a.end, title, subtitle)
            }
            if (n < SEARCH_TEXT_PAGE) break
            offset += n
        }
        return hits
    }

    override fun forEachChannel(sourceId: Long?, block: (EpgChannelInfo) -> Unit) {
        checkOpen()
        handle.aux.use { s ->
            val st = s.statement(if (sourceId == null) CHANNELS_ALL_SQL else CHANNELS_SOURCE_SQL)
            if (sourceId != null) st.bindLong(1, sourceId)
            try {
                var currentId = Long.MIN_VALUE
                var pending: PendingChannel? = null
                while (st.step()) {
                    val id = st.getLong(0)
                    if (id != currentId) {
                        pending?.let { block(it.toInfo()) }
                        currentId = id
                        pending = PendingChannel(st)
                    }
                    if (!st.isNull(7)) pending!!.addName(st.getText(7))
                }
                pending?.let { block(it.toInfo()) }
            } finally {
                st.reset()
            }
        }
    }

    override fun findChannels(text: String, limit: Int): List<EpgChannelInfo> {
        checkOpen()
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()
        val norm = NameNormalizer.forMatch(raw)
        return handle.aux.use { s ->
            val st = s.statement(FIND_SQL)
            st.bindText(1, norm)
            st.bindText(2, norm + "￿")
            st.bindText(3, escapeLike(raw) + "%")
            st.bindLong(4, limit.toLong())
            val rows = ArrayList<PendingChannel>()
            try {
                while (st.step()) rows += PendingChannel(st)
            } finally {
                st.reset()
            }
            val names = s.statement("SELECT name FROM epg_channel_name WHERE epg_channel_id = ?")
            for (r in rows) {
                names.bindLong(1, r.id)
                try { while (names.step()) r.addName(names.getText(0)) } finally { names.reset() }
            }
            rows.map { it.toInfo() }
        }
    }

    override fun refOf(channel: Int): EpgChannelRef? {
        checkOpen()
        if (channel < 0) return null
        return handle.guide.use { s ->
            val st = s.statement("SELECT source_id, xmltv_id FROM epg_channel WHERE id = ?")
            st.bindLong(1, channel.toLong())
            try { if (st.step()) EpgChannelRef(st.getLong(0), st.getText(1)) else null } finally { st.reset() }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) handle.release()
    }

    private fun checkOpen() = check(!closed.get()) { "EpgReader is closed" }

    private fun refreshIfPatched() {
        val seq = handle.info.patchSeq
        if (seq != seenPatchSeq) {
            seenPatchSeq = seq
            maxLen.fill(-1)
            titles.clear()
        }
    }

    private fun maxLenOf(s: ReaderConnection.Scope, ch: Int): Int {
        if (ch >= maxLen.size) {
            val old = maxLen.size
            maxLen = maxLen.copyOf(maxOf(ch + 1, old * 2, 64))
            maxLen.fill(-1, old, maxLen.size)
        }
        var v = maxLen[ch]
        if (v < 0) {
            val st = s.statement("SELECT max_len_min FROM epg_channel WHERE id = ?")
            st.bindLong(1, ch.toLong())
            v = try { if (st.step()) st.getLong(0).toInt() else EpgSchema.MAX_ROW_MIN } finally { st.reset() }
            maxLen[ch] = v
        }
        return v
    }

    private fun titleOf(st: SQLiteStatement, textId: Int): String {
        titles[textId]?.let { return it }
        st.bindLong(1, textId.toLong())
        val t = try { if (st.step()) st.getText(0) else "" } finally { st.reset() }
        titles.put(textId, t)
        return t
    }

    private fun firstInt(st: SQLiteStatement): Int = try { if (st.step()) st.getLong(0).toInt() else -1 } finally { st.reset() }

    private fun toDetail(st: SQLiteStatement, shift: Int): ProgrammeDetail {
        val title = st.getText(5)
        val rawStart = st.getLong(1).toInt()
        val rawEnd = st.getLong(2).toInt()
        return ProgrammeDetail(
            channel = st.getLong(0).toInt(),
            ref = EpgChannelRef(st.getLong(16), st.getText(17)),
            rawStartMin = rawStart,
            rawEndMin = rawEnd,
            startMin = rawStart + shift,
            endMin = rawEnd + shift,
            title = title,
            subtitle = st.textOrNull(6),
            description = st.textOrNull(7),
            categories = st.textOrNull(8)?.split(XmltvProgramme.CATEGORY_SEPARATOR) ?: emptyList(),
            episodeLabel = st.textOrNull(9),
            season = st.intOrNull(10),
            episode = st.intOrNull(11),
            iconUrl = st.textOrNull(12),
            rating = st.textOrNull(13),
            starRating = st.textOrNull(14),
            year = st.intOrNull(15),
            flags = st.getLong(3).toInt(),
            catchupId = st.textOrNull(4),
            titleHash = Hash64.of(title),
        )
    }

    /** Row of `epg_channel` (columns 0–6 of the channel queries) plus collected names. */
    private class PendingChannel(st: SQLiteStatement) {
        val id = st.getLong(0)
        val sourceId = st.getLong(1)
        val xmltvId: String = st.getText(2)
        val displayName: String = st.getText(3)
        val icon = st.textOrNull(4)
        val imported = st.getLong(5) != 0L
        val count = st.getLong(6).toInt()
        private val names = ArrayList<String>(2).apply { if (displayName.isNotEmpty()) add(displayName) }

        fun addName(n: String) {
            if (n !in names) names.add(n)
        }

        fun toInfo() = EpgChannelInfo(sourceId, xmltvId, names, icon, imported, count)
    }

    /** Fixed-size LRU `textId → title` without boxing (§2.4: 4,096 entries). */
    internal class IntLru(private val capacity: Int) {
        private val index = MutableIntIntMap(capacity * 2)
        private val keys = IntArray(capacity)
        private val values = arrayOfNulls<String>(capacity)
        private val prev = IntArray(capacity)
        private val next = IntArray(capacity)
        private var head = -1
        private var tail = -1
        private var size = 0

        operator fun get(key: Int): String? {
            val slot = index.getOrDefault(key, -1)
            if (slot < 0) return null
            moveToHead(slot)
            return values[slot]
        }

        fun put(key: Int, value: String) {
            val existing = index.getOrDefault(key, -1)
            if (existing >= 0) {
                values[existing] = value
                moveToHead(existing)
                return
            }
            val slot: Int
            if (size < capacity) {
                slot = size++
            } else {
                slot = tail
                unlink(slot)
                index.remove(keys[slot])
            }
            keys[slot] = key
            values[slot] = value
            index[key] = slot
            linkHead(slot)
        }

        fun clear() {
            index.clear()
            values.fill(null)
            head = -1
            tail = -1
            size = 0
        }

        private fun moveToHead(slot: Int) {
            if (slot == head) return
            unlink(slot)
            linkHead(slot)
        }

        private fun unlink(slot: Int) {
            val p = prev[slot]
            val n = next[slot]
            if (p >= 0) next[p] = n else head = n
            if (n >= 0) prev[n] = p else tail = p
        }

        private fun linkHead(slot: Int) {
            prev[slot] = -1
            next[slot] = head
            if (head >= 0) prev[head] = slot
            head = slot
            if (tail < 0) tail = slot
        }
    }

    companion object {
        const val TITLE_CACHE = 4096
        private const val SEARCH_TEXT_PAGE = 64

        /** ?1 channel, ?2 raw from, ?3 raw to, ?4 max_len_min: a bounded PK range scan, no sort (§2.4). */
        const val WINDOW_SQL =
            "SELECT start_min, end_min, text_id, flags FROM programme " +
                "WHERE epg_channel_id = ?1 AND start_min >= ?2 - ?4 AND start_min < ?3 AND end_min > ?2 ORDER BY start_min"

        private const val DETAIL_COLUMNS =
            "SELECT p.epg_channel_id, p.start_min, p.end_min, p.flags, p.catchup_id, t.title, t.subtitle, t.description, " +
                "t.categories, t.episode_label, t.season, t.episode, t.icon_url, t.rating, t.star_rating, t.year, " +
                "c.source_id, c.xmltv_id FROM programme p JOIN epg_text t ON t.id = p.text_id JOIN epg_channel c ON c.id = p.epg_channel_id "

        const val DETAIL_SQL = DETAIL_COLUMNS + "WHERE p.epg_channel_id = ?1 AND p.start_min = ?2"

        const val AT_SQL = DETAIL_COLUMNS +
            "WHERE p.epg_channel_id = ?1 AND p.start_min <= ?2 AND p.start_min >= ?2 - ?3 AND p.end_min > ?2 " +
            "ORDER BY p.start_min DESC LIMIT 1"

        private const val CHANNEL_COLUMNS =
            "SELECT c.id, c.source_id, c.xmltv_id, c.display_name, c.icon_url, c.imported, c.programme_count, n.name " +
                "FROM epg_channel c LEFT JOIN epg_channel_name n ON n.epg_channel_id = c.id "

        const val CHANNELS_ALL_SQL = CHANNEL_COLUMNS + "ORDER BY c.id"
        const val CHANNELS_SOURCE_SQL = CHANNEL_COLUMNS + "WHERE c.source_id = ?1 ORDER BY c.id"

        /** Picker (S13): normalised-name prefix through the norm index, or xmltv_id prefix. */
        const val FIND_SQL =
            "SELECT id, source_id, xmltv_id, display_name, icon_url, imported, programme_count FROM epg_channel " +
                "WHERE id IN (SELECT epg_channel_id FROM epg_channel_name WHERE norm >= ?1 AND norm < ?2) " +
                "OR xmltv_id LIKE ?3 ESCAPE '\\' " +
                "ORDER BY imported DESC, programme_count DESC, id LIMIT ?4"

        fun escapeLike(s: String): String {
            val sb = StringBuilder(s.length + 4)
            for (c in s) {
                if (c == '%' || c == '_' || c == '\\') sb.append('\\')
                sb.append(c)
            }
            return sb.toString()
        }
    }
}
