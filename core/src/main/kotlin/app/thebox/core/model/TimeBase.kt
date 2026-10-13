package app.thebox.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * Per-playlist clock model used by the catch-up URL builder (master plan §3 "Catch-up").
 *
 * @property serverZone the panel's zone (Xtream `server_info.timezone`, IANA); null when missing
 *   or not IANA.
 * @property serverOffset fallback when [serverZone] is null: the panel's UTC offset derived from
 *   `server_info.time_now − timestamp_now` (see [offsetFromServerInfo]). A fixed offset, so it is
 *   wrong across a DST change; refresh it on every login.
 * @property clockSkew panel clock minus device clock (`timestamp_now − device epoch` at response
 *   time). Used for "now" (live edge, archive window), never for programme times.
 * @property correctionHours M3U `catchup-correction` / user shift; **subtracted** from the
 *   programme UTC start (so `-4.0` requests 4 h later). A per-channel value overrides it.
 * @property tokenZone explicit per-playlist zone for `{Y}{m}{d}{H}{M}{S}`-style URL tokens and the
 *   Xtream timeshift segment. When null it falls back to the panel zone ([effectiveTokenZone]).
 *   Never the device zone by accident.
 * @property paddingBefore extra archive requested before the programme start.
 * @property paddingAfter extra archive requested after the programme end.
 */
data class TimeBase(
    val serverZone: ZoneId? = null,
    val serverOffset: ZoneOffset? = null,
    val clockSkew: Duration = Duration.ZERO,
    val correctionHours: Double = 0.0,
    val tokenZone: ZoneId? = null,
    val paddingBefore: Duration = Duration.ZERO,
    val paddingAfter: Duration = Duration.ZERO,
) {
    /**
     * Zone used to format panel-local catch-up times: [tokenZone] → [serverZone] →
     * [serverOffset]. Null means the panel zone is unknown; the builder must then use the
     * playlist's explicit setting or UTC, never `ZoneId.systemDefault()` silently.
     */
    val effectiveTokenZone: ZoneId? get() = tokenZone ?: serverZone ?: serverOffset

    /**
     * Archive request start for a programme starting at [programmeStart] (UTC):
     * `programmeStart − correction − paddingBefore`. [channelCorrectionHours] (M3U per-channel
     * `catchup-correction`) overrides [correctionHours] when non-null.
     */
    fun archiveStart(programmeStart: Instant, channelCorrectionHours: Double? = null): Instant =
        programmeStart.minus(correction(channelCorrectionHours)).minus(paddingBefore)

    /** Archive request end for a programme ending at [programmeStop], same correction rule. */
    fun archiveStop(programmeStop: Instant, channelCorrectionHours: Double? = null): Instant =
        programmeStop.minus(correction(channelCorrectionHours)).plus(paddingAfter)

    private fun correction(channelCorrectionHours: Double?): Duration =
        Duration.ofSeconds(((channelCorrectionHours ?: correctionHours) * 3600).roundToLong())

    companion object {
        val DEFAULT = TimeBase()

        /**
         * Panel UTC offset from Xtream `server_info`: `time_now` (panel wall clock) read as UTC,
         * minus `timestamp_now`, snapped to 15 minutes (protocols reference §3.3).
         */
        fun offsetFromServerInfo(timeNow: LocalDateTime, timestampNow: Long): ZoneOffset {
            val diffSeconds = timeNow.toEpochSecond(ZoneOffset.UTC) - timestampNow
            val quarterHours = Math.round(diffSeconds / 900.0)
            return ZoneOffset.ofTotalSeconds((quarterHours * 900).toInt().coerceIn(-18 * 3600, 18 * 3600))
        }
    }
}
