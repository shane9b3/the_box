package app.thebox.core.identity

import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType

/**
 * Group identity (docs/design/m1-data-layer.md §3.2.3; reviewed 2026-10-13, B5: `forGroup`, not `forKey`).
 * Xtream: `xcat:<category_id>` (`xcat:none` when null). M3U: `g:<forGroup(groupTitle)>`, or `g:` without a group.
 */
object GroupKeys {
    const val UNGROUPED = "g:"
    const val XTREAM_NONE = "xcat:none"

    fun groupKey(channel: ParsedChannel, playlistType: PlaylistType): String =
        if (playlistType == PlaylistType.XTREAM) xtream(channel.xtreamCategoryId) else m3u(channel.groupTitle)

    fun xtream(categoryId: String?): String = categoryId?.trim()?.takeIf { it.isNotEmpty() }?.let { "xcat:$it" } ?: XTREAM_NONE

    fun m3u(groupTitle: String?): String {
        if (groupTitle.isNullOrBlank()) return UNGROUPED
        return "g:" + FastNames.forGroup(groupTitle) // == NameNormalizer.forGroup
    }

    /** RADIO channels share the LIVE group space (`channel_group.kind = 0`). */
    fun groupKind(kind: EntryKind): EntryKind = if (kind == EntryKind.RADIO) EntryKind.LIVE else kind

    /** Small memo for the per-row hot path: provider lists have few distinct group titles. */
    internal class Cache(private val playlistType: PlaylistType) {
        private val m3u = HashMap<String, String>()
        private val xc = HashMap<String, String>()

        fun of(c: ParsedChannel): String {
            if (playlistType == PlaylistType.XTREAM) {
                val id = c.xtreamCategoryId ?: return XTREAM_NONE
                return xc[id] ?: xtream(id).also { if (xc.size < LIMIT) xc[id] = it }
            }
            val t = c.groupTitle ?: return UNGROUPED
            return m3u[t] ?: m3u(t).also { if (m3u.size < LIMIT) m3u[t] = it }
        }

        private companion object {
            const val LIMIT = 20_000
        }
    }
}
