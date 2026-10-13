package app.thebox.core.identity

import app.thebox.core.m3u.Attr
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.DerivedKey
import app.thebox.core.refresh.KeyContext
import app.thebox.core.refresh.KeyRule
import app.thebox.core.refresh.StableKeyDeriver
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import java.util.Locale

/**
 * StableKey priority chain (docs/design/m1-data-layer.md §3.2.1). First rule that yields a key wins:
 *
 * 1. XTREAM: an Xtream API `stream_id`, or (M3U) an Xtream URL whose `(username, password)` equals the
 *    playlist's voted pair, only when the playlist is Xtream-shaped (reviewed 2026-10-13, B6).
 *    LIVE/RADIO `xc:<id>`, MOVIE `xcm:<id>`, SERIES `xce:<id>`.
 * 2. TVG: `tvg:<tvgId lower-cased>|<forKey(name)>` when a non-junk `tvg-id` exists.
 * 3. URL: `url:<UrlKey>`.
 * 4. NAME_GROUP: `ng:<forKey(name)>|<forGroup(group)>` (reviewed 2026-10-13, B5).
 *
 * No occurrence suffix: the differ adds `~n` for duplicates.
 */
class StableKeyDeriverImpl : StableKeyDeriver {
    override fun derive(entry: ParsedChannel, ctx: KeyContext): DerivedKey {
        xtreamKey(entry, ctx)?.let { return DerivedKey(it, KeyRule.XTREAM) }
        val tvg = Attr.tvgId(entry.tvgId)
        if (tvg != null) {
            return DerivedKey(StableKey("tvg:" + tvg.lowercase(Locale.ROOT) + "|" + FastNames.forKey(entry.name)), KeyRule.TVG)
        }
        UrlKey.of(entry.url, ctx.credentials)?.let { return DerivedKey(StableKey("url:$it"), KeyRule.URL) }
        val group = entry.groupTitle?.takeIf { it.isNotBlank() }?.let { FastNames.forGroup(it) } ?: ""
        return DerivedKey(StableKey("ng:" + FastNames.forKey(entry.name) + "|" + group), KeyRule.NAME_GROUP)
    }

    private fun xtreamKey(entry: ParsedChannel, ctx: KeyContext): StableKey? {
        if (ctx.playlistType == PlaylistType.XTREAM) {
            val id = entry.xtreamStreamId ?: return null
            return byKind(entry.kind, id)
        }
        val cred = ctx.credentials ?: return null
        if (!ctx.xtreamShaped) return null
        val x = XtreamUrlShape.parse(entry.url, allowShort = true) ?: return null
        if (!x.matches(cred.username, cred.password)) return null
        // The URL path decides the id space; a RADIO entry on /live/ shares the live space.
        return byKind(x.kind, x.streamId)
    }

    private fun byKind(kind: EntryKind, id: Long): StableKey = when (kind) {
        EntryKind.LIVE, EntryKind.RADIO -> StableKey.xtream(id)
        EntryKind.MOVIE -> StableKey.xtreamMovie(id)
        EntryKind.SERIES -> StableKey.xtreamEpisode(id)
    }
}
