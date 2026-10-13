package app.thebox.core.catchup

import app.thebox.core.common.BoxLog
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.xtream.XtreamUrls
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Per-mode archive URL builders and probe-variant lists (docs/design/m1-data-layer.md §3.8.4–3.8.7).
 *
 * Times: `S = archiveStart(programmeStart)` (correction **subtracted**, padding before), `E = archiveStop(stop)`
 * or `N` when open-ended, `N = deviceNow + clockSkew`, `D = E − S`. The `xc` segment is formatted in
 * `effectiveTokenZone` (the panel zone), template tokens in `effectiveTemplateZone`; both fall back to UTC with a
 * diagnostic.
 */
class CatchupUrlBuilderImpl : CatchupUrlBuilder {

    /** Probe order with [CatchupTarget.winner] (`playlist.catchup_probe_winner`) moved first when it belongs to this target's list. */
    override fun variants(target: CatchupTarget): List<String> {
        val winner = target.winner
        val mode = target.catchup.mode
        val own: List<String> = when {
            mode == CatchupMode.XC -> XC_VARIANTS
            mode.isFlussonic -> FLUSSONIC_VARIANTS
            mode == CatchupMode.DEFAULT -> listOf(CatchupVariants.DEFAULT)
            mode == CatchupMode.APPEND -> listOf(CatchupVariants.APPEND)
            mode == CatchupMode.VOD -> listOf(CatchupVariants.VOD)
            mode == CatchupMode.SHIFT ->
                if (target.xtream != null) listOf(CatchupVariants.SHIFT, CatchupVariants.XC_PATH, CatchupVariants.XC_PHP)
                else listOf(CatchupVariants.SHIFT)
            else -> emptyList()
        }
        val overridable = mode == CatchupMode.XC || mode == CatchupMode.SHIFT || mode.isFlussonic
        val list = if (overridable && isAbsolute(target.catchup.source)) listOf(CatchupVariants.DEFAULT) + own else own
        if (winner == null || winner !in list || list.first() == winner) return list
        return listOf(winner) + (list - winner)
    }

    override fun build(target: CatchupTarget, timeBase: TimeBase, window: ArchiveWindow, variant: String?): CatchupBuild {
        val catchup = target.catchup
        val mode = catchup.mode
        if (mode == CatchupMode.OFF) return CatchupBuild.Unsupported("catch-up is off for this channel")
        val list = variants(target)
        val v = variant ?: list.firstOrNull() ?: return CatchupBuild.Unsupported("no archive variant for mode $mode")
        if (v !in list) return CatchupBuild.Unsupported("variant $v does not apply to mode $mode")

        // ---- window
        val corr = catchup.correctionHours
        val nowI = window.deviceNow.plus(timeBase.clockSkew)
        val now = nowI.epochSecond
        if (window.programmeStart.isAfter(nowI)) return CatchupBuild.Unsupported("the programme has not started yet")
        val days = catchup.days.coerceAtLeast(1)
        val windowStart = now - days * SECONDS_PER_DAY
        val correctedStart = timeBase.copy(paddingBefore = Duration.ZERO).archiveStart(window.programmeStart, corr).epochSecond
        if (correctedStart < windowStart) {
            return CatchupBuild.Unsupported("the programme is older than the $days-day archive")
        }
        var s = timeBase.archiveStart(window.programmeStart, corr).epochSecond
        if (s < windowStart) s = windowStart + EDGE_MARGIN_SEC // only the padding crossed the archive edge (S17)
        val open = window.programmeStop == null
        var e = window.programmeStop?.let { timeBase.archiveStop(it, corr).epochSecond } ?: now

        val isXc = v == CatchupVariants.XC_PATH || v == CatchupVariants.XC_PHP || v == CatchupVariants.XC_PATH_SECONDS
        if (open && isXc) e = s + minOf(now - s, MAX_OPEN_XC_SEC).coerceAtLeast(0) // B5.1
        val d = e - s

        val diagnostics = ArrayList<CatchupDiagnostic>(2)
        // The template zone only matters when the variant expands a template.
        val templateZone: ZoneId = timeBase.effectiveTemplateZone ?: ZoneOffset.UTC.also {
            if (v == CatchupVariants.DEFAULT || v == CatchupVariants.APPEND || v == CatchupVariants.VOD) {
                BoxLog.i(TAG) { "template zone unknown: using UTC for date tokens" }
                diagnostics += CatchupDiagnostic.TemplateZoneUnknown
            }
        }
        val values = TemplateExpander.Values(s, e, now, templateZone, window.catchupId)
        val seekRange = s..e

        fun ok(url: String, seek: LongRange?) = CatchupBuild.Ok(
            ArchiveRequest(
                url = url,
                headers = target.headers,
                mode = mode,
                variant = v,
                requestStart = Instant.ofEpochSecond(s),
                requestEnd = if (open) null else Instant.ofEpochSecond(e),
                nativeSeekRange = seek,
            ),
            diagnostics.toList(),
        )

        return when (v) {
            CatchupVariants.DEFAULT -> {
                val src = catchup.source ?: return CatchupBuild.Unsupported("no catchup-source")
                ok(resolveAgainst(target.liveUrl, expand(src, values, diagnostics)), null)
            }
            CatchupVariants.APPEND -> {
                val src = catchup.source ?: return CatchupBuild.Unsupported("no catchup-source")
                var tail = expand(src, values, diagnostics)
                if (tail.startsWith("?") && target.liveUrl.contains('?')) tail = "&" + tail.substring(1)
                ok(target.liveUrl + tail, null)
            }
            CatchupVariants.SHIFT -> {
                val sep = if (target.liveUrl.contains('?')) "&" else "?"
                ok("${target.liveUrl}${sep}utc=$s&lutc=$now", null)
            }
            CatchupVariants.VOD -> {
                if (window.catchupId.isNullOrEmpty()) return CatchupBuild.Unsupported("no catchup-id")
                val src = catchup.source ?: return CatchupBuild.Unsupported("no catchup-source")
                ok(resolveAgainst(target.liveUrl, expand(src, values, diagnostics)), seekRange)
            }
            CatchupVariants.XC_PATH, CatchupVariants.XC_PHP, CatchupVariants.XC_PATH_SECONDS -> {
                val ref = target.xtream ?: return CatchupBuild.Unsupported("no Xtream stream reference for xc catch-up")
                val zone: ZoneId = timeBase.effectiveTokenZone ?: ZoneOffset.UTC.also {
                    BoxLog.w(TAG, "panel time zone unknown, set it in playlist settings: xc catch-up uses UTC")
                    diagnostics += CatchupDiagnostic.PanelZoneUnknown
                }
                val urls = XtreamUrls(ref.base, ref.username, ref.password)
                val ext = ref.extension.trim().trimStart('.').ifEmpty { "ts" }
                val minutes = ceilDiv(d.coerceAtLeast(1), 60)
                when (v) {
                    CatchupVariants.XC_PHP ->
                        ok(urls.timeshiftPhp(ref.streamId, TokenFormatter.format(s, zone, TokenFormatter.XC_MINUTES), minutes), null)
                    else -> {
                        val pattern = if (v == CatchupVariants.XC_PATH) TokenFormatter.XC_MINUTES else TokenFormatter.XC_SECONDS
                        val seek = if (ext.equals("m3u8", ignoreCase = true)) seekRange else null
                        ok(urls.timeshiftPath(minutes, TokenFormatter.format(s, zone, pattern), ref.streamId, ext), seek)
                    }
                }
            }
            CatchupVariants.FLUSSONIC_RANGE, CatchupVariants.FLUSSONIC_ABS, CatchupVariants.FLUSSONIC_KODI -> {
                val f = FlussonicUrl.parse(target.liveUrl)
                    ?: return CatchupBuild.Unsupported("the live URL is not a Flussonic URL")
                val container = f.containerFor(mode)
                when (v) {
                    CatchupVariants.FLUSSONIC_RANGE ->
                        if (open) ok(f.rangeOpen(container, s), null) else ok(f.range(container, s, d), seekRange)
                    CatchupVariants.FLUSSONIC_ABS ->
                        f.absolute(container, s)?.let { ok(it, null) } ?: CatchupBuild.Unsupported("no absolute timeshift for DASH")
                    else ->
                        f.kodi(container, s, now)?.let { ok(it, null) } ?: CatchupBuild.Unsupported("no Kodi conversion for DASH")
                }
            }
            else -> CatchupBuild.Unsupported("unknown variant $v")
        }
    }

