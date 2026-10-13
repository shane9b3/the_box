package app.thebox.core.epg.store

import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.epg.store.StoreFixtures.at
import app.thebox.core.epg.store.StoreFixtures.buildAll
import app.thebox.core.epg.store.StoreFixtures.res
import app.thebox.core.epg.store.StoreFixtures.store
import app.thebox.core.epg.testutil.EpgTestDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgReaderTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var reader: EpgReader
    private lateinit var store: EpgStoreImpl

    private val ov = EpgChannelRef(1, "ov")
    private val ph = EpgChannelRef(1, "ph")
    private val bbc = EpgChannelRef(2, "bbc1.uk")
    private val itv = EpgChannelRef(2, "itv1.uk")
    private val shared = EpgChannelRef(3, "shared.id")

    @BeforeEach
    fun setUp() {
        val short = EpgTestDb.xmltv {
            channel("short", "Short Shows")
            for (k in 0 until 20) programme("short", at("06:00") + k * 30, at("06:00") + k * 30 + 30, "Half $k")
        }.writeTo(File(tmp, "short.xml"))
        store = store(File(tmp, "epg"))
        store.buildAll(1L to res("overlaps.xml"), 2L to res("basic.xml"), 3L to res("source_a.xml"), 4L to short)
        reader = store.openReader()!!
    }

    @AfterEach
    fun tearDown() = reader.close()

    private fun window(refs: List<EpgChannelRef>, shift: IntArray, from: Int, to: Int): List<String> {
        val ch = reader.resolve(refs)
        val w = EpgWindow(initialCapacity = 2) // grows
        reader.window(ch, shift, from, to, w)
        return (0 until w.size).map { "${w.channelIndex[it]}:${w.titles[it]}@${w.startMin[it] - at("00:00")}-${w.endMin[it] - at("00:00")}/${w.rawStartMin[it] - at("00:00")}" }
    }

    @Test
    fun windowIncludesProgrammesStartingBeforeIt() {
        assertEquals(
            listOf("0:A@360-420/360", "0:B@420-480/420"),
            window(listOf(ov), intArrayOf(0), at("06:30"), at("07:30")),
        )
    }

    @Test
    fun windowAppliesShiftInDisplayMinutesAndKeepsRawStart() {
        // +90: display = raw + 90. A display window 08:00–09:00 is raw 06:30–07:30.
        assertEquals(
            listOf("0:A@450-510/360", "0:B@510-570/420"),
            window(listOf(ov), intArrayOf(90), at("08:00"), at("09:00")),
        )
        // -90: display 05:00–05:40 is raw 06:30–07:10.
        assertEquals(
            listOf("0:A@270-330/360", "0:B@330-390/420"),
            window(listOf(ov), intArrayOf(-90), at("05:00"), at("05:40")),
        )
    }

    @Test
    fun gapsAndUnknownChannels() {
        assertEquals(emptyList(), window(listOf(ov), intArrayOf(0), at("08:35"), at("08:55")))
        val rows = window(listOf(EpgChannelRef(9, "nope"), ov, EpgChannelRef(1, "missing")), intArrayOf(0, 0, 0), at("09:10"), at("09:20"))
        assertEquals(listOf("1:D@540-600/540"), rows, "-1 channels skipped, channelIndex keeps the caller's position")
    }

    @Test
    fun sixHourBoundaryProgrammeIsFoundThroughMaxLen() {
        // "Off air" 00:00–06:00 starts 330 minutes before the window: found because max_len_min = 360.
        assertEquals(listOf("0:Off air@0-360/0"), window(listOf(ph), intArrayOf(0), at("05:30"), at("05:45")))
        // A channel of 30-minute shows has max_len_min 30, so its scan is short and still exact.
        val short = EpgChannelRef(4, "short")
        assertEquals(30, EpgTestDb.channels(EpgTestDb.currentFile(File(tmp, "epg"))!!, 4).single().maxLenMin)
        assertEquals(listOf("0:Half 3@450-480/450"), window(listOf(short), intArrayOf(0), at("07:40"), at("07:50")))
    }

    @Test
    fun manyChannelsSortedByChannelThenStart() {
        val rows = window(listOf(itv, bbc), intArrayOf(0, 60), at("07:00"), at("09:00"))
        assertEquals(
            listOf(
                "0:ITV1 Show 1@420-480/420", "0:ITV1 Show 2@480-540/480",
                "1:BBC1 Show 0@420-480/360", "1:BBC1 Show 1@480-540/420",
            ),
            rows,
        )
    }

    @Test
    fun atReturnsTheAiringProgrammeOrNull() {
        val ch = reader.resolve(listOf(ov))[0]
        assertEquals("B", reader.at(ch, at("07:15"), 0)!!.title)
        assertNull(reader.at(ch, at("08:45"), 0), "gap")
        assertEquals("A", reader.at(ch, at("07:15"), 60)!!.title, "display 07:15 with +60 is raw 06:15")
        val placeholder = reader.at(reader.resolve(listOf(ph))[0], at("05:59"), 0)!!
        assertEquals("Off air", placeholder.title)
        assertEquals(ProgrammeFlags.CONTINUED, placeholder.flags)
        assertNull(reader.at(-1, at("07:15"), 0))
    }

    @Test
    fun detailCarriesEverythingAndTheShift() {
        val ch = reader.resolve(listOf(bbc))[0]
        val d = reader.detail(ch, at("06:00"), 30)!!
        assertEquals(bbc, d.ref)
        assertEquals(ch, d.channel)
        assertEquals(at("06:00"), d.rawStartMin)
        assertEquals(at("07:00"), d.rawEndMin)
        assertEquals(at("06:30"), d.startMin)
        assertEquals(at("07:30"), d.endMin)
        assertEquals("BBC1 Show 0", d.title)
        assertEquals("Part 0", d.subtitle)
        assertEquals("Description of show 0 on bbc1.uk.", d.description)
        assertEquals(listOf("News", "Factual"), d.categories)
        assertEquals("S01E01", d.episodeLabel)
        assertEquals(1, d.season)
        assertEquals(1, d.episode)
        assertEquals("http://img.example/bbc1.uk/0.jpg", d.iconUrl)
        assertEquals("MPAA:PG-13", d.rating)
        assertEquals("7.5/10", d.starRating)
        assertEquals(2019, d.year)
        assertEquals(ProgrammeFlags.HAS_CATCHUP_ID or ProgrammeFlags.HD, d.flags)
        assertEquals("bbc1.uk-0", d.catchupId)
        assertEquals(Hash64.of("BBC1 Show 0"), d.titleHash)
        assertNull(reader.detail(ch, at("06:01"), 0))
    }

    @Test
    fun searchPrefixDiacriticsQuotesAndNow() {
        // Show 6 airs 12:00–13:00: with now = 12:00 the 11:00–12:00 airings have ended.
        val hits = reader.search("show 6", at("12:00"))
        assertEquals(setOf(EpgChannelRef(2, "bbc1.uk"), EpgChannelRef(2, "itv1.uk"), EpgChannelRef(2, "cnn.us")), hits.map { it.ref }.toSet())
        assertTrue(hits.all { it.rawStartMin == at("12:00") && it.rawEndMin == at("13:00") && it.subtitle == "Part 6" })
        assertEquals(emptyList(), reader.search("show 5", at("12:00")).map { it.title }, "ended at now")
        assertEquals(listOf("A1"), reader.search("TÉLÉ", 0).map { it.title }, "diacritics folded, prefix")
        assertEquals(listOf("A1"), reader.search("television", 0).map { it.title })
        assertEquals(listOf("A1"), reader.search("\"télév", 0).map { it.title }, "quotes in the query are harmless")
        assertEquals(10, reader.search("bbc1 sh", 0).size, "prefix on every token")
        assertEquals(emptyList(), reader.search("  !! ", 0))
        assertEquals(2, reader.search("show", 0, limit = 2).size)
        val hit = reader.search("half 19", 0).single()
        assertEquals(reader.resolve(listOf(EpgChannelRef(4, "short")))[0], hit.channel)
    }

    @Test
    fun resolveIsCaseInsensitiveOnFallback() {
        val exact = reader.resolve(listOf(bbc))[0]
        assertTrue(exact > 0)
        assertEquals(exact, reader.resolve(listOf(EpgChannelRef(2, "BBC1.UK")))[0])
        assertEquals(-1, reader.resolve(listOf(EpgChannelRef(2, "BBC1.UK")), caseInsensitive = false)[0])
        assertEquals(-1, reader.resolve(listOf(EpgChannelRef(1, "bbc1.uk")))[0], "other source")
        assertEquals(bbc, reader.refOf(exact))
        assertNull(reader.refOf(-1))
        assertNull(reader.refOf(99_999))
    }

    @Test
    fun findChannelsByNameOrId() {
        assertEquals(listOf("bbc1.uk"), reader.findChannels("BBC").map { it.xmltvId })
        val bbcInfo = reader.findChannels("bbc one").single()
        assertEquals(listOf("BBC One", "BBC 1"), bbcInfo.displayNames)
        assertEquals("http://img.example/bbc1.png", bbcInfo.iconUrl)
        assertTrue(bbcInfo.imported)
        assertEquals(10, bbcInfo.programmeCount)
        assertEquals(listOf("cnn.us"), reader.findChannels("cnn.").map { it.xmltvId }, "by id prefix")
        assertEquals(listOf("shared.id"), reader.findChannels("SHARED").map { it.xmltvId })
        assertEquals(emptyList(), reader.findChannels("   "))
        assertEquals(emptyList(), reader.findChannels("%"), "LIKE wildcards are escaped")
    }

    @Test
    fun forEachChannelAllOrOneSourceAndEarlyStopReleasesTheLock() {
        val all = ArrayList<EpgChannelInfo>()
        reader.forEachChannel { all += it }
        assertEquals(listOf("ov", "ph", "bbc1.uk", "itv1.uk", "cnn.us", "shared.id", "short"), all.map { it.xmltvId })
        assertEquals(listOf("BBC One", "BBC 1"), all.single { it.xmltvId == "bbc1.uk" }.displayNames)
        val src2 = ArrayList<String>()
        reader.forEachChannel(2L) { src2 += it.xmltvId }
        assertEquals(listOf("bbc1.uk", "itv1.uk", "cnn.us"), src2)

        class Stop : RuntimeException()
        assertFailsWith<Stop> { reader.forEachChannel { throw Stop() } }
        // Another thread can use the same connection right away: the lock and statement were released.
        var count = 0
        val t = thread { store.openReader()!!.use { r -> r.forEachChannel { count++ } } }
        t.join(TimeUnit.SECONDS.toMillis(10))
        assertEquals(7, count)
        assertEquals(3, reader.search("show 9", 0).size)
    }

    @Test
    fun closedReaderRejectsCalls() {
        val r = store.openReader()!!
        r.close()
        assertFailsWith<IllegalStateException> { r.window(intArrayOf(1), intArrayOf(0), 0, 1, EpgWindow()) }
        assertFailsWith<IllegalStateException> { r.search("x", 0) }
    }
}
