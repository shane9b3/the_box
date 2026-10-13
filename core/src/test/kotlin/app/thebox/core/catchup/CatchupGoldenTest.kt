package app.thebox.core.catchup

import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.source.XtreamStreamUrl
import app.thebox.core.xtream.XtreamStreamRef
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Catch-up golden matrix (docs/design/m1-data-layer.md §5.4). Expected values in `catchup/golden.tsv` come from an
 * independent reference implementation of §3.8.4–3.8.6, not from this builder.
 */
class CatchupGoldenTest {
    private val builder = CatchupUrlBuilderImpl()

    private fun rows(): List<Map<String, String>> {
        val text = javaClass.getResourceAsStream("/catchup/golden.tsv")!!.bufferedReader().use { it.readText() }
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first().split('\t')
        return lines.drop(1).map { line ->
            val cells = line.split('\t')
            require(cells.size == header.size) { "bad row: $line" }
            header.zip(cells).toMap()
        }
    }

    private fun zone(s: String): ZoneId? = if (s == "-") null else ZoneId.of(s)
    private fun opt(s: String): String? = if (s == "-") null else s

    @TestFactory
    fun golden(): List<DynamicTest> = rows().map { r ->
        DynamicTest.dynamicTest(r.getValue("id")) {
            val (padB, padA) = r.getValue("padding").split('/').map { it.toLong() }
            val tb = TimeBase(
                serverZone = zone(r.getValue("zone")),
                clockSkew = Duration.ofSeconds(r.getValue("skew").toLong()),
                correctionHours = r.getValue("correction").toDouble(),
                tokenZone = zone(r.getValue("tokenZone")),
                paddingBefore = Duration.ofSeconds(padB),
                paddingAfter = Duration.ofSeconds(padA),
                templateZone = zone(r.getValue("templateZone")),
            )
            val live = r.getValue("liveUrl")
            val ref = XtreamStreamUrl.parse(live, allowShort = false)?.let {
                XtreamStreamRef(it.base, it.username, it.password, it.streamId, it.extension ?: "ts")
            }
            val target = CatchupTarget(
                liveUrl = live,
                headers = mapOf("User-Agent" to "UA"),
                catchup = Catchup(CatchupMode.valueOf(r.getValue("mode")), days = 7, source = opt(r.getValue("source"))),
                xtream = ref,
            )
            val window = ArchiveWindow(
                programmeStart = Instant.parse(r.getValue("start")),
                programmeStop = opt(r.getValue("stop"))?.let { Instant.parse(it) },
                catchupId = opt(r.getValue("catchupId")),
                deviceNow = Instant.parse(r.getValue("now")),
            )
            val variant = r.getValue("variant")
            val build = builder.build(target, tb, window, variant)
            val ok = assertIs<CatchupBuild.Ok>(build, "$build")
            val req = ok.request
            assertEquals(r.getValue("expectedUrl"), req.url)
            assertEquals(Instant.parse(r.getValue("expectedStart")), req.requestStart)
            assertEquals(opt(r.getValue("expectedEnd"))?.let { Instant.parse(it) }, req.requestEnd)
            val seek = opt(r.getValue("expectedSeek"))?.split("..")?.let { (a, b) -> a.toLong()..b.toLong() }
            assertEquals(seek, req.nativeSeekRange)
            assertEquals(variant, req.variant)
            assertEquals(target.headers, req.headers)
        }
    }

    @Test
    fun matrixCoversTheRequiredAxes() {
        val rows = rows()
        assertTrue(rows.size >= 200)
        val variants = rows.map { it.getValue("variant") }.toSet()
        assertEquals(CatchupVariants.run { setOf(XC_PATH, XC_PHP, XC_PATH_SECONDS, DEFAULT, APPEND, SHIFT, FLUSSONIC_RANGE, FLUSSONIC_ABS, FLUSSONIC_KODI, VOD) }, variants)
        val zones = rows.map { it.getValue("zone") }.toSet()
        assertTrue(zones.containsAll(setOf("UTC", "Europe/London", "America/New_York", "Asia/Kolkata", "Asia/Kathmandu")))
        assertEquals(setOf("0", "-4.0", "1.5"), rows.map { it.getValue("correction") }.toSet())
        assertEquals(setOf("0/0", "60/300"), rows.map { it.getValue("padding") }.toSet())
        assertEquals(setOf("0", "95"), rows.map { it.getValue("skew") }.toSet())
    }

    @Test
    fun correctionIsSubtracted() {
        // -4.0 requests 4 h later; +1.5 requests 1.5 h earlier (Kodi's code, protocols §4.4).
        val byId = rows().associateBy { it.getValue("id") }
        val base = Instant.parse(byId.getValue("xc-path@london c=0 p=0/0 k=0").getValue("expectedStart"))
        val minus4 = Instant.parse(byId.getValue("xc-path@london c=-4.0 p=0/0 k=0").getValue("expectedStart"))
        val plus15 = Instant.parse(byId.getValue("xc-path@london c=1.5 p=0/0 k=0").getValue("expectedStart"))
        assertEquals(4 * 3600L, Duration.between(base, minus4).seconds)
        assertEquals((-1.5 * 3600).roundToLong(), Duration.between(base, plus15).seconds)
    }
}