    private fun expand(template: String, values: TemplateExpander.Values, diagnostics: MutableList<CatchupDiagnostic>): String {
        val r = TemplateExpander.expand(template, values)
        if (r.unknown.isNotEmpty()) {
            val unknown = r.unknown.distinct().take(5)
            BoxLog.w(TAG, "unknown catch-up placeholders left as written: $unknown")
            diagnostics += CatchupDiagnostic.UnknownPlaceholders(unknown)
        }
        return r.url
    }

    companion object {
        private const val TAG = "CatchupUrlBuilder"
        private const val SECONDS_PER_DAY = 86_400L
        private const val EDGE_MARGIN_SEC = 60L
        private const val MAX_OPEN_XC_SEC = 180L * 60

        private val XC_VARIANTS = listOf(CatchupVariants.XC_PATH, CatchupVariants.XC_PHP, CatchupVariants.XC_PATH_SECONDS)
        private val FLUSSONIC_VARIANTS =
            listOf(CatchupVariants.FLUSSONIC_RANGE, CatchupVariants.FLUSSONIC_ABS, CatchupVariants.FLUSSONIC_KODI)
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

        internal fun isAbsolute(source: String?): Boolean = source != null && SCHEME.containsMatchIn(source.trim())

        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

        /** Resolves an expanded (possibly relative) source against the live URL. */
        internal fun resolveAgainst(liveUrl: String, ref: String): String {
            if (SCHEME.containsMatchIn(ref)) return ref
            val schemeEnd = liveUrl.indexOf("://")
            if (schemeEnd < 0) return ref
            if (ref.startsWith("//")) return liveUrl.substring(0, schemeEnd + 1) + ref
            val authorityEnd = liveUrl.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3).let { if (it < 0) liveUrl.length else it }
            val origin = liveUrl.substring(0, authorityEnd)
            val pathAndRest = liveUrl.substring(authorityEnd)
            val queryAt = pathAndRest.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) pathAndRest.length else it }
            val path = pathAndRest.substring(0, queryAt)
            return when {
                ref.startsWith("/") -> origin + ref
                ref.startsWith("?") -> origin + path + ref
                ref.isEmpty() -> liveUrl
                else -> origin + path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" } + ref
            }
        }
    }
}
