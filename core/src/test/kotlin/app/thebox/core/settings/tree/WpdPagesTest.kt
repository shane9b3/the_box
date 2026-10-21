package app.thebox.core.settings.tree

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.MatchTarget
import app.thebox.core.backup.PlaylistMatch
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.Badge
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import app.thebox.core.setup.lan.LanLimits
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PhoneScreenEffect
import app.thebox.core.setup.lan.PhoneScreenModel
import app.thebox.core.setup.lan.PhoneScreenState
import app.thebox.core.setup.lan.ServerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-D's page builders as tables (docs/design/m5-setup-settings.md §3.3.5, §3.3.9, §3.7, §3.10.4, §3.5.1). */
class WpdPagesTest {
    /** Texts as "KEY(args)", so the tables show which string a row uses. */
    private val t = BackupTexts { key, args -> if (args.isEmpty()) key.name else key.name + args.joinToString(",", "(", ")") }

    private fun PageModel.ids() = rows.map { it.id.key }

    /** Every registered row a builder emits carries the registry's visibility and EffectTiming. */
    private fun assertMatchesRegistry(p: PageModel) {
        for (r in p.rows) {
            val spec = SettingsTree.spec(r.id) ?: continue
            assertEquals(spec.visibility, r.visibility, r.id.key)
            assertEquals(spec.effect, r.effect, r.id.key)
        }
    }

    @Test
    fun registryRowsAreWpdsAndInTheirSections() {
        assertEquals(setOf(SectionId.CHANNELS), ChannelsRows.rows.map { it.section }.toSet())
        assertEquals(setOf(SectionId.BACKUP), BackupRows.rows.map { it.section }.toSet())
        assertTrue((ChannelsRows.rows + BackupRows.rows).all { it.owner == "D" })
        assertEquals(Visibility.ADVANCED, SettingsTree.spec(ChannelsRows.HIDE_RULES)!!.visibility)
        // m6-channel-editor §3.1 (owner ruling Q3): the editor is SIMPLE, the hidden-channels page moves to ADVANCED.
        assertEquals(Visibility.SIMPLE, SettingsTree.spec(ChannelsRows.EDITOR)!!.visibility)
        assertEquals(Visibility.ADVANCED, SettingsTree.spec(ChannelsRows.HIDDEN)!!.visibility)
        assertEquals(EffectTiming.NEXT_LAUNCH, SettingsTree.spec(ChannelsRows.OPEN_GUIDE_ON)!!.effect)
        // channels.playlist is a context control (§3.3.5 footnote): 4 counted simple rows here, 2 in Backup.
        assertEquals(4, ChannelsRows.rows.count { it.visibility == Visibility.SIMPLE && it.id !in ChannelsRows.NOT_COUNTED })
        assertEquals(2, BackupRows.rows.count { it.visibility == Visibility.SIMPLE })
    }

    private val summary = ChannelsSummary(groups = 64, hiddenGroups = 12, lockedGroups = 2, hiddenChannels = 30, rules = 2, ruleHiddenChannels = 340, favouriteLists = 3)

    @Test
    fun channelsSection() {
        val one = ChannelsPages.section(t, advanced = false, version = 1, listOf(PlaylistChoice(7, "A")), 7, summary, OpenGuideOn.LAST)
        assertEquals(listOf("channels.editor", "channels.groups", "channels.favourites", "channels.open_guide_on"), one.ids())
        assertEquals("CHANNELS_EDITOR", one.rows[0].label)
        assertEquals("CHANNELS_EDITOR_VALUE", one.rows[0].value)
        assertEquals(SettingsRoute.ChannelEditor(7), (one.rows[0].kind as RowKind.Link).route)
        assertEquals("CHANNELS_GROUPS_VALUE(64,12,2)", one.rows[1].value)
        assertEquals(SettingsRoute.Groups(7), (one.rows[1].kind as RowKind.Link).route)
        assertEquals(2, (one.rows[3].kind as RowKind.Choice).selected)
        assertMatchesRegistry(one)

        val two = ChannelsPages.section(t, advanced = true, version = 2, listOf(PlaylistChoice(7, "A"), PlaylistChoice(9, "B")), 9, summary, OpenGuideOn.ALL)
        assertEquals(listOf("channels.editor", "channels.playlist", "channels.groups", "channels.hidden", "channels.favourites", "channels.open_guide_on", "channels.hide_rules"), two.ids())
        assertEquals(SettingsRoute.ChannelEditor(9), (two.rows[0].kind as RowKind.Link).route)
        assertEquals(1, (two.rows[1].kind as RowKind.Choice).selected)
        assertEquals(SettingsRoute.HiddenChannels(9), (two.rows[3].kind as RowKind.Link).route)
        assertEquals(Visibility.ADVANCED, two.rows[3].visibility)
        assertEquals(SettingsRoute.HideRules(9), (two.rows.last().kind as RowKind.Link).route)
        assertMatchesRegistry(two)

        val none = ChannelsPages.section(t, false, 3, emptyList(), null, ChannelsSummary(), OpenGuideOn.LAST)
        assertEquals("NO_PLAYLIST", none.placeholder)
    }

