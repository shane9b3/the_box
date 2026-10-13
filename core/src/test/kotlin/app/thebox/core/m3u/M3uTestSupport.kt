package app.thebox.core.m3u

import app.thebox.core.common.SourceException
import app.thebox.core.io.Compression
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParseWarning
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.WarningCode
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source

/** Collects everything a parse produced. */
class CollectingSink : M3uSink {
    var header: M3uHeader? = null
    var headerCalls = 0
    var headerBeforeFirstEntry = true
    val entries = ArrayList<ParsedChannel>()
    val warnings = ArrayList<ParseWarning>()

    override fun onHeader(header: M3uHeader) {
        headerCalls++
        if (entries.isNotEmpty()) headerBeforeFirstEntry = false
        this.header = header
    }

    override fun onEntry(entry: ParsedChannel) {
        if (header == null) headerBeforeFirstEntry = false
        entries += entry
    }

    override fun onWarning(warning: ParseWarning) { warnings += warning }

    fun codes(): List<WarningCode> = warnings.map { it.code }
    fun byName(name: String): ParsedChannel = entries.single { it.name == name }
}

class ParseResult(val summary: M3uParseSummary, val sink: CollectingSink) {
    val entries get() = sink.entries
    val header get() = sink.header!!
    fun byName(name: String) = sink.byName(name)
}

object M3u {
    fun resourceBytes(path: String): ByteArray =
        M3u::class.java.getResourceAsStream("/$path")?.use { it.readBytes() } ?: error("missing fixture $path")

    fun fixture(path: String): BufferedSource = Compression.decompress(Buffer().write(resourceBytes(path)))

    fun parse(source: BufferedSource, options: M3uParseOptions = M3uParseOptions()): ParseResult {
        val sink = CollectingSink()
        val summary = M3uParserImpl().parse(source, options, sink)
        return ParseResult(summary, sink)
    }

    fun parseFixture(path: String, options: M3uParseOptions = M3uParseOptions()) = parse(fixture(path), options)

    fun parseText(text: String, options: M3uParseOptions = M3uParseOptions()) = parse(Buffer().writeUtf8(text), options)

    fun parseBytes(bytes: ByteArray, options: M3uParseOptions = M3uParseOptions()) = parse(Buffer().write(bytes), options)

    fun failure(text: String): SourceException = try {
        parseText(text)
        error("expected SourceException")
    } catch (e: SourceException) {
        e
    }

    fun streamOf(bytes: ByteArray): BufferedSource = bytes.inputStream().source().buffer()
}
