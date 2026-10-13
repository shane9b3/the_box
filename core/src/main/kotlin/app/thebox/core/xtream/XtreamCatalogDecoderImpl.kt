package app.thebox.core.xtream

import app.thebox.core.model.TimeBase
import app.thebox.core.source.ParsedChannel
import java.io.InputStream

/** Tree-per-item Xtream decoding (docs/design/m1-data-layer.md §3.7). Commit-0 stub; WP-C owns this file. */
class XtreamCatalogDecoderImpl : XtreamCatalogDecoder {
    override fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo = TODO("WP-C")
    override fun categories(input: InputStream): List<XtreamCategory> = TODO("WP-C")
    override fun liveChannels(input: InputStream, categories: List<XtreamCategory>): Sequence<ParsedChannel> = TODO("WP-C")
    override fun epgListings(input: InputStream, timeBase: TimeBase): List<XtreamEpgListing> = TODO("WP-C")
}
