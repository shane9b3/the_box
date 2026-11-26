package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.Pane
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.storage.StoragePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-player-settings-panel.md §2.6 (route table, S11), §4.1, §4.4 (S8); "Commit 0 rulings" (0b). */
class SettingsPanelRulesTest {
    private val ch = ChannelIdentity(1, StableKey("xc:1"))

    /** The §2.6 table: route → expands. Every route class appears at least once (checked below). */
    private val table: List<Pair<SettingsRoute, Boolean>> = buildList {
        add(SettingsRoute.Root to false)
        add(SettingsRoute.FirstRun to true)
        SectionId.entries.forEach { add(SettingsRoute.Section(it) to false) }
        add(SettingsRoute.AddSource() to true)
        add(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true) to true)
        PhonePurpose.entries.forEach { add(SettingsRoute.PhoneSetup(purpose = it) to true) }
        add(SettingsRoute.PhoneSetup(firstRun = true, purpose = PhonePurpose.NETWORK_FOLDER, browse = 3) to true)
        add(SettingsRoute.ImportProgress(1, false) to true)
        add(SettingsRoute.GroupsReview(1, false) to true)
        add(SettingsRoute.MatchingReport(1, false) to true)
        add(SettingsRoute.Playlist(1) to false)
        add(SettingsRoute.Playlist(1, RowId("playlist.user_agent")) to false)
        add(SettingsRoute.PlaylistSource(1) to false)
        add(SettingsRoute.NetworkTest(1) to false)
        add(SettingsRoute.Groups(1) to false)
        add(SettingsRoute.HiddenChannels(1) to false)
        add(SettingsRoute.HideRules(1) to false)
        add(SettingsRoute.FavouriteLists to false)
        add(SettingsRoute.FavouriteList(2) to false)
        add(SettingsRoute.EpgSources to false)
        add(SettingsRoute.SyncStatus to false)
        add(SettingsRoute.FixGuide() to false)
        add(SettingsRoute.FixGuide(ch) to false)
        add(SettingsRoute.Channel(ch) to false)
        add(SettingsRoute.ChannelEditor(1) to true)
        add(SettingsRoute.ChannelEditor(1, "g") to true)
        add(SettingsRoute.ButtonMapping to false)
        add(SettingsRoute.ActionBindings(BoxAction.OPEN_GUIDE) to false)
        add(SettingsRoute.Diagnostics to false)
        add(SettingsRoute.Licences to true)
        add(SettingsRoute.Backup to false)
        add(SettingsRoute.RestorePreview(7) to true)
        add(SettingsRoute.PauseRewind to false)
        StoragePurpose.entries.forEach { add(SettingsRoute.SaveLocation(it) to false) }
        add(SettingsRoute.LocationDetail("internal") to false)
        add(SettingsRoute.FindComputers to true)
        add(SettingsRoute.ComputerShares(1) to true)
        add(SettingsRoute.ComputerSignIn(1) to true)
        add(SettingsRoute.LocationSignIn("smb-1") to true)
        add(SettingsRoute.NetworkFolder(1, "TV") to true)
        add(SettingsRoute.LocationTest() to true)
        add(SettingsRoute.LocationTest(browse = 1) to true)
        add(SettingsRoute.LocationTest(locationId = "smb-1") to true)
    }

    @Test
    fun everyRouteIsClassifiedAsTheDesignTableSays() {
        for ((route, expected) in table) assertEquals(expected, SettingsPanelRules.expands(route), "expands($route)")
    }

    @Test
    fun theTableCoversEveryRouteClass() {
        val routeClasses = SettingsRoute::class.java.declaredClasses.filter { SettingsRoute::class.java.isAssignableFrom(it) }.map { it.simpleName }.toSet()
        val covered = table.map { it.first::class.java.simpleName }.toSet()
        assertEquals(routeClasses, covered, "a SettingsRoute without a row in this test's table")
    }

    private fun ui(route: SettingsRoute, pane: Pane) = SettingsUiState(route = route, pane = pane)

    @Test
    fun theSettingsKeyExpandsAnywhere() {
        for (ui in listOf(ui(SettingsRoute.Root, Pane.SECTIONS), ui(SettingsRoute.Section(SectionId.PLAYBACK), Pane.PAGE),
            ui(SettingsRoute.Playlist(1), Pane.PAGE), ui(SettingsRoute.NetworkTest(1), Pane.PAGE))) {
            assertTrue(SettingsPanelRules.expandKey(ui, BoxAction.OPEN_SETTINGS), "OPEN_SETTINGS at ${ui.route}")
        }
    }

    @Test
    fun menuExpandsOnlyOnTheRootList() {
        assertTrue(SettingsPanelRules.expandKey(ui(SettingsRoute.Root, Pane.SECTIONS), BoxAction.CONTEXT_MENU))
        // S11: on a row (any page, Root's page pane included) neither Menu nor a long OK (the same action) expands.
        assertFalse(SettingsPanelRules.expandKey(ui(SettingsRoute.Root, Pane.PAGE), BoxAction.CONTEXT_MENU))
        assertFalse(SettingsPanelRules.expandKey(ui(SettingsRoute.Section(SectionId.PLAYBACK), Pane.PAGE), BoxAction.CONTEXT_MENU))
        assertFalse(SettingsPanelRules.expandKey(ui(SettingsRoute.Groups(1), Pane.PAGE), BoxAction.CONTEXT_MENU))
    }

    @Test
    fun noOtherKeyExpands() {
        val root = ui(SettingsRoute.Root, Pane.SECTIONS)
        val page = ui(SettingsRoute.Section(SectionId.PLAYBACK), Pane.PAGE)
        for (a in BoxAction.entries - BoxAction.OPEN_SETTINGS - BoxAction.CONTEXT_MENU) {
            assertFalse(SettingsPanelRules.expandKey(root, a), "$a at the root list")
            assertFalse(SettingsPanelRules.expandKey(page, a), "$a on a page")
        }
    }

    private fun row(id: String, effect: EffectTiming, kind: RowKind = RowKind.Toggle(true)) = SettingsRow(RowId(id), "label", kind = kind, effect = effect)

    @Test
    fun timingFollowsTheRowEffect() {
        val expected = mapOf(
            EffectTiming.LIVE to PanelTiming.AT_ONCE,
            EffectTiming.REPREPARE to PanelTiming.REPREPARE,
            EffectTiming.NEXT_TUNE to PanelTiming.CHANGE_CHANNEL,
            EffectTiming.NEXT_LAUNCH to PanelTiming.NEXT_START,
            EffectTiming.RESTART to PanelTiming.RESTART_APP,
            EffectTiming.NEXT_REFRESH to PanelTiming.NEXT_UPDATE,
            EffectTiming.NEXT_EPG_IMPORT to PanelTiming.NEXT_GUIDE_UPDATE,
            EffectTiming.NONE to PanelTiming.NONE,
        )
        assertEquals(EffectTiming.entries.toSet(), expected.keys, "every EffectTiming mapped")
        val kinds = listOf(
            RowKind.Toggle(false), RowKind.Choice(emptyList(), 0), RowKind.MultiChoice(emptyList(), emptySet()),
            RowKind.Text("v", app.thebox.core.settings.TextHint.URL), RowKind.Number(1, 0, 9), RowKind.Action(),
            RowKind.Link(SettingsRoute.Root), RowKind.Info(emptyList()), RowKind.Item(),
        )
        for ((effect, timing) in expected) for (k in kinds) assertEquals(timing, SettingsPanelRules.timing(row("x.y", effect, k)), "$effect / $k")
    }

    @Test
    fun timingTextHasALineExceptForNoneAndAtOnce() {
        assertNull(SettingsPanelRules.timingText(PanelTiming.NONE))
        assertNull(SettingsPanelRules.timingText(PanelTiming.AT_ONCE))
        assertEquals(SettingsText.PANEL_TIMING_REPREPARE, SettingsPanelRules.timingText(PanelTiming.REPREPARE))
        assertEquals(SettingsText.PANEL_TIMING_CHANGE_CHANNEL, SettingsPanelRules.timingText(PanelTiming.CHANGE_CHANNEL))
        assertEquals(SettingsText.PANEL_TIMING_NEXT_START, SettingsPanelRules.timingText(PanelTiming.NEXT_START))
        assertEquals(SettingsText.PANEL_TIMING_RESTART_APP, SettingsPanelRules.timingText(PanelTiming.RESTART_APP))
        assertEquals(SettingsText.PANEL_TIMING_NEXT_UPDATE, SettingsPanelRules.timingText(PanelTiming.NEXT_UPDATE))
        assertEquals(SettingsText.PANEL_TIMING_NEXT_GUIDE_UPDATE, SettingsPanelRules.timingText(PanelTiming.NEXT_GUIDE_UPDATE))
    }

    @Test
    fun quickBarHintForAspectAudioLanguageAndSubtitlesOnly() {
        for (id in listOf(PlaybackRows.ASPECT, PlaybackRows.AUDIO_LANGUAGE, PlaybackRows.SUBTITLES)) {
            assertTrue(SettingsPanelRules.quickBarHint(row(id.key, EffectTiming.NEXT_TUNE)), id.key)
            assertTrue(SettingsPanelRules.quickBarHint(row(id.key + ".sub", EffectTiming.NEXT_TUNE)), "${id.key} sub-row")
        }
        for (id in listOf("playback.decoder", "playback.buffer", "playback.external_player", "playback.timeshift",
            "playlist.user_agent", "playback.aspectx", "channel.aspect")) {
            assertFalse(SettingsPanelRules.quickBarHint(row(id, EffectTiming.NEXT_TUNE)), id)
        }
    }
}
