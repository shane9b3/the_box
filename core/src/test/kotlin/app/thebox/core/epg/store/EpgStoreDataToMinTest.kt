package app.thebox.core.epg.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.testutil.EpgTestDb
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Event channels, docs/design/m6-event-channels.md §8.5, §11.1 `EpgStoreDataToMinTest`: the end of usable guide data is
 * the median last programme end over the channels holding at least one real listing, capped at the window's end,
 * written at publish, after an in-place build and after a patch. The placeholder test is injected.
 */
class EpgStoreDataToMinTest {
    @TempDir
    lateinit var tmp: File

    private val nowMs = EpgTestDb.minutes("2026-11-25T06:00:00Z") * 60_000L
    private val now = (nowMs / 60_000L).toInt()
    private val fillers = setOf("No Event Today", "Next Event: Devils at Blue Jackets at 07:00PM EST on Nov 25")

    private fun store(dir: String = "epg") =
        EpgStoreImpl(File(tmp, dir).apply { mkdirs() }, BundledSQLiteDriver(), EpgTestDb.PLENTY_OF_SPACE, ::KXmlParser, { nowMs }, false, isFiller = { it in fillers })

    private fun request(file: File, forceInPlace: Boolean = false) =
        EpgBuildRequest(listOf(EpgSourceFile(1, file)), nowMs, pastDays = 1, futureDays = 2, forceInPlace = forceInPlace)

    private fun whitelist(vararg not: String) = EpgWhitelistResolver { _, _ -> EpgWhitelist { it !in not } }

    /** Programmes on [id] in 60 min blocks from now − 60 until [lastEnd] (relative to now), titled [title]. */
    private fun EpgTestDb.XmltvBuilder.line(id: String, lastEnd: Int, title: (Int) -> String) {
        channel(id, id)
        var m = now - 60
        var k = 0
        while (m < now + lastEnd) {
            val e = minOf(m + 60, now + lastEnd)
            programme(id, m, e, title(k++))
            m = e
        }
    }

    private fun guide(withExtra: Boolean = true) = EpgTestDb.xmltv {
        line("real1", 600) { "News $it" }
        line("real2", 1_200) { "Film $it" }
        line("real3", 1_800) { "Sport $it" }
        // A placeholder channel with one real listing counts as real (183 of the owner's BossSports channels).
        line("mixed", 3_000) { if (it == 3) "Devils at Blue Jackets" else "No Event Today" }
        line("boss1", 100) { "No Event Today" }
        line("boss2", 200) { if (it == 0) "Next Event: Devils at Blue Jackets at 07:00PM EST on Nov 25" else "No Event Today" }
        if (withExtra) line("real4", 2_400) { "Show $it" }
    }.writeTo(File(tmp, "guide.xml"))

    @Test
    fun theMedianLeavesOutPlaceholderOnlyChannels() {
        val s = store()
        s.build(request(guide()), whitelist("real4"))
        // Real: 600, 1200, 1800, 3000 → the lower median 1200. Counting boss1 and boss2 would give 600.
        assertEquals(now + 1_200, s.info.value!!.dataToMin)
        assertEquals((now + 1_200).toString(), EpgTestDb.meta(EpgTestDb.currentFile(File(tmp, "epg"))!!)["data_to_min"])
    }

    @Test
    fun aPatchRecomputesItOverTheWholeGeneration() {
        val s = store()
        val file = guide()
        s.build(request(file), whitelist("real4"))
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1, file)), listOf(EpgChannelRef(1, "real4")), nowMs, 1, 2))
        // Real: 600, 1200, 1800, 2400, 3000 → 1800. Without classifying the untouched channels' texts the two
        // placeholder-only channels would count and give 1200.
        assertEquals(now + 1_800, s.info.value!!.dataToMin)
    }

    @Test
    fun anInPlaceBuildWritesItToo() {
        val s = store()
        val file = guide()
        s.build(request(file), whitelist("real4"))
        s.build(request(file, forceInPlace = true), whitelist())
        assertEquals(now + 1_800, s.info.value!!.dataToMin)
    }

    @Test
    fun itIsCappedAtTheWindowsEnd() {
        val s = store()
        val windowTo = now + 2 * 1440
        // Every channel's last programme starts inside the window and ends 10 h after it.
        val f = EpgTestDb.xmltv {
            for (c in 0 until 3) {
                channel("c$c", "c$c")
                programme("c$c", now, windowTo - 30, "Day $c")
                programme("c$c", windowTo - 30, windowTo + 600, "Night $c")
            }
        }.writeTo(File(tmp, "long.xml"))
        s.build(request(f), whitelist())
        assertEquals(windowTo, s.info.value!!.windowToMin)
        assertEquals(windowTo, s.info.value!!.dataToMin)
    }

    @Test
    fun onlyPlaceholdersFallsBackToEveryChannelAndNoProgrammesGivesNone() {
        val s = store()
        val f = EpgTestDb.xmltv {
            line("boss1", 100) { "No Event Today" }
            line("boss2", 200) { "No Event Today" }
            line("boss3", 300) { "No Event Today" }
        }.writeTo(File(tmp, "boss.xml"))
        s.build(request(f), whitelist())
        assertEquals(now + 200, s.info.value!!.dataToMin)
        val empty = store("empty")
        empty.build(request(EpgTestDb.xmltv { channel("x", "x") }.writeTo(File(tmp, "e.xml"))), whitelist())
        assertNull(empty.info.value!!.dataToMin)
    }

    /**
     * Code review 2026-11-25, data Q2: a generation built before `data_to_min` existed gets the same value computed in
     * memory when it is opened (placeholder-only channels left out, as the build does), not the window's end; nothing
     * is written into it.
     */
    @Test
    fun anOlderGenerationWithoutTheRowGetsTheBuildsValueInMemory() {
        store().build(request(guide()), whitelist("real4"))
        val file = EpgTestDb.currentFile(File(tmp, "epg"))!!
        BundledSQLiteDriver().open(file.path).let { c ->
            try {
                c.prepare("DELETE FROM meta WHERE key = 'data_to_min'").use { it.step() }
            } finally {
                c.close()
            }
        }
        assertNull(EpgTestDb.meta(file)["data_to_min"])
        val reopened = store()
        reopened.recover()
        val info = reopened.info.value!!
        // Real: 600, 1200, 1800, 3000 → 1200, as the build gives; the plain median with the placeholders would be 600.
        assertEquals(now + 1_200, info.dataToMin)
        kotlin.test.assertTrue(info.dataToMin!! < info.windowToMin)
        assertNull(EpgTestDb.meta(file)["data_to_min"], "never written into the live generation")
    }

    @Test
    fun anOlderGenerationWithNoProgrammesHasNoDataEnd() {
        val empty = store("empty")
        empty.build(request(EpgTestDb.xmltv { channel("x", "x") }.writeTo(File(tmp, "e.xml"))), whitelist())
        val reopened = store("empty")
        reopened.recover()
        assertNull(reopened.info.value!!.dataToMin)
    }
}
