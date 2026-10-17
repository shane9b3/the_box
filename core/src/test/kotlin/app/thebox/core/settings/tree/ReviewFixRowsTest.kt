package app.thebox.core.settings.tree

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.RestorePreview
import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.QueueEffects
import app.thebox.core.settings.state.SettingsMachineImpl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Row builders and their reachability after the M5 code review (2026-10-16); each test names its finding. */
class ReviewFixRowsTest {
    private val texts = RowTexts { key, args -> if (args.isEmpty()) key else "$key(${args.joinToString()})" }
    private val bt = BackupTexts { k, args -> if (args.isEmpty()) k.name else k.name + args.joinToString(",", "(", ")") }
    private val names = object : ButtonMappingRows.Names {
        override fun key(keyCode: Int) = "K$keyCode"
        override fun action(action: BoxAction) = action.id
        override fun group(group: ActionGroup) = group.name
    }
    private val adult = GroupAdminRow(kind = 0, key = "adult", name = "Adult", channels = 4, hidden = false, lockedExplicit = null, adult = true, ruleHidden = false)

    /**
     * C-B2 (adopted from the core reviewer): §3.9.2 "Lock/Unlock in the channel or group menu" asks for the PIN when one
     * is set. The group manager's Unlock (Settings → Channels and groups → Groups) ran with no PIN.
     */
    @Test
    fun groupManagerUnlockAsksForThePin() {
        val gate = app.thebox.core.settings.fakes.FakePinGate(pinSet = true)
        val effects = QueueEffects()
        val m = SettingsMachineImpl(effects, { 0L })
        m.pinGate = gate
        val route = SettingsRoute.Groups(1)
        m.open(route, fromFull = false)
        val item = ChannelsPages.groupItem(adult, bt, pinSet = true)
        m.dispatch(SettingsEvent.Page(ChannelsPages.groups(route, bt, 1, count = 1, loaded = mapOf(0 to listOf(item)))))
        m.act(GuideEvent.Act(BoxAction.DOWN)) // Show all → the group
        m.act(GuideEvent.Act(BoxAction.CONTEXT_MENU))
        val menu = assertNotNull(m.ui.value.menu)
        repeat(menu.options.indexOfFirst { it.id == ChannelsRows.M_UNLOCK }) { m.act(GuideEvent.Act(BoxAction.DOWN)) }
        m.act(GuideEvent.Act(BoxAction.SELECT))
        assertTrue(gate.requests.isNotEmpty(), "Unlock ran without the PIN: ${effects.intents}")
        assertTrue(effects.intents.isEmpty(), "nothing before the grant")
    }

    /** C-B2: Unlock and Reset lower protection (PIN while one is set); Lock, Hide and Move never ask. */
    @Test
    fun unlockAndResetNeedThePinWhenSet() {
        val withPin = (ChannelsPages.groupItem(adult, bt, pinSet = true).kind as RowKind.Item).menu.associate { it.id to it.needsPin }
        assertEquals(mapOf(ChannelsRows.M_HIDE to false, ChannelsRows.M_UNLOCK to true, ChannelsRows.M_MOVE to false, ChannelsRows.M_RESET to true), withPin)
        val noPin = (ChannelsPages.groupItem(adult, bt, pinSet = false).kind as RowKind.Item).menu
        assertTrue(noPin.none { it.needsPin }, "without a PIN nothing asks")
        val unlocked = adult.copy(lockedExplicit = false)
        val lock = (ChannelsPages.groupItem(unlocked, bt, pinSet = true).kind as RowKind.Item).menu.single { it.id == ChannelsRows.M_LOCK }
        assertFalse(lock.needsPin, "Lock raises protection: no PIN")
    }

    /** C-S4 (§3.7.1, §3.15 d): OK on a group opens its menu with Show/Hide first (Lock, Move and Reset without long OK). */
    @Test
    fun groupItemOkOpensTheMenuWithShowHideFirst() {
        val m = SettingsMachineImpl(QueueEffects(), { 0L })
        val route = SettingsRoute.Groups(1)
        m.open(route, fromFull = false)
        m.dispatch(SettingsEvent.Page(ChannelsPages.groups(route, bt, 1, count = 1, loaded = mapOf(0 to listOf(ChannelsPages.groupItem(adult, bt, pinSet = false))))))
        m.act(GuideEvent.Act(BoxAction.DOWN))
        m.act(GuideEvent.Act(BoxAction.SELECT))
        assertEquals(ChannelsRows.M_HIDE, m.ui.value.menu?.options?.first()?.id)
    }

