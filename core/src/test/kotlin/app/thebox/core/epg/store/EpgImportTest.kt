package app.thebox.core.epg.store

import app.thebox.core.common.CancelCheck
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.ProgrammeFlags.CLIPPED
import app.thebox.core.epg.ProgrammeFlags.CONTINUATION
import app.thebox.core.epg.ProgrammeFlags.CONTINUED
import app.thebox.core.epg.ProgrammeFlags.STOP_INFERRED
import app.thebox.core.epg.store.StoreFixtures.ALL
import app.thebox.core.epg.store.StoreFixtures.at
import app.thebox.core.epg.store.StoreFixtures.buildAll
import app.thebox.core.epg.store.StoreFixtures.ids
import app.thebox.core.epg.store.StoreFixtures.request
import app.thebox.core.epg.store.StoreFixtures.res
import app.thebox.core.epg.store.StoreFixtures.store
import app.thebox.core.epg.testutil.EpgTestDb
import app.thebox.core.epg.testutil.EpgTestDb.ProgrammeRow
import app.thebox.core.epg.testutil.XmltvGenerator
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgImportTest {
    @TempDir
    lateinit var tmp: File

    private val dir get() = File(tmp, "epg")

    private fun current() = EpgTestDb.currentFile(dir)!!

    private fun rows(xmltvId: String) = EpgTestDb.programmes(current(), xmltvId = xmltvId).map { Triple(it.title, it.startMin to it.endMin, it.flags) }

    @Test
    fun splitClipInferAndPlaceholderRepair() {
        val report = store(dir).buildAll(1L to res("overlaps.xml"))
        assertEquals(
            listOf(
                Triple("A", at("06:00") to at("07:00"), CLIPPED),
                Triple("B", at("07:00") to at("08:00"), 0),
                Triple("C2", at("08:00") to at("08:30"), 0), // duplicate start: the later row wins
                Triple("D", at("09:00") to at("10:00"), 0), // out-of-order input
                Triple("E", at("10:00") to at("11:00"), 0),
                Triple("F", at("11:00") to at("12:00"), STOP_INFERRED), // from the next start
                Triple("Seven", at("12:00") to at("18:00"), CONTINUED), // 7 h split at 6 h
                Triple("Seven", at("18:00") to at("19:00"), CONTINUATION),
                Triple("Last", at("19:00") to at("20:00"), STOP_INFERRED), // last one: +60
            ),
            rows("ov"),
        )
        // S12: a 30 h placeholder's continuation segments never clip real programmes.
        assertEquals(
            listOf(
                Triple("Off air", at("00:00") to at("06:00"), CONTINUED),
                Triple("Off air", at("06:00") to at("07:30"), CONTINUATION or CLIPPED),
                Triple("R1", at("07:30") to at("08:30"), 0),
                Triple("R2", at("08:30") to at("12:30"), 0),
                Triple("R3", at("12:30") to at("13:00"), 0),
                Triple("Off air", at("18:00") to at("20:00"), CONTINUATION or CLIPPED),
                Triple("R4", at("20:00") to at("21:00"), 0),
                Triple("Off air", at("+1 00:00") to at("+1 06:00"), CONTINUATION),
            ),
            rows("ph"),
        )
        val stat = report.sources.single()
        assertEquals(2, stat.split)
        assertEquals(3, stat.clipped)
        assertEquals(2, stat.badTimes, "zero and negative length")
        assertEquals(16, stat.programmesSeen)
        assertEquals(14, stat.programmesKept)
        val channels = EpgTestDb.channels(current()).associateBy { it.xmltvId }
        assertEquals(360, channels.getValue("ov").maxLenMin)
        assertEquals(9, channels.getValue("ov").programmeCount)
        assertEquals(360, channels.getValue("ph").maxLenMin)
    }

    @Test
    fun whitelistIsAskedOncePerSourceBeforeTheFirstProgramme() {
        val calls = ArrayList<Pair<Long, List<String>>>()
        val resolver = EpgWhitelistResolver { sourceId, channels ->
            calls += sourceId to channels.map { it.xmltvId }.toList()
            EpgWhitelist { it == "early" || it == "late" || it == "shared.id" }
        }
        val s = store(dir)
        s.build(request(1L to res("late_channel.xml"), 2L to res("source_b.xml"), 3L to res("no_channels.xml")), resolver)
        // Only the leading <channel> is known when the first programme arrives.
        assertEquals(listOf(1L to listOf("early"), 2L to listOf("shared.id", "b.only"), 3L to emptyList()), calls)
    }

    @Test
    fun resolverGetsDisplayNamesAndIsCalledForSourcesWithoutProgrammes() {
        val seen = ArrayList<EpgChannelInfo>()
        val emptySource = EpgTestDb.xmltv { channel("lonely", "Lonely TV", "Lonely") }.writeTo(File(tmp, "lonely.xml"))
        store(dir).build(
            request(1L to res("basic.xml"), 2L to emptySource),
            EpgWhitelistResolver { _, channels ->
                seen += channels.toList()
                EpgWhitelist.ALL
            },
        )
        assertEquals(listOf("BBC One", "BBC 1"), seen.first { it.xmltvId == "bbc1.uk" }.displayNames)
        assertEquals("http://img.example/bbc1.png", seen.first { it.xmltvId == "bbc1.uk" }.iconUrl)
        assertEquals(listOf("Lonely TV", "Lonely"), seen.single { it.sourceId == 2L }.displayNames)
    }

    @Test
    fun programmesWithoutChannelElementsAreWhitelistedById() {
        val report = store(dir).build(request(1L to res("no_channels.xml")), ids("a.id"))
        assertEquals(listOf("A1", "A2"), EpgTestDb.programmes(current()).map { it.title })
        val ch = EpgTestDb.channels(current())
        assertEquals(listOf("a.id"), ch.map { it.xmltvId }, "rejected unknown ids get no row")
        assertTrue(ch.single().imported)
        assertEquals("", ch.single().displayName)
        assertEquals(1, report.sources.single().skippedWhitelist)
    }

    @Test
    fun lateChannelElementsAreStoredAndUpdateLazyRows() {
        store(dir).build(request(1L to res("late_channel.xml")), ids("early", "late"))
        assertEquals(listOf("L1", "L2"), EpgTestDb.programmes(current(), xmltvId = "late").map { it.title })
        val ch = EpgTestDb.channels(current()).associateBy { it.xmltvId }
        assertEquals("Late Channel", ch.getValue("late").displayName)
        assertTrue(ch.getValue("late").imported)
        assertFalse(ch.getValue("other").imported, "every <channel> is stored, whitelisted or not")
        assertEquals(0, ch.getValue("other").programmeCount)
    }

    @Test
    fun windowDropsProgrammesBeforeTheirChildrenAreParsed() {
        val s = store(dir)
        // now = 2026-10-14T09:00Z, 1 day back → from 2026-10-13T09:00Z; to = now.
        val req = request(1L to res("basic.xml"), pastDays = 1, futureDays = 0)
            .copy(nowMs = EpgTestDb.minutes("2026-10-14T09:00:00Z") * 60_000L)
        val report = s.build(req, ALL)
        assertEquals(9, report.sources.single().skippedWindow, "06–09 on three channels end at or before the window start")
        assertEquals(21, report.programmes)
        val req2 = request(1L to res("basic.xml"), pastDays = 0, futureDays = 0).copy(nowMs = at("10:00") * 60_000L)
        val r2 = s.build(req2, ALL)
        assertEquals(30, r2.sources.single().skippedWindow, "window [10:00, 10:00) keeps nothing")
    }

    @Test
    fun interleavedFileEqualsGroupedFileAndTextsAreDeduplicated() {
        val a = store(File(tmp, "a")).buildAll(1L to res("interleaved.xml"))
        val b = store(File(tmp, "b")).buildAll(1L to res("grouped.xml"))
        assertEquals(1000, a.programmes)
        assertEquals(7, a.texts, "P0..P6 repeat across 50 channels")
        assertEquals(a.texts, b.texts)
        assertEquals(EpgTestDb.programmes(EpgTestDb.currentFile(File(tmp, "a"))!!), EpgTestDb.programmes(EpgTestDb.currentFile(File(tmp, "b"))!!))
    }

    @Test
    fun twoSourcesWithTheSameChannelIdKeepSeparateRows() {
        val report = store(dir).buildAll(1L to res("source_a.xml"), 2L to res("source_b.xml"))
        assertEquals(2, report.sources.size)
        val shared = EpgTestDb.programmes(current(), xmltvId = "shared.id")
        assertEquals(listOf(1L to "A1", 2L to "B1", 2L to "B2"), shared.map { it.sourceId to it.title })
        assertEquals(
            listOf(1L to "Shared A", 2L to "Shared B", 2L to "B Only"),
            EpgTestDb.channels(current()).map { it.sourceId to it.displayName },
        )
        assertEquals(1, EpgTestDb.scalar(current(), "SELECT count(*) FROM source_stat WHERE source_id = 1"))
    }

    @Test
    fun flagsAndCatchupIdsAreStored() {
        store(dir).buildAll(1L to res("basic.xml"))
        val bbc = EpgTestDb.programmes(current(), xmltvId = "bbc1.uk")
        assertEquals("bbc1.uk-0", bbc[0].catchupId)
        assertNull(bbc[1].catchupId)
        assertTrue(bbc.all { it.flags and app.thebox.core.epg.ProgrammeFlags.HAS_CATCHUP_ID != 0 == (it.catchupId != null) })
        assertEquals(30, EpgTestDb.scalar(current(), "SELECT count(*) FROM epg_text"), "every basic programme has its own text")
    }

    // ------------------------------------------------------------ failures

    /** About 1 MB of gzip: big enough that stream read-ahead (≈ 72 KB) does not blur the 90 % rule. */
    private fun bigGz(): File = XmltvGenerator(channels = 500, whitelisted = 500, days = 2, perDay = 24, gzip = true, seed = 7)
        .writeTo(File(tmp, "big.xml.gz")).also { assertTrue(it.length() > 500_000, "fixture size ${it.length()}") }

    @Test
    fun truncatedAt95PercentIsAcceptedAndMarked() {
        val full = bigGz()
        val cut = File(tmp, "truncated.xml.gz").apply { writeBytes(full.readBytes().let { it.copyOf(it.size * 95 / 100) }) }
        val report = store(dir).buildAll(1L to cut)
        assertNull(report.failedSourceId)
        val stat = report.sources.single()
        assertTrue(stat.truncated)
        assertNull(stat.errorCode)
        assertTrue(report.programmes > 10_000, "${report.programmes}")
        assertNotNull(EpgTestDb.currentGeneration(dir))
    }

    /**
     * A source that breaks early fails the build and the old generation stays current. Note: a file that is
     * cleanly cut at 40 % cannot be told from one cut at 95 % (the error always surfaces at EOF of the
     * shorter file), so "early" here means corrupt bytes from 40 % on (design note in the WP-B report).
     */
    @Test
    fun corruptAt40PercentFailsAndKeepsTheOldGeneration() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val full = bigGz().readBytes()
        val bad = full.copyOf()
        val garbage = ByteArray(full.size - full.size * 40 / 100).also { Random(3).nextBytes(it) }
        System.arraycopy(garbage, 0, bad, full.size * 40 / 100, garbage.size)
        val early = File(tmp, "truncated_early.xml.gz").apply { writeBytes(bad) }
        val report = s.buildAll(1L to res("basic.xml"), 2L to early)
        assertEquals(2L, report.failedSourceId)
        assertEquals("parse", report.sources.last().errorCode)
        assertEquals(1L, report.generation, "old generation reported as current")
        assertEquals(1L, EpgTestDb.currentGeneration(dir))
        assertEquals(1L, s.info.value!!.generation)
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir), "the .building file is gone")
    }

    @Test
    fun notXmltvMissingFileAndOversizedXzFailTheSource() {
        val s = store(dir)
        assertEquals("format.wrong_format", s.buildAll(1L to res("not_xmltv.html")).sources.single().errorCode)
        assertEquals("missing", s.buildAll(1L to File(tmp, "nope.xml")).sources.single().errorCode)
        // S3: a 32 MiB-dictionary .xz is Unsupported.
        val xz = File(tmp, "big-dict.xml.xz").apply { writeBytes(xzClaiming32MiBDictionary(res("basic.xml").readBytes())) }
        val r = s.buildAll(1L to xz)
        assertEquals(1L, r.failedSourceId)
        assertEquals("unsupported", r.sources.single().errorCode)
        assertNull(s.info.value)
        assertEquals(emptyList(), EpgTestDb.fileNames(dir))
    }

    @Test
    fun anEndlessTitleInsideASmallGzipFailsTheSourceAsUnsupported() {
        // Security review F1: a few-KB gzip that inflates to one huge text token.
        val gz = File(tmp, "bomb.xml.gz").apply {
            java.util.zip.GZIPOutputStream(outputStream()).use { o ->
                o.write("<tv><programme start=\"20261013060000 +0000\" stop=\"20261013070000 +0000\" channel=\"c\"><title>".toByteArray())
                val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
                repeat(16) { o.write(chunk) }
                o.write("</title></programme></tv>".toByteArray())
            }
        }
        val r = store(dir).buildAll(1L to gz)
        assertEquals(1L, r.failedSourceId)
        assertEquals("unsupported", r.sources.single().errorCode)
    }

    @Test
    fun cancellationMidSourceDeletesTheBuildingFile() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val big = bigGz()
        var polls = 0
        val cancel = CancelCheck { ++polls > 5 }
        assertFailsWith<CancellationException> { s.build(request(1L to big), ALL, cancel = cancel) }
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir))
        assertEquals(1L, s.info.value!!.generation)
    }

    @Test
    fun progressIsReportedPerStage() {
        val stages = ArrayList<EpgProgress.Stage>()
        store(dir).build(request(1L to res("basic.xml.gz")), ALL) { stages += it.stage }
        assertEquals(listOf(EpgProgress.Stage.PARSING, EpgProgress.Stage.REPAIRING, EpgProgress.Stage.INDEXING, EpgProgress.Stage.PUBLISHING), stages.distinct())
    }

    @Test
    fun compressedSourcesImportTheSameRows() {
        val expected = run {
            store(File(tmp, "plain")).buildAll(1L to res("basic.xml"))
            EpgTestDb.programmes(EpgTestDb.currentFile(File(tmp, "plain"))!!)
        }
        for (name in listOf("basic.xml.gz", "basic.xml.xz", "basic.zip", "basic.xml.gz.gz", "bom.xml")) {
            val d = File(tmp, name)
            store(d).buildAll(1L to res(name))
            assertEquals<List<ProgrammeRow>>(expected, EpgTestDb.programmes(EpgTestDb.currentFile(d)!!), name)
        }
    }
}

