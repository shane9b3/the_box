package app.thebox.core.settings.tree

import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility

/**
 * WP-D's Channels and groups rows (docs/design/m5-setup-settings.md §3.3.5, §3.7) and the pure builders of their pages.
 * `channels.playlist` is a context control (§3.3.5 footnote): registered with [EffectTiming.NONE] so builders can
 * emit it, and **not counted** by `SimpleRowCountTest`.
 */
object ChannelsRows : RowSource {
    /** m6-channel-editor §3.1: the dedicated editor, the section's first row (SIMPLE). */
    val EDITOR = RowId("channels.editor")
    val PLAYLIST = RowId("channels.playlist")
    val GROUPS = RowId("channels.groups")
    val HIDDEN = RowId("channels.hidden")
    val FAVOURITES = RowId("channels.favourites")
    val OPEN_GUIDE_ON = RowId("channels.open_guide_on")
    val HIDE_RULES = RowId("channels.hide_rules")

    /** Row ids that are not settings (context controls), excluded from the simple-row count. */
    val NOT_COUNTED: Set<RowId> = setOf(PLAYLIST)

    override val rows: List<RowSpec> = listOf(
        RowSpec(EDITOR, SectionId.CHANNELS, Visibility.SIMPLE, EffectTiming.LIVE, "D"),
        RowSpec(PLAYLIST, SectionId.CHANNELS, Visibility.SIMPLE, EffectTiming.NONE, "D"),
        RowSpec(GROUPS, SectionId.CHANNELS, Visibility.SIMPLE, EffectTiming.LIVE, "D"),
        // Owner ruling Q3 (m6-channel-editor §3.1): advanced; the editor's "Show: Hidden only" does the job in simple view.
        RowSpec(HIDDEN, SectionId.CHANNELS, Visibility.ADVANCED, EffectTiming.LIVE, "D"),
        RowSpec(FAVOURITES, SectionId.CHANNELS, Visibility.SIMPLE, EffectTiming.LIVE, "D"),
        RowSpec(OPEN_GUIDE_ON, SectionId.CHANNELS, Visibility.SIMPLE, EffectTiming.NEXT_LAUNCH, "D"),
        RowSpec(HIDE_RULES, SectionId.CHANNELS, Visibility.ADVANCED, EffectTiming.LIVE, "D"),
    )

    // Sub-page rows (management pages; not settings, not in the registry).
    val SHOW_ALL = RowId("groups.show_all")
    const val GROUP_PREFIX = "group/"
    val UNHIDE_ALL = RowId("hidden.unhide_all")
    const val HIDDEN_PREFIX = "hidden/"
    val RULE_TARGET = RowId("rules.target")
    val RULE_PATTERN = RowId("rules.pattern")
    val RULE_KIND = RowId("rules.kind")
    val RULE_PREVIEW = RowId("rules.preview")
    val RULE_SAVE = RowId("rules.save")
    const val RULE_PREFIX = "rule/"
    val FAV_NEW = RowId("fav.new")
    const val FAV_LIST_PREFIX = "favlist/"
    val FAV_LIST_NAME = RowId("favlist.name")
    val FAV_LIST_DELETE = RowId("favlist.delete")
    const val FAV_ENTRY_PREFIX = "fav/"

    // Menu option ids.
    const val M_HIDE = "hide"
    const val M_SHOW = "show"
    const val M_LOCK = "lock"
    const val M_UNLOCK = "unlock"
    const val M_MOVE = "move"
    const val M_RESET = "reset"
    const val M_ENABLE = "enable"
    const val M_DISABLE = "disable"
    const val M_DELETE = "delete"
    const val M_REMOVE = "remove"
    const val P_TOGGLE = "toggle"
    const val P_UNHIDE = "unhide"
}

/** Rule target (stored in `hide_rule.target`; append only). */
enum class RuleTarget { GROUP_NAME, CHANNEL_NAME }

/** Why a rule was switched off automatically (stored in `hide_rule.disabled_reason`). */
enum class RuleDisabledReason { TOO_SLOW, BAD_REGEX }

data class PlaylistChoice(val id: Long, val name: String)

data class ChannelsSummary(
    val groups: Int = 0,
    val hiddenGroups: Int = 0,
    val lockedGroups: Int = 0,
    val hiddenChannels: Int = 0,
    val rules: Int = 0,
    val ruleHiddenChannels: Int = 0,
    val favouriteLists: Int = 0,
)

