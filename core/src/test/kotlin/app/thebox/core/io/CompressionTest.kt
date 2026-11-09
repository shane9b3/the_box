package app.thebox.core.io

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import okio.Buffer
import okio.BufferedSource
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompressionTest {
    private val text = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://h/1.ts\n"

    private fun src(bytes: ByteArray): BufferedSource = Buffer().write(bytes)

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun xz(b: ByteArray, dictSize: Int = LZMA2Options.DICT_SIZE_MIN): ByteArray = ByteArrayOutputStream().also { o ->
        XZOutputStream(o, LZMA2Options().apply { this.dictSize = dictSize }).use { it.write(b) }
    }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (data != null) z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun decompressToString(bytes: ByteArray): String = Compression.decompress(src(bytes)).readUtf8()

    @Test
    fun sniffByMagicBytes() {
        assertEquals(Compression.Kind.NONE, Compression.sniff(src(text.toByteArray())))
        assertEquals(Compression.Kind.GZIP, Compression.sniff(src(gzip(text.toByteArray()))))
        assertEquals(Compression.Kind.XZ, Compression.sniff(src(xz(text.toByteArray()))))
        assertEquals(Compression.Kind.ZIP, Compression.sniff(src(zip("a.m3u" to text.toByteArray()))))
        assertEquals(Compression.Kind.NONE, Compression.sniff(src(ByteArray(0))))
        // Only a prefix of the gzip magic.
        assertEquals(Compression.Kind.NONE, Compression.sniff(src(byteArrayOf(0x1f))))
    }

    @Test
    fun sniffDoesNotConsume() {
        val s = src(gzip(text.toByteArray()))
        Compression.sniff(s)
        assertEquals(0x1f, s.readByte().toInt())
    }

    @Test
    fun plainPassesThrough() {
        assertEquals(text, decompressToString(text.toByteArray()))
        assertEquals("", decompressToString(ByteArray(0)))
    }

    @Test
    fun gzipAndDoubleGzip() {
        assertEquals(text, decompressToString(gzip(text.toByteArray())))
        assertEquals(text, decompressToString(gzip(gzip(text.toByteArray()))))
    }

    @Test
    fun layersAreCapped() {
        // Four layers with maxLayers = 3: the result is still gzip, not text.
        val four = gzip(gzip(gzip(gzip(text.toByteArray()))))
        val out = Compression.decompress(src(four))
        assertEquals(Compression.Kind.GZIP, Compression.sniff(out))
    }

    @Test
    fun xzAndXzInsideGzip() {
        assertEquals(text, decompressToString(xz(text.toByteArray())))
        assertEquals(text, decompressToString(gzip(xz(text.toByteArray()))))
    }

    @Test
    fun oversizedXzDictionaryIsUnsupported() {
        // A 1 MiB dictionary against a 512 KiB limit: the MemoryLimitException surfaces on the first read
        // and must come out as SourceException(Unsupported), never raw (review S3).
        val bytes = xz(text.toByteArray(), dictSize = 1 shl 20)
        val e = assertFailsWith<SourceException> {
            Compression.decompress(src(bytes), xzMemoryLimitKiB = 512).readUtf8()
        }
        assertIs<SourceError.Unsupported>(e.error)
    }

    @Test
    fun zipTakesFirstFileEntrySkippingDirectoriesAndMacMetadata() {
        val bytes = zip(
            "dir/" to null,
            "__MACOSX/._list.m3u" to byteArrayOf(0, 5, 22, 7),
            "list.m3u" to text.toByteArray(),
            "other.m3u" to "#EXTM3U\n".toByteArray(),
        )
        assertEquals(text, decompressToString(bytes))
        // A gzip inside the zip is unwrapped too.
        assertEquals(text, decompressToString(zip("epg.xml.gz" to gzip(text.toByteArray()))))
    }

    @Test
    fun zipWithoutFileEntryIsEmpty() {
        val e = assertFailsWith<SourceException> { Compression.decompress(src(zip("dir/" to null))) }
        assertEquals(SourceError.NotASource(NotASourceKind.EMPTY), e.error)
    }

    @Test
    fun outputOverTheCeilingIsUnsupported() {
        val big = ByteArray(1 shl 20) { 'a'.code.toByte() }
        val bomb = gzip(big)
        assertEquals(big.size.toLong(), Compression.decompress(src(bomb), maxBytes = big.size.toLong()).readByteArray().size.toLong())
        val e = assertFailsWith<SourceException> { Compression.decompress(src(bomb), maxBytes = big.size - 1L).readByteArray() }
        assertIs<SourceError.Unsupported>(e.error)
        val nested = assertFailsWith<SourceException> { Compression.decompress(src(gzip(gzip(big))), maxBytes = 1000).readByteArray() }
        assertIs<SourceError.Unsupported>(nested.error)
        // Plain input is not counted: the download cap bounds it.
        assertEquals(big.size, Compression.decompress(src(big), maxBytes = 10).readByteArray().size)
    }
}
