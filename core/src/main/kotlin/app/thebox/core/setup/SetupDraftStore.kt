package app.thebox.core.setup

/**
 * The onboarding forms' drafts for the process lifetime (docs/design/m5-setup-settings.md §3.4.3): memory only, never
 * persisted, never logged. `toString()` of every draft hides the values, so an accidental log line cannot leak a
 * password. Main thread only (the page builders run their state changes on the provider's main scope).
 */
class SetupDraftStore {
    var xtream: XtreamDraft = XtreamDraft()
    var m3u: M3uDraft = M3uDraft()
    /** Edit-in-place drafts per playlist id (PlaylistSource page). */
    val edits: MutableMap<Long, EditDraft> = HashMap()

    fun clear() {
        xtream = XtreamDraft()
        m3u = M3uDraft()
        edits.clear()
    }
}

data class XtreamDraft(
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    /** `AUTO` | `TS` | `HLS`. */
    val output: String = "AUTO",
    val userAgent: String = "",
    /** Backup server URLs, comma separated as typed. */
    val backups: String = "",
    val moreOptions: Boolean = false,
    /** The playlist this draft already created (a retry after a failed import updates it instead of adding another). */
    val createdId: Long? = null,
) {
    val isEmpty: Boolean get() = server.isEmpty() && username.isEmpty() && password.isEmpty()
    override fun toString(): String = "XtreamDraft(server=${if (server.isEmpty()) "" else "…"}, user=${username.isNotEmpty()}, pass=${password.isNotEmpty()})"
}

data class M3uDraft(
    val name: String = "",
    val url: String = "",
    val fileUri: String? = null,
    val fileName: String? = null,
    val epgUrls: List<String> = emptyList(),
    val userAgent: String = "",
    val moreOptions: Boolean = false,
    /** Set when the URL looked like Xtream and the user chose "Keep as M3U". */
    val keepAsM3u: Boolean = false,
    val createdId: Long? = null,
) {
    override fun toString(): String = "M3uDraft(url=${url.isNotEmpty()}, file=${fileUri != null}, epg=${epgUrls.size})"
}

/** Edit in place of an existing playlist's source (PlaylistSource page). */
data class EditDraft(val source: EditableSource) {
    override fun toString(): String = "EditDraft(${source.javaClass.simpleName})"
}