data class GroupAdminRow(
    val kind: Int,
    val key: String,
    val name: String,
    val channels: Int,
    val hidden: Boolean,
    /** The user's explicit decision; null = automatic (locked groups: `is_adult`). */
    val lockedExplicit: Boolean?,
    val adult: Boolean,
    val ruleHidden: Boolean,
) {
    val locked: Boolean get() = lockedExplicit ?: adult
}

data class HiddenChannelRow(val stableKey: String, val name: String, val group: String?, val byUser: Boolean, val ruleId: Long?, val rulePattern: String?)

data class HideRuleRow(
    val id: Long,
    val target: RuleTarget,
    val pattern: String,
    val isRegex: Boolean,
    val enabled: Boolean,
    val disabledReason: RuleDisabledReason?,
    val channels: Int,
)

data class RuleDraft(val target: RuleTarget = RuleTarget.GROUP_NAME, val pattern: String = "", val isRegex: Boolean = false)

/** Live preview (§3.7.3): counts, or why the pattern cannot be used. */
sealed interface RulePreview {
    data class Counts(val groups: Int, val channels: Int) : RulePreview
    data class Invalid(val reason: RuleProblem) : RulePreview
}

enum class RuleProblem { EMPTY, TOO_LONG, BAD_REGEX, TOO_SLOW, DUPLICATE }

data class FavouriteListRow(val id: Long, val name: String, val entries: Int)
data class FavouriteEntryRow(val playlistId: Long, val stableKey: String, val name: String)

/** Pure builders of the Channels pages (`ChannelsPagesTest`). Texts come from [BackupTexts]. */
object ChannelsPages {
    fun section(
        t: BackupTexts,
        advanced: Boolean,
        version: Long,
        playlists: List<PlaylistChoice>,
        selected: Long?,
        summary: ChannelsSummary,
        openGuideOn: OpenGuideOn,
    ): PageModel {
        val route = SettingsRoute.Section(SectionId.CHANNELS)
        val pid = selected ?: return PageModel(route, t.get(BackupText.CHANNELS_TITLE), rows = emptyList(), version = version, placeholder = t.get(BackupText.NO_PLAYLIST))
        val rows = ArrayList<SettingsRow>()
        // m6-channel-editor §2.1, §3.1: the editor is the first row (for the playlist the chooser below selects).
        rows += SettingsRow(ChannelsRows.EDITOR, t.get(BackupText.CHANNELS_EDITOR), value = t.get(BackupText.CHANNELS_EDITOR_VALUE),
            kind = RowKind.Link(SettingsRoute.ChannelEditor(pid)))
        if (playlists.size >= 2) {
            val idx = playlists.indexOfFirst { it.id == pid }.coerceAtLeast(0)
            rows += SettingsRow(ChannelsRows.PLAYLIST, t.get(BackupText.CHANNELS_PLAYLIST), value = playlists[idx].name,
                kind = RowKind.Choice(playlists.map { ChoiceOption(it.id.toString(), it.name) }, idx), effect = EffectTiming.NONE)
        }
        rows += SettingsRow(ChannelsRows.GROUPS, t.get(BackupText.CHANNELS_GROUPS),
            value = t.get(BackupText.CHANNELS_GROUPS_VALUE, summary.groups, summary.hiddenGroups, summary.lockedGroups), kind = RowKind.Link(SettingsRoute.Groups(pid)))
        if (advanced) {
            rows += SettingsRow(ChannelsRows.HIDDEN, t.get(BackupText.CHANNELS_HIDDEN), value = summary.hiddenChannels.toString(),
                kind = RowKind.Link(SettingsRoute.HiddenChannels(pid)), visibility = Visibility.ADVANCED)
        }
        rows += SettingsRow(ChannelsRows.FAVOURITES, t.get(BackupText.CHANNELS_FAVOURITES),
            value = t.get(BackupText.CHANNELS_FAVOURITES_VALUE, summary.favouriteLists), kind = RowKind.Link(SettingsRoute.FavouriteLists))
        val open = listOf(
            ChoiceOption(OpenGuideOn.ALL.name, t.get(BackupText.OPEN_ALL)),
            ChoiceOption(OpenGuideOn.FAVOURITES.name, t.get(BackupText.OPEN_FAVOURITES)),
            ChoiceOption(OpenGuideOn.LAST.name, t.get(BackupText.OPEN_LAST)),
        )
        val sel = open.indexOfFirst { it.id == openGuideOn.name }.coerceAtLeast(0)
        rows += SettingsRow(ChannelsRows.OPEN_GUIDE_ON, t.get(BackupText.CHANNELS_OPEN_GUIDE_ON), value = open[sel].label,
            description = t.get(BackupText.OPEN_GUIDE_ON_EFFECT), kind = RowKind.Choice(open, sel), effect = EffectTiming.NEXT_LAUNCH)
        if (advanced) {
            rows += SettingsRow(ChannelsRows.HIDE_RULES, t.get(BackupText.CHANNELS_HIDE_RULES),
                value = t.get(BackupText.CHANNELS_HIDE_RULES_VALUE, summary.rules, summary.ruleHiddenChannels),
                kind = RowKind.Link(SettingsRoute.HideRules(pid)), visibility = Visibility.ADVANCED)
        }
        return PageModel(route, t.get(BackupText.CHANNELS_TITLE), rows = rows, version = version)
    }

