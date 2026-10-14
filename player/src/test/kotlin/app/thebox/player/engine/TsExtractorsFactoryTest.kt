package app.thebox.player.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeExtractorOutput
import app.thebox.player.internal.TsCompat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TS-only extractor list, compat levels and the dead-track guard (§3.8.5, review S5). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TsExtractorsFactoryTest {
    private val clip = EngineTestSupport.fixture("h264_aac_2s.ts")

    @Test
    fun onlyTsExtractorsWithTheDefaultFlags() {
        val extractors = TsExtractorsFactory(TsCompat.DEFAULT).createExtractors()
        assertEquals(1, extractors.size)
        assertTrue(extractors.single().underlyingImplementation is TsExtractor)
        assertEquals(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            TsExtractorsFactory.TS_FLAGS,
        )
        assertEquals("HDMV DTS stays off (S5)", 0, TsExtractorsFactory.TS_FLAGS and DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
    }

    @Test
    fun compatLevelSelectsTheTsMode() {
        assertEquals(TsExtractor.MODE_SINGLE_PMT, modeOf(TsExtractorsFactory(TsCompat.DEFAULT)))
        assertEquals(TsExtractor.MODE_MULTI_PMT, modeOf(TsExtractorsFactory(TsCompat.MULTI_PMT)))
    }

    private fun modeOf(factory: TsExtractorsFactory): Int {
        val ts = factory.createExtractors().single().underlyingImplementation
        return TsExtractor::class.java.getDeclaredField("mode").apply { isAccessible = true }.getInt(ts)
    }

    private class Run(val output: FakeExtractorOutput, val dead: MutableList<Pair<Int, Int>>, val endedAtBytes: Long)

    private fun extract(bytes: ByteArray, guarded: Boolean, grace: Long = TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES): Run {
        val dead = mutableListOf<Pair<Int, Int>>()
        val extractor: Extractor = if (guarded) {
            TsExtractorsFactory(TsCompat.DEFAULT, onDeadTrack = { pid, type -> dead += pid to type }, graceBytes = grace).createExtractors().single()
        } else {
            DefaultExtractorsFactory().setTsExtractorFlags(TsExtractorsFactory.TS_FLAGS).createExtractors().filterIsInstance<TsExtractor>().single()
        }
        val output = FakeExtractorOutput()
        extractor.init(output)
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(Uri.parse("thebox-session://1")))
        val input = DefaultExtractorInput(source, 0, C.LENGTH_UNSET.toLong())
        val holder = PositionHolder()
        var endedAt = -1L
        while (true) {
            val r = extractor.read(input, holder)
            if (endedAt < 0 && output.tracksEnded) endedAt = input.position
            if (r == Extractor.RESULT_END_OF_INPUT) break
            check(r == Extractor.RESULT_CONTINUE) { "unexpected result $r" }
        }
        return Run(output, dead, endedAt)
    }

    @Test
    fun cleanStreamEndsTracksAtOnceAndKeepsEverySample() {
        val plain = extract(clip, guarded = false)
        val guarded = extract(clip, guarded = true)
        assertTrue(guarded.output.tracksEnded)
        assertTrue("ends as soon as every track has a format, long before the grace", guarded.endedAtBytes in 1 until 64 * 1024)
        assertTrue(guarded.dead.isEmpty())

        // The pre-format bytes are replayed: the guarded output is sample-for-sample identical (first keyframe kept).
        assertEquals(plain.output.numberOfTracks, guarded.output.numberOfTracks)
        for (i in 0 until plain.output.trackOutputs.size()) {
            val id = plain.output.trackOutputs.keyAt(i)
            val a = plain.output.trackOutputs.valueAt(i)
            val b = guarded.output.trackOutputs.get(id)
            assertEquals("track $id format", a.lastFormat, b.lastFormat)
            assertEquals("track $id samples", a.sampleCount, b.sampleCount)
            for (s in 0 until a.sampleCount) {
                assertEquals(a.getSampleTimeUs(s), b.getSampleTimeUs(s))
                assertEquals(a.getSampleFlags(s), b.getSampleFlags(s))
                assertArrayEquals("track $id sample $s", a.getSampleData(s), b.getSampleData(s))
            }
        }
        val video = guarded.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single()
        val firstKey = (0 until video.sampleCount).first { (video.getSampleFlags(it) and C.BUFFER_FLAG_KEY_FRAME) != 0 }
        assertTrue("the first GOP's keyframe survives (index $firstKey of ${video.sampleCount})", firstKey < 25)
        assertEquals("the clip's first video PTS is kept", plain.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single().getSampleTimeUs(firstKey), video.getSampleTimeUs(firstKey))
    }

    @Test
    fun deadPidIsGivenUpAtTheEndOfAFiniteInput() {
        val ts = EngineTestSupport.withExtraPmtEntries(clip, listOf(0x81 to 0x333))
        val run = extract(ts, guarded = true)
        assertTrue(run.output.tracksEnded)
        assertEquals(listOf(0x333 to C.TRACK_TYPE_AUDIO), run.dead)
        assertEquals("the dead PID never became a track", null, run.output.trackOutputs.get(0x333))
        assertEquals(1, run.output.getTrackOutputsForType(C.TRACK_TYPE_AUDIO).size)
        assertTrue(run.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single().sampleCount > 0)
    }

    @Test
    fun deadPidIsGivenUpAfterTheGraceOnAnEndlessStream() {
        // ~1.1 MiB of TS (the clip looped): the guard must end the tracks after 1 MiB, before the input ends.
        val one = EngineTestSupport.withExtraPmtEntries(clip, listOf(0x81 to 0x333, 0x82 to 0x334))
        val looped = ByteArray(one.size * 12).also { for (i in 0 until 12) System.arraycopy(one, 0, it, i * one.size, one.size) }
        val run = extract(looped, guarded = true)
        assertTrue(run.output.tracksEnded)
        assertTrue("ended at ${run.endedAtBytes}", run.endedAtBytes >= TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES)
        assertTrue("ended at ${run.endedAtBytes}", run.endedAtBytes < TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES + 64 * 1024)
        assertEquals("0x82 (SCTE-27 here) gets no reader at all without the HDMV flag", listOf(0x333 to C.TRACK_TYPE_AUDIO), run.dead)
    }

    @Test
    fun withoutTheGuardADeadPidNeverEndsTracksWithFormats() {
        // Documents the hazard: the unguarded extractor declares the AC-3 track, which never gets a format.
        val run = extract(EngineTestSupport.withExtraPmtEntries(clip, listOf(0x81 to 0x333)), guarded = false)
        val ac3 = run.output.trackOutputs.get(0x333)
        assertTrue(ac3 != null && ac3.lastFormat == null)
    }

    @Test
    fun smallGraceStillKeepsLiveTracks() {
        val run = extract(EngineTestSupport.withExtraPmtEntries(clip, listOf(0x81 to 0x333)), guarded = true, grace = 32 * 1024)
        assertEquals(listOf(0x333 to C.TRACK_TYPE_AUDIO), run.dead)
        assertFalse(run.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).isEmpty())
    }
}
