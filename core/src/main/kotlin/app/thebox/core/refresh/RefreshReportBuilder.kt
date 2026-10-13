package app.thebox.core.refresh

import app.thebox.core.common.SourceError
import app.thebox.core.m3u.M3uParseSummary
import app.thebox.core.source.ParseWarning

/** Counters, capped samples and capped warnings for one refresh (docs/design/m1-data-layer.md §3.3, S12). */
internal class RefreshReportBuilder(private val ctx: MergeContext) {
    var entries = 0
    var live = 0
    var radio = 0
    var movies = 0
    var series = 0
    var added = 0
    var addedLive = 0
    var updated = 0
    var urlChanged = 0
    var renamed = 0
    var moved = 0
    var unchanged = 0
    var restored = 0
    var newlyMissing = 0
    var groupsTotal = 0
    var duplicateKeys = 0
    var credentialsTemplated = false
    private val keyRules = IntArray(KeyRule.entries.size)
    private val addedSample = ArrayList<String>(SAMPLE_CAP)
    private val warnings = ArrayList<String>(4)

    fun rule(r: KeyRule) { keyRules[r.ordinal]++ }

    fun added(name: String) {
        added++
        if (addedSample.size < SAMPLE_CAP) addedSample += name
    }

    fun warning(w: ParseWarning) { if (warnings.size < WARNING_CAP) warnings += RefreshReports.format(w) }

    fun build(finishedAtMs: Long, finish: FinishStats, massRemap: Boolean, keysMigrated: Int): RefreshReport = RefreshReport(
        playlistId = ctx.playlistId,
        refreshSeq = ctx.refreshSeq,
        startedAtMs = ctx.nowMs,
        finishedAtMs = finishedAtMs,
        outcome = RefreshOutcome.SUCCESS,
        entries = entries, live = live, radio = radio, movies = movies, series = series,
        added = added, updated = updated, urlChanged = urlChanged, renamed = renamed, moved = moved,
        unchanged = unchanged, restored = restored, newlyMissing = newlyMissing, purged = finish.purged,
        groupsTotal = groupsTotal,
        duplicateKeys = duplicateKeys,
        keyRules = buildMap { for (r in KeyRule.entries) if (keyRules[r.ordinal] > 0) put(r.name, keyRules[r.ordinal]) },
        favouritesKept = finish.favouritesKept,
        favouritesOrphaned = finish.favouritesOrphaned,
        massRemapSuggested = massRemap,
        keysMigrated = keysMigrated,
        credentialsTemplated = credentialsTemplated,
        addedSample = addedSample.toList(),
        removedSample = finish.removedSample.take(SAMPLE_CAP),
        warnings = warnings.toList(),
    )

    companion object {
        const val SAMPLE_CAP = 10
        const val WARNING_CAP = 50
    }
}

/** Helpers WP-D uses to complete or fail a report (the differ does not see the parser or the download). */
object RefreshReports {
    /** `"<WarningCode>@<line>"`. */
    fun format(w: ParseWarning): String = w.code.name + "@" + w.line

    /**
     * Adds the parse result (charset, warnings) to a differ report. Parser warnings come first, then the
     * differ's own; the list stays capped at 50.
     */
    fun withParse(report: RefreshReport, summary: M3uParseSummary?, parseWarnings: List<ParseWarning>): RefreshReport {
        val merged = ArrayList<String>(RefreshReportBuilder.WARNING_CAP)
        for (w in parseWarnings) { if (merged.size >= RefreshReportBuilder.WARNING_CAP) break; merged += format(w) }
        for (w in report.warnings) { if (merged.size >= RefreshReportBuilder.WARNING_CAP) break; merged += w }
        return report.copy(charset = summary?.charset ?: report.charset, warnings = merged)
    }

    /** A FAILED report (shrink, format, network, …) with the stable error code. */
    fun failed(ctx: MergeContext, finishedAtMs: Long, error: SourceError, cancelled: Boolean = false): RefreshReport = RefreshReport(
        playlistId = ctx.playlistId,
        refreshSeq = ctx.refreshSeq,
        startedAtMs = ctx.nowMs,
        finishedAtMs = finishedAtMs,
        outcome = if (cancelled) RefreshOutcome.CANCELLED else RefreshOutcome.FAILED,
        errorCode = error.code,
    )

    /** Keeps the first 50 parser warnings (and the total) for [withParse]; call [add] from `M3uSink.onWarning`. */
    class WarningCollector(private val cap: Int = RefreshReportBuilder.WARNING_CAP) {
        private val list = ArrayList<ParseWarning>()
        var total = 0
            private set

        fun add(w: ParseWarning) {
            total++
            if (list.size < cap) list += w
        }

        val warnings: List<ParseWarning> get() = list
    }
}