    /** C-S4 (adopted from the core reviewer): §3.8.3 "ActionBindings: one Item per key; OK opens a menu". */
    @Test
    fun okOnAKeyRowOpensItsMenu() {
        val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(KeyBindingOverrides()) }
        val m = SettingsMachineImpl(QueueEffects(), { 0L })
        val route = SettingsRoute.ActionBindings(BoxAction.ZAP_UP)
        m.open(route, fromFull = false)
        val page = ButtonMappingRows.actionPage(BoxAction.ZAP_UP, map, KeyBindingOverrides(), names, texts, 1)
        m.dispatch(SettingsEvent.Page(page))
        val first = page.rows.first()
        require(first.kind is RowKind.Item && (first.kind as RowKind.Item).menu.isNotEmpty()) { "a key row with a menu comes first" }
        m.act(GuideEvent.Act(BoxAction.SELECT))
        assertNotNull(m.ui.value.menu, "OK on a key row opens Remove / Reset this key")
    }

    /** C-B2 / D-B1 (adopted from the data reviewer): Restore and Restore now ask for the PIN while one is set. */
    @Test
    fun restoreAndRestoreNowNeedThePinWhenSet() {
        val page = BackupPages.backup(BackupPageState(pinSet = true), bt, advanced = false, version = 1)
        assertTrue((page.rows.first { it.id == BackupRows.RESTORE }.kind as RowKind.Action).needsPin, "Restore must ask for the PIN when one is set (§3.9.2)")
        val open = BackupPages.backup(BackupPageState(pinSet = false), bt, advanced = false, version = 1)
        assertFalse((open.rows.first { it.id == BackupRows.RESTORE }.kind as RowKind.Action).needsPin)
        val preview = BackupPages.preview(SettingsRoute.RestorePreview(1), BackupPages.PreviewState(preview(), pinSet = true), bt, 2)
        assertTrue((preview.rows.first { it.id == BackupRows.PREVIEW_GO }.kind as RowKind.Action).needsPin, "every restore path ends on Restore now")
    }

    /** C-S8 (§3.4.1, §3.4.6): a successful restore offers Start watching, and a PIN when it carried locks without one. */
    @Test
    fun successfulRestoreOffersStartWatchingAndThePin() {
        val route = SettingsRoute.RestorePreview(1)
        val done = BackupPages.preview(route, BackupPages.PreviewState(preview(), result = "DONE", restored = true), bt, 1)
        assertEquals(listOf(BackupRows.PREVIEW_RESULT, BackupRows.PREVIEW_START), done.rows.takeLast(2).map { it.id })
        assertEquals(BackupRows.PREVIEW_START, done.focus)
        val locks = BackupPages.preview(route, BackupPages.PreviewState(preview(), result = "DONE", restored = true, pinOfferGroups = 3), bt, 2)
        assertEquals(
            listOf(BackupRows.PREVIEW_RESULT, BackupRows.PREVIEW_PIN_OFFER, BackupRows.PREVIEW_PIN_SET, BackupRows.PREVIEW_PIN_LATER, BackupRows.PREVIEW_START),
            locks.rows.takeLast(5).map { it.id },
        )
        assertEquals("RESTORE_PIN_OFFER(3)", locks.rows.first { it.id == BackupRows.PREVIEW_PIN_OFFER }.label)
        val failed = BackupPages.preview(route, BackupPages.PreviewState(preview(), result = "FAIL"), bt, 3)
        assertFalse(failed.rows.any { it.id == BackupRows.PREVIEW_START }, "no Start watching after a failure")
    }

    /** C-S3 (§3.3.8): Change PIN is a plain action; the CreatePin(change) flow does the one verify. */
    @Test
    fun changePinRowDoesNotAskOnItsOwn() {
        val st = app.thebox.core.parental.ParentalState(pinSet = true, unlocked = true, policy = app.thebox.core.parental.ParentalPolicy())
        val row = ParentalRows.page(st, texts, 1, advanced = false).rows.first { it.id == ParentalRows.PIN }
        assertFalse((row.kind as RowKind.Action).needsPin)
    }

    /** C-L1 (§3.2.1): the section the pane highlights is the section BACK returns to, for every route. */
    @Test
    fun sectionOfMatchesParentChain() {
        val id = app.thebox.core.refresh.ChannelIdentity(1, app.thebox.core.model.StableKey("k"))
        val routes = listOf(
            SettingsRoute.Playlist(1), SettingsRoute.PlaylistSource(1), SettingsRoute.NetworkTest(1), SettingsRoute.Groups(1),
            SettingsRoute.HiddenChannels(1), SettingsRoute.HideRules(1), SettingsRoute.FavouriteLists, SettingsRoute.FavouriteList(1),
            SettingsRoute.EpgSources, SettingsRoute.FixGuide(null), SettingsRoute.FixGuide(id), SettingsRoute.Channel(id),
            SettingsRoute.ButtonMapping, SettingsRoute.ActionBindings(BoxAction.ZAP_UP), SettingsRoute.Diagnostics, SettingsRoute.SyncStatus,
            SettingsRoute.Licences, SettingsRoute.Backup, SettingsRoute.RestorePreview(1), SettingsRoute.AddSource(),
            SettingsRoute.PhoneSetup(), SettingsRoute.ImportProgress(1, false), SettingsRoute.GroupsReview(1, false), SettingsRoute.MatchingReport(1, false),
        )
        for (r in routes) {
            var p: SettingsRoute? = r
            var guard = 0
            while (p != null && p !is SettingsRoute.Section && guard++ < 8) p = SettingsMachineImpl.parentOf(p)
            assertEquals((p as? SettingsRoute.Section)?.id, SettingsTree.sectionOf(r), "$r")
        }
        assertEquals(SectionId.GUIDE, SettingsTree.sectionOf(SettingsRoute.SyncStatus))
        assertNull(SettingsTree.sectionOf(SettingsRoute.FirstRun))
    }

    private fun preview() = RestorePreview(BackupHeader(createdAtMs = 0, appVersionName = "1.0", appVersionCode = 1, includesLogins = false, counts = emptyMap()), emptyList(), needsPassphrase = false, problems = emptyList())
}
