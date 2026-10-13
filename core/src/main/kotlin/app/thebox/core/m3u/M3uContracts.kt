package app.thebox.core.m3u

import app.thebox.core.common.CancelCheck
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.ParseWarning
import okio.BufferedSource

interface M3uPlaylistParser {
    /**
     * Streams an already-decompressed [source] into [sink]. Never buffers the whole input.
     * Throws SourceException(NotASource(HTML_PAGE | HLS_MANIFEST | EMPTY | BINARY)).
     * [M3uSink.onHeader] is called exactly once, before the first entry (empty header if absent).
     */
    fun parse(source: BufferedSource, options: M3uParseOptions, sink: M3uSink, cancel: CancelCheck = CancelCheck.Never): M3uParseSummary
}

data class M3uParseOptions(
    /** Playlist URL, for relative entry URLs. Null for local files (relative URLs are then skipped with a warning). */
    val baseUrl: String? = null,
    /** `Content-Type; charset=` from the download, if any. */
    val charsetHint: String? = null,
    /** User override (playlist.charset_override); wins over detection. */
    val forcedCharset: String? = null,
    val extgrpSticky: Boolean = false,
    val maxLineBytes: Int = 64 * 1024,
)

interface M3uSink {
    fun onHeader(header: M3uHeader) {}
    fun onEntry(entry: ParsedChannel)
    fun onWarning(warning: ParseWarning) {}
}

data class M3uParseSummary(
    val entries: Int, val live: Int, val radio: Int, val movies: Int, val series: Int,
    val skippedLines: Int, val warnings: Int, val charset: String, val hadHeader: Boolean,
    /**
     * (amended 2026-10-13) The header merged over **every** `#EXTM3U` line of the file (§3.4 M25), including
     * ones after the first entry (concatenated lists), which [M3uSink.onHeader] cannot include because it
     * fires before the first entry. Callers that store the header use this one. Null only from parsers that
     * predate the field; fall back to the [M3uSink.onHeader] value then.
     */
    val header: M3uHeader? = null,
)

/** Stub in commit 0 at m3u/M3uParserImpl.kt; WP-A owns the body. */
// class M3uParserImpl : M3uPlaylistParser
