package app.thebox.core.xtream

import app.thebox.core.common.BoxLog
import app.thebox.core.model.TimeBase
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.RawCatchup
import java.io.InputStream

/** Tree-per-item Xtream decoding (docs/design/m1-data-layer.md §3.7), mapped to the shared source types (§3.7.4). */
class XtreamCatalogDecoderImpl : XtreamCatalogDecoder {
    override fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo =
        XtreamDecoders.account(input, measuredAtDeviceMs)

    override fun categories(input: InputStream): List<XtreamCategory> = XtreamDecoders.categories(input)

    override fun liveChannels(input: InputStream, categories: List<XtreamCategory>): Sequence<ParsedChannel> {
        val names = HashMap<String, String>(categories.size * 2)
        for (c in categories) names[c.id] = c.name
        var position = 0
        return XtreamDecoders.liveStreams(input).map { s ->
            toParsedChannel(s, names, position++)
        }.onEachEnd { emitted ->
            BoxLog.i(TAG) { "live streams decoded: $emitted" }
        }
    }

    override fun epgListings(input: InputStream, timeBase: TimeBase): List<XtreamEpgListing> =
        XtreamDecoders.epgListings(input, timeBase)

    companion object {
        private const val TAG = "XtreamCatalogDecoder"
        const val UNCATEGORISED = "Uncategorised"

        /** §3.4 rule M15 junk `tvg-id` values (case-insensitive, after trimming). */
        private val JUNK_IDS = setOf(
            "", "none", "null", "nil", "0", "-", "n/a", "na", "unknown", "noepg", "no-epg", "no_epg", "undefined",
        )

        internal fun junkFilter(id: String?): String? {
            val t = id?.trim() ?: return null
            return if (t.lowercase() in JUNK_IDS) null else t
        }

        internal fun liveUrlTemplate(streamId: Long): String = "{{base}}/live/{{u}}/{{p}}/$streamId.{{ext}}"

        internal fun toParsedChannel(s: XtreamLiveStream, categoryNames: Map<String, String>, position: Int): ParsedChannel {
            val primary = s.categoryId
            val group = if (primary == XtreamDecoders.UNCATEGORISED_ID) UNCATEGORISED else categoryNames[primary] ?: UNCATEGORISED
            val extra = s.categoryIds.asSequence()
                .filter { it != primary }
                .mapNotNull { categoryNames[it] }
                .filter { it != group }
                .distinct()
                .toList()
            val attributes = LinkedHashMap<String, String>(4)
            s.directSource?.let { attributes["direct_source"] = it }
            s.customSid?.let { attributes["custom_sid"] = it }
            s.added?.let { attributes["added"] = it }
            s.thumbnail?.let { attributes["thumbnail"] = it }
            val radio = s.streamType?.contains("radio", ignoreCase = true) == true
            return ParsedChannel(
                kind = if (radio) EntryKind.RADIO else EntryKind.LIVE,
                name = s.name ?: "Stream ${s.streamId}",
                url = liveUrlTemplate(s.streamId),
                position = position,
                groupTitle = group,
                extraGroups = extra,
                xtreamCategoryId = primary,
                tvgId = junkFilter(s.epgChannelId),
                logoUrl = s.streamIcon,
                number = s.num,
                catchup = RawCatchup(xtreamTvArchive = s.tvArchive, xtreamArchiveDays = s.tvArchiveDuration),
                xtreamStreamId = s.streamId,
                adult = s.isAdult,
                attributes = attributes,
            )
        }

        /** Runs [action] with the element count once the sequence is fully consumed. */
        private fun <T> Sequence<T>.onEachEnd(action: (Int) -> Unit): Sequence<T> = Sequence {
            val it = this@onEachEnd.iterator()
            var n = 0
            var done = false
            object : Iterator<T> {
                override fun hasNext(): Boolean {
                    val h = it.hasNext()
                    if (!h && !done) {
                        done = true
                        action(n)
                    }
                    return h
                }

                override fun next(): T = it.next().also { n++ }
            }
        }
    }
}
