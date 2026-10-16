package app.thebox.core.playback.resolve

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.playback.CredentialExpander
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.ResolveInput
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.StreamUrlBuilder
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.source.PlaylistType
import app.thebox.core.source.XtreamStreamUrl
import app.thebox.core.xtream.XtreamStreamRef
import app.thebox.core.xtream.XtreamUrls
import java.util.Locale

object ResolveConstants {
    /**
     * `PlaylistPlaybackInfo.playlistId` of a `TuneTarget.DirectUrl` resolution: the result has no identity and the
     * broker key `d:<host>` (§3.2.1).
     */
    const val DIRECT_PLAYLIST_ID = -1L
    const val PLAYLIST_BROKER_PREFIX = "p:"
    const val DIRECT_BROKER_PREFIX = "d:"
    const val EXT_TS = "ts"
    const val EXT_HLS = "m3u8"

    fun playlistBrokerKey(playlistId: Long): String = PLAYLIST_BROKER_PREFIX + playlistId
}

/**
 * §3.2 steps 2–7 (pure, except that it never logs or persists anything it builds).
 *
 * Kind detection (§3.2.2): an `XTREAM` playlist whose template contains `{{base}}` is **Xtream API**; an M3U template
 * with `{{u}}`/`{{p}}` is **Xtream-shaped M3U**; anything else is **plain** and used as is.
 */
class StreamUrlBuilderImpl(private val expander: CredentialExpander = CredentialExpanderImpl()) : StreamUrlBuilder {

    override fun build(input: ResolveInput): ResolveOutcome {
        val ch = input.channel
        val pl = input.playlist
        val creds = input.credentials
        val template = ch.urlTemplate
        val direct = pl.playlistId == ResolveConstants.DIRECT_PLAYLIST_ID

        val headerTemplates = HeaderPolicy.merge(
            playlist = pl.extraHeaders,
            channel = ch.headers,
            playlistUserAgent = pl.userAgent,
            defaultUserAgent = input.defaultUserAgent,
            userAgentOverride = input.userAgentOverride,
            secret = creds?.secretHeaders ?: emptyMap(),
        )

        val needsCreds = Placeholders.hasCredentials(template) || headerTemplates.values.any { Placeholders.hasCredentials(it) }
        if (needsCreds && (creds?.username == null || creds.password == null)) {
            return ResolveOutcome.Failed(PlaybackErrorCode.NEEDS_REAUTH, "template needs credentials")
        }

        val xtreamApi = !direct && pl.type == PlaylistType.XTREAM && template.contains(Placeholders.BASE)
        val baseCount: Int
        val baseIndex: Int
        val liveUrlTemplate: String
        val output: XtreamOutput?
        var shaped = false
        if (xtreamApi) {
            val bases = creds?.baseUrls.orEmpty()
            if (bases.isEmpty()) return ResolveOutcome.Failed(PlaybackErrorCode.NEEDS_REAUTH, "no Xtream base URL")
            baseCount = bases.size
            baseIndex = Math.floorMod(input.baseIndex, baseCount)
            output = xtreamOutput(input)
            liveUrlTemplate = expander.expandNonSecret(template, bases[baseIndex], output.extension)
        } else {
            baseCount = 1
            baseIndex = 0
            shaped = !direct && pl.type != PlaylistType.XTREAM && Placeholders.hasCredentials(template)
            val t = expander.expandNonSecret(template, null, null)
            if (shaped) {
                val wanted = shapedOutput(input)
                val flipped = if (wanted != null) flipShapedExtension(t, wanted) else t
                liveUrlTemplate = flipped
                output = outputOfUrl(flipped)
            } else {
                liveUrlTemplate = t
                output = null
            }
        }
        if (liveUrlTemplate.contains(Placeholders.BASE)) {
            return ResolveOutcome.Failed(PlaybackErrorCode.NEEDS_REAUTH, "template needs a base URL")
        }

        val user = creds?.username
        val pass = creds?.password
        val url = expander.expandCredentials(liveUrlTemplate, user, pass)
        val host = FormatHints.host(url)
        if (host.isEmpty()) return ResolveOutcome.Failed(PlaybackErrorCode.UNSUPPORTED_SOURCE, "not an absolute URL")

        val expanded = LinkedHashMap<String, String>(headerTemplates.size * 2)
        for ((k, v) in headerTemplates) expanded[k] = expander.expandHeaderValue(v, user, pass)
        val (headers, droppedHeaders) = HeaderPolicy.sanitize(expanded)

        val xtreamRef: XtreamStreamRef? = when {
            xtreamApi -> {
                val streamId = ch.xtreamStreamId ?: XtreamStreamUrl.parse(url, allowShort = false)?.streamId
                if (streamId != null && user != null && pass != null) {
                    XtreamStreamRef(XtreamUrls.normalizeBase(creds.baseUrls[baseIndex]), user, pass, streamId, output!!.extension!!)
                } else null
            }
            shaped -> XtreamStreamUrl.parse(url, allowShort = true)?.let {
                XtreamStreamRef(it.base, it.username, it.password, it.streamId, it.extension ?: ResolveConstants.EXT_TS)
            }
            else -> null
        }

        return ResolveOutcome.Ok(
            ResolvedStream(
                identity = if (direct) null else ch.identity,
                brokerKey = if (direct) ResolveConstants.DIRECT_BROKER_PREFIX + host else ResolveConstants.playlistBrokerKey(pl.playlistId),
                url = url,
                headers = headers,
                formatHint = FormatHints.hint(url),
                xtream = xtreamRef,
                output = output,
                baseIndex = baseIndex,
                baseCount = baseCount,
                host = host,
                ignoreTlsErrors = !direct && pl.ignoreTlsErrors,
                liveUrlTemplate = liveUrlTemplate,
                headerTemplates = headerTemplates,
                droppedHeaders = droppedHeaders,
                baseHosts = if (xtreamApi && baseCount > 1) creds?.baseUrls.orEmpty().map { FormatHints.host(it) } else emptyList(),
            ),
        )
    }

