package app.thebox.core.backup

/**
 * Pure restore rules (docs/design/m5-setup-settings.md §3.10.4), shared by the preview and the apply pass:
 * playlist matching (same uid → same source → new), the column whitelists, and which reminders travel.
 */
object MergePlanner {
    /** A playlist of the backup, with its login when the secrets were opened. */
    data class BackupPlaylist(val uid: String, val name: String, val type: String, val displaySource: String, val urls: List<String>?, val username: String?)

    /** A playlist on this TV, with its login when the credential store could read it. */
    data class LocalPlaylist(val id: Long, val uid: String?, val type: String, val displaySource: String, val urls: List<String>?, val username: String?)

    /**
     * One target per backup playlist; a local playlist is claimed at most once (uid matches first, then source).
     * Source identity: Xtream = type + host:port (lower case, default port) + username; M3U = URL without query or
     * fragment. Both sides' logins are compared when both are known, else `display_source` with the type.
     */
    fun match(backup: List<BackupPlaylist>, local: List<LocalPlaylist>): List<PlaylistMatch> {
        val claimed = HashSet<Long>()
        val byUid = local.filter { it.uid != null }.associateBy { it.uid!! }
        val first = backup.map { b ->
            byUid[b.uid]?.let { l -> claimed += l.id; PlaylistMatch(b.uid, b.name, MatchTarget.SAME_UID, l.id) }
        }
        return backup.mapIndexed { i, b ->
            first[i] ?: run {
                val l = local.firstOrNull { it.id !in claimed && sameSource(b, it) }
                if (l != null) {
                    claimed += l.id
                    PlaylistMatch(b.uid, b.name, MatchTarget.SAME_SOURCE, l.id)
                } else {
                    PlaylistMatch(b.uid, b.name, MatchTarget.NEW, null)
                }
            }
        }
    }

    fun sameSource(b: BackupPlaylist, l: LocalPlaylist): Boolean {
        if (b.type != l.type) return false
        val bu = b.urls?.firstOrNull()
        val lu = l.urls?.firstOrNull()
        if (bu != null && lu != null) {
            return if (b.type.startsWith("XTREAM")) {
                hostPort(bu) == hostPort(lu) && (b.username ?: "") == (l.username ?: "")
            } else {
                urlWithoutQuery(bu) == urlWithoutQuery(lu)
            }
        }
        return b.displaySource.isNotEmpty() && b.displaySource.equals(l.displaySource, ignoreCase = true)
    }

    /** "HTTP://Panel.Example.com/" → "panel.example.com:80". */
    fun hostPort(url: String): String {
        val u = url.trim()
        val scheme = u.substringBefore("://", "http").lowercase()
        val rest = u.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').lowercase()
        val hasPort = authority.lastIndexOf(':') > authority.lastIndexOf(']')
        return if (hasPort) authority else authority + ":" + (if (scheme == "https") 443 else 80)
    }

    /** Scheme and host lower-cased; query and fragment dropped. */
    fun urlWithoutQuery(url: String): String {
        val u = url.trim().substringBefore('#').substringBefore('?')
        val scheme = u.substringBefore("://", "")
        if (scheme.isEmpty()) return u
        val rest = u.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.substring(authority.length)
        return scheme.lowercase() + "://" + authority.lowercase() + path
    }

    /** User-settable `playlist` columns a backup carries (§3.10.2); learned columns are never read from a file. */
    val PLAYLIST_COLUMNS: Set<String> = linkedSetOf(
        "user_agent", "extra_headers_json", "use_playlist_epg", "update_on_start", "refresh_interval_h", "numbering_mode", "order_mode",
        "xtream_output", "content_types", "extgrp_sticky", "charset_override", "catchup_mode_setting", "catchup_days_override",
        "token_zone_setting", "padding_before_sec", "padding_after_sec", "max_connections_override", "ignore_tls_errors",
        "name_prefix_mode", "name_prefix_custom",
    )

    /** User columns of `channel_prefs` (never `playback_flags`, `fps_milli`: learned, M2 §2.10). */
    val CHANNEL_PREF_COLUMNS: Set<String> = linkedSetOf(
        "hidden", "locked", "custom_name", "custom_number", "custom_logo", "custom_sort_index", "epg_shift_min", "aspect_mode",
        "audio_lang", "subtitle_lang", "video_decoder", "buffer_profile", "last_watched_at", "watch_count",
    )

    /** Pending reminders whose start is after [nowMs] travel; fired, dismissed, orphaned and past ones do not. */
    fun reminderTravels(state: Int, startMin: Int, nowMs: Long): Boolean = state == 0 && startMin.toLong() * 60_000L > nowMs

    /** Portable key of an EPG source for `epg_mapping` records: origin, owning playlist uid, ordinal, URL hash. */
    fun sourceKey(origin: String, playlistUid: String?, ordinal: Int, urlHash: Long): String = "$origin|${playlistUid ?: ""}|$ordinal|$urlHash"

    data class SourceKey(val origin: String, val playlistUid: String?, val ordinal: Int, val urlHash: Long)

    fun parseSourceKey(key: String): SourceKey? {
        val p = key.split('|')
        if (p.size != 4) return null
        return SourceKey(p[0], p[1].ifEmpty { null }, p[2].toIntOrNull() ?: return null, p[3].toLongOrNull() ?: return null)
    }
}

/**
 * Which automatic backups to delete (§3.10.5): only entries this app wrote ([written], newest last or in any order),
 * keeping the newest [keep]. Files the user or another app put there are never in [written], so never deleted.
 */
object AutoBackupRetention {
    data class Written(val location: String, val createdAtMs: Long)

    fun toDelete(written: List<Written>, keep: Int = AUTO_BACKUP_KEEP): List<Written> =
        written.sortedByDescending { it.createdAtMs }.drop(keep.coerceAtLeast(1))
}
