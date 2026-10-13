package app.thebox.core.identity

import app.thebox.core.common.Hash64
import app.thebox.core.source.ParsedChannel

/**
 * `contentHash`, `urlHash` and `nameHash` (docs/design/m1-data-layer.md §3.3.1). The channel passed in is
 * the stored form: its `url` is already the template. Position is excluded. Persisted: never reorder the
 * fields without a migration that recomputes `channel.content_hash`.
 */
object ContentHasher {
    fun contentHash(c: ParsedChannel, groupKey: String): Long {
        var h = Hash64.OFFSET
        h = Hash64.long(h, c.kind.ordinal.toLong())
        h = Hash64.str(h, c.name)
        h = Hash64.str(h, c.url)
        h = Hash64.str(h, groupKey)
        h = Hash64.str(h, c.groupTitle)
        h = Hash64.long(h, c.extraGroups.size.toLong())
        for (g in c.extraGroups) h = Hash64.str(h, g)
        h = Hash64.str(h, c.logoUrl)
        h = Hash64.int(h, c.number)
        h = Hash64.str(h, c.tvgId)
        h = Hash64.str(h, c.tvgName)
        h = Hash64.int(h, c.epgShiftMinutes)
        h = map(h, c.headers)
        val k = c.catchup
        h = Hash64.str(h, k.type)
        h = Hash64.str(h, k.source)
        h = Hash64.int(h, k.days)
        h = Hash64.double(h, k.correctionHours)
        h = Hash64.int(h, k.timeshiftDays)
        h = Hash64.int(h, k.tvgRecDays)
        h = nullableLong(h, k.catchupTimeSec)
        h = if (k.xtreamTvArchive == null) Hash64.str(h, null) else Hash64.bool(h, k.xtreamTvArchive)
        h = Hash64.int(h, k.xtreamArchiveDays)
        h = nullableLong(h, c.xtreamStreamId)
        h = Hash64.str(h, c.containerExtension)
        h = Hash64.bool(h, c.adult)
        h = nullableLong(h, c.durationSec)
        h = map(h, c.kodiProps)
        h = map(h, c.vlcOpts)
        h = map(h, c.attributes)
        return h
    }

    fun urlHash(urlTemplate: String): Long = Hash64.of(urlTemplate)

    fun nameHash(name: String): Long = Hash64.of(name)

    /** Exactly [Hash64.map], without sorting (and allocating) for the common empty and single-entry maps. */
    internal fun map(h: Long, m: Map<String, String>): Long = when (m.size) {
        0 -> Hash64.long(h, 0)
        1 -> m.entries.first().let { (k, v) -> Hash64.str(Hash64.str(Hash64.long(h, 1), k), v) }
        else -> Hash64.map(h, m)
    }

    private fun nullableLong(h: Long, v: Long?): Long = if (v == null) Hash64.str(h, null) else Hash64.long(h, v)
}
