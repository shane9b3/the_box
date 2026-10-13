package app.thebox.core.xtream

import app.thebox.core.common.AuthReason
import app.thebox.core.common.BoxLog
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.LenientJson.bool
import app.thebox.core.xtream.LenientJson.double
import app.thebox.core.xtream.LenientJson.int
import app.thebox.core.xtream.LenientJson.long
import app.thebox.core.xtream.LenientJson.obj
import app.thebox.core.xtream.LenientJson.objOrArrayValues
import app.thebox.core.xtream.LenientJson.str
import app.thebox.core.xtream.LenientJson.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tree-per-item decoders for every Xtream action (docs/design/m1-data-layer.md §3.7). Lists are lazy sequences
 * that must be consumed while the input is open; small responses (login, info, EPG) are decoded as one tree.
 */
object XtreamDecoders {
    private const val TAG = "XtreamDecoders"
    const val UNCATEGORISED_ID = "none"

    // ---------------------------------------------------------------- account

    /** Login response → account card. Throws `SourceException(Auth | NotASource | Parse)`. */
    fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo {
        val sniffed = XtreamJson.sniff(input)
        when (sniffed.kind) {
            XtreamPayloadKind.EMPTY, XtreamPayloadKind.ARRAY -> throw auth(AuthReason.BAD_CREDENTIALS)
            // An HTML page instead of JSON on login is usually an "account expired" / portal page.
            XtreamPayloadKind.HTML -> throw auth(AuthReason.UNKNOWN)
            XtreamPayloadKind.OTHER -> throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
            XtreamPayloadKind.OBJECT -> Unit
        }
        val root = XtreamJson.tree(sniffed.stream) as? JsonObject
            ?: throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
        val ui = obj(root, "user_info")
        if (ui == null || ui.isEmpty()) throw auth(AuthReason.BAD_CREDENTIALS)
        val statusRaw = str(ui, "status")
        val status = statusOf(statusRaw)
        val authOk = bool(ui, "auth") ?: (status == AccountStatus.ACTIVE)
        if (!authOk) {
            throw auth(
                when (status) {
                    AccountStatus.EXPIRED -> AuthReason.EXPIRED
                    AccountStatus.BANNED -> AuthReason.BANNED
                    AccountStatus.DISABLED -> AuthReason.DISABLED
                    else -> AuthReason.BAD_CREDENTIALS
                },
            )
        }
        val si = obj(root, "server_info")
        val server = XtreamServerInfo(
            url = str(si, "url"),
            port = int(si, "port"),
            httpsPort = int(si, "https_port"),
            protocol = str(si, "server_protocol"),
            timezone = str(si, "timezone"),
            timestampNow = long(si, "timestamp_now"),
            timeNow = str(si, "time_now"),
            xui = bool(si, "xui") ?: false,
            version = str(si, "version"),
        )
        return XtreamAccountInfo(
            auth = true,
            status = status,
            statusRaw = statusRaw,
            expiresAtSec = long(ui, "exp_date")?.takeIf { it > 0 },
            isTrial = bool(ui, "is_trial") ?: false,
            activeConnections = int(ui, "active_cons"),
            maxConnections = int(ui, "max_connections"),
            allowedOutputFormats = strList(ui, "allowed_output_formats"),
            createdAtSec = long(ui, "created_at")?.takeIf { it > 0 },
            message = str(ui, "message"),
            server = server,
            measuredAtDeviceMs = measuredAtDeviceMs,
        )
    }

    private fun statusOf(raw: String?): AccountStatus = when (raw?.trim()?.lowercase()) {
        "active" -> AccountStatus.ACTIVE
        "banned" -> AccountStatus.BANNED
        "disabled" -> AccountStatus.DISABLED
        "expired" -> AccountStatus.EXPIRED
        else -> AccountStatus.UNKNOWN
    }

    private fun auth(reason: AuthReason) = SourceException(SourceError.Auth(reason))

    // ---------------------------------------------------------------- categories

    fun categories(input: InputStream): List<XtreamCategory> {
        val out = ArrayList<XtreamCategory>()
        for (e in XtreamJson.items(input)) {
            val o = e as? JsonObject ?: continue
            val id = str(o, "category_id") ?: continue
            val parent = str(o, "parent_id")?.takeIf { it != "0" }
            out += XtreamCategory(id, str(o, "category_name") ?: "Category $id", parent)
        }
        return out
    }

    // ---------------------------------------------------------------- lists