    /**
     * Archive requests (§3.14): [request] was built by `CatchupUrlBuilder` from `liveUrlTemplate`, so its URL and
     * headers may still hold `{{u}}`/`{{p}}`. They are expanded here, last (M1 code review Later 28).
     */
    fun buildArchive(input: ResolveInput, request: ArchiveRequest): ResolveOutcome {
        val creds = input.credentials
        val needsCreds = Placeholders.hasCredentials(request.url) || request.headers.values.any { Placeholders.hasCredentials(it) }
        if (needsCreds && (creds?.username == null || creds.password == null)) {
            return ResolveOutcome.Failed(PlaybackErrorCode.NEEDS_REAUTH, "template needs credentials")
        }
        val user = creds?.username
        val pass = creds?.password
        val url = expander.expandCredentials(request.url, user, pass)
        val host = FormatHints.host(url)
        if (host.isEmpty()) return ResolveOutcome.Failed(PlaybackErrorCode.UNSUPPORTED_SOURCE, "not an absolute URL")
        val expanded = LinkedHashMap<String, String>(request.headers.size * 2)
        for ((k, v) in request.headers) expanded[k] = expander.expandHeaderValue(v, user, pass)
        val (headers, droppedHeaders) = HeaderPolicy.sanitize(expanded)
        val bases = creds?.baseUrls.orEmpty()
        return ResolveOutcome.Ok(
            ResolvedStream(
                identity = input.channel.identity,
                brokerKey = ResolveConstants.playlistBrokerKey(input.playlist.playlistId),
                url = url,
                headers = headers,
                formatHint = FormatHints.hint(url),
                xtream = null,
                output = null,
                baseIndex = if (bases.isEmpty()) 0 else Math.floorMod(input.baseIndex, bases.size),
                baseCount = bases.size.coerceAtLeast(1),
                host = host,
                ignoreTlsErrors = input.playlist.ignoreTlsErrors,
                liveUrlTemplate = request.url,
                headerTemplates = request.headers,
                droppedHeaders = droppedHeaders,
                baseHosts = if (bases.size > 1) bases.map { FormatHints.host(it) } else emptyList(),
            ),
        )
    }

