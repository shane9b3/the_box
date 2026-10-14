package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.ConnectionHandle
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamFormat
import app.thebox.player.internal.StreamSessionContext
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.SocketEffect
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val PKT = 188

internal fun res(path: String): ByteArray =
    LiveTsDataSourceTest::class.java.getResourceAsStream("/$path")?.use { it.readBytes() } ?: error("missing fixture $path")

internal val FIXTURE: ByteArray by lazy { res("ts/h264_aac_2s.ts") }

/** Real monotonic clock (Robolectric's SystemClock does not advance by itself). */
internal val REAL_CLOCK = MonoClock { System.nanoTime() / 1_000_000 }

internal class FakeClock(start: Long = 1_000_000) : MonoClock {
    val now = AtomicLong(start)
    override fun nowMs(): Long = now.get()
    fun advance(ms: Long) { now.addAndGet(ms) }
}

internal class FakeLease(override val id: Long, override val kind: LeaseKind, override val ownerTuneId: Long) : CoreLease {
    val handles = CopyOnWriteArrayList<ConnectionHandle>()
    val attachCount = AtomicInteger()
    val detachCount = AtomicInteger()
    val releaseCount = AtomicInteger()
    @Volatile var preempted = false
    @Volatile var onRelease: (() -> Unit)? = null
    override val isPreempted: Boolean get() = preempted

    override fun attach(handle: ConnectionHandle): Boolean {
        if (preempted) return false
        handles += handle
        attachCount.incrementAndGet()
        return true
    }

    override fun detach(handle: ConnectionHandle) {
        handles -= handle
        detachCount.incrementAndGet()
    }

    override fun release() {
        if (releaseCount.getAndIncrement() == 0) onRelease?.invoke()
    }

    /** What the broker core does on pre-emption: mark, then cancel every handle synchronously. */
    fun preempt() {
        preempted = true
        for (h in handles) h.cancel()
    }
}

internal class FakeBroker(override val key: String = "p:1") : ConnectionBrokerCore {
    val leases = CopyOnWriteArrayList<FakeLease>()
    @Volatile var refuse: RuntimeException? = null
    private val ids = AtomicLong()

    override fun configure(maxConnections: Int) = Unit
    override fun preemptPlayback(reason: String): Int = leases.filter { !it.preempted }.onEach { it.preempt() }.size
    override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease {
        refuse?.let { throw it }
        return FakeLease(ids.incrementAndGet(), kind, ownerTuneId).also { leases += it }
    }
    override fun snapshot() = BrokerSnapshot(key, leases.count { it.releaseCount.get() == 0 }, 0, 1, 0, emptyMap())
    val lease: FakeLease get() = leases.single()
}

internal class EventRecorder {
    val events = CopyOnWriteArrayList<Pair<PlaybackEventType, String>>()
    fun record(type: PlaybackEventType, code: String, detail: String) { events += type to "$code $detail" }
    fun count(type: PlaybackEventType) = events.count { it.first == type }
}

internal fun resolved(url: String, headers: Map<String, String> = mapOf("User-Agent" to "TestUA/1.0"), ignoreTls: Boolean = false) =
    ResolvedStream(
        identity = null,
        brokerKey = "d:test",
        url = url,
        headers = headers,
        formatHint = StreamFormat.TS,
        xtream = null,
        output = null,
        baseIndex = 0,
        baseCount = 1,
        host = "test-host",
        ignoreTlsErrors = ignoreTls,
        liveUrlTemplate = url,
        headerTemplates = headers,
    )

@OptIn(UnstableApi::class)
internal fun sessionCtx(
    url: String,
    broker: ConnectionBrokerCore = FakeBroker(),
    tuneId: Long = 7,
    events: EventRecorder = EventRecorder(),
    sink: DataSink.Factory? = null,
    headers: Map<String, String> = mapOf("User-Agent" to "TestUA/1.0"),
) = StreamSessionContext(tuneId, resolved(url, headers), broker, LeaseKind.PLAYBACK, sink, events::record)

/** A live body: [data], then either the end (socket closed by the response) or a stall until [release]. */
internal class LiveBody(private val data: ByteArray, private val stall: Boolean = false) : MockResponseBody {
    private val gate = CountDownLatch(1)
    override val contentLength: Long = -1L
    override fun writeTo(sink: BufferedSink) {
        sink.write(data)
        sink.flush()
        if (stall) gate.await(30, TimeUnit.SECONDS)
    }
    fun release() = gate.countDown()
}

/** Writes [chunk] forever (optionally chunk-framed) until the client goes away; [disconnected] then trips. */
internal class FloodBody(private val chunk: ByteArray, private val chunked: Boolean = false) : MockResponseBody {
    val disconnected = CountDownLatch(1)
    val written = AtomicLong()
    override val contentLength: Long = -1L
    override fun writeTo(sink: BufferedSink) {
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                if (chunked) {
                    sink.writeHexadecimalUnsignedLong(chunk.size.toLong()).writeUtf8("\r\n").write(chunk).writeUtf8("\r\n")
                } else {
                    sink.write(chunk)
                }
                sink.flush()
                written.addAndGet(chunk.size.toLong())
            }
        } catch (e: IOException) {
            disconnected.countDown()
            throw e
        }
    }
}

/** 200 with a body that ends by closing the socket (no Content-Length, no chunking: read until EOF). */
internal fun liveResponse(body: MockResponseBody): MockResponse =
    MockResponse.Builder().code(200).addHeader("Content-Type", "video/mp2t").body(body).removeHeader("Content-Length")
        .onResponseEnd(SocketEffect.CloseSocket()).build()

internal fun liveResponse(data: ByteArray): MockResponse = liveResponse(LiveBody(data))

/** Reads from [ds] until [n] bytes arrived (or throws what the source throws). */
internal fun readExactly(ds: DataSource, n: Int, readLen: Int = 32 * 1024): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(readLen)
    while (out.size() < n) {
        val r = ds.read(buf, 0, minOf(readLen, n - out.size()))
        check(r >= 0) { "unexpected end of input" }
        out.write(buf, 0, r)
    }
    return out.toByteArray()
}

/** [b] with every packet's CC nibble cleared (the aligner rewrites CCs after a splice). */
internal fun maskCc(b: ByteArray): ByteArray {
    val out = b.copyOf()
    var off = 0
    while (off + PKT <= out.size) {
        out[off + 3] = (out[off + 3].toInt() and 0xF0).toByte()
        off += PKT
    }
    return out
}

internal fun sessionSpec(tuneId: Long = 7, position: Long = 0): DataSpec =
    DataSpec.Builder().setUri("${LiveTsDataSource.SESSION_SCHEME}://$tuneId").setPosition(position).build()

/** A recording sink for the TeeDataSource seam. */
@OptIn(UnstableApi::class)
internal class RecordingSink : DataSink {
    val bytes = ByteArrayOutputStream()
    @Volatile var opened = 0
    @Volatile var closed = 0
    override fun open(dataSpec: DataSpec) { opened++ }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { synchronized(bytes) { bytes.write(buffer, offset, length) } }
    override fun close() { closed++ }
}

/** A sleeper that records the requested delays and advances [clock] instead of sleeping. */
internal class RecordingSleeper(private val clock: FakeClock? = null) : LiveTsDataSource.Sleeper {
    val delays = CopyOnWriteArrayList<Long>()
    override fun sleep(ms: Long) {
        delays += ms
        clock?.advance(ms)
    }
}

internal fun fastTiming(sleeper: LiveTsDataSource.Sleeper = RecordingSleeper()) = LiveTsDataSource.Timing(sleeper = sleeper)