    fun groups(route: SettingsRoute.Groups, t: BackupTexts, version: Long, count: Int, loaded: Map<Int, List<SettingsRow>>): PageModel {
        val rows = listOf(SettingsRow(ChannelsRows.SHOW_ALL, t.get(BackupText.GROUPS_SHOW_ALL), kind = RowKind.Action()))
        return PageModel(route, t.get(BackupText.GROUPS_TITLE), rows = rows, list = PagedList(count, loaded = loaded), version = version,
            placeholder = if (count == 0) t.get(BackupText.NO_GROUPS) else null)
    }

    fun groupId(g: GroupAdminRow) = RowId(ChannelsRows.GROUP_PREFIX + g.kind + "/" + g.key)

    fun groupItem(g: GroupAdminRow, t: BackupTexts, pinSet: Boolean): SettingsRow {
        val badges = buildSet {
            if (g.hidden || g.ruleHidden) add(Badge.HIDDEN)
            if (g.locked) add(Badge.LOCKED)
            if (g.adult) add(Badge.ADULT)
        }
        // C-B2 (§3.9.2): Unlock and Reset lower protection, so they ask for the PIN when one is set; Lock stays ungated.
        val menu = listOf(
            if (g.hidden) ChoiceOption(ChannelsRows.M_SHOW, t.get(BackupText.GROUP_SHOW)) else ChoiceOption(ChannelsRows.M_HIDE, t.get(BackupText.GROUP_HIDE)),
            if (g.locked) ChoiceOption(ChannelsRows.M_UNLOCK, t.get(BackupText.GROUP_UNLOCK), needsPin = pinSet)
            else ChoiceOption(ChannelsRows.M_LOCK, t.get(BackupText.GROUP_LOCK), if (pinSet) null else t.get(BackupText.GROUP_LOCK_NEEDS_PIN)),
            ChoiceOption(ChannelsRows.M_MOVE, t.get(BackupText.GROUP_MOVE)),
            ChoiceOption(ChannelsRows.M_RESET, t.get(BackupText.GROUP_RESET), needsPin = pinSet),
        )
        return SettingsRow(
            groupId(g), g.name, value = t.get(BackupText.GROUP_VALUE, g.channels),
            description = if (g.locked && !pinSet) t.get(BackupText.GROUP_LOCK_NEEDS_PIN) else null,
            // C-S4 (§3.15 d): OK opens the menu (Show/Hide first), so Lock, Move and Reset are reachable without long OK.
            kind = RowKind.Item(menu = menu, movable = true, badges = badges),
        )
    }

    fun hidden(route: SettingsRoute.HiddenChannels, t: BackupTexts, version: Long, count: Int, loaded: Map<Int, List<SettingsRow>>): PageModel {
        val rows = if (count == 0) emptyList() else listOf(
            SettingsRow(
                ChannelsRows.UNHIDE_ALL, t.get(BackupText.HIDDEN_UNHIDE_ALL),
                kind = RowKind.Action(confirm = ConfirmSpec(t.get(BackupText.HIDDEN_UNHIDE_ALL_CONFIRM_TITLE), t.get(BackupText.HIDDEN_UNHIDE_ALL_CONFIRM_BODY), t.get(BackupText.HIDDEN_UNHIDE_ALL), t.get(BackupText.CANCEL))),
            ),
        )
        return PageModel(route, t.get(BackupText.HIDDEN_TITLE), rows = rows, list = PagedList(count, loaded = loaded), version = version,
            placeholder = if (count == 0) t.get(BackupText.NO_HIDDEN) else null)
    }

