package app.thebox.core.full.feed

import app.thebox.core.full.StatLabel
import app.thebox.core.full.StatLine
import app.thebox.core.full.StatsFacts
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.StreamFormat
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m4-player-ux.md §5.3 `StatsFormatterTest` (§3.4.4, §3.2.4). */
class StatsFormatterTest {
    private val full = StatsFacts(
        videoMime = "video/avc", videoCodecs = "avc1.640028", videoDecoder = "c2.amlogic.avc.decoder",
        width = 1920, height = 1080, fps = 50f, interlaced = false, hdr = HdrKind.SDR,
        audioMime = "audio/eac3", audioChannels = 6, audioDecoder = "passthrough", audioPassthrough = true,
        streamBitrateBps = 7_812_345, bufferMs = 2_400, droppedFrames = 3,
        httpStatus = 200, host = "panel.example:8080", redirects = 1, format = StreamFormat.TS, reconnects = 2,
        broker = BrokerSnapshot("p:1", active = 1, closing = 0, max = 2, waiting = 0, byKind = emptyMap()),
        bufferProfile = BufferProfile(startMs = 1_000, afterRebufferMs = 2_500, minBufferMs = 15_000, maxBufferMs = 30_000, targetBytes = 0, level = 1),
        afr = AfrMode.SEAMLESS, displayRefreshHz = 50f, tunneling = false, serverIndex = 1, serverCount = 3,
    )

    @Test
    fun everyLabelInOrder() {
        val lines = StatsFormatter.lines(full)
        assertEquals(StatLabel.entries.toList(), lines.map { it.label }, "every label, in StatLabel order")
        val v = lines.associate { it.label to it.value }
        assertEquals("H.264 High", v[StatLabel.VIDEO])
        assertEquals("1920×1080", v[StatLabel.RESOLUTION])
        assertEquals("50 fps · AFR seamless", v[StatLabel.FRAME_RATE])
        assertEquals("c2.amlogic.avc.decoder", v[StatLabel.VIDEO_DECODER])
        assertEquals("E-AC-3 5.1 · passthrough", v[StatLabel.AUDIO])
        assertEquals("passthrough", v[StatLabel.AUDIO_DECODER])
        assertEquals("7.8 Mbit/s", v[StatLabel.BITRATE])
        assertEquals("2.4 s", v[StatLabel.BUFFER])
        assertEquals("3", v[StatLabel.DROPPED])
        assertEquals("TS", v[StatLabel.FORMAT])
        assertEquals("200 · 1 redirect · 2 reconnects", v[StatLabel.HTTP])
        assertEquals("panel.example:8080 · server 2 of 3", v[StatLabel.SERVER])
        assertEquals("1/2", v[StatLabel.CONNECTIONS])
        assertEquals("Auto L1 · start 1.0 s", v[StatLabel.BUFFER_PROFILE])
        assertEquals("50 Hz", v[StatLabel.DISPLAY])
        assertEquals("off", v[StatLabel.TUNNELING])
    }

    @Test
    fun emptyFactsAreSkipped() {
        val lines = StatsFormatter.lines(StatsFacts())
        // Only facts that are meaningful at zero remain: the buffer, dropped frames and the tunneling switch.
        assertEquals(listOf(StatLabel.BUFFER, StatLabel.DROPPED, StatLabel.TUNNELING), lines.map { it.label })
        assertEquals(listOf(StatLine(StatLabel.BUFFER, "0.0 s"), StatLine(StatLabel.DROPPED, "0"), StatLine(StatLabel.TUNNELING, "off")), lines)
        assertTrue(lines.none { it.value.isBlank() })
    }