    companion object {
        /** §3.2.3 for Xtream API playlists. Never AUTO. */
        fun xtreamOutput(input: ResolveInput): XtreamOutput {
            input.output?.takeIf { it != XtreamOutput.AUTO }?.let { return it }
            return when (input.playlist.xtreamOutput) {
                XtreamOutput.TS -> XtreamOutput.TS
                XtreamOutput.HLS -> XtreamOutput.HLS
                XtreamOutput.AUTO -> input.playlist.xtreamOutputLearned?.takeIf { it != XtreamOutput.AUTO } ?: run {
                    val allowed = input.account?.allowedOutputFormats.orEmpty().map { it.trim().lowercase(Locale.ROOT) }
                    if (allowed.isNotEmpty() && ResolveConstants.EXT_TS !in allowed && ResolveConstants.EXT_HLS in allowed) {
                        XtreamOutput.HLS
                    } else XtreamOutput.TS
                }
            }
        }

        /**
         * Xtream-shaped M3U: the literal extension is kept unless something asks for a format: a ladder flip, a forced
         * setting, or a learned output. AUTO with nothing learned keeps what the playlist wrote.
         */
        fun shapedOutput(input: ResolveInput): XtreamOutput? {
            input.output?.takeIf { it != XtreamOutput.AUTO }?.let { return it }
            return when (input.playlist.xtreamOutput) {
                XtreamOutput.TS -> XtreamOutput.TS
                XtreamOutput.HLS -> XtreamOutput.HLS
                XtreamOutput.AUTO -> input.playlist.xtreamOutputLearned?.takeIf { it != XtreamOutput.AUTO }
            }
        }

        /**
         * Replaces the extension of an Xtream-shaped URL's last path segment (`<digits>`, `<digits>.ts` or
         * `<digits>.m3u8`) with [to]'s. An absent extension stays absent for TS (`http://h/u/p/123` is TS by
         * convention). Any other shape is returned unchanged.
         */
        fun flipShapedExtension(url: String, to: XtreamOutput): String {
            var end = url.length
            for (i in url.indices) if (url[i] == '?' || url[i] == '#') { end = i; break }
            val slash = url.lastIndexOf('/', end - 1)
            if (slash < 0) return url
            val segment = url.substring(slash + 1, end)
            val dot = segment.indexOf('.')
            val digits = if (dot < 0) segment else segment.substring(0, dot)
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return url
            val ext = if (dot < 0) null else segment.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext != null && ext != ResolveConstants.EXT_TS && ext != ResolveConstants.EXT_HLS) return url
            val newSegment = when (to) {
                XtreamOutput.HLS -> "$digits.${ResolveConstants.EXT_HLS}"
                XtreamOutput.TS -> if (ext == null) digits else "$digits.${ResolveConstants.EXT_TS}"
                XtreamOutput.AUTO -> segment
            }
            return url.substring(0, slash + 1) + newSegment + url.substring(end)
        }

        /** Output implied by a shaped URL's extension: `.ts` or none → TS, `.m3u8` → HLS, other → null (no flip possible). */
        fun outputOfUrl(url: String): XtreamOutput? {
            val path = FormatHints.path(url)
            val segment = path.substring(path.lastIndexOf('/') + 1)
            val dot = segment.indexOf('.')
            if (dot < 0) return if (segment.isNotEmpty() && segment.all { it in '0'..'9' }) XtreamOutput.TS else null
            return when (segment.substring(dot + 1).lowercase(Locale.ROOT)) {
                ResolveConstants.EXT_TS -> XtreamOutput.TS
                ResolveConstants.EXT_HLS -> XtreamOutput.HLS
                else -> null
            }
        }
    }
}
