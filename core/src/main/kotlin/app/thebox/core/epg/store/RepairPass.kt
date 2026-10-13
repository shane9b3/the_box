package app.thebox.core.epg.store

import androidx.sqlite.SQLiteConnection
import app.thebox.core.epg.ProgrammeFlags

/**
 * Per-channel repair after a source is loaded (§3.5.5). Reads the channel's rows in PK order into
 * reusable arrays, then for each row with next start *n*:
 *
 * 1. (review S12) while the next row is a CONTINUATION segment that starts inside this real row's
 *    unclipped span, delete it: real programmes win over the 6 h segments of a long placeholder;
 * 2. STOP_INFERRED: `end = n ? min(n, start + 360) : min(start + 60, windowTo)`;
 *    else `end > n` → `end = n`, CLIPPED;
 * 3. `end ≤ start` → delete;
 * 4. CONTINUED without a CONTINUATION next → clear CONTINUED.
 *
 * Then `programme_count`, `max_len_min`, `first_start_min`, `last_end_min` go to `epg_channel`.
 * Memory is bounded by the largest channel.
 */
internal class RepairPass(conn: SQLiteConnection, private val windowToMin: Int) {
    private val select = conn.prepare("SELECT start_min, end_min, flags FROM programme WHERE epg_channel_id = ? ORDER BY start_min")
    private val update = conn.prepare("UPDATE programme SET end_min = ?, flags = ? WHERE epg_channel_id = ? AND start_min = ?")
    private val delete = conn.prepare("DELETE FROM programme WHERE epg_channel_id = ? AND start_min = ?")
    private val stats = conn.prepare(
        "UPDATE epg_channel SET programme_count = ?, max_len_min = ?, first_start_min = ?, last_end_min = ? WHERE id = ?",
    )

    private var start = IntArray(256)
    private var end = IntArray(256)
    private var flags = IntArray(256)
    private var origEnd = IntArray(256)
    private var origFlags = IntArray(256)
    private var deleted = BooleanArray(256)

    var clipped = 0
        private set
    var deletedRows = 0
        private set

    fun repairChannel(channelId: Int) {
        val n = load(channelId)
        var i = 0
        while (i < n) {
            if (deleted[i]) {
                i++
                continue
            }
            val real = flags[i] and ProgrammeFlags.CONTINUATION == 0
            var j = i + 1
            while (j < n && (deleted[j] || (real && isContinuation(j) && start[j] < origEnd[i] && !isInferred(i)))) {
                deleted[j] = true
                j++
            }
            val hasNext = j < n
            val next = if (hasNext) start[j] else 0
            if (flags[i] and ProgrammeFlags.STOP_INFERRED != 0) {
                end[i] = if (hasNext) minOf(next, start[i] + EpgSchema.MAX_ROW_MIN) else minOf(start[i] + EpgSchema.LAST_INFERRED_MIN, windowToMin)
            } else if (hasNext && end[i] > next) {
                end[i] = next
                flags[i] = flags[i] or ProgrammeFlags.CLIPPED
                clipped++
            }
            if (end[i] <= start[i]) {
                deleted[i] = true
            } else if (flags[i] and ProgrammeFlags.CONTINUED != 0 && !(hasNext && isContinuation(j))) {
                flags[i] = flags[i] and ProgrammeFlags.CONTINUED.inv()
            }
            i = if (deleted[i]) i + 1 else j
        }
        write(channelId, n)
    }

    private fun isContinuation(k: Int) = flags[k] and ProgrammeFlags.CONTINUATION != 0
    private fun isInferred(k: Int) = flags[k] and ProgrammeFlags.STOP_INFERRED != 0

    private fun load(channelId: Int): Int {
        var n = 0
        select.bindLong(1, channelId.toLong())
        try {
            while (select.step()) {
                if (n == start.size) grow()
                start[n] = select.getLong(0).toInt()
                end[n] = select.getLong(1).toInt()
                flags[n] = select.getLong(2).toInt()
                origEnd[n] = end[n]
                origFlags[n] = flags[n]
                deleted[n] = false
                n++
            }
        } finally {
            select.reset()
        }
        return n
    }

    private fun write(channelId: Int, n: Int) {
        var count = 0
        var maxLen = 0
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        val ch = channelId.toLong()
        for (k in 0 until n) {
            if (deleted[k]) {
                delete.bindLong(1, ch)
                delete.bindLong(2, start[k].toLong())
                delete.run()
                deletedRows++
                continue
            }
            if (end[k] != origEnd[k] || flags[k] != origFlags[k]) {
                update.bindLong(1, end[k].toLong())
                update.bindLong(2, flags[k].toLong())
                update.bindLong(3, ch)
                update.bindLong(4, start[k].toLong())
                update.run()
            }
            count++
            val len = end[k] - start[k]
            if (len > maxLen) maxLen = len
            if (start[k] < first) first = start[k]
            if (end[k] > last) last = end[k]
        }
        stats.bindLong(1, count.toLong())
        stats.bindLong(2, maxLen.toLong())
        if (count == 0) stats.bindNull(3) else stats.bindLong(3, first.toLong())
        if (count == 0) stats.bindNull(4) else stats.bindLong(4, last.toLong())
        stats.bindLong(5, ch)
        stats.run()
    }

    private fun grow() {
        val c = start.size * 2
        start = start.copyOf(c)
        end = end.copyOf(c)
        flags = flags.copyOf(c)
        origEnd = origEnd.copyOf(c)
        origFlags = origFlags.copyOf(c)
        deleted = deleted.copyOf(c)
    }

    fun close() {
        select.close()
        update.close()
        delete.close()
        stats.close()
    }
}
