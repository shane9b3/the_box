package app.thebox.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.HdrPreviewMode
import app.thebox.core.playback.Viewport
import app.thebox.player.api.TrackRef
import app.thebox.player.internal.PlayerBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TrackController] (§3.12): `Tracks` → `TrackInfo`, selection, preferences, HDR and the interlace hint. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var bundle: PlayerBundle
    private lateinit var tracks: TrackController

    private val ac3 = Format.Builder().setId("0x101").setSampleMimeType(MimeTypes.AUDIO_AC3).setLanguage("eng").setChannelCount(6).build()
    private val ad = Format.Builder().setId("0x102").setSampleMimeType(MimeTypes.AUDIO_E_AC3).setLanguage("deu").setLabel("Audiodeskription")
        .setChannelCount(2).setRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO).build()
    private val mp2 = Format.Builder().setId("0x103").setSampleMimeType(MimeTypes.AUDIO_MPEG_L2).setLanguage("fra").setChannelCount(2).build()
    private val dvb = Format.Builder().setId("0x200").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_DVBSUBS).setLanguage("eng").build()
    private val cea = Format.Builder().setId("cc1").setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()
    private val hdrVideo = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160)
        .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(C.COLOR_RANGE_LIMITED).build())
        .build()

    private val audioGroup = TrackGroup("1:audio", ac3, ad)
    private val mp2Group = TrackGroup("1:mp2", mp2)
    private val textGroup = TrackGroup("1:text", dvb)
    private val ccGroup = TrackGroup("1:cc", cea)
    private val videoGroup = TrackGroup("1:video", hdrVideo)

    private fun sample(textSelected: Boolean = false) = Tracks(
        listOf(
            Tracks.Group(videoGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            Tracks.Group(audioGroup, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false)),
            Tracks.Group(mp2Group, false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false)),
            Tracks.Group(textGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(textSelected)),
            Tracks.Group(ccGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
        ),
    )

    @Before fun setUp() {
        bundle = EngineTestSupport.bundle(context, FakeClock(true))
        tracks = TrackController(bundle)
    }

    @After fun tearDown() = bundle.player.release()

    @Test
    fun mapsAudioAndTextTracks() {
        val info = tracks.update(sample())
        assertEquals(listOf("AC-3", "E-AC-3", "MP2"), info.audio.map { it.codec })
        val (eng, desc, fra) = info.audio
        assertEquals("en", eng.language) // Format normalises ISO 639-2 to 639-1
        assertEquals(6, eng.channels)
        assertTrue(eng.selected)
        assertTrue(eng.supported)
        assertFalse(eng.describesVideo)
        assertEquals(TrackRef(C.TRACK_TYPE_AUDIO, "1:audio", 0), eng.ref)
        assertTrue("audio description role", desc.describesVideo)
        assertEquals("Audiodeskription", desc.label)
        assertEquals(TrackRef(C.TRACK_TYPE_AUDIO, "1:audio", 1), desc.ref)
        assertFalse(fra.supported)
        assertFalse(fra.selected)

        assertEquals(listOf("DVB", "CEA-608"), info.text.map { it.kind })
        assertFalse(info.textEnabled)
        assertFalse("text type disabled by default (§3.8.6)", tracks.update(sample(textSelected = true)).textEnabled)
        assertEquals(tracks.update(sample()), tracks.current())
    }

    @Test
    fun videoFactsAndMilestoneInput() {
        tracks.update(sample())
        assertEquals(HdrKind.HDR10, tracks.video.hdr)
        assertEquals(3840, tracks.video.width)
        assertFalse(tracks.video.interlacedHint)
        assertTrue(tracks.videoMilestoneExpected())
        tracks.setVideoDisabled(true)
        assertFalse("video type disabled (poster/HIDDEN): audio milestone", tracks.videoMilestoneExpected())
        assertTrue(tracks.videoDisabled)
        tracks.setVideoDisabled(false)
        assertTrue(tracks.videoMilestoneExpected())
        val audioOnly = Tracks(listOf(Tracks.Group(audioGroup, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))))
        tracks.update(audioOnly)
        assertFalse("radio", tracks.videoMilestoneExpected())
        assertEquals(TrackController.VideoFacts(), tracks.video)
    }

    @Test
    fun audioSelectionIsAnOverrideAndReturnsTheFormat() {
        tracks.update(sample())
        val chosen = tracks.selectAudioTrack(TrackRef(C.TRACK_TYPE_AUDIO, "1:audio", 1))
        assertEquals("de", chosen?.language)
        val override = bundle.player.trackSelectionParameters.overrides[audioGroup]!!
        assertEquals(listOf(1), override.trackIndices)

        assertNull(tracks.selectAudioTrack(null))
        assertTrue(bundle.player.trackSelectionParameters.overrides.isEmpty())
        assertNull("unknown group", tracks.selectAudioTrack(TrackRef(C.TRACK_TYPE_AUDIO, "gone", 0)))
    }

    @Test
    fun textSelectionAndOff() {
        tracks.update(sample())
        assertEquals("en", tracks.selectTextTrack(TrackRef(C.TRACK_TYPE_TEXT, "1:text", 0))?.language)
        val p = bundle.player.trackSelectionParameters
        assertFalse(C.TRACK_TYPE_TEXT in p.disabledTrackTypes)
        assertEquals(listOf(0), p.overrides[textGroup]!!.trackIndices)

        assertNull(tracks.selectTextTrack(null))
        val off = bundle.player.trackSelectionParameters
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertTrue(off.overrides.isEmpty())
    }

    @Test
    fun tuneDefaultsClearOverridesAndSetPreferences() {
        tracks.update(sample())
        tracks.selectAudioTrack(TrackRef(C.TRACK_TYPE_AUDIO, "1:audio", 1))
        tracks.applyTuneDefaults(audioLanguages = listOf("fra", "eng"), textLanguages = listOf("eng"), subtitles = "deu", subtitlesEnabled = false)
        val p = bundle.player.trackSelectionParameters
        assertTrue(p.overrides.isEmpty())
        assertEquals(listOf("fr", "en"), p.preferredAudioLanguages)
        assertEquals(C.ROLE_FLAG_MAIN, p.preferredAudioRoleFlags)
        assertEquals(listOf("de", "en"), p.preferredTextLanguages)
        assertFalse("a remembered language turns subtitles on", C.TRACK_TYPE_TEXT in p.disabledTrackTypes)

        tracks.applyTuneDefaults(listOf("eng"), emptyList(), subtitles = "", subtitlesEnabled = true)
        assertTrue("\"\" = off on this channel", C.TRACK_TYPE_TEXT in bundle.player.trackSelectionParameters.disabledTrackTypes)
        tracks.applyTuneDefaults(listOf("eng"), emptyList(), subtitles = null, subtitlesEnabled = true)
        assertFalse(C.TRACK_TYPE_TEXT in bundle.player.trackSelectionParameters.disabledTrackTypes)
    }

    @Test
    fun tunnelingSwitch() {
        assertFalse(tracks.tunnelingRequested)
        tracks.setTunnelingEnabled(true)
        assertTrue(bundle.trackSelector.parameters.tunnelingEnabled)
        tracks.setTunnelingEnabled(false)
        assertFalse(tracks.tunnelingRequested)
    }

    @Test
    fun hdrKinds() {
        assertEquals(HdrKind.SDR, TrackController.hdrKindOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()))
        assertEquals(HdrKind.DOLBY_VISION, TrackController.hdrKindOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        fun withTransfer(t: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(t).setColorSpace(C.COLOR_SPACE_BT2020).build()).build()
        assertEquals(HdrKind.HDR10, TrackController.hdrKindOf(withTransfer(C.COLOR_TRANSFER_ST2084)))
        assertEquals(HdrKind.HLG, TrackController.hdrKindOf(withTransfer(C.COLOR_TRANSFER_HLG)))
        assertEquals(HdrKind.SDR, TrackController.hdrKindOf(withTransfer(C.COLOR_TRANSFER_SDR)))
    }

    @Test
    fun posterRule() {
        assertTrue(TrackController.posterFor(HdrKind.HDR10, Viewport.MINI, HdrPreviewMode.POSTER))
        assertFalse(TrackController.posterFor(HdrKind.HDR10, Viewport.FULL, HdrPreviewMode.POSTER))
        assertFalse(TrackController.posterFor(HdrKind.HLG, Viewport.MINI, HdrPreviewMode.ALLOW))
        assertFalse(TrackController.posterFor(HdrKind.SDR, Viewport.MINI, HdrPreviewMode.POSTER))
    }

    @Test
    fun codecNames() {
        assertEquals("AAC", TrackController.codecName(MimeTypes.AUDIO_AAC))
        assertEquals("MP3", TrackController.codecName(MimeTypes.AUDIO_MPEG))
        assertEquals("DTS", TrackController.codecName(MimeTypes.AUDIO_DTS_HD))
        assertEquals("Opus", TrackController.codecName(MimeTypes.AUDIO_OPUS))
        assertEquals("E-AC-3", TrackController.codecName(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("?", TrackController.codecName(null))
    }

    // ------------------------------------------------------------------ interlace hint (Q12)

    /** A minimal Main-profile SPS (start code included, as `H264Reader` stores it) with the given frame_mbs_only_flag. */
    private fun sps(frameMbsOnly: Boolean): ByteArray {
        val bits = StringBuilder()
        fun u(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.append(if (((value shr i) and 1) == 1) '1' else '0') }
        fun ue(value: Int) {
            val v = value + 1
            val len = 32 - Integer.numberOfLeadingZeros(v)
            repeat(len - 1) { bits.append('0') }
            u(v, len)
        }
        u(77, 8); u(0, 8); u(40, 8) // profile_idc Main, constraints, level 4.0
        ue(0) // seq_parameter_set_id
        ue(0) // log2_max_frame_num_minus4
        ue(0) // pic_order_cnt_type
        ue(0) // log2_max_pic_order_cnt_lsb_minus4
        ue(1) // max_num_ref_frames
        u(0, 1) // gaps_in_frame_num_value_allowed_flag
        ue(119) // pic_width_in_mbs_minus1 (1920)
        ue(if (frameMbsOnly) 67 else 33) // pic_height_in_map_units_minus1 (1088 frame / 544 field pairs)
        u(if (frameMbsOnly) 1 else 0, 1)
        if (!frameMbsOnly) u(1, 1) // mb_adaptive_frame_field_flag
        u(1, 1) // direct_8x8_inference_flag
        u(0, 1) // frame_cropping_flag
        u(0, 1) // vui_parameters_present_flag
        bits.append('1') // rbsp_stop_one_bit
        while (bits.length % 8 != 0) bits.append('0')
        val payload = ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        return byteArrayOf(0, 0, 0, 1, 0x67) + payload
    }

    private fun avc(initData: List<ByteArray>) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setInitializationData(initData).build()

    @Test
    fun interlacedHintFromTheH264Sps() {
        assertTrue("field/MBAFF coding", TrackController.isInterlaced(avc(listOf(sps(frameMbsOnly = false)))))
        assertFalse("progressive", TrackController.isInterlaced(avc(listOf(sps(frameMbsOnly = true)))))
        assertFalse("3-byte start code works too", TrackController.isInterlaced(avc(listOf(sps(true).copyOfRange(1, sps(true).size)))))
        assertTrue(TrackController.isInterlaced(avc(listOf(sps(false).copyOfRange(1, sps(false).size)))))
        assertFalse("no SPS", TrackController.isInterlaced(avc(emptyList())))
        assertFalse("garbage never throws", TrackController.isInterlaced(avc(listOf(byteArrayOf(0, 0, 1, 0x67, 0x4D)))))
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setInitializationData(listOf(sps(false))).build()
        assertFalse("HEVC stays false (Q12)", TrackController.isInterlaced(hevc))

        tracks.onVideoFormat(avc(listOf(sps(frameMbsOnly = false))).buildUpon().setWidth(1920).setHeight(1080).build())
        assertTrue(tracks.video.interlacedHint)
    }
}
