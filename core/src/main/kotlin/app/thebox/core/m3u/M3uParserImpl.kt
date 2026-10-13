package app.thebox.core.m3u

import app.thebox.core.common.CancelCheck
import okio.BufferedSource

/** M3U state machine (docs/design/m1-data-layer.md §3.4). Commit-0 stub; WP-A owns this file. */
class M3uParserImpl : M3uPlaylistParser {
    override fun parse(source: BufferedSource, options: M3uParseOptions, sink: M3uSink, cancel: CancelCheck): M3uParseSummary =
        TODO("WP-A")
}