    fun hiddenItem(pid: Long, h: HiddenChannelRow, t: BackupTexts): SettingsRow =
        if (h.byUser || h.ruleId == null) {
            SettingsRow(RowId(ChannelsRows.HIDDEN_PREFIX + h.stableKey), h.name, value = h.group, description = t.get(BackupText.HIDDEN_BY_YOU),
                kind = RowKind.Item(primary = ChannelsRows.P_UNHIDE, menu = listOf(ChoiceOption(ChannelsRows.P_UNHIDE, t.get(BackupText.HIDDEN_UNHIDE))), badges = setOf(Badge.HIDDEN)))
        } else {
            // Rules are undone at the rule, not per channel (§3.7.2).
            SettingsRow(RowId(ChannelsRows.HIDDEN_PREFIX + h.stableKey), h.name, value = h.group, description = t.get(BackupText.HIDDEN_BY_RULE, h.rulePattern ?: ""),
                kind = RowKind.Item(route = SettingsRoute.HideRules(pid), badges = setOf(Badge.HIDDEN)))
        }

    fun rules(route: SettingsRoute.HideRules, t: BackupTexts, version: Long, draft: RuleDraft, preview: RulePreview?, rules: List<HideRuleRow>): PageModel {
        val rows = ArrayList<SettingsRow>()
        val targets = listOf(ChoiceOption(RuleTarget.GROUP_NAME.name, t.get(BackupText.RULE_TARGET_GROUP)), ChoiceOption(RuleTarget.CHANNEL_NAME.name, t.get(BackupText.RULE_TARGET_CHANNEL)))
        rows += SettingsRow(ChannelsRows.RULE_TARGET, t.get(BackupText.RULE_TARGET), value = targets[draft.target.ordinal].label, kind = RowKind.Choice(targets, draft.target.ordinal), effect = EffectTiming.NONE)
        rows += SettingsRow(ChannelsRows.RULE_PATTERN, t.get(BackupText.RULE_PATTERN), value = draft.pattern.ifEmpty { null }, kind = RowKind.Text(draft.pattern, TextHint.PATTERN), effect = EffectTiming.NONE)
        val kinds = listOf(ChoiceOption("CONTAINS", t.get(BackupText.RULE_KIND_CONTAINS)), ChoiceOption("REGEX", t.get(BackupText.RULE_KIND_REGEX)))
        val k = if (draft.isRegex) 1 else 0
        rows += SettingsRow(ChannelsRows.RULE_KIND, t.get(BackupText.RULE_KIND), value = kinds[k].label, kind = RowKind.Choice(kinds, k), effect = EffectTiming.NONE)
        val (previewText, tone) = when (preview) {
            null -> t.get(BackupText.RULE_PREVIEW_EMPTY) to Tone.NORMAL
            is RulePreview.Counts -> t.get(BackupText.RULE_PREVIEW, preview.groups, preview.channels) to Tone.NORMAL
            is RulePreview.Invalid -> problemText(preview.reason, t) to Tone.WARN
        }
        rows += SettingsRow(ChannelsRows.RULE_PREVIEW, previewText, kind = RowKind.Info(emptyList(), tone), effect = EffectTiming.NONE)
        val canSave = preview is RulePreview.Counts
        rows += SettingsRow(ChannelsRows.RULE_SAVE, t.get(BackupText.RULE_SAVE), kind = RowKind.Action(), enabled = canSave,
            disabledReason = if (canSave) null else (preview as? RulePreview.Invalid)?.let { problemText(it.reason, t) } ?: t.get(BackupText.RULE_PREVIEW_EMPTY))
        for (r in rules) rows += ruleItem(r, t)
        return PageModel(route, t.get(BackupText.RULES_TITLE), rows = rows, version = version)
    }

