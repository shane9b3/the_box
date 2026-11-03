// Contract (M3 commit 0): docs/design/m3-guide.md §2.4a. Verbatim; changes go through the lead.
package app.thebox.core.guide

import kotlinx.coroutines.flow.StateFlow

/** EPG retention the guide limits use (DataSettings in :data; bound in GuideDataModule). */
data class GuideDataWindow(val pastDays: Int = 2, val futureDays: Int = 7)
interface GuideDataWindowSource { val window: StateFlow<GuideDataWindow> }

/**
 * Sync chip input (SyncStatusRepository in :data maps to this; bound in GuideDataModule).
 *
 * (owner finding 2026-11-01, docs/decisions/2026-11-01-guide-sync-chip.md) Additive: the newest unacknowledged
 * failure's stable `SourceError.code` ([lastFailureCode], e.g. "http.500"), its refresh_log target
 * ([lastFailureTarget], e.g. [TARGET_EPG_BUILD]) and start time, and the built-at of the guide generation on screen
 * ([guideBuiltAtMs], null when there is none). [syncChipReason] turns them into the chip's reason.
 */
data class SyncFeedState(
    val busy: Boolean,
    val progress: String?,
    val unacknowledgedFailures: Int,
    val lastFailureCode: String? = null,
    val lastFailureTarget: String? = null,
    val lastFailureAtMs: Long? = null,
    val guideBuiltAtMs: Long? = null,
) {
    companion object {
        /** Mirrors `RefreshLogEntity.TARGET_EPG_BUILD` in :data (a :data test pins the equality). */
        const val TARGET_EPG_BUILD = "EPG_BUILD"
    }
}
interface SyncFeed { val state: StateFlow<SyncFeedState> }

/** What the guide's sync chip says; :ui maps each case to a string (strings_guide.xml `guide_sync_*`). */
sealed interface SyncChipReason {
    /** "Updating guide…" or the worker's progress line. */
    data class Busy(val progress: String?) : SyncChipReason
    /**
     * A guide build hit a transient error (HTTP 5xx/408/429, or a network error when [httpStatus] is null) and kept
     * the generation built at [builtAtMs]: the viewer is looking at that guide, and the worker retries.
     */
    data class Stale(val httpStatus: Int?, val builtAtMs: Long) : SyncChipReason
    /** The guide server refused the download (HTTP 4xx other than 408/429). */
    data class Refused(val httpStatus: Int) : SyncChipReason
    /** The provider rejected the account (`auth.*`). */
    data object SignIn : SyncChipReason
    /** The download was not a readable guide (`format.*`, `parse`). */
    data object Unreadable : SyncChipReason
    /** Not enough free space to build the guide (`disk`). */
    data object NoSpace : SyncChipReason
    /** Anything else, a playlist failure, or a transient failure with no guide to show: the generic wording. */
    data object Failed : SyncChipReason
}

/**
 * (owner finding 2026-11-01) Pure chip selection: busy wins; then the newest unacknowledged failure. Only guide builds
 * ([SyncFeedState.TARGET_EPG_BUILD]) get a specific reason. A transient one reads as [SyncChipReason.Stale] only when
 * a generation exists and was not built after the failure. Since 2026-11-02 a successful build's log row acknowledges
 * the earlier build failures, so a newer generation with the failure still open means either a build swapped the
 * generation but logged no row (worker cancelled after the swap, a throw in `setImported`, `enforceCachePolicy` or
 * `afterBuild` outside the build's try, or process death), or the wall clock was wrong when the failure started.
 * "Stale" would then misdate the guide on screen, so the generic wording stays until the next run or Dismiss.
 */
fun syncChipReason(s: SyncFeedState): SyncChipReason? {
    if (s.busy) return SyncChipReason.Busy(s.progress)
    if (s.unacknowledgedFailures <= 0) return null
    val code = s.lastFailureCode
    if (s.lastFailureTarget != SyncFeedState.TARGET_EPG_BUILD || code == null) return SyncChipReason.Failed
    val http = if (code.startsWith("http.")) code.removePrefix("http.").toIntOrNull() else null
    val transient = code.startsWith("net.") || (http != null && (http == 408 || http == 429 || http >= 500))
    if (transient) {
        val built = s.guideBuiltAtMs ?: return SyncChipReason.Failed
        val at = s.lastFailureAtMs
        return if (at == null || built <= at) SyncChipReason.Stale(http, built) else SyncChipReason.Failed
    }
    return when {
        http != null && http in 400..499 -> SyncChipReason.Refused(http)
        code.startsWith("auth.") -> SyncChipReason.SignIn
        code.startsWith("format.") || code == "parse" -> SyncChipReason.Unreadable
        code == "disk" -> SyncChipReason.NoSpace
        else -> SyncChipReason.Failed
    }
}

/** Bound in :ui (InputModule, WP-D) from ActivityManager; computed once. */
fun interface DeviceClassSource { fun get(): DeviceClass }

/** Debug-only probe for the instrumented suite: the live VM registers its machine; release builds bind a no-op. */
interface GuideDebugProbe {
    fun register(machine: GuideStateMachine?)
    val current: GuideStateMachine?
}
