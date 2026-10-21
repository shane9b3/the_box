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

    @Test
    fun theDebugOverrideTurnsOffOnlyAccessUnitDetection() {
        // Follow-up 2026-10-14 (O-1 diagnosis): compat level and PMT mode untouched.
        try {
            app.thebox.player.debug.PlayerDebugHooks.detectAccessUnitsOff = true
            assertEquals(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, TsExtractorsFactory.flagsFor(TsCompat.DEFAULT))
            assertEquals(TsExtractor.MODE_SINGLE_PMT, modeOf(TsExtractorsFactory(TsCompat.DEFAULT)))
            assertEquals(TsExtractor.MODE_MULTI_PMT, modeOf(TsExtractorsFactory(TsCompat.MULTI_PMT)))
        } finally {
            app.thebox.player.debug.PlayerDebugHooks.detectAccessUnitsOff = false
        }
        assertEquals(TsExtractorsFactory.TS_FLAGS, TsExtractorsFactory.flagsFor(TsCompat.DEFAULT))
    }

    private fun modeOf(factory: TsExtractorsFactory): Int {
        val ts = factory.createExtractors().single().underlyingImplementation
        return TsExtractor::class.java.getDeclaredField("mode").apply { isAccessible = true }.getInt(ts)
    }

    /**
     * Device finding 2026-10-18 (capture of xc 538850): the audio PES timestamps advanced 3 ticks per packet while video
     * ran normally, so the player's buffered position (the minimum over tracks) never grew. The guard reports the PID
     * once; a healthy track (32 ms per sample) is never reported.
     */
    /**
     * Device finding 2026-10-18 (capture of xc 538850): the audio PES timestamps advanced 3 ticks per packet while video
     * ran normally, so the player's buffered position (the minimum over tracks) never grew. The guard reports the PID
     * once; a healthy track (32 ms per sample) is never reported, nor after one odd first timestamp (window check).
     */
    @Test
    fun stuckAudioTimestampsAreReportedOnceHealthyAudioNever() {
        assertEquals(listOf(0x101), feed(audioStepUs = 33L).stuck)
        assertEquals(emptyList<Int>(), feed(audioStepUs = 32_000L).stuck)
        assertEquals(emptyList<Int>(), feed(audioStepUs = 32_000L, firstAudioUs = 15_000_000L).stuck)
    }

    /** L33r: the stuck track's timestamps are regenerated at the learned spacing from the video clock. */
    @Test
    fun repairRegeneratesStuckAudioTimestampsOnTheVideoClock() {
        val r = feed(audioStepUs = 33L, repair = true)
        assertEquals("no verdict while repairing", emptyList<Int>(), r.stuck)
        val audio = r.output.getTrackOutputsForType(C.TRACK_TYPE_AUDIO).single()
        val n = audio.sampleCount
        assertTrue(n > 100)
        assertEquals("first audio sample on the video clock", 1_000_000L, audio.getSampleTimeUs(0))
        for (i in 1 until n) assertEquals("step $i", audio.getSampleTimeUs(i - 1) + 32_000L, audio.getSampleTimeUs(i))
    }

    private class Feed(val output: FakeExtractorOutput, val stuck: List<Int>)

    /**
     * 6 s of 50 fps video with two audio samples per three video samples. Stuck audio = [audioStepUs] of 33 µs per
     * sample (the capture's 3 ticks); healthy = 32 ms. The stuck pattern's intra-PES spacing is what the repair learns,
     * so stuck feeds carry the capture's shape: three samples 32 ms apart, then back by 96 ms − 33 µs.
     */
    private fun feed(audioStepUs: Long, firstAudioUs: Long = 5_000_000L, repair: Boolean = false): Feed {
        val stuck = mutableListOf<Int>()
        val output = FakeExtractorOutput()
        val guard = DeadTrackGuard(output, TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES, null, onStuckAudio = { stuck += it }, repairAudio = repair)
        val video = guard.track(0x100, C.TRACK_TYPE_VIDEO)
        val audio = guard.track(0x101, C.TRACK_TYPE_AUDIO)
        video.format(androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_H265).build())
        audio.format(androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_E_AC3).build())
        guard.endTracks()
        val payload = androidx.media3.common.util.ParsableByteArray(ByteArray(64))
        var videoUs = 1_000_000L
        var pesBaseUs = firstAudioUs
        var audioIndex = 0
        repeat(300) { i ->
            payload.position = 0; video.sampleData(payload, 64, androidx.media3.extractor.TrackOutput.SAMPLE_DATA_PART_MAIN)
            video.sampleMetadata(videoUs, C.BUFFER_FLAG_KEY_FRAME, 64, 0, null)
            videoUs += 20_000
            if (i % 3 != 2) {
                val stuckShape = audioStepUs < 1_000L
                val inPes = audioIndex % 3
                val t = if (stuckShape) pesBaseUs + inPes * 32_000L else pesBaseUs
                payload.position = 0; audio.sampleData(payload, 64, androidx.media3.extractor.TrackOutput.SAMPLE_DATA_PART_MAIN)
                audio.sampleMetadata(t, C.BUFFER_FLAG_KEY_FRAME, 64, 0, null)
                audioIndex++
                if (stuckShape) { if (inPes == 2) pesBaseUs += audioStepUs } else {
                    // After an odd first stamp, healthy audio continues from a sane base.
                    pesBaseUs = if (audioIndex == 1 && firstAudioUs != 5_000_000L) 5_000_000L else pesBaseUs + audioStepUs
                }
            }
        }
        return Feed(output, stuck)
    }

    private class Run(val output: FakeExtractorOutput, val dead: MutableList<Pair<Int, Int>>, val endedAtBytes: Long)

    private fun extract(bytes: ByteArray, guarded: Boolean, grace: Long = TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES): Run {
        val dead = mutableListOf<Pair<Int, Int>>()
        val extractor: Extractor = if (guarded) {
            TsExtractorsFactory(TsCompat.DEFAULT, onDeadTrack = { pid, type -> dead += pid to type }, graceBytes = grace).createExtractors().single()
        } else {
            DefaultExtractorsFactory().setTsExtractorFlags(TsExtractorsFactory.TS_FLAGS).setTsSubtitleFormats(TsExtractorsFactory.SPECULATIVE_CC_FORMATS)
                .createExtractors().filterIsInstance<TsExtractor>().single()
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

    /** V8: the `multi` clip (CC1 in H.264 SEI, no caption descriptor in the PMT) lists one CEA-608 text track. */
    @Test
    fun multiClipListsOneSpeculativeCea608Track() {
        val run = extract(EngineTestSupport.fixture("h264_multi_cc_1s.ts"), guarded = true)
        assertTrue(run.output.tracksEnded)
        val text = run.output.getTrackOutputsForType(C.TRACK_TYPE_TEXT)
        assertEquals(1, text.size)
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_CEA608, text.single().lastFormat?.sampleMimeType)
        assertTrue("the SEI carries caption samples", text.single().sampleCount > 0)
        assertTrue(run.dead.isEmpty())
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

    /**
     * (M4 integration 2026-10-15) A finite TS (archive with a Content-Length) larger than the grace: after the PMT,
     * TsExtractor's duration reader seeks to the end of the file and back. The guard counted that jump as read bytes and
     * gave up every track before its first PES (a period prepared with no tracks, NO_DATA_TIMEOUT on the device).
     */
    @Test
    fun durationReadSeekOnAFiniteInputIsNotCountedAsRead() {
        val looped = ByteArray(clip.size * 30).also { for (i in 0 until 30) System.arraycopy(clip, 0, it, i * clip.size, clip.size) }
        assertTrue("larger than the grace (${looped.size})", looped.size > TsExtractorsFactory.DEAD_TRACK_GRACE_BYTES * 2)
        val dead = mutableListOf<Pair<Int, Int>>()
        val extractor = TsExtractorsFactory(TsCompat.DEFAULT, onDeadTrack = { pid, type -> dead += pid to type }).createExtractors().single()
        val output = FakeExtractorOutput()
        extractor.init(output)
        fun open(position: Long): DefaultExtractorInput {
            val source = ByteArrayDataSource(looped)
            source.open(DataSpec.Builder().setUri(Uri.parse("thebox-session://1")).setPosition(position).build())
            return DefaultExtractorInput(source, position, looped.size.toLong())
        }
        var input = open(0)
        val holder = PositionHolder()
        var seeks = 0
        var reads = 0
        while (output.trackOutputs.size() == 0 || !output.tracksEnded || output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single().sampleCount == 0) {
            check(++reads < 200_000) { "no samples" }
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> { seeks++; input = open(holder.position) }
            }
        }
        assertTrue("the duration reader seeked ($seeks)", seeks >= 1)
        assertTrue("no track given up: $dead", dead.isEmpty())
        assertTrue(output.tracksEnded)
        assertTrue("video has a format", output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).single().lastFormat != null)
        assertTrue("audio has a format", output.getTrackOutputsForType(C.TRACK_TYPE_AUDIO).single().lastFormat != null)
    }

    @Test
    fun smallGraceStillKeepsLiveTracks() {
        val run = extract(EngineTestSupport.withExtraPmtEntries(clip, listOf(0x81 to 0x333)), guarded = true, grace = 32 * 1024)
        assertEquals(listOf(0x333 to C.TRACK_TYPE_AUDIO), run.dead)
        assertFalse(run.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).isEmpty())
    }
}