    @Test
    fun groupItems() {
        val adultAuto = GroupAdminRow(0, "g:a", "Locked group", 40, hidden = false, lockedExplicit = null, adult = true, ruleHidden = false)
        val row = ChannelsPages.groupItem(adultAuto, t, pinSet = false)
        val kind = row.kind as RowKind.Item
        assertEquals(setOf(Badge.LOCKED, Badge.ADULT), kind.badges)
        assertTrue(kind.movable)
        assertEquals(listOf("hide", "unlock", "move", "reset"), kind.menu.map { it.id })
        assertEquals("GROUP_LOCK_NEEDS_PIN", row.description)
        assertEquals("group/0/g:a", row.id.key)

        val unlocked = ChannelsPages.groupItem(adultAuto.copy(lockedExplicit = false, hidden = true), t, pinSet = true)
        assertEquals(listOf("show", "lock", "move", "reset"), (unlocked.kind as RowKind.Item).menu.map { it.id })
        assertEquals(setOf(Badge.HIDDEN, Badge.ADULT), (unlocked.kind as RowKind.Item).badges)
        assertNull(unlocked.description)
        val lockNoPin = ChannelsPages.groupItem(adultAuto.copy(lockedExplicit = false, adult = false), t, pinSet = false)
        assertEquals("GROUP_LOCK_NEEDS_PIN", (lockNoPin.kind as RowKind.Item).menu[1].detail)
    }

    @Test
    fun hiddenChannels() {
        val r = SettingsRoute.HiddenChannels(7)
        assertEquals("NO_HIDDEN", ChannelsPages.hidden(r, t, 1, 0, emptyMap()).placeholder)
        val page = ChannelsPages.hidden(r, t, 1, 3, emptyMap())
        assertNotNull((page.rows.single().kind as RowKind.Action).confirm)
        assertEquals(3, page.list!!.count)
        val byUser = ChannelsPages.hiddenItem(7, HiddenChannelRow("k", "One", "News", byUser = true, ruleId = null, rulePattern = null), t)
        assertEquals("unhide", (byUser.kind as RowKind.Item).primary)
        // A rule-hidden channel is undone at the rule (§3.7.2).
        val byRule = ChannelsPages.hiddenItem(7, HiddenChannelRow("k", "XXX One", "News", byUser = false, ruleId = 1, rulePattern = "xxx"), t)
        assertEquals(SettingsRoute.HideRules(7), (byRule.kind as RowKind.Item).route)
        assertEquals("HIDDEN_BY_RULE(xxx)", byRule.description)
    }

    @Test
    fun hideRulesPage() {
        val r = SettingsRoute.HideRules(7)
        val empty = ChannelsPages.rules(r, t, 1, RuleDraft(), null, emptyList())
        assertEquals(listOf("rules.target", "rules.pattern", "rules.kind", "rules.preview", "rules.save"), empty.ids())
        assertFalse(empty.rows.last().enabled)
        val counted = ChannelsPages.rules(r, t, 2, RuleDraft(RuleTarget.CHANNEL_NAME, "xxx"), RulePreview.Counts(0, 12), emptyList())
        assertEquals("RULE_PREVIEW(0,12)", counted.rows[3].label)
        assertTrue(counted.rows[4].enabled)
        val slow = ChannelsPages.rules(r, t, 3, RuleDraft(pattern = "(a+)+", isRegex = true), RulePreview.Invalid(RuleProblem.TOO_SLOW), emptyList())
        assertEquals(Tone.WARN, (slow.rows[3].kind as RowKind.Info).tone)
        assertEquals("RULE_TOO_SLOW", slow.rows[4].disabledReason)
        val withRules = ChannelsPages.rules(r, t, 4, RuleDraft(), null, listOf(
            HideRuleRow(1, RuleTarget.GROUP_NAME, "Sports", false, true, null, 340),
            HideRuleRow(2, RuleTarget.CHANNEL_NAME, "^x", true, false, RuleDisabledReason.TOO_SLOW, 0),
        ))
        val r1 = withRules.rows[5]
        assertEquals("RULE_VALUE_CONTAINS(RULE_TARGET_GROUP,Sports)", r1.label)
        assertEquals("RULE_CHANNELS(340)", r1.value)
        val r2 = withRules.rows[6]
        assertEquals("RULE_TOO_SLOW", r2.description)
        assertEquals(setOf(Badge.FAILED), (r2.kind as RowKind.Item).badges)
        assertEquals(listOf("enable", "delete"), (r2.kind as RowKind.Item).menu.map { it.id })
    }

