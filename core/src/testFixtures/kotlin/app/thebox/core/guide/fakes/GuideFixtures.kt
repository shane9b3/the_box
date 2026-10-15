package app.thebox.core.guide.fakes

import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.RowStatus
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity

/**
 * Shared guide fixtures (docs/design/m3-guide.md §1.1 "Fakes", commit 0). Small, deterministic builders every WP can
 * test against before the real implementations land. Times are epoch minutes.
 */
object GuideFixtures {
    /** 2026-10-14 19:47 UTC: the prototype's "now" (its now line sits at 687.8 px, §5.1). */
    const val NOW_MIN = 29_866_787 // 2026-10-14T19:47Z / 60_000

    fun identity(i: Int, playlistId: Long = 1): ChannelIdentity = ChannelIdentity(playlistId, StableKey("xc:$i"))

    fun channel(
        i: Int,
        playlistId: Long = 1,
        name: String = "Channel $i",
        number: Int? = i + 1,
        archiveDays: Int = 0,
        epg: EpgChannelRef? = EpgChannelRef(1, "ch$i"),
        epgShiftMin: Int = 0,
        epgHasData: Boolean = epg != null,
        logoUrl: String? = null,
    ): GuideChannel {
        val id = identity(i, playlistId)
        return GuideChannel(
            rowId = 1_000L + i,
            identity = id,
            displayName = name,
            number = number,
            logoUrl = logoUrl,
            archiveDays = archiveDays,
            epg = epg,
            epgShiftMin = epgShiftMin,
            epgHasData = epgHasData,
            identityHash = GuideChannel.hashOf(id),
        )
    }

    fun channels(n: Int, archiveEvery: Int = 5): List<GuideChannel> =
        List(n) { channel(it, archiveDays = if (archiveEvery > 0 && it % archiveEvery == 0) 7 else 0) }

    fun info(startMin: Int, endMin: Int, title: String = "Programme $startMin", shiftMin: Int = 0, flags: Int = 0): ProgrammeInfo =
        ProgrammeInfo(
            rawStartMin = startMin - shiftMin, startMin = startMin, endMin = endMin, title = title, subtitle = null,
            description = null, genre = null, episodeLabel = null, flags = flags, titleHash = Hash64.of(title),
        )

    /** One cell of a fixture row. [rawStart] defaults to [start] (no shift). */
    data class Cell(val start: Int, val end: Int, val kind: Byte = CellKind.PROGRAMME, val title: String? = null, val flags: Int = 0, val rawStart: Int = start)

    /**
     * A small synthetic EPG: for each row, programmes of [durationsMin] (cycled, offset by the row index so rows do
     * not align) tiling [fromMin, toMin). Rows listed in [noEpgRows] get one NO_EPG cell.
     */
    fun syntheticRows(
        rows: Int,
        fromMin: Int,
        toMin: Int,
        durationsMin: IntArray = intArrayOf(30, 60, 10, 120, 5, 30),
        noEpgRows: Set<Int> = emptySet(),
    ): List<List<Cell>> = List(rows) { r ->
        if (r in noEpgRows) return@List listOf(Cell(fromMin, toMin, CellKind.NO_EPG))
        val cells = ArrayList<Cell>()
        var t = fromMin - (r * 7) % 30
        var k = r
        while (t < toMin) {
            val d = durationsMin[k++ % durationsMin.size]
            cells += Cell(t, t + d, CellKind.PROGRAMME, "Row $r show ${cells.size}")
            t += d
        }
        cells
    }

    /** A [GuideWindowData] over [rows] (index 0 = lineup row [firstRow]). Widths unmeasured (−1), no time text. */
    fun windowData(
        rows: List<List<Cell>>,
        firstRow: Int = 0,
        fromMin: Int,
        toMin: Int,
        seq: Long = 1,
        lineupVersion: Long = 1,
        rowsVersion: Long = 1,
        generation: Long = 1,
        rowStatus: ByteArray? = null,
        metricsScale: Float = 1f,
        rowEpgBasis: LongArray? = null,
    ): GuideWindowData {
        val n = rows.sumOf { it.size }
        val first = IntArray(rows.size)
        val count = IntArray(rows.size)
        val start = IntArray(n); val end = IntArray(n); val raw = IntArray(n)
        val kind = ByteArray(n); val flags = IntArray(n); val textId = IntArray(n)
        val title = arrayOfNulls<String>(n); val titleWidth = FloatArray(n) { -1f }; val titleKey = LongArray(n)
        val timeText = arrayOfNulls<String>(n); val timeWidth = FloatArray(n) { -1f }
        var c = 0
        rows.forEachIndexed { r, cells ->
            first[r] = c
            count[r] = cells.size
            for (cell in cells) {
                start[c] = cell.start; end[c] = cell.end; raw[c] = cell.rawStart; kind[c] = cell.kind; flags[c] = cell.flags
                textId[c] = if (cell.kind == CellKind.PROGRAMME) c else -1
                title[c] = cell.title
                titleKey[c] = if (cell.title != null) Hash64.of(cell.title) else 0L
                c++
            }
        }
        val status = rowStatus ?: ByteArray(rows.size) { r ->
            if (rows[r].size == 1 && rows[r][0].kind == CellKind.NO_EPG) RowStatus.NO_EPG else RowStatus.OK
        }
        return GuideWindowData(
            seq = seq, lineupVersion = lineupVersion, generation = generation, patchSeq = 0, firstRow = firstRow,
            rowCount = rows.size, fromMin = fromMin, toMin = toMin, rowStatus = status, rowFirstCell = first,
            rowCellCount = count, cellStart = start, cellEnd = end, cellRawStart = raw, cellKind = kind, cellFlags = flags,
            cellTextId = textId, cellTitle = title, cellTitleWidth = titleWidth, cellTitleKey = titleKey,
            cellTimeText = timeText, cellTimeWidth = timeWidth, metricsScale = metricsScale, rowsVersion = rowsVersion,
            rowEpgBasis = rowEpgBasis ?: LongArray(rows.size),
        )
    }

    fun floor30(min: Int): Int = Math.floorDiv(min, 30) * 30
}
