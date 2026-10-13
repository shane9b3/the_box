package app.thebox.core.catchup

import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.model.TimeBase
import app.thebox.core.source.PlaylistType
import app.thebox.core.source.RawCatchup
import app.thebox.core.xtream.XtreamStreamRef
import java.time.Instant

sealed interface CatchupModeSetting {
    data object Auto : CatchupModeSetting
    data object Off : CatchupModeSetting
    data class Forced(val mode: CatchupMode) : CatchupModeSetting
}

/**
 * Zone for `{Y}{m}{d}{H}{M}{S}`, `{utc:FMT}` and the Xtream timeshift segment (product §4.2).
 * (reviewed 2026-10-13, blocker B1) Only an explicit choice (Utc/Device/Server/Fixed) sets TimeBase.tokenZone,
 * and only then does it override the panel zone for `xc`.
 */
sealed interface TokenZoneSetting {
    /** `xc`: always the panel zone (any playlist type). Template tokens: Xtream → panel zone, M3U → UTC (TimeBase.templateZone). */
    data object Auto : TokenZoneSetting
    data object Utc : TokenZoneSetting
    data object Device : TokenZoneSetting
    data object Server : TokenZoneSetting
    data class Fixed(val zoneId: String) : TokenZoneSetting
}

data class PlaylistCatchupDefaults(
    val type: PlaylistType,
    val modeSetting: CatchupModeSetting = CatchupModeSetting.Auto,
    val daysOverride: Int? = null,
    /** M3U header-level catchup attributes (empty for Xtream). */
    val header: RawCatchup = RawCatchup.NONE,
    val defaultDays: Int = 3,
    /** (reviewed 2026-10-13, B6) Pass-1 vote result for M3U playlists; gates Xtream-URL detection in §3.8.1 (f). */
    val xtreamShaped: Boolean = false,
)

/** (reviewed 2026-10-13, B2) Per-playlist inputs of TimeBaseFactory (playlist columns, §2.1.2). */
data class TimeBaseSettings(
    val tokenZone: TokenZoneSetting = TokenZoneSetting.Auto,
    /** playlist.catchup_correction_h (user value). */
    val correctionHours: Double? = null,
    /** M3U header `catchup-correction`. */
    val headerCorrectionHours: Double? = null,
    val paddingBeforeSec: Int = 60,
    val paddingAfterSec: Int = 300,
)

/** Stub in commit 0 at catchup/TimeBaseFactoryImpl.kt; WP-C owns the body (§3.8.3). WP-D calls it. */
interface TimeBaseFactory {
    /** Xtream playlists, and Xtream-shaped M3U playlists after a login with the detected credentials. */
    fun fromAccount(type: PlaylistType, info: app.thebox.core.xtream.XtreamAccountInfo, settings: TimeBaseSettings): TimeBase
    /** M3U playlists with no account info (no panel zone known). */
    fun forM3u(settings: TimeBaseSettings): TimeBase
}

/** Stub in commit 0 at catchup/CatchupResolverImpl.kt; WP-C owns the body. */
interface CatchupResolver {
    /** Mode chain §3.8.1 and archive-days chain §3.8.2. [urlTemplate] is the stored channel.url. */
    fun resolve(raw: RawCatchup, urlTemplate: String, xtreamStreamId: Long?, defaults: PlaylistCatchupDefaults): Catchup
}

data class CatchupTarget(
    /** Live URL with credentials expanded (M2 StreamUrlResolver output, before redirects). */
    val liveUrl: String,
    val headers: Map<String, String>,
    val catchup: Catchup,
    /** Present for Xtream API channels and for Xtream-shaped M3U URLs. */
    val xtream: XtreamStreamRef?,
    /**
     * (amended 2026-10-13) The persisted probe winner (`playlist.catchup_probe_winner`, an [ArchiveRequest.variant]).
     * [CatchupUrlBuilder.variants] moves it first when it applies to this target, so it is also the default
     * variant of [CatchupUrlBuilder.build]. A winner of another mode is ignored.
     */
    val winner: String? = null,
)

data class ArchiveWindow(
    /** Raw (unshifted) programme times. */
    val programmeStart: Instant,
    /** null = open-ended ("watch from here", pause-live resume). */
    val programmeStop: Instant?,
    val catchupId: String? = null,
    /** Device now; the builder adds TimeBase.clockSkew. */
    val deviceNow: Instant,
)

data class ArchiveRequest(
    val url: String,
    val headers: Map<String, String>,
    val mode: CatchupMode,
    /** One of CatchupVariants; persisted as playlist.catchup_probe_winner. */
    val variant: String,
    /** Wall-clock UTC the first media byte corresponds to (ArchivePlayback timeline origin). */
    val requestStart: Instant,
    val requestEnd: Instant?,
    /** Epoch seconds the player can seek natively inside this request; null = re-request to seek. */
    val nativeSeekRange: LongRange?,
)

sealed interface CatchupBuild {
    /**
     * (amended 2026-10-13) [diagnostics]: the URL was built, but with a fallback the user may need to fix.
     * M4 shows them (with the next step) when playback of the archive fails, and in the channel's diagnostics.
     */
    data class Ok(val request: ArchiveRequest, val diagnostics: List<CatchupDiagnostic> = emptyList()) : CatchupBuild
    data class Unsupported(val reason: String) : CatchupBuild
}

/** (amended 2026-10-13) Non-fatal catch-up build findings, surfaced to the UI through [CatchupBuild.Ok.diagnostics]. */
sealed interface CatchupDiagnostic {
    /** Stable code for logs and diagnostics screens. */
    val code: String

    /** `xc`: the panel time zone is unknown, so the timeshift segment used UTC. Next step: set the zone in playlist settings. */
    data object PanelZoneUnknown : CatchupDiagnostic {
        override val code: String get() = "catchup.panel_zone_unknown"
    }

    /** Template date tokens used UTC because no template zone is known. */
    data object TemplateZoneUnknown : CatchupDiagnostic {
        override val code: String get() = "catchup.template_zone_unknown"
    }

    /** `catchup-source` placeholders the builder does not know; they were left as written (at most 5). */
    data class UnknownPlaceholders(val placeholders: List<String>) : CatchupDiagnostic {
        override val code: String get() = "catchup.unknown_placeholder"
    }
}

object CatchupVariants {
    const val XC_PATH = "xc-path"
    const val XC_PHP = "xc-php"
    const val XC_PATH_SECONDS = "xc-path-sec"
    const val FLUSSONIC_RANGE = "flussonic-range"
    const val FLUSSONIC_ABS = "flussonic-abs"
    const val FLUSSONIC_KODI = "flussonic-kodi"
    const val SHIFT = "shift"
    const val APPEND = "append"
    const val DEFAULT = "default"
    const val VOD = "vod"
}

/** Stub in commit 0 at catchup/CatchupUrlBuilderImpl.kt; WP-C owns the body. */
interface CatchupUrlBuilder {
    fun build(target: CatchupTarget, timeBase: TimeBase, window: ArchiveWindow, variant: String? = null): CatchupBuild

    /**
     * Probe order for this target (first = default). M4 tries them in order and stores the winner.
     * (amended 2026-10-13) [CatchupTarget.winner] comes first when it belongs to this target's list.
     */
    fun variants(target: CatchupTarget): List<String>
}