    @Test
    fun favourites() {
        val lists = ChannelsPages.favouriteLists(t, 1, listOf(FavouriteListRow(1, "Favourites", 12), FavouriteListRow(2, "Kids", 0)))
        assertEquals(listOf("fav.new", "favlist/1", "favlist/2"), lists.ids())
        assertEquals(SettingsRoute.FavouriteList(2), (lists.rows[2].kind as RowKind.Item).route)
        val only = ChannelsPages.favouriteList(SettingsRoute.FavouriteList(1), t, 1, FavouriteListRow(1, "Favourites", 1), listOf(FavouriteEntryRow(7, "k", "One")), onlyList = true)
        assertFalse(only.rows[1].enabled)
        assertEquals("FAV_LIST_ONLY_ONE", only.rows[1].disabledReason)
        assertEquals("fav/7/k", only.rows[2].id.key)
        assertTrue((only.rows[2].kind as RowKind.Item).movable)
    }

    @Test
    fun backupPageStages() {
        val idle = BackupPages.backup(BackupPageState(), t, advanced = false, version = 1)
        assertEquals(listOf("backup.now", "backup.restore", "backup.nudge").filter { it != "backup.nudge" }, idle.ids())
        assertMatchesRegistry(idle)

        val targets = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.CHOOSE_TARGET, documentsUi = false), t, false, 2)
        assertEquals(listOf("backup.now", "backup.now.phone", "backup.now.downloads", "backup.flow.cancel", "backup.restore"), targets.ids())
        assertEquals(SettingsRoute.PhoneSetup(purpose = PhonePurpose.BACKUP_DOWNLOAD), (targets.rows[1].kind as RowKind.Link).route)
        val withFile = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.CHOOSE_TARGET, documentsUi = true), t, false, 3)
        assertTrue("backup.now.file" in withFile.ids())

        // Include logins is on and focused first (N13); two masked passphrase rows.
        val logins = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.LOGINS), t, false, 4)
        assertEquals(listOf("backup.now", "backup.now.logins", "backup.now.pass1", "backup.now.pass2", "backup.now.go", "backup.flow.cancel", "backup.restore"), logins.ids())
        assertTrue((logins.rows[1].kind as RowKind.Toggle).on)
        assertTrue((logins.rows[2].kind as RowKind.Text).masked)
        val noLogins = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.LOGINS, includeLogins = false, error = "BAD"), t, false, 5)
        assertFalse("backup.now.pass1" in noLogins.ids())
        assertEquals(Tone.BAD, (noLogins.rows.first { it.id == BackupRows.STATUS }.kind as RowKind.Info).tone)
        val running = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.RUNNING), t, false, 6)
        assertFalse(running.rows.first { it.id == BackupRows.GO }.enabled)

        val sources = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.CHOOSE_SOURCE), t, false, 7)
        assertEquals(SettingsRoute.PhoneSetup(purpose = PhonePurpose.RESTORE), (sources.rows.first { it.id == BackupRows.FROM_PHONE }.kind as RowKind.Link).route)
        val list = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.RESTORE_LIST, downloads = listOf("TheBox-auto-2026-10-11.thebox")), t, false, 8)
        assertTrue("backup.restore.downloads/TheBox-auto-2026-10-11.thebox" in list.ids())
        val unreadable = BackupPages.backup(BackupPageState(stage = BackupPageState.Stage.RESTORE_LIST, downloadsUnreadable = true), t, false, 9)
        assertEquals("RESTORE_DOWNLOADS_UNREADABLE", unreadable.rows.first { it.id.key == BackupRows.DOWNLOAD_FILE_PREFIX }.label)
    }

    @Test
    fun backupPageAdvancedRowsAndHonestWeeklyValue() {
        val s = BackupPageState(lastAutoAtMs = 5, lastAutoLocation = "TheBox-auto-2026-10-11.thebox", durable = false, nudge = true, formatTime = { "Sun 03:00" })
        val p = BackupPages.backup(s, t, advanced = true, version = 1)
        assertEquals(listOf("backup.now", "backup.restore", "backup.nudge", "backup.weekly", "backup.passphrase"), p.ids())
        val weekly = p.rows.first { it.id == BackupRows.WEEKLY }
        assertEquals("WEEKLY_VALUE(Sun 03:00,TheBox-auto-2026-10-11.thebox)", weekly.value)
        assertEquals("WEEKLY_APP_ONLY", weekly.description)
        assertEquals(SettingsRoute.PhoneSetup(purpose = PhonePurpose.BACKUP_DOWNLOAD), (p.rows.first { it.id == BackupRows.NUDGE }.kind as RowKind.Link).route)
        assertMatchesRegistry(p)
        val lost = BackupPages.backup(s.copy(passphraseLostToKeyReset = true, durable = true), t, true, 2)
        assertEquals("WEEKLY_NO_LOGINS_AFTER_RESET", lost.rows.first { it.id == BackupRows.WEEKLY }.description)
    }

    @Test
    fun restorePreview() {
        val header = BackupHeader(createdAtMs = 1, appVersionName = "1.0.3", appVersionCode = 3, includesLogins = true,
            counts = mapOf("fav_lists" to 3, "favourites" to 112, "reminders" to 4, "channel_prefs" to 340, "settings" to 41))
        val preview = RestorePreview(header, listOf(PlaylistMatch("u1", "Provider A", MatchTarget.SAME_UID, 7), PlaylistMatch("u2", "Provider B", MatchTarget.NEW, null)), needsPassphrase = true, problems = emptyList())
        val route = SettingsRoute.RestorePreview(1)
        val p = BackupPages.preview(route, BackupPages.PreviewState(preview), t, 1)
        assertEquals("PREVIEW_SUMMARY(3,112,4,340,41)", p.rows[0].label)
        assertEquals(listOf("PREVIEW_WILL_UPDATE", "PREVIEW_WILL_ADD"), p.rows.drop(1).take(2).map { it.value })
        assertTrue(p.ids().containsAll(listOf("restore.passphrase", "restore.mode", "restore.go")))
        assertEquals(0, (p.rows.first { it.id == BackupRows.PREVIEW_MODE }.kind as RowKind.Choice).selected) // Merge is the default
        assertNull((p.rows.first { it.id == BackupRows.PREVIEW_GO }.kind as RowKind.Action).confirm)
        val replace = BackupPages.preview(route, BackupPages.PreviewState(preview, mode = RestoreMode.REPLACE), t, 2)
        assertNotNull((replace.rows.first { it.id == BackupRows.PREVIEW_GO }.kind as RowKind.Action).confirm)
        val done = BackupPages.preview(route, BackupPages.PreviewState(preview, result = "RESTORE_DONE(2,500)"), t, 3)
        assertFalse("restore.go" in done.ids())
        assertEquals("PREVIEW_GONE", BackupPages.preview(route, BackupPages.PreviewState(null), t, 4).placeholder)
        // Every failure has its own reason + next step text.
        assertEquals(RestoreFailure.entries.size, RestoreFailure.entries.map { BackupPages.failureText(it, t) }.toSet().size)
    }

    @Test
    fun phoneScreenKeysAndHint() {
        val s0 = PhoneScreenState()
        assertEquals(PhoneScreenState(large = true) to null, PhoneScreenModel.act(s0, BoxAction.SELECT, typeOnTvShown = false))
        assertEquals(s0, PhoneScreenModel.act(s0, BoxAction.DOWN, typeOnTvShown = false).first)
        val down = PhoneScreenModel.act(s0, BoxAction.DOWN, typeOnTvShown = true).first
        assertTrue(down.typeOnTvFocused)
        assertEquals(PhoneScreenEffect.TYPE_ON_TV, PhoneScreenModel.act(down, BoxAction.SELECT, true).second)
        assertFalse(PhoneScreenModel.act(down, BoxAction.UP, true).first.typeOnTvFocused)
        assertEquals(PhoneScreenEffect.LEAVE, PhoneScreenModel.act(s0, BoxAction.BACK, false).second)

        val waiting = LanServerState(ServerPhase.WAITING)
        assertFalse(PhoneScreenModel.showIsolationHint(waiting, 0, LanLimits.NO_REQUEST_HINT_MS - 1))
        assertTrue(PhoneScreenModel.showIsolationHint(waiting, 0, LanLimits.NO_REQUEST_HINT_MS))
        assertFalse(PhoneScreenModel.showIsolationHint(waiting.copy(lastRequestAtMs = 1), 0, LanLimits.NO_REQUEST_HINT_MS * 2))
        assertTrue(PhoneScreenModel.showTypeOnTv(hint = false, vpn = true, purpose = PhonePurpose.SETUP))
        assertFalse(PhoneScreenModel.showTypeOnTv(hint = true, vpn = true, purpose = PhonePurpose.RESTORE))
    }
}