    fun ruleItem(r: HideRuleRow, t: BackupTexts): SettingsRow {
        val target = t.get(if (r.target == RuleTarget.GROUP_NAME) BackupText.RULE_TARGET_GROUP else BackupText.RULE_TARGET_CHANNEL)
        val label = t.get(if (r.isRegex) BackupText.RULE_VALUE_REGEX else BackupText.RULE_VALUE_CONTAINS, target, r.pattern)
        val reason = when (r.disabledReason) {
            RuleDisabledReason.TOO_SLOW -> t.get(BackupText.RULE_TOO_SLOW)
            RuleDisabledReason.BAD_REGEX -> t.get(BackupText.RULE_BAD_REGEX)
            null -> if (!r.enabled) t.get(BackupText.RULE_OFF) else null
        }
        val menu = listOf(
            if (r.enabled) ChoiceOption(ChannelsRows.M_DISABLE, t.get(BackupText.RULE_DISABLE)) else ChoiceOption(ChannelsRows.M_ENABLE, t.get(BackupText.RULE_ENABLE)),
            ChoiceOption(ChannelsRows.M_DELETE, t.get(BackupText.RULE_DELETE)),
        )
        return SettingsRow(RowId(ChannelsRows.RULE_PREFIX + r.id), label, value = t.get(BackupText.RULE_CHANNELS, r.channels),
            description = reason, kind = RowKind.Item(menu = menu, badges = if (r.enabled) emptySet() else setOf(Badge.FAILED)))
    }

    fun problemText(p: RuleProblem, t: BackupTexts): String = t.get(
        when (p) {
            RuleProblem.EMPTY -> BackupText.RULE_EMPTY
            RuleProblem.TOO_LONG -> BackupText.RULE_TOO_LONG
            RuleProblem.BAD_REGEX -> BackupText.RULE_BAD_REGEX
            RuleProblem.TOO_SLOW -> BackupText.RULE_TOO_SLOW
            RuleProblem.DUPLICATE -> BackupText.RULE_DUPLICATE
        },
    )

    fun favouriteLists(t: BackupTexts, version: Long, lists: List<FavouriteListRow>): PageModel {
        val rows = ArrayList<SettingsRow>()
        rows += SettingsRow(ChannelsRows.FAV_NEW, t.get(BackupText.FAV_NEW), kind = RowKind.Text("", TextHint.NAME), effect = EffectTiming.LIVE)
        for (l in lists) {
            rows += SettingsRow(RowId(ChannelsRows.FAV_LIST_PREFIX + l.id), l.name, value = t.get(BackupText.FAV_LIST_VALUE, l.entries),
                kind = RowKind.Item(route = SettingsRoute.FavouriteList(l.id), menu = listOf(ChoiceOption(ChannelsRows.M_MOVE, t.get(BackupText.FAV_MOVE))), movable = true))
        }
        return PageModel(SettingsRoute.FavouriteLists, t.get(BackupText.FAV_TITLE), rows = rows, version = version,
            placeholder = null)
    }

    fun favouriteList(route: SettingsRoute.FavouriteList, t: BackupTexts, version: Long, list: FavouriteListRow?, entries: List<FavouriteEntryRow>, onlyList: Boolean): PageModel {
        if (list == null) return PageModel(route, t.get(BackupText.FAV_TITLE), rows = emptyList(), version = version, placeholder = t.get(BackupText.NO_LISTS))
        val rows = ArrayList<SettingsRow>()
        rows += SettingsRow(ChannelsRows.FAV_LIST_NAME, t.get(BackupText.FAV_LIST_NAME), value = list.name, kind = RowKind.Text(list.name, TextHint.NAME))
        rows += SettingsRow(
            ChannelsRows.FAV_LIST_DELETE, t.get(BackupText.FAV_LIST_DELETE),
            kind = RowKind.Action(destructive = true, confirm = ConfirmSpec(t.get(BackupText.FAV_LIST_DELETE_CONFIRM_TITLE), t.get(BackupText.FAV_LIST_DELETE_CONFIRM_BODY, list.name), t.get(BackupText.DELETE), t.get(BackupText.CANCEL))),
            enabled = !onlyList, disabledReason = if (onlyList) t.get(BackupText.FAV_LIST_ONLY_ONE) else null,
        )
        for (e in entries) {
            rows += SettingsRow(RowId(ChannelsRows.FAV_ENTRY_PREFIX + e.playlistId + "/" + e.stableKey), e.name,
                kind = RowKind.Item(menu = listOf(ChoiceOption(ChannelsRows.M_REMOVE, t.get(BackupText.FAV_REMOVE)), ChoiceOption(ChannelsRows.M_MOVE, t.get(BackupText.FAV_MOVE))), movable = true))
        }
        return PageModel(route, list.name, rows = rows, version = version, placeholder = if (entries.isEmpty()) t.get(BackupText.NO_FAVOURITES) else null)
    }
}