    fun liveStreams(input: InputStream): Sequence<XtreamLiveStream> = XtreamJson.items(input).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = long(o, "stream_id") ?: return@mapNotNull null
        XtreamLiveStream(
            streamId = id,
            num = int(o, "num"),
            name = str(o, "name"),
            streamType = str(o, "stream_type"),
            streamIcon = str(o, "stream_icon"),
            epgChannelId = str(o, "epg_channel_id"),
            added = str(o, "added"),
            isAdult = bool(o, "is_adult") ?: false,
            categoryId = str(o, "category_id") ?: UNCATEGORISED_ID,
            categoryIds = strList(o, "category_ids"),
            customSid = str(o, "custom_sid"),
            tvArchive = bool(o, "tv_archive"),
            tvArchiveDuration = int(o, "tv_archive_duration"),
            directSource = str(o, "direct_source"),
            thumbnail = str(o, "thumbnail"),
        )
    }

    fun vodStreams(input: InputStream): Sequence<XtreamVodStream> = XtreamJson.items(input).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = long(o, "stream_id") ?: return@mapNotNull null
        XtreamVodStream(
            streamId = id,
            num = int(o, "num"),
            name = str(o, "name"),
            title = str(o, "title"),
            year = str(o, "year"),
            streamIcon = str(o, "stream_icon"),
            rating = double(o, "rating"),
            rating5 = double(o, "rating_5based"),
            added = str(o, "added"),
            categoryId = str(o, "category_id") ?: UNCATEGORISED_ID,
            categoryIds = strList(o, "category_ids"),
            containerExtension = str(o, "container_extension"),
            customSid = str(o, "custom_sid"),
            directSource = str(o, "direct_source"),
            tmdb = str(o, "tmdb") ?: str(o, "tmdb_id"),
            isAdult = bool(o, "is_adult") ?: false,
        )
    }

    fun series(input: InputStream): Sequence<XtreamSeries> = XtreamJson.items(input).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = long(o, "series_id") ?: return@mapNotNull null
        XtreamSeries(
            seriesId = id,
            num = int(o, "num"),
            name = str(o, "name"),
            cover = str(o, "cover"),
            plot = str(o, "plot"),
            cast = str(o, "cast"),
            director = str(o, "director"),
            genre = str(o, "genre"),
            releaseDate = str(o, "releaseDate") ?: str(o, "release_date"),
            lastModified = str(o, "last_modified"),
            rating = double(o, "rating"),
            rating5 = double(o, "rating_5based"),
            backdropPaths = strList(o, "backdrop_path"),
            youtubeTrailer = str(o, "youtube_trailer"),
            episodeRunTime = str(o, "episode_run_time"),
            categoryId = str(o, "category_id") ?: UNCATEGORISED_ID,
            categoryIds = strList(o, "category_ids"),
        )
    }

    // ---------------------------------------------------------------- infos

    /** `get_series_info`. An empty response (`[]`, `{}`) → null (the client then retries with `series=`). */
    fun seriesInfo(input: InputStream): XtreamSeriesInfo? {
        val root = treeOrNull(input) as? JsonObject ?: return null
        if (root.isEmpty()) return null
        val info = obj(root, "info")
        val episodes = sortedMapOf<Int, MutableList<XtreamSeriesInfo.Episode>>()
        fun add(e: JsonElement, fallbackSeason: Int) {
            val o = e as? JsonObject ?: return
            val id = long(o, "id") ?: return
            val season = int(o, "season") ?: fallbackSeason
            val ei = obj(o, "info")
            episodes.getOrPut(season) { ArrayList() } += XtreamSeriesInfo.Episode(
                id = id,
                episodeNum = int(o, "episode_num"),
                title = str(o, "title"),
                containerExtension = str(o, "container_extension"),
                season = season,
                added = str(o, "added"),
                customSid = str(o, "custom_sid"),
                directSource = str(o, "direct_source"),
                plot = str(ei, "plot"),
                image = str(ei, "movie_image"),
                releaseDate = str(ei, "releasedate") ?: str(ei, "release_date"),
                rating = double(ei, "rating"),
                durationSecs = long(ei, "duration_secs") ?: parseHms(str(ei, "duration")),
            )
        }
        when (val eps = root["episodes"]) {
            is JsonObject -> for ((k, v) in eps) {
                val keySeason = k.trim().toIntOrNull() ?: 1
                for (e in objOrArrayValues(v)) add(e, keySeason)
            }
            is JsonArray -> eps.forEachIndexed { index, v ->
                if (v is JsonArray || (v is JsonObject && long(v, "id") == null)) {
                    for (e in objOrArrayValues(v)) add(e, index + 1)
                } else {
                    add(v, 1) // flat array: grouped by int(season) ?: 1
                }
            }
            else -> Unit
        }
        val declared = objOrArrayValues(root, "seasons").mapNotNull { s ->
            val o = s as? JsonObject ?: return@mapNotNull null
            val n = int(o, "season_number") ?: int(o, "season") ?: return@mapNotNull null
            XtreamSeriesInfo.Season(n, str(o, "name"), int(o, "episode_count"), str(o, "air_date"), str(o, "cover"))
        }
        val seasons = if (declared.isNotEmpty()) {
            val known = declared.map { it.number }.toSet()
            (declared + episodes.keys.filter { it !in known }.map { XtreamSeriesInfo.Season(it, null, episodes[it]?.size, null, null) })
                .sortedBy { it.number }
        } else {
            episodes.map { (n, list) -> XtreamSeriesInfo.Season(n, "Season $n", list.size, null, null) }
        }
        return XtreamSeriesInfo(
            name = str(info, "name"),
            cover = str(info, "cover"),
            plot = str(info, "plot"),
            backdropPaths = strList(info, "backdrop_path"),
            seasons = seasons,
            episodes = episodes,
        )
    }

    /** `get_vod_info`. An empty response → null. */
    fun vodInfo(input: InputStream): XtreamVodInfo? {
        val root = treeOrNull(input) as? JsonObject ?: return null
        if (root.isEmpty()) return null
        val info = obj(root, "info") ?: JsonObject(emptyMap())
        val md = obj(root, "movie_data") ?: JsonObject(emptyMap())
        return XtreamVodInfo(
            streamId = long(md, "stream_id"),
            name = str(info, "name") ?: str(md, "name"),
            originalName = str(info, "o_name"),
            tmdbId = str(info, "tmdb_id"),
            image = str(info, "movie_image") ?: str(info, "cover_big"),
            backdropPaths = strList(info, "backdrop_path"),
            youtubeTrailer = str(info, "youtube_trailer"),
            genre = str(info, "genre"),
            plot = str(info, "plot") ?: str(info, "description"),
            cast = str(info, "cast") ?: str(info, "actors"),
            director = str(info, "director"),
            releaseDate = str(info, "releasedate") ?: str(info, "release_date"),
            durationSecs = long(info, "duration_secs") ?: parseHms(str(info, "duration")),
            rating = double(info, "rating"),
            country = str(info, "country"),
            containerExtension = str(md, "container_extension"),
            categoryId = str(md, "category_id"),
            directSource = str(md, "direct_source"),
        )
    }

    private fun treeOrNull(input: InputStream): JsonElement? {
        val sniffed = XtreamJson.sniff(input)
        return when (sniffed.kind) {
            XtreamPayloadKind.EMPTY -> null
            else -> XtreamJson.tree(sniffed.stream)
        }
    }

    /** `HH:MM:SS` or `MM:SS` → seconds. */
    internal fun parseHms(s: String?): Long? {
        if (s == null) return null
        val parts = s.trim().split(':')
        if (parts.size !in 2..3) return null
        var total = 0L
        for (p in parts) total = total * 60 + (p.trim().toLongOrNull() ?: return null)
        return total
    }

    // ---------------------------------------------------------------- EPG

    /**
     * `get_short_epg` / `get_simple_data_table` → listings. Titles/descriptions per §3.7.3. `*_timestamp` (UTC)
     * preferred; otherwise `start`/`end` in the panel zone (`timeBase.effectiveTokenZone`, else UTC + warning).
     */
    fun epgListings(input: InputStream, timeBase: TimeBase): List<XtreamEpgListing> {
        val root = treeOrNull(input) ?: return emptyList()
        val rows = when (root) {
            is JsonObject -> objOrArrayValues(root, "epg_listings")
            is JsonArray -> root
            else -> emptyList()
        }.filterIsInstance<JsonObject>()
        if (rows.isEmpty()) return emptyList()
        val base64 = Base64OrPlain.isBase64Response(rows.asSequence().map { str(it, "title") })
        val zone: ZoneId = timeBase.effectiveTokenZone ?: ZoneOffset.UTC
        var warnedUtc = false
        val out = ArrayList<XtreamEpgListing>(rows.size)
        for (o in rows) {
            var start = long(o, "start_timestamp")
            var stop = long(o, "stop_timestamp") ?: long(o, "end_timestamp")
            if (start == null || stop == null) {
                if (timeBase.effectiveTokenZone == null && !warnedUtc) {
                    warnedUtc = true
                    BoxLog.w(TAG, "EPG listing without *_timestamp and no panel zone known: reading start/end as UTC")
                }
                if (start == null) start = panelLocal(str(o, "start"), zone)
                if (stop == null) stop = panelLocal(str(o, "end") ?: str(o, "stop"), zone)
            }
            if (start == null || stop == null) continue
            val title = Base64OrPlain.field(str(o, "title"), base64)?.trim().orEmpty()
            out += XtreamEpgListing(
                title = title,
                description = Base64OrPlain.field(str(o, "description"), base64)?.trim()?.ifEmpty { null },
                startUtcSec = start,
                stopUtcSec = stop,
                nowPlaying = bool(o, "now_playing") ?: false,
                hasArchive = bool(o, "has_archive"),
            )
        }
        return out
    }

    /** `yyyy-MM-dd HH:mm:ss` in [zone] → epoch seconds, or null. */
    internal fun panelLocal(s: String?, zone: ZoneId): Long? {
        if (s == null) return null
        val t = s.trim().replace(' ', 'T')
        val ldt = try {
            LocalDateTime.parse(t)
        } catch (_: Exception) {
            return null
        }
        return ldt.atZone(zone).toEpochSecond()
    }
}
