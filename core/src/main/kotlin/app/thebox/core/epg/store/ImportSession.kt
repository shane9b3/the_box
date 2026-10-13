package app.thebox.core.epg.store

import androidx.collection.MutableIntSet
import androidx.collection.MutableObjectIntMap
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.text.NameNormalizer
import app.thebox.core.xmltv.XmltvChannel
import app.thebox.core.xmltv.XmltvHandler
import app.thebox.core.xmltv.XmltvProgramme
import app.thebox.core.xmltv.XmltvTime

internal enum class ImportMode { REBUILD, PATCH, IN_PLACE }

/** Shared id counter for `epg_channel` across the sources of one build. */
internal class IdCounter(var next: Long)

/**
 * Per-source [XmltvHandler] (§3.5.3): stores every `<channel>`, asks the whitelist once before the first
 * programme, decides per distinct `channel` attribute (cached), drops programmes outside the window
 * before their children are parsed, de-duplicates text, splits rows over 6 h and inserts in arrival
 * order in batches. The caller runs [RepairPass] over [acceptedChannels] afterwards.
 *
 * One map holds both the channel rows of this source and the per-attribute decision, so a 50k-channel
 * source keeps one String per id (§4.5). Value = `rowId shl 2 or state`.
 */
internal class ImportSession(
    private val conn: SQLiteConnection,
    private val sourceId: Long,
    private val mode: ImportMode,
    private val resolver: EpgWhitelistResolver?,
    /** PATCH: the xmltv ids to (re)import for this source. */
    private val patchIds: Set<String>?,
    private val windowFromMin: Int,
    private val windowToMin: Int,
    private val texts: TextDedup,
    private val channelIds: IdCounter,
    private val batchRows: Int,
    private val gate: ImportGate,
    private val cancel: CancelCheck,
    private val onBatch: (programmesKept: Int) -> Unit,
) : XmltvHandler {
    private val decision = MutableObjectIntMap<String>(1024)
    val acceptedChannels = MutableIntSet(256)
    private var whitelist: EpgWhitelist? = null
    private var current = -1
    private var rowsInBatch = 0
    private var inTransaction = false

    var channels = 0; private set
    var programmesSeen = 0; private set
    var programmesKept = 0; private set
    var skippedWhitelist = 0; private set
    var skippedWindow = 0; private set
    var split = 0; private set
    var badTimes = 0; private set

    private val statements = StatementCache(conn)
    private val insertReplace: SQLiteStatement = statements[
        "INSERT OR REPLACE INTO programme(epg_channel_id, start_min, end_min, text_id, flags, catchup_id) VALUES (?,?,?,?,?,?)",
    ]
    private val insertIgnore: SQLiteStatement = statements[
        "INSERT OR IGNORE INTO programme(epg_channel_id, start_min, end_min, text_id, flags, catchup_id) VALUES (?,?,?,?,?,?)",
    ]

    fun begin() {
        if (mode == ImportMode.PATCH) {
            // Requested refs lose their old rows even if the new file has no programme for them.
            for (id in patchIds.orEmpty()) {
                val row = lookupChannel(id)
                if (row > 0) deleteProgrammes(row)
            }
        }
        beginTx()
    }

    /** Commits the open batch and makes sure the whitelist was asked even for a source without programmes. */
    fun finish() {
        ensureWhitelist()
        commitTx()
        statements.closeAll()
    }

    /** Leaves the connection without an open transaction after a failure. */
    fun abort() {
        if (inTransaction) {
            try { conn.exec("ROLLBACK") } catch (_: Throwable) { }
            inTransaction = false
        }
        statements.closeAll()
    }

    override fun onChannel(c: XmltvChannel) {
        channels++
        if (mode == ImportMode.PATCH && patchIds?.contains(c.id) != true) return
        val v = decision.getOrDefault(c.id, ABSENT)
        val known = if (v != ABSENT) v ushr 2 else if (mode != ImportMode.REBUILD) lookupChannel(c.id) else 0
        val displayName = c.displayNames.firstOrNull()?.name ?: ""
        val row: Int
        if (known > 0) {
            row = known
            if (displayName.isNotEmpty() || c.iconUrl != null) {
                val st = statements["UPDATE epg_channel SET display_name = CASE WHEN ?1 = '' THEN display_name ELSE ?1 END, icon_url = COALESCE(?2, icon_url) WHERE id = ?3 AND (display_name = '' OR ?4)"]
                st.bindText(1, displayName)
                st.bindTextOrNull(2, c.iconUrl)
                st.bindLong(3, row.toLong())
                // In-place/patch refresh the row; a duplicate <channel> inside one rebuild source keeps the first.
                st.bindLong(4, if (mode == ImportMode.REBUILD) 0 else 1)
                st.run()
            }
            if (v == ABSENT) decision[c.id] = row shl 2 or KNOWN
        } else {
            row = insertChannel(c.id, displayName, c.iconUrl)
            decision[c.id] = row shl 2 or KNOWN
        }
        if (c.displayNames.isNotEmpty()) {
            val st = statements["INSERT OR IGNORE INTO epg_channel_name(epg_channel_id, name, norm, lang) VALUES (?,?,?,?)"]
            for (n in c.displayNames) {
                st.bindLong(1, row.toLong())
                st.bindText(2, n.name)
                st.bindText(3, NameNormalizer.forMatch(n.name))
                st.bindTextOrNull(4, n.lang)
                st.run()
            }
        }
    }

    override fun acceptProgramme(channelId: String, startMin: Int, stopMin: Int): Boolean {
        programmesSeen++
        ensureWhitelist()
        var v = decision.getOrDefault(channelId, ABSENT)
        if (v == ABSENT || v and 3 == KNOWN) v = decide(channelId, v)
        if (v and 3 == REJECTED) {
            skippedWhitelist++
            return false
        }
        if ((stopMin != XmltvTime.MISSING && stopMin <= windowFromMin) || startMin >= windowToMin) {
            skippedWindow++
            return false
        }
        current = v ushr 2
        return true
    }

    override fun onProgramme(p: XmltvProgramme) {
        val textId = texts.idFor(p)
        val ch = current.toLong()
        val start = p.startMin
        if (!p.hasStop()) {
            insert(insertReplace, ch, start, start + 1, textId, p.flags or ProgrammeFlags.STOP_INFERRED, p.catchupId)
        } else if (p.stopMin - start > EpgSchema.MAX_ROW_MIN) {
            split++
            var s = start
            while (s < p.stopMin) {
                val e = minOf(s + EpgSchema.MAX_ROW_MIN, p.stopMin)
                var f = p.flags
                if (s != start) f = f or ProgrammeFlags.CONTINUATION
                if (e != p.stopMin) f = f or ProgrammeFlags.CONTINUED
                // A continuation segment never replaces a real programme that starts at the same minute.
                insert(if (s == start) insertReplace else insertIgnore, ch, s, e, textId, f, p.catchupId)
                s = e
            }
        } else {
            insert(insertReplace, ch, start, p.stopMin, textId, p.flags, p.catchupId)
        }
        programmesKept++
        if (rowsInBatch >= batchRows) {
            commitTx()
            gate.awaitClear()
            cancel.throwIfCancelled()
            onBatch(programmesKept)
            beginTx()
        }
    }

    override fun onBadTime(channelId: String) {
        programmesSeen++
        badTimes++
    }

    override fun onEnd() = ensureWhitelist()

    private fun insert(st: SQLiteStatement, ch: Long, start: Int, end: Int, textId: Int, flags: Int, catchupId: String?) {
        st.bindLong(1, ch)
        st.bindLong(2, start.toLong())
        st.bindLong(3, end.toLong())
        st.bindLong(4, textId.toLong())
        st.bindLong(5, flags.toLong())
        st.bindTextOrNull(6, catchupId)
        st.run()
        rowsInBatch++
    }

    private fun ensureWhitelist() {
        if (whitelist != null) return
        whitelist = when (mode) {
            ImportMode.PATCH -> EpgWhitelist { patchIds?.contains(it) == true }
            else -> {
                val r = resolver ?: return run { whitelist = EpgWhitelist.ALL }
                val seq = ChannelSequence()
                try {
                    r.whitelistFor(sourceId, seq)
                } finally {
                    seq.close()
                }
            }
        }
    }

    private fun decide(attr: String, v: Int): Int {
        val wl = whitelist ?: EpgWhitelist.NONE
        val known = if (v != ABSENT) v ushr 2 else 0
        if (!wl.accepts(attr)) {
            val r = known shl 2 or REJECTED
            decision[attr] = r
            return r
        }
        var row = known
        if (row == 0 && mode != ImportMode.REBUILD) row = lookupChannel(attr)
        if (row == 0) row = insertChannel(attr, "", null)
        val st = statements["UPDATE epg_channel SET imported = 1 WHERE id = ?"]
        st.bindLong(1, row.toLong())
        st.run()
        if (mode != ImportMode.REBUILD) deleteProgrammes(row)
        acceptedChannels.add(row)
        val r = row shl 2 or ACCEPTED
        decision[attr] = r
        return r
    }

    private fun lookupChannel(xmltvId: String): Int {
        val st = statements["SELECT id FROM epg_channel WHERE source_id = ? AND xmltv_id = ?"]
        st.bindLong(1, sourceId)
        st.bindText(2, xmltvId)
        return try { if (st.step()) st.getLong(0).toInt() else 0 } finally { st.reset() }
    }

    private fun insertChannel(xmltvId: String, displayName: String, icon: String?): Int {
        val id = channelIds.next++
        val st = statements["INSERT INTO epg_channel(id, source_id, xmltv_id, display_name, icon_url) VALUES (?,?,?,?,?)"]
        st.bindLong(1, id)
        st.bindLong(2, sourceId)
        st.bindText(3, xmltvId)
        st.bindText(4, displayName)
        st.bindTextOrNull(5, icon)
        st.run()
        return id.toInt()
    }

    private fun deleteProgrammes(row: Int) {
        val st = statements["DELETE FROM programme WHERE epg_channel_id = ?"]
        st.bindLong(1, row.toLong())
        st.run()
    }

    private fun beginTx() {
        conn.exec(if (mode == ImportMode.REBUILD) "BEGIN" else "BEGIN IMMEDIATE")
        inTransaction = true
        rowsInBatch = 0
    }

    private fun commitTx() {
        if (!inTransaction) return
        conn.exec("COMMIT")
        inTransaction = false
    }

    /**
     * The source's channels as stored so far, backed by the building database and valid only during
     * the whitelist call (contract of [EpgWhitelistResolver]). Statements are reset when the call returns,
     * even if the resolver stopped iterating early.
     */
    private inner class ChannelSequence : Sequence<EpgChannelInfo> {
        private val list = conn.prepare(
            "SELECT id, xmltv_id, display_name, icon_url, imported, programme_count FROM epg_channel WHERE source_id = ? ORDER BY id",
        )
        private val names = conn.prepare("SELECT name FROM epg_channel_name WHERE epg_channel_id = ?")

        override fun iterator(): Iterator<EpgChannelInfo> = iterator {
            list.reset()
            list.bindLong(1, sourceId)
            while (list.step()) {
                val id = list.getLong(0)
                val display = list.getText(2)
                val all = ArrayList<String>(2)
                if (display.isNotEmpty()) all.add(display)
                names.bindLong(1, id)
                try {
                    while (names.step()) {
                        val n = names.getText(0)
                        if (n != display) all.add(n)
                    }
                } finally {
                    names.reset()
                }
                yield(
                    EpgChannelInfo(
                        sourceId = sourceId,
                        xmltvId = list.getText(1),
                        displayNames = all,
                        iconUrl = list.textOrNull(3),
                        imported = list.getLong(4) != 0L,
                        programmeCount = list.getLong(5).toInt(),
                    ),
                )
            }
            list.reset()
        }

        fun close() {
            try { list.close() } catch (_: Throwable) { }
            try { names.close() } catch (_: Throwable) { }
        }
    }

    companion object {
        private const val ABSENT = Int.MIN_VALUE
        private const val KNOWN = 0
        private const val ACCEPTED = 1
        private const val REJECTED = 2
    }
}
