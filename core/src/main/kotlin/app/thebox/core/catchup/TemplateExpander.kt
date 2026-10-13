package app.thebox.core.catchup

import app.thebox.core.xtream.XtreamUrls
import java.time.ZoneId

/**
 * Catch-up placeholder expansion (docs/design/m1-data-layer.md §3.8.4; protocols §4.3). Single pass: substituted
 * values are never re-scanned. Unknown `{…}` tokens are left as written and reported in [Result.unknown].
 *
 * Both the `{name}` and the `${name}` spelling are accepted for every name.
 *
 * | Token(s) | Value |
 * |---|---|
 * | `{utc}` `{start}` | [Values.start], unix seconds |
 * | `{utcend}` `{end}` | [Values.end] |
 * | `{lutc}` `{now}` `{timestamp}` | [Values.now] |
 * | `{duration}` / `{duration:N}` | D, D / N |
 * | `{offset}` / `{offset:N}` | now − start, (now − start) / N |
 * | `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` | start in [Values.zone] |
 * | `{utc:FMT}` `{start:FMT}` / `{utcend:FMT}` `{end:FMT}` / `{lutc:FMT}` `{now:FMT}` | TokenFormatter pattern |
 * | `{start-iso}` `{end-iso}` `{now-iso}` | ISO-8601 with offset, in the zone |
 * | `{catchup-id}` | percent-encoded as a query value |
 */
object TemplateExpander {
    /** Epoch seconds; [zone] is the template zone (`effectiveTemplateZone ?: UTC`). */
    data class Values(val start: Long, val end: Long, val now: Long, val zone: ZoneId, val catchupId: String? = null) {
        val duration: Long get() = end - start
    }

    data class Result(val url: String, val unknown: List<String>, val usesCatchupId: Boolean)

    private val FIELDS = setOf("Y", "m", "d", "H", "M", "S")

    fun expand(template: String, v: Values): Result {
        val sb = StringBuilder(template.length + 32)
        var unknown: MutableList<String>? = null
        var usesId = false
        var i = 0
        val n = template.length
        while (i < n) {
            val c = template[i]
            val dollar = c == '$' && i + 1 < n && template[i + 1] == '{'
            if (c != '{' && !dollar) {
                sb.append(c)
                i++
                continue
            }
            val open = if (dollar) i + 1 else i
            val close = template.indexOf('}', open + 1)
            if (close < 0) {
                sb.append(template, i, n)
                break
            }
            val body = template.substring(open + 1, close)
            if (body == "catchup-id") usesId = true
            val value = resolve(body, v)
            if (value == null) {
                val raw = template.substring(i, close + 1)
                (unknown ?: ArrayList<String>().also { unknown = it }).add(raw)
                sb.append(raw)
            } else {
                sb.append(value)
            }
            i = close + 1
        }
        return Result(sb.toString(), unknown ?: emptyList(), usesId)
    }

    private fun resolve(body: String, v: Values): String? {
        if (body in FIELDS) return TokenFormatter.field(v.start, v.zone, body[0])
        val colon = body.indexOf(':')
        if (colon >= 0) {
            val name = body.substring(0, colon)
            val arg = body.substring(colon + 1)
            return when (name) {
                "duration" -> arg.toLongOrNull()?.takeIf { it > 0 }?.let { (v.duration / it).toString() }
                "offset" -> arg.toLongOrNull()?.takeIf { it > 0 }?.let { ((v.now - v.start) / it).toString() }
                "utc", "start" -> TokenFormatter.format(v.start, v.zone, arg)
                "utcend", "end" -> TokenFormatter.format(v.end, v.zone, arg)
                "lutc", "now", "timestamp" -> TokenFormatter.format(v.now, v.zone, arg)
                else -> null
            }
        }
        return when (body) {
            "utc", "start" -> v.start.toString()
            "utcend", "end" -> v.end.toString()
            "lutc", "now", "timestamp" -> v.now.toString()
            "duration" -> v.duration.toString()
            "offset" -> (v.now - v.start).toString()
            "start-iso" -> TokenFormatter.iso(v.start, v.zone)
            "end-iso" -> TokenFormatter.iso(v.end, v.zone)
            "now-iso" -> TokenFormatter.iso(v.now, v.zone)
            "catchup-id" -> v.catchupId?.let { XtreamUrls.query(it) } ?: ""
            else -> null
        }
    }
}
