package app.thebox.data.sports

import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.EspnSummaryDto
import app.thebox.core.sports.hub.NflHubFixtures
import app.thebox.data.sports.EspnHubTestSupport.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.management.ManagementFactory

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §5.3, §5.4, §8.2 `EspnSummaryDecodeTest`) The one capped streaming decode
 * ([EspnHttp.decodeCapped], the transport's own) over each fixture and over synthetic paddings: the skipped sections are
 * read through (a counting stream sees every byte; no early stop, ESPN's key order varies) but never built, the cap
 * turns into `Bad` with no value, `header` first or last decodes equally, and the bytes allocated per decode are
 * recorded and bounded (the yardstick the Fire TV device check compares against, arch m10).
 */
class EspnSummaryDecodeTest {
    private val cap = SportsConstants.ESPN_SUMMARY_MAX_BYTES

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) n++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) n += it }
    }

    private fun decode(body: String): Pair<EspnHttp.Decoded<EspnSummaryDto>, Long> {
        val bytes = body.encodeToByteArray()
        val counting = Counting(ByteArrayInputStream(bytes))
        val d = EspnHttp.decodeCapped(counting, EspnSummaryDto.serializer(), cap)
        return d to counting.n
    }

    private fun ok(body: String): EspnSummaryDto {
        val (d, read) = decode(body)
        assertTrue("decoded", d is EspnHttp.Decoded.Ok)
        assertEquals("read to the end (drained): no early stop", body.encodeToByteArray().size.toLong(), read)
        return (d as EspnHttp.Decoded.Ok).value
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** The bytes allocated on this thread by [block] (HotSpot's per-thread counter), the median of 5 runs after a warm-up. */
    private fun allocated(block: () -> Unit): Long {
        repeat(3) { block() }
        val id = Thread.currentThread().id
        return List(5) {
            val before = threads.getThreadAllocatedBytes(id)
            block()
            threads.getThreadAllocatedBytes(id) - before
        }.sorted()[2]
    }

    @Test fun everyFixtureDecodes() {
        for (path in NflHubFixtures.SUMMARIES) {
            val dto = ok(text(path))
            assertTrue(path, dto.header?.competitions?.firstOrNull()?.competitors?.size == 2)
        }
        val mnf = ok(text(NflHubFixtures.FINAL_MNF))
        assertEquals(4, mnf.videos!!.size)
        assertTrue(mnf.scoringPlays!!.isNotEmpty())
        val pre = ok(text(NflHubFixtures.PRE_GB_LAR))
        assertEquals("absent in a pre-game summary", null, pre.scoringPlays)
        assertTrue(pre.lastFiveGames!!.isNotEmpty())
    }

    @Test fun aSyntheticOnePointFiveMegabyteDrivesDecodes() {
        val body = EspnHubTestSupport.padded(text(NflHubFixtures.FINAL_MNF), kb = 1_536)
        assertTrue(body.length in 1_500_000..cap.toInt())
        val dto = ok(body)
        assertEquals(4, dto.videos!!.size)
    }

    @Test fun overTwoMebibytesIsBadWithNoValue() {
        val body = EspnHubTestSupport.padded(text(NflHubFixtures.FINAL_MNF), kb = 2_100, first = false)
        val (d, read) = decode(body)
        assertTrue(d is EspnHttp.Decoded.Bad)
        assertTrue((d as EspnHttp.Decoded.Bad).overCap)
        assertTrue("stopped just past the cap: $read", read in cap..cap + 64 * 1024)
    }

    /**
     * (Code review 2026-11-26, data #11) The live capture's other top-level sections (§18.4: `odds`, `againstTheSpread`,
     * `standings`, `format`, `meta`, `broadcasts`, `wallclockAvailable`), in the blanked shape `reduce_summary.py` now
     * writes for the odds ones (no odds value), before and after `header`: skipped, and the DTO equals the plain one.
     */
    @Test fun theLiveCapturesOtherTopLevelSectionsAreSkipped() {
        val tree = Json.parseToJsonElement(text(NflHubFixtures.LIVE_GB_LAR_1)).jsonObject
        val extra = Json.parseToJsonElement(
            """{"odds":[{"provider":{"id":"x","name":"x"},"details":"x","overUnder":0,"spread":0}],
               "againstTheSpread":[{"team":{"id":"x","abbreviation":"x"},"records":[{"id":"x","summary":"x"}]}],
               "standings":{"groups":[{"standings":{"entries":[]}}]},"format":{"regulation":{"periods":4}},
               "meta":{"gp_topic":"x","lastPlayWallClock":"x"},"broadcasts":[{"media":{"shortName":"x"}}],"wallclockAvailable":true}""",
        ).jsonObject
        val before = JsonObject(extra + tree).toString()
        val after = JsonObject(tree + extra).toString()
        val id = app.thebox.core.sports.GameId(app.thebox.core.sports.League.NFL, "401873079")
        fun mapped(dto: EspnSummaryDto) = app.thebox.core.sports.hub.EspnDetailMapper.detail(dto, id, null)
        val plain = mapped(ok(tree.toString()))
        assertTrue(plain != null)
        assertEquals(plain, mapped(ok(before)))
        assertEquals(plain, mapped(ok(after)))
        assertEquals(boxOf(ok(tree.toString())), boxOf(ok(after)))
    }

    private fun boxOf(dto: EspnSummaryDto) = app.thebox.core.sports.hub.EspnDetailMapper.box(dto)

    @Test fun headerFirstOrLastDecodesEqually() {
        val tree = Json.parseToJsonElement(text(NflHubFixtures.FINAL_PIT_PHI)).jsonObject
        val header = tree["header"]!!
        val rest = tree.filterKeys { it != "header" }
        val first = JsonObject(linkedMapOf("header" to header) + rest).toString()
        val last = JsonObject(rest + ("header" to header)).toString()
        val a = ok(first)
        val b = ok(last)
        val json = NflHubFixtures.JSON
        assertEquals(json.encodeToString(EspnSummaryDto.serializer(), a), json.encodeToString(EspnSummaryDto.serializer(), b))
    }

    /**
     * The decode's allocation churn (strings the lexer makes while skipping), recorded and bounded: < 8 MB for a 1.5 MB
     * `drives` (§8.2), and the kept DTO is the same whatever the padding (the skipped sections are not retained).
     */
    @Test fun allocatedBytesPerDecodeAreRecordedAndBounded() {
        val base = text(NflHubFixtures.FINAL_MNF)
        val sizes = linkedMapOf("largest fixture (MNF)" to base)
        for (kb in listOf(400, 600, 1_536)) sizes["MNF + ${kb} KB drives"] = EspnHubTestSupport.padded(base, kb)
        val report = StringBuilder()
        var plain = 0L
        for ((label, body) in sizes) {
            val bytes = body.encodeToByteArray()
            val alloc = allocated { EspnHttp.decodeCapped(ByteArrayInputStream(bytes), EspnSummaryDto.serializer(), cap) }
            if (plain == 0L) plain = alloc
            report.append("$label: ${bytes.size} B decoded, ${alloc} B allocated\n")
            if (bytes.size > 1_500_000) assertTrue("$label: $alloc", alloc < 8L * 1024 * 1024)
        }
        println("EspnSummaryDecodeTest allocation:\n$report")
        val json = NflHubFixtures.JSON
        val kept = json.encodeToString(EspnSummaryDto.serializer(), ok(base))
        val keptPadded = json.encodeToString(EspnSummaryDto.serializer(), ok(EspnHubTestSupport.padded(base, 600)))
        assertEquals("the padding is skipped, not kept", kept, keptPadded)
        println("EspnSummaryDecodeTest kept DTO (re-encoded): ${kept.length} B")
    }
}
