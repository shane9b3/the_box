package app.thebox.player.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.test.utils.robolectric.IdlingMediaCodecAdapterFactory
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.InMemoryPlaybackSettings
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared fixtures and fakes for the WP-C engine tests (Robolectric). */
@OptIn(UnstableApi::class)
internal object EngineTestSupport {

    fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("ts/$name")) { "missing fixture ts/$name" }.use { it.readBytes() }

    /** A [PlayerFactory] wired with the S14 seams: the given clock, idling codec adapters, an optional FFmpeg stub. */
    fun factory(
        context: Context,
        clock: Clock,
        settings: PlaybackSettings = PlaybackSettings(),
        ffmpegSupports: ((String) -> Boolean)? = { false },
        configure: ((ExoPlayer.Builder) -> Unit)? = null,
    ): PlayerFactory {
        val source = InMemoryPlaybackSettings().also { s -> s.update { settings } }
        return PlayerFactory(context, source, DeviceCapabilities(context)).apply {
            testHooks = PlayerFactory.TestHooks(
                configureBuilder = { b ->
                    b.setClock(clock)
                    configure?.invoke(b)
                },
                codecAdapterFactory = IdlingMediaCodecAdapterFactory(context, clock),
                ffmpegSupports = ffmpegSupports,
            )
        }
    }

    fun bundle(context: Context, clock: Clock, settings: PlaybackSettings = PlaybackSettings(), ffmpegSupports: ((String) -> Boolean)? = { false }): PlayerBundle =
        factory(context, clock, settings, ffmpegSupports).create(Looper.getMainLooper(), softwareAudioFallback = true).also {
            it.player.setVideoSurface(Surface(SurfaceTexture(1)))
        }

    fun resolved(url: String = "http://panel.example:8080/live/u/p/1.ts", format: StreamFormat = StreamFormat.TS): ResolvedStream = ResolvedStream(
        identity = null,
        brokerKey = "d:panel.example",
        url = url,
        headers = emptyMap(),
        formatHint = format,
        xtream = null,
        output = null,
        baseIndex = 0,
        baseCount = 1,
        host = "panel.example:8080",
        ignoreTlsErrors = false,
        liveUrlTemplate = url,
        headerTemplates = emptyMap(),
    )

    class RecordedEvent(val type: PlaybackEventType, val code: String, val detail: String)

    fun context(tuneId: Long = 7, resolved: ResolvedStream = resolved(), events: MutableList<RecordedEvent> = mutableListOf()): StreamSessionContext =
        StreamSessionContext(
            tuneId = tuneId,
            resolved = resolved,
            broker = NoBroker,
            leaseKind = LeaseKind.PLAYBACK,
            sinkFactory = null,
            onEvent = { type, code, detail -> synchronized(events) { events += RecordedEvent(type, code, detail) } },
        )

    /** Test builder: Media3's default policy instead of WP-B's (still a stub in this worktree). */
    fun mediaSources(): MediaSourceBuilder = MediaSourceBuilder { DefaultLoadErrorHandlingPolicy() }

    /** Serves fixed bytes from both transports and records which one was used and the specs opened. */
    class FakeTransport(private val factory: DataSource.Factory) : TransportFactory {
        val telemetry = FakeTelemetry()
        val calls = mutableListOf<String>()

        constructor(bytes: ByteArray) : this(DataSource.Factory { ByteArrayDataSource(bytes) })

        override fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> {
            calls += "liveTs"
            return factory to telemetry
        }

        override fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry> {
            calls += "http"
            return factory to telemetry
        }

        override fun endSession(tuneId: Long) = Unit
    }

    class FakeTelemetry : StreamTelemetry {
        @Volatile override var bytesTotal = 0L
        @Volatile override var lastByteAtMs = 0L
        @Volatile override var firstByteAtMs = 0L
        @Volatile override var bitrateBps = 0L
        @Volatile override var httpStatus = 0
        @Volatile override var finalHost: String? = null
        @Volatile override var redirects = 0
        @Volatile override var inReconnect = false
        @Volatile override var reconnects = 0
        @Volatile override var sniffed = StreamFormat.UNKNOWN
        @Volatile override var readPendingSinceMs = 0L
    }

    /**
     * Serves [bytes] once, then blocks every further read until closed (an endless live body whose data stopped).
     * Records the URIs it was opened with.
     */
    class StallingDataSource(private val bytes: ByteArray) : BaseDataSource(/* isNetwork = */ true) {
        private var uri: Uri? = null
        private var pos = 0
        private val closed = CountDownLatch(1)
        val openedUris = mutableListOf<Uri>()

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            openedUris += dataSpec.uri
            transferInitializing(dataSpec)
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos >= bytes.size) {
                closed.await(30, TimeUnit.SECONDS)
                return C.RESULT_END_OF_INPUT
            }
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, offset, n)
            pos += n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            closed.countDown()
            if (uri != null) transferEnded()
            uri = null
        }
    }

    object NoBroker : ConnectionBrokerCore {
        override val key = "test"
        override fun configure(maxConnections: Int) = Unit
        override fun preemptPlayback(reason: String) = 0
        override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease = error("no broker in engine tests")
        override fun snapshot() = BrokerSnapshot(key, 0, 0, 1, 0, emptyMap())
    }

    // ------------------------------------------------------------------ TS editing (PMT entries)

    /**
     * Returns [ts] with extra elementary-stream entries appended to every PMT section (CRC recomputed). Each entry is
     * (stream_type, PID) with no descriptors. The PIDs carry no packets: they are "dead" tracks.
     */
    fun withExtraPmtEntries(ts: ByteArray, entries: List<Pair<Int, Int>>): ByteArray {
        val out = ts.copyOf()
        val pmtPid = findPmtPid(out)
        var p = 0
        while (p + 188 <= out.size) {
            check(out[p].toInt() == 0x47) { "lost sync at $p" }
            val pid = ((out[p + 1].toInt() and 0x1F) shl 8) or (out[p + 2].toInt() and 0xFF)
            val pusi = (out[p + 1].toInt() and 0x40) != 0
            if (pid == pmtPid && pusi) rewritePmt(out, p, entries)
            p += 188
        }
        return out
    }

    private fun payloadStart(b: ByteArray, p: Int): Int {
        val afc = (b[p + 3].toInt() shr 4) and 0x3
        return if ((afc and 0x2) != 0) p + 5 + (b[p + 4].toInt() and 0xFF) else p + 4
    }

    private fun findPmtPid(b: ByteArray): Int {
        var p = 0
        while (p + 188 <= b.size) {
            val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
            if (pid == 0 && (b[p + 1].toInt() and 0x40) != 0) {
                val s = payloadStart(b, p) + 1 + (b[payloadStart(b, p)].toInt() and 0xFF)
                // table_id(1) len(2) tsid(2) ver(1) sec(1) last(1) then program loop
                var q = s + 8
                val end = s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)) - 4
                while (q < end) {
                    val program = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
                    val pmt = ((b[q + 2].toInt() and 0x1F) shl 8) or (b[q + 3].toInt() and 0xFF)
                    if (program != 0) return pmt
                    q += 4
                }
            }
            p += 188
        }
        error("no PAT")
    }

    private fun rewritePmt(b: ByteArray, p: Int, entries: List<Pair<Int, Int>>) {
        val ps = payloadStart(b, p)
        val s = ps + 1 + (b[ps].toInt() and 0xFF)
        val sectionLength = ((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)
        val crcAt = s + 3 + sectionLength - 4
        val added = entries.size * 5
        check(crcAt + 4 + added <= p + 188) { "no room in the PMT packet" }
        for (i in (p + 187 - added) downTo crcAt) b[i + added] = b[i] // shift CRC (and stuffing) right
        var q = crcAt
        for ((type, pid) in entries) {
            b[q] = type.toByte()
            b[q + 1] = (0xE0 or (pid shr 8)).toByte()
            b[q + 2] = (pid and 0xFF).toByte()
            b[q + 3] = 0xF0.toByte()
            b[q + 4] = 0
            q += 5
        }
        val newLength = sectionLength + added
        b[s + 1] = ((b[s + 1].toInt() and 0xF0) or (newLength shr 8)).toByte()
        b[s + 2] = (newLength and 0xFF).toByte()
        val crc = crc32Mpeg(b, s, q)
        b[q] = (crc ushr 24).toByte()
        b[q + 1] = (crc ushr 16).toByte()
        b[q + 2] = (crc ushr 8).toByte()
        b[q + 3] = crc.toByte()
    }

    private fun crc32Mpeg(b: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if ((crc and Int.MIN_VALUE) != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}
