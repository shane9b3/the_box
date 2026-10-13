package app.thebox.core.catchup

import app.thebox.core.common.BoxLog
import app.thebox.core.model.TimeBase
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Panel zone, skew and template zone (docs/design/m1-data-layer.md §3.8.3, review B1).
 *
 * @param deviceZone resolves `TokenZoneSetting.Device` when the TimeBase is built (injectable for tests).
 */
class TimeBaseFactoryImpl(private val deviceZone: () -> ZoneId = { ZoneId.systemDefault() }) : TimeBaseFactory {

    override fun fromAccount(type: PlaylistType, info: XtreamAccountInfo, settings: TimeBaseSettings): TimeBase {
        val server = info.server
        var zone = validZone(server.timezone)
        val offset = serverOffset(server.timeNow, server.timestampNow)
        if (zone != null && offset != null) {
            val at = server.timestampNow?.let { Instant.ofEpochSecond(it) } ?: Instant.ofEpochMilli(info.measuredAtDeviceMs)
            val zoneOffset = zone.rules.getOffset(at)
            if (zoneOffset != offset) {
                BoxLog.w(
                    TAG,
                    "panel zone ${server.timezone} is $zoneOffset now but time_now says $offset: " +
                        "stale tzdata or misconfigured panel, using the measured fixed offset",
                )
                zone = null
            }
        } else if (server.timezone != null && zone == null) {
            BoxLog.i(TAG) { "panel timezone '${server.timezone}' is not an IANA region; using offset $offset" }
        }
        var skew = Duration.ZERO
        val ts = server.timestampNow
        if (ts != null) {
            val s = ts - Math.floorDiv(info.measuredAtDeviceMs, 1000L)
            if (Math.abs(s) < MAX_SKEW_SEC) {
                skew = Duration.ofSeconds(s)
            } else {
                BoxLog.w(TAG, "panel clock differs from the device by ${s}s (> 24 h): skew ignored")
            }
        }
        return build(type, settings, zone, offset, skew)
    }

    override fun forM3u(settings: TimeBaseSettings): TimeBase =
        build(PlaylistType.M3U_URL, settings, null, null, Duration.ZERO)

    private fun build(type: PlaylistType, settings: TimeBaseSettings, zone: ZoneId?, offset: ZoneOffset?, skew: Duration): TimeBase {
        val tokenZone: ZoneId? = when (val t = settings.tokenZone) {
            TokenZoneSetting.Auto, TokenZoneSetting.Server -> null
            TokenZoneSetting.Utc -> ZoneOffset.UTC
            TokenZoneSetting.Device -> deviceZone()
            is TokenZoneSetting.Fixed -> try {
                ZoneId.of(t.zoneId.trim())
            } catch (e: Exception) {
                BoxLog.w(TAG, "invalid fixed token zone '${t.zoneId}': ignored")
                null
            }
        }
        val templateZone: ZoneId? =
            if (settings.tokenZone == TokenZoneSetting.Auto && type != PlaylistType.XTREAM) ZoneOffset.UTC else null
        return TimeBase(
            serverZone = zone,
            serverOffset = offset,
            clockSkew = skew,
            correctionHours = settings.correctionHours ?: settings.headerCorrectionHours ?: 0.0,
            tokenZone = tokenZone,
            paddingBefore = Duration.ofSeconds(settings.paddingBeforeSec.toLong().coerceAtLeast(0)),
            paddingAfter = Duration.ofSeconds(settings.paddingAfterSec.toLong().coerceAtLeast(0)),
            templateZone = templateZone,
        )
    }

    companion object {
        private const val TAG = "TimeBaseFactory"
        private const val MAX_SKEW_SEC = 24L * 3600

        /**
         * `server_info.timezone` accepted only when `ZoneId.of` succeeds, the result is a region (not an offset id
         * such as `UTC+3`), and the id contains `/` or is `UTC`/`Etc/UTC`.
         */
        fun validZone(raw: String?): ZoneId? {
            val id = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val z = try {
                ZoneId.of(id)
            } catch (_: Exception) {
                return null
            }
            // "UTC+3" parses to the region-like id "UTC+03:00": rejected by the id rule below.
            if (z is ZoneOffset) return null
            return if (z.id.contains('/') || z.id == "UTC") z else null
        }

        /** `time_now − timestamp_now` snapped to 15 min, or null when either is missing or unparseable. */
        fun serverOffset(timeNow: String?, timestampNow: Long?): ZoneOffset? {
            if (timeNow == null || timestampNow == null) return null
            val ldt = try {
                LocalDateTime.parse(timeNow.trim().replace(' ', 'T'))
            } catch (_: Exception) {
                return null
            }
            return TimeBase.offsetFromServerInfo(ldt, timestampNow)
        }
    }
}
