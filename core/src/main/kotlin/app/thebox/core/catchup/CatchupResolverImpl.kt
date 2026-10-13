package app.thebox.core.catchup

import app.thebox.core.model.ArchiveDaysSource
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.source.RawCatchup
import app.thebox.core.source.XtreamStreamUrl

/** Mode chain and archive-days chain (docs/design/m1-data-layer.md §3.8.1–3.8.2; protocols §4.1, §4.5). */
class CatchupResolverImpl : CatchupResolver {
    override fun resolve(raw: RawCatchup, urlTemplate: String, xtreamStreamId: Long?, defaults: PlaylistCatchupDefaults): Catchup {
        val header = defaults.header
        val source = (raw.source ?: header.source)?.trim()?.ifEmpty { null }
        var mode = when (val setting = defaults.modeSetting) {
            CatchupModeSetting.Off -> CatchupMode.OFF
            is CatchupModeSetting.Forced -> setting.mode
            CatchupModeSetting.Auto -> autoMode(raw, urlTemplate, defaults, source)
        }
        // Post-rule: default/append without a source behave as SIPTV shift (diagnostic "no catchup-source").
        if ((mode == CatchupMode.DEFAULT || mode == CatchupMode.APPEND) && source == null) mode = CatchupMode.SHIFT
        if (mode == CatchupMode.OFF) {
            return Catchup(CatchupMode.OFF, days = 0, source = source, correctionHours = raw.correctionHours, daysSource = null)
        }
        val (days, daysSource) = archiveDays(raw, defaults)
        return Catchup(mode, days.coerceIn(MIN_DAYS, MAX_DAYS), source, raw.correctionHours, daysSource)
    }

    private fun autoMode(raw: RawCatchup, url: String, defaults: PlaylistCatchupDefaults, source: String?): CatchupMode {
        // a. channel attribute (unrecognised non-empty values fall through)
        CatchupMode.fromM3uAttribute(raw.type).let { if (it != CatchupMode.OFF) return it }
        // b. timeshift= / tvg-rec= force SIPTV shift (Kodi)
        if ((raw.timeshiftDays ?: 0) > 0 || (raw.tvgRecDays ?: 0) > 0) return CatchupMode.SHIFT
        // c. Xtream tv_archive is authoritative either way
        when (raw.xtreamTvArchive) {
            true -> return CatchupMode.XC
            false -> return CatchupMode.OFF
            null -> Unit
        }
        // d. playlist header default
        CatchupMode.fromM3uAttribute(defaults.header.type).let { if (it != CatchupMode.OFF) return it }
        // e. a source alone means a full template
        if (source != null) return CatchupMode.DEFAULT
        // f. Xtream-shaped URL in an Xtream-shaped playlist (review B6) with days advertised
        if (defaults.xtreamShaped &&
            ((raw.days ?: 0) > 0 || (raw.catchupTimeSec ?: 0) > 0) &&
            XtreamStreamUrl.parse(url, allowShort = true) != null
        ) {
            return CatchupMode.XC
        }
        // g.
        return CatchupMode.OFF
    }

    private fun archiveDays(raw: RawCatchup, defaults: PlaylistCatchupDefaults): Pair<Int, ArchiveDaysSource> {
        defaults.daysOverride?.let { if (it > 0) return it to ArchiveDaysSource.PLAYLIST_OVERRIDE }
        raw.days?.let { if (it > 0) return it to ArchiveDaysSource.CATCHUP_DAYS }
        raw.timeshiftDays?.let { if (it > 0) return it to ArchiveDaysSource.TIMESHIFT }
        raw.tvgRecDays?.let { if (it > 0) return it to ArchiveDaysSource.TVG_REC }
        raw.catchupTimeSec?.let { sec ->
            if (sec > 0) {
                val d = ((sec + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                return d to ArchiveDaysSource.CATCHUP_TIME
            }
        }
        raw.xtreamArchiveDays?.let { if (it > 0) return it to ArchiveDaysSource.XTREAM_DURATION }
        defaults.header.days?.let { if (it > 0) return it to ArchiveDaysSource.PLAYLIST_HEADER }
        return defaults.defaultDays to ArchiveDaysSource.DEFAULT
    }

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 30
        private const val SECONDS_PER_DAY = 86_400L
    }
}
