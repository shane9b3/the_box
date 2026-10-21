// WP-A (docs/design/m6-timeshift.md §3.1, §8.3, A-S7, §18).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_FORMAT_VERSION
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_MAGIC
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32

/**
 * The ring file header (§3.1): `magic` "TBXRING1" (8), `version` u32, `packetSize` u32, `blockSize` u32, `capacity` u64,
 * `generation` u64, `createdMonoMs` u64, `pid` u32, CRC32 of the preceding fields (u32); zero padding to 4,096 bytes.
 * Little-endian. Shared with WP-B's `AndroidRingStorage`.
 */
object RingHeaderCodec {
    /** Bytes covered by the CRC. */
    const val FIELDS_BYTES = 8 + 4 + 4 + 4 + 8 + 8 + 8 + 4

    fun encode(generation: Long, capacity: Long, createdMonoMs: Long, pid: Int): ByteBuffer {
        val b = ByteBuffer.allocate(RING_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put(RING_MAGIC.toByteArray(Charsets.US_ASCII))
        b.putInt(RING_FORMAT_VERSION).putInt(TS_PACKET).putInt(RING_BLOCK_BYTES)
        b.putLong(capacity).putLong(generation).putLong(createdMonoMs).putInt(pid)
        val crc = CRC32()
        crc.update(b.array(), 0, FIELDS_BYTES)
        b.putInt(crc.value.toInt())
        (b as Buffer).clear()
        return b
    }

    /** Parsed header, or null when the magic, version or CRC does not match. */
    class Header(val capacity: Long, val generation: Long, val createdMonoMs: Long, val pid: Int)

    fun decode(src: ByteBuffer): Header? {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < FIELDS_BYTES + 4) return null
        val start = b.position()
        val magic = ByteArray(8).also { b.get(it) }
        if (String(magic, Charsets.US_ASCII) != RING_MAGIC) return null
        if (b.int != RING_FORMAT_VERSION || b.int != TS_PACKET || b.int != RING_BLOCK_BYTES) return null
        val capacity = b.long; val generation = b.long; val created = b.long; val pid = b.int
        val crcStored = b.int
        val bytes = ByteArray(FIELDS_BYTES)
        val d = src.duplicate(); (d as Buffer).position(start); d.get(bytes)
        val crc = CRC32(); crc.update(bytes)
        if (crc.value.toInt() != crcStored) return null
        return Header(capacity, generation, created, pid)
    }
}

/**
 * A pure-JVM [RingStorage] over a [FileChannel] (§18 WP-A): for tests and as the fallback. Opened lazily, so constructing
 * it touches no disk. `allocate` extends the file sparsely (`setLength`); `isUnlinked` is "the path no longer exists"
 * (the JVM cannot `fstat` an fd); `availableBytes` is the partition's usable space.
 *
 * [capacityBytes] 0 (the default, the commit-0 shape) means "no space": a ring over it runs RAM-only.
 */
class FileRingStorage(
    private val file: File,
    override val capacityBytes: Long = 0L,
    private val pid: Int = 0,
    private val createdMonoMs: Long = 0L,
) : RingStorage {
    @Volatile private var raf: RandomAccessFile? = null
    @Volatile private var channel: FileChannel? = null
    @Volatile private var closed = false

    @Throws(IOException::class)
    private fun ch(): FileChannel {
        channel?.let { return it }
        synchronized(this) {
            channel?.let { return it }
            if (closed) throw IOException("ring storage closed")
            file.parentFile?.mkdirs()
            val r = RandomAccessFile(file, "rw")
            raf = r
            return r.channel.also { channel = it }
        }
    }

    @Throws(IOException::class)
    override fun allocate(upToBytes: Long): Long {
        val c = ch()
        synchronized(this) {
            val r = raf ?: throw IOException("ring storage closed")
            if (r.length() < upToBytes) r.setLength(upToBytes)
            return c.size()
        }
    }

    @Throws(IOException::class)
    override fun pwrite(src: ByteBuffer, filePosition: Long): Int = ch().write(src, filePosition)

    @Throws(IOException::class)
    override fun pread(dst: ByteBuffer, filePosition: Long): Int = ch().read(dst, filePosition)

    override fun isUnlinked(): Boolean = channel != null && !file.exists()

    override fun availableBytes(): Long = (file.parentFile ?: file.absoluteFile.parentFile)?.usableSpace ?: 0L

    @Throws(IOException::class)
    override fun writeHeader(generation: Long, capacity: Long) {
        val b = RingHeaderCodec.encode(generation, capacity, createdMonoMs, pid)
        val c = ch()
        var pos = 0L
        while (b.hasRemaining()) pos += c.write(b, pos)
    }

    override fun delete() {
        close()
        file.delete()
    }

    override fun close() {
        synchronized(this) {
            closed = true
            try { channel?.close() } catch (_: IOException) {}
            try { raf?.close() } catch (_: IOException) {}
            channel = null; raf = null
        }
    }
}