/**
 * Encodes [data] with a 1 MiB dictionary, then rewrites the LZMA2 filter property in the block header to
 * claim 32 MiB (and fixes the header CRC32). Decoding needs the claimed size, so the 16 MiB cap trips
 * without the test allocating a 32 MiB encoder.
 */
internal fun xzClaiming32MiBDictionary(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    XZOutputStream(out, LZMA2Options().apply { dictSize = 1 shl 20 }).use { it.write(data) }
    val b = out.toByteArray()
    val start = 12 // stream header
    val size = ((b[start].toInt() and 0xFF) + 1) * 4
    val flags = b[start + 1].toInt() and 0xFF
    var i = start + 2
    fun skipVli() {
        while (b[i].toInt() and 0x80 != 0) i++
        i++
    }
    if (flags and 0x40 != 0) skipVli()
    if (flags and 0x80 != 0) skipVli()
    check(b[i].toInt() == 0x21 && b[i + 1].toInt() == 1) { "unexpected filter flags" }
    b[i + 2] = 26 // 2 << (26 / 2 + 11) = 32 MiB
    val crc = java.util.zip.CRC32().apply { update(b, start, size - 4) }.value
    for (k in 0 until 4) b[start + size - 4 + k] = (crc ushr (8 * k)).toByte()
    return b
}
