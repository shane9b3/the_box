package app.thebox.core.settings.state

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.MatchTarget
import app.thebox.core.backup.PlaylistMatch
import app.thebox.core.backup.RestorePreview
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.BackupPageState
import app.thebox.core.settings.tree.BackupPages
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.ButtonMappingRows
import app.thebox.core.settings.tree.ChannelsPages
import app.thebox.core.settings.tree.ChannelsSummary
import app.thebox.core.settings.tree.FavouriteEntryRow
import app.thebox.core.settings.tree.FavouriteListRow
import app.thebox.core.settings.tree.GroupAdminRow
import app.thebox.core.settings.tree.HiddenChannelRow
import app.thebox.core.settings.tree.HideRuleRow
import app.thebox.core.settings.tree.ParentalRows
import app.thebox.core.settings.tree.PlaylistChoice
import app.thebox.core.settings.tree.RowTexts
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.core.setup.FakeGroups
import app.thebox.core.setup.FakeMatching
import app.thebox.core.setup.FakePlaylistAdmin
import app.thebox.core.setup.KeyStrings
import app.thebox.core.setup.RecordingHost
import app.thebox.core.setup.SourceInputNormalizerImpl
import app.thebox.core.setup.detail
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.core.setup.pages.SetupPages
import app.thebox.core.setup.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneOffset
import java.util.Locale

/**
 * C-S9 (M5 code review 2026-10-16): the other WPs' **real** page builders for the reachability, row-count and coverage
 * tests, over fixed data with [playlists] playlists (0, 1 or 2): WP-B's `SetupPages` (fakes of `SetupPagesTest`), WP-D's
 * `ChannelsPages` and `BackupPages`, WP-C's `ParentalRows` and `ButtonMappingRows`. WP-E's About page is built in `:ui`
 * (`DiagPages`) and is not part of `:core`'s tests; routes nobody here builds fall back to [FixturePages].
 */
class RealPages(val playlists: Int, private val pinSet: Boolean = false) {
    val bt = BackupTexts { k, args -> if (args.isEmpty()) k.name else k.name + args.joinToString(",", "(", ")") }
    val rowTexts = RowTexts { key, args -> if (args.isEmpty()) key else "$key(${args.joinToString()})" }
    private val names = object : ButtonMappingRows.Names {
        override fun key(keyCode: Int) = "K$keyCode"
        override fun action(action: BoxAction) = action.id
        override fun group(group: ActionGroup) = group.name
    }
    private val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(KeyBindingOverrides()) }
    val parental = ParentalState(pinSet = pinSet, unlocked = false, policy = ParentalPolicy())
    private val admin = FakePlaylistAdmin().also { a -> for (i in 1..playlists) a.put(detail(summary(i.toLong()))) }
    private val setup = SetupPages(
        SetupPageDeps(
            strings = KeyStrings(), normalizer = SourceInputNormalizerImpl(), setup = FakeSourceSetup(), admin = admin, groups = FakeGroups(),
            matching = FakeMatching(), store = FakeSettingsStore(), parental = MutableStateFlow(parental), scope = CoroutineScope(Dispatchers.Unconfined),
            nowMs = { 1_790_000_000_000L }, locale = { Locale.UK }, zone = { ZoneOffset.UTC },
        ),
    )

    private val groups = listOf(
        GroupAdminRow(0, "news", "News", 9, hidden = false, lockedExplicit = null, adult = false, ruleHidden = false),
        GroupAdminRow(0, "late", "Late", 4, hidden = true, lockedExplicit = null, adult = true, ruleHidden = false),
    )
    private val hidden = listOf(HiddenChannelRow("k1", "Shop", "News", byUser = true, ruleId = null, rulePattern = null))
    private val rules = listOf(HideRuleRow(3, RuleTarget.GROUP_NAME, "XXX", isRegex = false, enabled = true, disabledReason = null, channels = 2))
    private val lists = listOf(FavouriteListRow(1, "Mine", 1), FavouriteListRow(2, "Kids", 0))

    fun handles(route: SettingsRoute): Boolean = build(route, advanced = true) != null

    fun build(route: SettingsRoute, advanced: Boolean): PageModel? {
        if (setup.handles(route)) {
            if (route is SettingsRoute.Playlist && route.playlistId > playlists) return null
            val host = RecordingHost()
            setup.open(route, host)
            setup.close(route)
            return host.pages.lastOrNull()
        }
        val pid = if (playlists > 0) 1L else null
        return when (route) {
            SettingsRoute.Section(SectionId.CHANNELS) -> ChannelsPages.section(
                bt, advanced, 1, (1..playlists).map { PlaylistChoice(it.toLong(), "P$it") }, pid,
                ChannelsSummary(groups = 2, hiddenGroups = 1, lockedGroups = 1, hiddenChannels = 1, rules = 1, ruleHiddenChannels = 2, favouriteLists = 2), OpenGuideOn.ALL,
            )
            is SettingsRoute.Groups -> ChannelsPages.groups(route, bt, 1, groups.size, mapOf(0 to items(route, 0, groups.size)))
            is SettingsRoute.HiddenChannels -> ChannelsPages.hidden(route, bt, 1, hidden.size, mapOf(0 to items(route, 0, hidden.size)))
            is SettingsRoute.HideRules -> ChannelsPages.rules(route, bt, 1, RuleDraft(), null, rules)
            SettingsRoute.FavouriteLists -> ChannelsPages.favouriteLists(bt, 1, lists)
            is SettingsRoute.FavouriteList -> ChannelsPages.favouriteList(route, bt, 1, lists.firstOrNull { it.id == route.listId },
                listOf(FavouriteEntryRow(1, "xc:1-1", "Channel 1.1")), onlyList = false)
            SettingsRoute.Section(SectionId.BACKUP), SettingsRoute.Backup -> BackupPages.backup(BackupPageState(pinSet = pinSet, passphraseStored = true), bt, advanced, 1, route)
            is SettingsRoute.RestorePreview -> BackupPages.preview(route, BackupPages.PreviewState(preview(), pinSet = pinSet), bt, 1)
            SettingsRoute.Section(SectionId.PARENTAL) -> ParentalRows.page(parental, rowTexts, 1, advanced)
            SettingsRoute.ButtonMapping -> ButtonMappingRows.mappingPage(map, KeyBindingOverrides(), names, rowTexts, 1)
            is SettingsRoute.ActionBindings -> ButtonMappingRows.actionPage(route.action, map, KeyBindingOverrides(), names, rowTexts, 1)
            else -> null
        }
    }

    fun items(route: SettingsRoute, from: Int, to: Int): List<SettingsRow> = when (route) {
        is SettingsRoute.Groups -> groups.subList(from.coerceAtMost(groups.size), to.coerceAtMost(groups.size)).map { ChannelsPages.groupItem(it, bt, pinSet) }
        is SettingsRoute.HiddenChannels -> hidden.subList(from.coerceAtMost(hidden.size), to.coerceAtMost(hidden.size)).map { ChannelsPages.hiddenItem(route.playlistId, it, bt) }
        else -> emptyList()
    }

    private fun preview() = RestorePreview(
        BackupHeader(createdAtMs = 0, appVersionName = "1.0", appVersionCode = 1, includesLogins = false, counts = emptyMap()),
        listOf(PlaylistMatch("uid1", "P1", MatchTarget.SAME_UID, 1)), needsPassphrase = false, problems = emptyList(),
    )
}
