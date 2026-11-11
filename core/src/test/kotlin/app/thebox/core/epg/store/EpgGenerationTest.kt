package app.thebox.core.epg.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.store.StoreFixtures.ALL
import app.thebox.core.epg.store.StoreFixtures.NOW_MS
import app.thebox.core.epg.store.StoreFixtures.at
import app.thebox.core.epg.store.StoreFixtures.buildAll
import app.thebox.core.epg.store.StoreFixtures.ids
import app.thebox.core.epg.store.StoreFixtures.request
import app.thebox.core.epg.store.StoreFixtures.res
import app.thebox.core.epg.store.StoreFixtures.store
import app.thebox.core.epg.testutil.EpgTestDb
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgGenerationTest {
    @TempDir
    lateinit var tmp: File

    private val dir get() = File(tmp, "epg")

    private fun titlesIn(reader: app.thebox.core.epg.EpgReader, ref: EpgChannelRef): List<String> {
        val ch = reader.resolve(listOf(ref))
        val w = EpgWindow()
        reader.window(ch, intArrayOf(0), at("00:00"), at("+1 06:00"), w)
        return (0 until w.size).map { w.titles[it]!! }
    }

    @Test
    fun buildPublishesAndWritesThePointer() {
        val s = store(dir)
        assertNull(s.info.value)
        assertNull(s.openReader())
        val r = s.buildAll(1L to res("basic.xml"))
        assertEquals(EpgImportReport.Mode.REBUILD, r.mode)
        assertEquals(1L, r.generation)
        assertEquals(30, r.programmes)
        assertEquals("fts5", r.fts)
        assertEquals("1\n", File(dir, "epg-current").readText())
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir))
        val info = s.info.value!!
        assertEquals(1L, info.generation)
        assertEquals(0, info.patchSeq)
        assertEquals(30, info.programmes)
        assertEquals(3, info.channels)
        assertEquals(NOW_MS, info.builtAtMs)
        assertEquals(at("12:00") - 1440, info.windowFromMin)
        assertEquals(at("12:00") + 7 * 1440, info.windowToMin)
        val meta = EpgTestDb.meta(File(dir, "epg-1.db"))
        assertEquals("1", meta["complete"])
        assertEquals("1", meta["schema_version"])
        assertEquals(1L, EpgTestDb.scalar(File(dir, "epg-1.db"), "PRAGMA user_version"))
        assertEquals("delete", EpgTestDb.read(File(dir, "epg-1.db")) { c -> c.prepare("PRAGMA journal_mode").use { it.step(); it.getText(0) } })

        val r2 = s.buildAll(1L to res("source_a.xml"))
        assertEquals(2L, r2.generation)
        assertEquals(listOf("epg-2.db", "epg-current"), EpgTestDb.fileNames(dir), "unreferenced old generation is deleted at once")
        assertEquals(2L, s.info.value!!.generation)
    }

    @Test
    fun readerKeepsItsGenerationWhileANewOnePublishesUnderLoad() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val old = s.openReader()!!
        val ref = EpgChannelRef(1, "bbc1.uk")
        val expectedOld = titlesIn(old, ref)
        assertEquals(10, expectedOld.size)

        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val queries = AtomicInteger()
        val started = CountDownLatch(1)
        val t = thread {
            try {
                started.countDown()
                while (!stop.get()) {
                    assertEquals(expectedOld, titlesIn(old, ref))
                    queries.incrementAndGet()
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        started.await()
        s.buildAll(1L to res("source_a.xml"), 2L to res("basic.xml"))
        stop.set(true)
        t.join()
        failure.get()?.let { throw it }
        assertTrue(queries.get() > 0)

        assertEquals(2L, s.info.value!!.generation)
        assertEquals(1L, old.info.generation)
        assertEquals(expectedOld, titlesIn(old, ref), "old reader still sees generation 1")
        assertTrue(File(dir, "epg-1.db").exists(), "kept while a reader holds it")
        val fresh = s.openReader()!!
        assertEquals(2L, fresh.info.generation)
        assertEquals(emptyList(), titlesIn(fresh, ref), "source 1 is now source_a, which has no bbc1.uk")
        old.close()
        old.close() // idempotent
        assertFalse(File(dir, "epg-1.db").exists(), "deleted after the last reader closed")
        assertEquals(listOf("epg-2.db", "epg-current"), EpgTestDb.fileNames(dir))
        fresh.close()
        assertTrue(File(dir, "epg-2.db").exists(), "the current generation stays")
        assertFailsWith<IllegalStateException> { old.refOf(1) }
    }

    @Test
    fun recoverPicksThePointerAndCleansUp() {
        store(dir).buildAll(1L to res("basic.xml"))
        val g1 = File(dir, "epg-1.db")
        // Leftovers of crashes at each step of §2.5.2.
        g1.copyTo(File(dir, "epg-2.db.building"))           // crash during the build
        g1.copyTo(File(dir, "epg-3.db"))                    // crash after the rename, before the pointer
        File(dir, "epg-current.tmp").writeText("3\n")       // crash while writing the pointer
        File(dir, "epg-4.db-journal").writeText("x")        // stray journal
        File(dir, "unrelated.txt").writeText("keep")
        val s = store(dir)
        s.recover()
        assertEquals(1L, s.info.value!!.generation)
        assertEquals(listOf("epg-1.db", "epg-current", "unrelated.txt"), EpgTestDb.fileNames(dir))
        s.recover() // idempotent
        assertEquals(1L, s.info.value!!.generation)
        assertNotNull(s.openReader()).close()
        // The next build continues after the highest number ever used.
        assertEquals(2L, s.buildAll(1L to res("basic.xml")).generation)
    }

    @Test
    fun crashAfterThePointerRenameKeepsTheNewGeneration() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        s.buildAll(1L to res("source_a.xml"))
        // Simulate: gen 2 committed but gen 1 never cleaned up (process died after the pointer rename).
        File(dir, "epg-2.db").copyTo(File(dir, "epg-1.db"))
        val fresh = store(dir)
        fresh.recover()
        assertEquals(2L, fresh.info.value!!.generation)
        assertEquals(listOf("epg-2.db", "epg-current"), EpgTestDb.fileNames(dir))
    }

    @Test
    fun halfWrittenGenerationIsIgnoredOnRecover() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val half = File(tmp, "half.db")
        // Copy the .building file in the middle of a build, then cancel it.
        assertFailsWith<CancellationException> {
            s.build(request(1L to res("interleaved.xml")), ALL, progress = { p ->
                if (p.stage == EpgProgress.Stage.REPAIRING && !half.exists()) {
                    File(dir, "epg-2.db.building").copyTo(half)
                    throw CancellationException("simulated process death")
                }
            })
        }
        assertTrue(half.length() > 0)
        // Worst case: the half-written file sits under a published name and the pointer names it.
        half.copyTo(File(dir, "epg-2.db"))
        half.copyTo(File(dir, "epg-3.db.building"))
        File(dir, "epg-current").writeText("2\n")
        val fresh = store(dir)
        fresh.recover()
        assertEquals(1L, fresh.info.value!!.generation, "incomplete generation 2 rejected, fallback to 1")
        assertEquals("1\n", File(dir, "epg-current").readText())
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir))
    }

    @Test
    fun missingPointerFallsBackToTheHighestValidGeneration() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val r = s.openReader()!! // keeps gen 1 on disk
        s.buildAll(1L to res("source_a.xml"))
        r.close()
        File(dir, "epg-2.db").copyTo(File(dir, "epg-1.db"), overwrite = true)
        File(dir, "epg-current").delete()
        val fresh = store(dir)
        fresh.recover()
        assertEquals(2L, fresh.info.value!!.generation)
        assertEquals("2\n", File(dir, "epg-current").readText())
    }

    @Test
    fun schemaVersionMismatchLeavesNoGeneration() {
        store(dir).buildAll(1L to res("basic.xml"))
        val c = BundledSQLiteDriver().open(File(dir, "epg-1.db").path)
        c.execSQL("PRAGMA user_version=99")
        c.close()
        val fresh = store(dir)
        fresh.recover()
        assertNull(fresh.info.value)
        assertNull(fresh.openReader())
        assertEquals(emptyList(), EpgTestDb.fileNames(dir))
    }

    @Test
    fun hotJournalOfAnInterruptedPatchIsRolledBack() {
        val gen = app.thebox.core.epg.testutil.XmltvGenerator(channels = 100, whitelisted = 100, days = 1, perDay = 24, gzip = false)
        store(dir).buildAll(1L to gen.writeTo(File(tmp, "gen.xml")))
        val g1 = File(dir, "epg-1.db")
        val programmes = EpgTestDb.scalar(g1, "SELECT count(*) FROM programme")!!
        val crashDir = File(tmp, "crash").apply { mkdirs() }
        // A writer that dies mid-transaction: small cache so pages spill to the file, journal holds the originals.
        val w = BundledSQLiteDriver().open(g1.path)
        w.execSQL("PRAGMA journal_mode=DELETE")
        w.execSQL("PRAGMA cache_size=2")
        w.execSQL("BEGIN IMMEDIATE")
        w.execSQL("DELETE FROM programme")
        w.execSQL("UPDATE epg_text SET title = 'gone'")
        File(dir, "epg-1.db-journal").copyTo(File(crashDir, "epg-1.db-journal"))
        g1.copyTo(File(crashDir, "epg-1.db"))
        w.execSQL("ROLLBACK")
        w.close()
        File(crashDir, "epg-current").writeText("1\n")
        val s = store(crashDir)
        s.recover()
        assertEquals(1L, s.info.value!!.generation)
        assertFalse(File(crashDir, "epg-1.db-journal").exists())
        assertEquals(programmes, EpgTestDb.scalar(File(crashDir, "epg-1.db"), "SELECT count(*) FROM programme"))
        assertEquals(0, EpgTestDb.scalar(File(crashDir, "epg-1.db"), "SELECT count(*) FROM epg_text WHERE title = 'gone'"))
    }

    @Test
    fun lowDiskUsesInPlaceAndWithoutAGenerationFails() {
        val tiny = EpgTestDb.freeSpace(10L shl 20)
        val e = assertFailsWith<SourceException> { store(dir, freeSpace = tiny).buildAll(1L to res("basic.xml")) }
        val disk = e.error as SourceError.DiskSpace
        assertEquals(10L shl 20, disk.freeBytes)
        assertTrue(disk.neededBytes > 64L shl 20)

        store(dir).buildAll(1L to res("basic.xml"), 2L to res("source_a.xml"))
        val s = store(dir, freeSpace = tiny)
        s.recover()
        val reader = s.openReader()!!
        assertEquals(listOf("BBC1 Show 0"), titlesIn(reader, EpgChannelRef(1, "bbc1.uk")).take(1))
        val r = s.build(request(1L to res("source_b.xml")), ALL)
        assertEquals(EpgImportReport.Mode.IN_PLACE, r.mode)
        assertEquals(1L, r.generation)
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir))
        val info = s.info.value!!
        assertEquals(1L, info.generation)
        assertEquals(1, info.patchSeq)
        assertEquals(3, info.programmes, "source_b replaced everything (B1, B2, B Only Show); source 2 is gone")
        // The open reader sees the new content of the same file.
        assertEquals(listOf("B1", "B2"), titlesIn(reader, EpgChannelRef(1, "shared.id")))
        assertEquals(-1, reader.resolve(listOf(EpgChannelRef(2, "shared.id")))[0], "source 2 dropped")
        assertEquals(emptyList(), titlesIn(reader, EpgChannelRef(1, "bbc1.uk")))
        assertTrue(reader.search("show", at("00:00")).none { it.title.startsWith("BBC1") })
        assertEquals(listOf("B1"), reader.search("b1", at("00:00")).map { it.title })
        assertEquals(EpgTestDb.scalar(File(dir, "epg-1.db"), "SELECT count(*) FROM epg_text"), 3L, "unreferenced texts deleted")
        reader.close()
    }

    @Test
    fun forceInPlaceUsesTheCurrentFile() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val r = s.build(request(1L to res("basic.xml"), forceInPlace = true), ALL)
        assertEquals(EpgImportReport.Mode.IN_PLACE, r.mode)
        assertEquals(30, r.programmes)
        assertEquals(30, r.texts, "texts re-used through the hash index, not duplicated")
        assertEquals(1, s.info.value!!.patchSeq)
        // forceInPlace without a generation falls back to a rebuild.
        val other = store(File(tmp, "other"))
        assertEquals(EpgImportReport.Mode.REBUILD, other.build(request(1L to res("basic.xml"), forceInPlace = true), ALL).mode)
    }

    @Test
    fun buildInputsAreStoredWithTheGenerationAndReplacedByEveryFullBuild() {
        // Review 2026-11-10 S1: the orchestrator compares this against the current inputs before skipping a build.
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        assertNull(s.info.value!!.buildInputs, "no fingerprint requested: none stored (a generation from before S1)")
        s.build(request(1L to res("basic.xml")).copy(buildInputs = "fp-1"), ALL)
        assertEquals("fp-1", s.info.value!!.buildInputs)
        assertEquals("fp-1", EpgTestDb.meta(File(dir, "epg-2.db"))["build_inputs"])
        assertEquals(EpgSchema.IMPORTER_VERSION, EpgTestDb.meta(File(dir, "epg-2.db"))["app_version"])
        // A patch keeps it; an in-place build replaces it, and one without a fingerprint removes the stale one.
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1, res("basic.xml"))), listOf(EpgChannelRef(1, "bbc1.uk")), NOW_MS, 1, 7))
        assertEquals("fp-1", s.info.value!!.buildInputs)
        s.build(request(1L to res("basic.xml"), forceInPlace = true).copy(buildInputs = "fp-2"), ALL)
        assertEquals("fp-2", s.info.value!!.buildInputs)
        s.build(request(1L to res("basic.xml"), forceInPlace = true), ALL)
        assertNull(s.info.value!!.buildInputs)
        assertNull(EpgTestDb.meta(File(dir, "epg-2.db"))["build_inputs"])
    }

    @Test
    fun patchAddsAChannelAndReadersSeeItAfterReResolve() {
        val s = store(dir)
        s.build(request(1L to res("no_channels.xml"), 2L to res("basic.xml")), ids("a.id", "bbc1.uk"))
        val reader = s.openReader()!!
        val bRef = EpgChannelRef(1, "b.id")
        val itvRef = EpgChannelRef(2, "itv1.uk")
        assertEquals(-1, reader.resolve(listOf(bRef))[0], "never seen: no row yet")
        val itv = reader.resolve(listOf(itvRef))[0]
        assertTrue(itv > 0, "every <channel> is stored")
        assertEquals(emptyList(), titlesIn(reader, itvRef))

        // Readers keep querying while the patch writes.
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val busy = thread {
            val r2 = s.openReader()!!
            try {
                while (!stop.get()) titlesIn(r2, EpgChannelRef(2, "bbc1.uk")).let { check(it.size == 10) { "bbc1 changed: $it" } }
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                r2.close()
            }
        }
        val report = s.patch(
            EpgPatchRequest(
                sources = listOf(EpgSourceFile(1, res("no_channels.xml")), EpgSourceFile(2, res("basic.xml"))),
                refs = listOf(bRef, itvRef),
                nowMs = NOW_MS,
                pastDays = 1,
                futureDays = 7,
            ),
        )
        stop.set(true)
        busy.join()
        failure.get()?.let { throw it }

        assertEquals(EpgImportReport.Mode.PATCH, report.mode)
        assertEquals(1L, report.generation)
        assertEquals(1, s.info.value!!.patchSeq)
        assertEquals(1, reader.info.patchSeq)
        assertEquals(10 + 2 + 10 + 1, s.info.value!!.programmes)
        val b = reader.resolve(listOf(bRef))[0]
        assertTrue(b > 0, "re-resolve after patchSeq finds the new row (S13)")
        assertEquals(listOf("B1"), titlesIn(reader, bRef))
        assertEquals(10, titlesIn(reader, itvRef).size)
        assertEquals(listOf("B1"), reader.search("b1", at("00:00")).map { it.title }, "FTS rows added for new texts")
        assertEquals(10, reader.search("itv1 show", at("00:00")).size)
        val ch = EpgTestDb.channels(File(dir, "epg-1.db")).associateBy { it.sourceId to it.xmltvId }
        assertTrue(ch.getValue(1L to "b.id").imported)
        assertTrue(ch.getValue(2L to "itv1.uk").imported)
        assertEquals(10, ch.getValue(2L to "itv1.uk").programmeCount)
        assertEquals(listOf("epg-1.db", "epg-current"), EpgTestDb.fileNames(dir), "no journal left behind")

        // Patching the same refs again replaces their rows instead of duplicating them.
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(2, res("basic.xml"))), listOf(itvRef), NOW_MS, 1, 7))
        assertEquals(10, titlesIn(reader, itvRef).size)
        assertEquals(2, reader.info.patchSeq)
        reader.close()
    }

    @Test
    fun patchWithoutGenerationIsANoOp() {
        val r = store(dir).patch(EpgPatchRequest(listOf(EpgSourceFile(1, res("basic.xml"))), listOf(EpgChannelRef(1, "bbc1.uk")), NOW_MS, 1, 7))
        assertEquals(0, r.programmes)
        assertEquals(0L, r.generation)
    }

    @Test
    fun clearDeletesEverythingOnceReadersClose() {
        val s = store(dir)
        s.buildAll(1L to res("basic.xml"))
        val r = s.openReader()!!
        s.clear()
        assertNull(s.info.value)
        assertNull(s.openReader())
        assertEquals(listOf("epg-1.db"), EpgTestDb.fileNames(dir), "kept for the open reader, pointer gone")
        assertEquals(10, titlesIn(r, EpgChannelRef(1, "bbc1.uk")).size)
        r.close()
        assertEquals(emptyList(), EpgTestDb.fileNames(dir))
        val fresh = store(dir)
        fresh.recover()
        assertNull(fresh.info.value)
        assertEquals(1L, s.buildAll(1L to res("basic.xml")).generation)
    }

    @Test
    fun forcedFts4Path() {
        val s = store(dir, fts4 = true)
        val r = s.buildAll(1L to res("basic.xml"), 2L to res("source_a.xml"))
        assertEquals("fts4", r.fts)
        s.openReader()!!.use { reader ->
            assertEquals("fts4", reader.info.fts)
            assertEquals(3, reader.search("show 9", at("00:00")).size)
            assertEquals(listOf("A1"), reader.search("tele", at("00:00")).map { it.title }, "diacritics folded in FTS4 too")
        }
    }
}
