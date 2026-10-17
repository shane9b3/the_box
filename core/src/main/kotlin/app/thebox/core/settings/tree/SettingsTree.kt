// Commit-0 skeleton (M5): docs/design/m5-setup-settings.md §1.3a, §3.2.3, §3.3. Nobody edits this file after commit 0:
// each WP fills its own `*Rows.kt` object, which this registry already lists.
package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.Visibility

/**
 * Static facts of one settings row (§3.3): where it lives, whether the simple view shows it and when a change takes
 * effect. Page builders produce [app.thebox.core.settings.SettingsRow]s whose `id`, `visibility` and `effect` must
 * match the spec (`EffectTimingCoverageTest`, `SimpleRowCountTest`).
 */
data class RowSpec(
    val id: RowId,
    val section: SectionId,
    val visibility: Visibility,
    val effect: EffectTiming,
    /** The work package that owns the row ("A" … "E"), for the coverage tests' messages. */
    val owner: String,
)

/** One WP's rows. Each `*Rows.kt` object of §1.3a implements it in its own file. */
interface RowSource {
    val rows: List<RowSpec>
}

/**
 * The registry of every settings row (§1.3a). Lookups are built once on first use; a duplicate row id fails fast
 * (two WPs claiming one row is a contract problem for the lead).
 */
object SettingsTree {
    /** Every row source, one per `*Rows.kt` file of §1.3a, in section order. */
    val sources: List<RowSource> = listOf(
        PlaylistRows, SetupRows, MatchingRows, // B
        GuideRows, PlaybackRows, InterfaceRows, RemoteRows, NetworkRows, ChannelOptionsRows, // A
        ChannelsRows, BackupRows, // D
        ParentalRows, ButtonMappingRows, // C
        AboutRows, // E
    )

    private val byId: Map<RowId, RowSpec> by lazy {
        val out = LinkedHashMap<RowId, RowSpec>()
        for (s in sources) for (r in s.rows) {
            val prev = out.put(r.id, r)
            require(prev == null) { "row ${r.id.key} registered twice (${prev?.owner} and ${r.owner})" }
        }
        out
    }

    val all: Collection<RowSpec> get() = byId.values

    fun spec(id: RowId): RowSpec? = byId[id]

    fun rowsOf(section: SectionId): List<RowSpec> = byId.values.filter { it.section == section }

    /**
     * The section the sections pane highlights for [route] (§3.2.1); null for Root and FirstRun. (C-L1, code review
     * 2026-10-16) It follows the parent chain BACK walks (`SettingsMachineImpl.parentOf`): FixGuide, EpgSources and
     * SyncStatus are TV guide rows (§3.3.2), NetworkTest a Playlist page row (§3.3.1).
     */
    fun sectionOf(route: SettingsRoute): SectionId? = when (route) {
        SettingsRoute.Root, SettingsRoute.FirstRun -> null
        is SettingsRoute.Section -> route.id
        is SettingsRoute.AddSource, is SettingsRoute.PhoneSetup, is SettingsRoute.ImportProgress,
        is SettingsRoute.GroupsReview, is SettingsRoute.MatchingReport, is SettingsRoute.Playlist, is SettingsRoute.PlaylistSource,
        is SettingsRoute.NetworkTest -> SectionId.PLAYLISTS
        SettingsRoute.EpgSources, is SettingsRoute.FixGuide, SettingsRoute.SyncStatus -> SectionId.GUIDE
        is SettingsRoute.Groups, is SettingsRoute.HiddenChannels, is SettingsRoute.HideRules,
        SettingsRoute.FavouriteLists, is SettingsRoute.FavouriteList, is SettingsRoute.Channel -> SectionId.CHANNELS
        SettingsRoute.ButtonMapping, is SettingsRoute.ActionBindings -> SectionId.REMOTE
        SettingsRoute.Diagnostics, SettingsRoute.Licences -> SectionId.ABOUT
        SettingsRoute.Backup, is SettingsRoute.RestorePreview -> SectionId.BACKUP
    }
}