    @Test
    fun localeRootEverywhere() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // decimal comma
            val v = StatsFormatter.lines(full.copy(fps = 29.97f, displayRefreshHz = 59.94f)).associate { it.label to it.value }
            assertEquals("7.8 Mbit/s", v[StatLabel.BITRATE])
            assertEquals("2.4 s", v[StatLabel.BUFFER])
            assertEquals("29.97 fps · AFR seamless", v[StatLabel.FRAME_RATE])
            assertEquals("59.94 Hz", v[StatLabel.DISPLAY])
            assertEquals("1080p29.97", StatsFormatter.resolutionLabel(1080, 29.97f, false))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun variants() {
        val v = StatsFormatter.lines(
            full.copy(
                videoMime = "video/hevc", videoCodecs = "hvc1.2.4.L153.B0", hdr = HdrKind.HDR10, width = 3840, height = 2160,
                audioMime = "audio/mpeg-L2", audioChannels = 2, audioPassthrough = false, streamBitrateBps = 850_000,
                httpStatus = null, redirects = 0, reconnects = 0, serverCount = 1, host = null, format = StreamFormat.HLS,
                broker = BrokerSnapshot("p:1", active = 2, closing = 1, max = 2, waiting = 1, byKind = emptyMap()),
                bufferProfile = BufferProfile(2_500, 5_000, 30_000, 60_000, 0, level = -1), tunneling = true, afr = AfrMode.OFF,
                displayRefreshHz = null, fps = null,
            ),
        ).associate { it.label to it.value }
        assertEquals("H.265 Main 10", v[StatLabel.VIDEO])
        assertEquals("3840×2160 · HDR10", v[StatLabel.RESOLUTION])
        assertNull(v[StatLabel.FRAME_RATE], "no fps yet")
        assertEquals("MP2 2.0", v[StatLabel.AUDIO])
        assertEquals("850 kbit/s", v[StatLabel.BITRATE])
        assertEquals("HLS", v[StatLabel.FORMAT])
        assertNull(v[StatLabel.HTTP], "no status, redirects or reconnects")
        assertNull(v[StatLabel.SERVER], "one base and no host")
        assertEquals("2/2 · 1 waiting · 1 closing", v[StatLabel.CONNECTIONS])
        assertEquals("Manual · start 2.5 s", v[StatLabel.BUFFER_PROFILE])
        assertNull(v[StatLabel.DISPLAY])
        assertEquals("on", v[StatLabel.TUNNELING])
    }

    @Test
    fun serverLine() {
        assertEquals("h:80 · server 2 of 3", StatsFormatter.server("h:80", 1, 3))
        assertEquals("h:80", StatsFormatter.server("h:80", 0, 1))
        assertEquals("server 1 of 2", StatsFormatter.server(null, 0, 2))
        assertEquals("server 3 of 3", StatsFormatter.server("", 7, 3), "out-of-range index clamps")
        assertNull(StatsFormatter.server(null, 0, 1))
    }

    @Test
    fun resolutionLabelTable() {
        assertEquals("1080p50", StatsFormatter.resolutionLabel(1080, 50f, false))
        assertEquals("1080p50", StatsFormatter.resolutionLabel(1088, 50f, false), "coded 1088 snaps to 1080")
        assertEquals("720p", StatsFormatter.resolutionLabel(720, null, false))
        assertEquals("2160p", StatsFormatter.resolutionLabel(2160, null, false))
        assertEquals("576i25", StatsFormatter.resolutionLabel(576, 25f, true))
        assertEquals("1080i25", StatsFormatter.resolutionLabel(1080, 50f, true), "50 fields → 25 frames")
        assertEquals("1080i29.97", StatsFormatter.resolutionLabel(1080, 59.94f, true))
        assertEquals("720p59.94", StatsFormatter.resolutionLabel(720, 59.94f, false))
        assertEquals("1080p23.98", StatsFormatter.resolutionLabel(1080, 23.976f, false))
        assertEquals("404p25", StatsFormatter.resolutionLabel(404, 25f, false), "non-broadcast heights stay as they are")
        assertNull(StatsFormatter.resolutionLabel(0, 50f, false))
    }

    @Test
    fun channelLayouts() {
        assertEquals(listOf(null, "1.0", "2.0", "5.1", "7.1", "4 ch"), listOf(0, 1, 2, 6, 8, 4).map { StatsFormatter.channelLayout(it) })
    }

    @Test
    fun codecNames() {
        assertEquals("H.264 Main", StatsFormatter.videoName("video/avc", "avc1.4D401F"))
        assertEquals("H.264", StatsFormatter.videoName("video/avc", null))
        assertEquals("MPEG-2", StatsFormatter.videoName("video/mpeg2", null))
        assertEquals("H.265 Main", StatsFormatter.videoName("video/hevc", "mp4a.40.2, hev1.1.6.L93.B0"))
        assertNull(StatsFormatter.videoName(null, "avc1.640028"))
        assertEquals("AAC", StatsFormatter.audioName("audio/mp4a-latm"))
        assertEquals("DTS", StatsFormatter.audioName("audio/vnd.dts.hd"))
        assertEquals("X-WEIRD", StatsFormatter.audioName("audio/x-weird"))
    }
}
