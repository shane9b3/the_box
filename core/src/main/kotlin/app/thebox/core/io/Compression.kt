package app.thebox.core.io

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import org.tukaani.xz.XZInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Magic-byte detection, never by extension or Content-Encoding (protocols §1.6, §2.2). */
object Compression {
    enum class Kind { NONE, GZIP, XZ, ZIP }

    const val BUFFER = 64 * 1024
    const val MAX_DECOMPRESSED_BYTES = 8L shl 30
    private val GZIP_MAGIC = "1f8b".decodeHex()
    private val XZ_MAGIC = "fd377a585a00".decodeHex()
    private val ZIP_MAGIC = "504b0304".decodeHex()

    fun sniff(source: BufferedSource): Kind = when {
        source.rangeEquals(0, XZ_MAGIC) -> Kind.XZ
        source.rangeEquals(0, GZIP_MAGIC) -> Kind.GZIP
        source.rangeEquals(0, ZIP_MAGIC) -> Kind.ZIP
        else -> Kind.NONE
    }

    /**
     * Unwraps up to [maxLayers] layers (some hosts gzip twice). XZ memory is capped: a dictionary
     * bigger than [xzMemoryLimitKiB] fails with Unsupported instead of blowing the importer heap.
     *
     * (reviewed 2026-10-13: the xz block header, and so the MemoryLimitException, comes on the first
     * read, not in the constructor. The review probe showed a raw MemoryLimitException escaping from
     * the next sniff(). The XZ stream is therefore wrapped so the exception is mapped on every read.)
     *
     * Once a layer was unwrapped, more than [maxBytes] of output fails with Unsupported, so a small
     * compression bomb cannot keep an import busy for ever (security review F1). Plain input is
     * returned as is: its size is already bounded by the download cap.
     */
    fun decompress(
        source: BufferedSource,
        xzMemoryLimitKiB: Int = 16 * 1024,
        maxLayers: Int = 3,
        maxBytes: Long = MAX_DECOMPRESSED_BYTES,
    ): BufferedSource {
        var s = source
        var layers = 0
        while (layers < maxLayers) {
            s = when (sniff(s)) {
                Kind.NONE -> break
                Kind.GZIP -> GZIPInputStream(s.inputStream(), BUFFER).source().buffer()
                Kind.XZ -> XzLimitMapping(XZInputStream(s.inputStream(), xzMemoryLimitKiB), xzMemoryLimitKiB).source().buffer()
                Kind.ZIP -> firstEntry(ZipInputStream(s.inputStream()))
            }
            layers++
        }
        return if (layers == 0) s else Ceiling(s, maxBytes).buffer()
    }

    private class Ceiling(delegate: Source, private val maxBytes: Long) : ForwardingSource(delegate) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                total += n
                if (total > maxBytes) {
                    throw SourceException(SourceError.Unsupported("unpacks to more than ${maxBytes shr 20} MiB"))
                }
            }
            return n
        }
    }

    /** Maps xz's MemoryLimitException (an IOException) to SourceException(Unsupported) wherever it surfaces. */
    private class XzLimitMapping(private val inner: java.io.InputStream, private val limitKiB: Int) : java.io.FilterInputStream(inner) {
        private fun <T> mapped(block: () -> T): T = try { block() } catch (e: org.tukaani.xz.MemoryLimitException) {
            throw SourceException(SourceError.Unsupported("xz dictionary larger than $limitKiB KiB"), e)
        }
        override fun read(): Int = mapped { inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = mapped { inner.read(b, off, len) }
    }

    private fun firstEntry(zip: ZipInputStream): BufferedSource {
        while (true) {
            val e = zip.nextEntry ?: throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
            if (!e.isDirectory && !e.name.startsWith("__MACOSX/")) return zip.source().buffer()
        }
    }
}
