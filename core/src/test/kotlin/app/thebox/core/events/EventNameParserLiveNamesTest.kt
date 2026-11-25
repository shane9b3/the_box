package app.thebox.core.events

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A private check over a real playlist's live names (docs/design/m6-event-channels.md §2.1). Skipped unless
 * `THEBOX_EVENT_NAMES` names a local TSV of `name<TAB>group[<TAB>…]` rows (never committed; channel names only). The
 * optional `THEBOX_EVENT_NOW_MS` is the playlist's `last_refresh_at`. It writes a tally to `<file>.report.txt` and one
 * line per name to `<file>.parsed.tsv`, and asserts that ordinary channels never read as events or empty slots: every
 * group with a hit is a sports or PPV group (arch review: parser precision).
 */
class EventNameParserLiveNamesTest {
    @Test
    fun liveNames() {
        val path = System.getenv("THEBOX_EVENT_NAMES")
        assumeTrue(path != null && File(path).isFile, "set THEBOX_EVENT_NAMES to a TSV of live channel names")
        val file = File(path!!)
        val now = System.getenv("THEBOX_EVENT_NOW_MS")?.toLongOrNull() ?: System.currentTimeMillis()
        val ctx = EventParseContext(now, ZoneId.of("America/New_York"))
        val shapes = sortedMapOf<String, Int>()
        val empties = sortedMapOf<String, Int>()
        val falsePositives = ArrayList<String>()
        val groupRows = HashMap<String, Int>()
        val groupHits = HashMap<String, Int>()
        var events = 0
        var empty = 0
        var none = 0
        var noneWithClock = 0
        var slotMismatch = 0
        val out = StringBuilder()
        file.forEachLine { line ->
            val cols = line.split('\t')
            val name = cols[0]
            val group = cols.getOrElse(1) { "" }
            val r = EventNameParser.parse(name, ctx)
            groupRows.merge(group, 1, Int::plus)
            if (r != ParsedName.None) groupHits.merge(group, 1, Int::plus)
            when (r) {
                is ParsedName.Event -> {
                    events++
                    shapes.merge("${r.shape} ${r.zone} ${r.zoneId}", 1, Int::plus)
                    if (EventNameParser.slotOf(name) != r.slot) slotMismatch++
                    out.append("EVENT\t${r.shape}\t${r.zone}\t$name\n")
                }
                is ParsedName.Empty -> {
                    empty++
                    val kind = when {
                        name.trimEnd().endsWith("NO EVENT", ignoreCase = true) -> "NO EVENT"
                        name.trimEnd().endsWith("Offline", ignoreCase = true) -> "Offline"
                        name.trimEnd().endsWith(")") -> "ISO placeholder"
                        name.trimEnd().endsWith(":") || name.trimEnd().endsWith("|") -> "bare"
                        else -> "other"
                    }
                    empties.merge(kind, 1, Int::plus)
                    if (!EventNameParser.isEmptySlot(name) || EventNameParser.slotOf(name) != r.slot) slotMismatch++
                    out.append("EMPTY\t$kind\t\t$name\n")
                }
                ParsedName.None -> {
                    none++
                    if (hasClock(name)) noneWithClock++
                    out.append("NONE\t\t\t$name\n")
                }
            }
        }
        // A hit outside a sports or PPV group is an ordinary channel read as an event slot. (Mixed sports groups are
        // expected: "NCAAF 097" without a colon, "Sky Sport Mix" and the unhandled §3.6 shapes sit next to the slots.)
        for ((g, hits) in groupHits) {
            val lower = g.lowercase()
            if ("sport" !in lower && "ppv" !in lower) falsePositives += "$g: $hits of ${groupRows[g]} rows"
        }
        val report = buildString {
            appendLine("now=$now events=$events empty=$empty none=$none noneWithClock=$noneWithClock slotMismatch=$slotMismatch")
            appendLine("shapes:"); shapes.forEach { (k, v) -> appendLine("  $k: $v") }
            appendLine("empties:"); empties.forEach { (k, v) -> appendLine("  $k: $v") }
            appendLine("groups with hits: ${groupHits.size}")
            groupHits.toSortedMap().forEach { (g, h) -> appendLine("  $g: $h / ${groupRows[g]}") }
            appendLine("false positives: ${falsePositives.size}"); falsePositives.forEach { appendLine("  $it") }
        }
        File(file.path + ".report.txt").writeText(report)
        File(file.path + ".parsed.tsv").writeText(out.toString())
        assertEquals(emptyList(), falsePositives)
        assertEquals(0, slotMismatch)
    }

    private fun hasClock(s: String): Boolean {
        for (i in 1 until s.length - 2) {
            if (s[i] == ':' && s[i - 1].isDigit() && s[i + 1] in '0'..'5' && s[i + 2].isDigit()) return true
        }
        return false
    }
}
