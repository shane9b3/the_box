package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.EffectTiming.NEXT_EPG_IMPORT
import app.thebox.core.settings.EffectTiming.NEXT_LAUNCH
import app.thebox.core.settings.EffectTiming.NEXT_REFRESH
import app.thebox.core.settings.EffectTiming.NEXT_TUNE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * WP-B's per-playlist rows (docs/design/m5-setup-settings.md §3.3.1): the playlist page's table, seven simple rows
 * (§3.3.13) and the advanced ones. Page-local rows (the account card, sub-rows such as `playlist.numbering.start`,
 * header/backup-server items, the Playlists section's items and actions) are not settings and are not listed; the
 * page builders give sub-rows their parent's visibility and effect (`SetupPage.row`).
 */
object PlaylistRows : RowSource {
    private fun spec(id: String, v: Visibility, e: EffectTiming) = RowSpec(RowId(id), SectionId.PLAYLISTS, v, e, owner = "B")

    override val rows: List<RowSpec> = listOf(
        spec("playlist.name", SIMPLE, LIVE),
        spec("playlist.source", SIMPLE, NEXT_REFRESH),
        spec("playlist.refresh", SIMPLE, LIVE),
        spec("playlist.update_on_start", SIMPLE, NEXT_LAUNCH),
        spec("playlist.numbering", SIMPLE, NEXT_REFRESH),
        spec("playlist.name_prefix", SIMPLE, LIVE),
        spec("playlist.remove", SIMPLE, LIVE),
        spec("playlist.enabled", ADVANCED, LIVE),
        spec("playlist.interval", ADVANCED, LIVE),
        spec("playlist.order", ADVANCED, NEXT_REFRESH),
        spec("playlist.playlist_epg", ADVANCED, NEXT_EPG_IMPORT),
        spec("playlist.user_agent", ADVANCED, NEXT_TUNE),
        spec("playlist.headers", ADVANCED, NEXT_TUNE),
        spec("playlist.stream_format", ADVANCED, NEXT_TUNE),
        spec("playlist.backup_servers", ADVANCED, NEXT_TUNE),
        spec("playlist.catchup_mode", ADVANCED, NEXT_TUNE),
        spec("playlist.catchup_days", ADVANCED, NEXT_TUNE),
        spec("playlist.catchup_zone", ADVANCED, NEXT_TUNE),
        spec("playlist.padding_before", ADVANCED, NEXT_TUNE),
        spec("playlist.padding_after", ADVANCED, NEXT_TUNE),
        spec("playlist.max_connections", ADVANCED, NEXT_TUNE),
        spec("playlist.ignore_tls", ADVANCED, NEXT_TUNE),
        spec("playlist.network_test", ADVANCED, EffectTiming.NONE),
        // The Playlists section's "Refresh all now" (§3.3.1, advanced).
        spec("playlists.refresh_all", ADVANCED, LIVE),
    )
}
