package app.thebox.ui.settings

import android.content.res.Resources
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.core.playback.AspectMode
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ChoiceState
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.MenuState
import app.thebox.core.settings.MoveState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.Tone
import app.thebox.core.settings.UiTextScale
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.ExternalPlayerApp
import app.thebox.core.settings.state.SettingsPanelRules
import app.thebox.core.settings.state.WpaPageEnv
import app.thebox.core.settings.state.WpaPages
import app.thebox.core.settings.tree.GuideRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.ui.R
import app.thebox.ui.full.QuickBar
import app.thebox.ui.settings.vm.ResourceSettingsTexts
import app.thebox.ui.theme.BoxTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** States and helpers shared by the panel's layout tests (docs/design/m6-player-settings-panel.md §5). */
internal object PanelFixtures {
    val sections = SectionId.entries.toList()

    fun root(cursor: Int, notice: String? = null) = SettingsUiState(
        route = SettingsRoute.Root, stack = listOf(SettingsRoute.Root), pane = Pane.SECTIONS, sections = sections,
        sectionCursor = cursor, notice = notice, presentation = SettingsPresentation.PANEL,
    )

    fun onPage(page: PageModel, cursor: Int, stack: List<SettingsRoute> = listOf(SettingsRoute.Root, page.route), top: Int = maxOf(0, cursor - 7)) = SettingsUiState(
        route = page.route, stack = stack, pane = Pane.PAGE, sections = sections, sectionCursor = 0, page = page,
        cursor = cursor, top = top, presentation = SettingsPresentation.PANEL,
    )

    private class Env : WpaPageEnv {
        override val sdkInt = 30
        override val deviceLowEnd = false
        override val hasTmdbKey = true
        override fun system24h() = true
        override fun externalPlayers() = listOf(ExternalPlayerApp("org.videolan.vlc", "VLC"))
        override fun runningSoftwareAudioFallback(): Boolean? = false
        override fun restartNow() = Unit
        override fun keyOverrideCount() = 0
    }

    /** A real WpaPages section page with the app's strings, built for the panel (A4: no timing sentences). */
    fun wpa(res: Resources, id: SectionId, panel: Boolean = true): PageModel =
        WpaPages(FakeSettingsStore(), ResourceSettingsTexts(res), Env()).build(SettingsRoute.Section(id), advanced = true, panel = panel)

    /** Settings → Playlists as in the owner's mock-up. */
    val playlists = PageModel(
        SettingsRoute.Section(SectionId.PLAYLISTS), "Playlists",
        rows = listOf(
            SettingsRow(
                RowId("playlists.item.1"), "Jarvis (Active)", value = "Xtream · 13,110 channels · updated 00:25",
                kind = RowKind.Item(route = SettingsRoute.Playlist(1), menu = listOf(ChoiceOption("refresh", "Refresh now"), ChoiceOption("edit", "Edit"), ChoiceOption("test", "Test connection"), ChoiceOption("remove", "Remove"))),
            ),
            SettingsRow(RowId("playlists.add"), "Add playlist", kind = RowKind.Link(SettingsRoute.AddSource())),
            SettingsRow(RowId("playlists.phone"), "Set up from phone", kind = RowKind.Link(SettingsRoute.PhoneSetup())),
            SettingsRow(RowId("playlists.refresh_all"), "Refresh all now", kind = RowKind.Action()),
        ),
        version = 1,
    )

    /** A playlist page (account card N8, connection rows with the NEXT_TUNE sentence as their whole description, F14). */
    fun playlist(nextTune: String) = PageModel(
        SettingsRoute.Playlist(1), "Jarvis",
        rows = listOf(
            SettingsRow(
                RowId("playlist.account"), "Account",
                kind = RowKind.Info(listOf("Active · expires in 212 days", "1 of 2 connections in use", "Panel time zone Europe/London, in sync"), Tone.GOOD),
            ),
            SettingsRow(RowId("playlist.user_agent"), "User agent", description = nextTune, effect = EffectTiming.NEXT_TUNE, kind = RowKind.Text("TheBox/0.1 (Linux; Android 11) ExoPlayerLib/1.11.1", app.thebox.core.settings.TextHint.NAME)),
            SettingsRow(
                RowId("playlist.stream_format"), "Stream format", description = nextTune, effect = EffectTiming.NEXT_TUNE,
                kind = RowKind.Choice(listOf(ChoiceOption("auto", "Automatic (MPEG-TS, then HLS when the panel offers it)"), ChoiceOption("ts", "MPEG-TS"), ChoiceOption("hls", "HLS (m3u8)")), 0),
            ),
            SettingsRow(RowId("playlist.password"), "Password", kind = RowKind.Text("hunter2", app.thebox.core.settings.TextHint.PASSWORD, masked = true)),
            SettingsRow(RowId("playlist.url"), "Server", kind = RowKind.Text("http://line.example-provider.tv:8080", app.thebox.core.settings.TextHint.URL)),
            SettingsRow(RowId("playlist.max_connections"), "Max connections", effect = EffectTiming.NEXT_TUNE, kind = RowKind.Number(2, 1, 10)),
            SettingsRow(RowId("playlist.remove"), "Remove playlist", kind = RowKind.Action(destructive = true)),
        ),
        version = 1,
    )

    /** Channels → Groups with movable items (move mode, S9). */
    val groups = PageModel(
        SettingsRoute.Groups(1), "Groups",
        subtitle = "Hold OK on a group to move or hide it. Hidden groups stay out of the guide.",
        rows = listOf("UK | Entertainment", "UK | News", "UK | Sports", "US | NFL Sunday Ticket", "Movies 4K", "Kids", "Documentaries", "Music", "Radio", "Adult").mapIndexed { i, n ->
            SettingsRow(
                RowId("groups.g.$i"), n, value = "${(i + 3) * 37} channels",
                kind = RowKind.Item(route = SettingsRoute.Groups(1), movable = true, badges = if (i == 9) setOf(Badge.HIDDEN, Badge.ADULT) else emptySet()),
            )
        },
        version = 1,
    )

    val quickBar = QuickBarState(
        items = listOf(QuickItem.FAVOURITE_ADD, QuickItem.START_OVER, QuickItem.PREVIOUS_CHANNEL, QuickItem.STREAM_INFO, QuickItem.VIDEO_DECODER, QuickItem.SCREEN_OFF, QuickItem.SETTINGS),
        focused = 6, statsOn = false, audio = null, subtitle = null, aspect = AspectMode.FIT, decoder = null, previous = null, panelOpen = true,
    )
}

/**
 * (Settings panel A5, §5, §10 `SettingsPanelBoundsTest`) The panel laid out with real fonts over a stand-in video: the box,
 * header, hairline, every shown row and the band inside the picker box; no row wider than 464; every shown row above the
 * band at every focus index; the option list, the context menu and move mode inside; at Default and Largest. Labels are
 * whole (no overflow, no ellipsis), no text is under 22 design px, and the band's first line is the timing line. PNGs go
 * to `ui/build/settings-panel/` (review by eye; not goldens).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPanelBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() { UiTextScale.running = TextSize.DEFAULT }

    private val out = File("build/settings-panel").apply { mkdirs() }
    private val shown = mutableStateOf<SettingsUiState?>(null)
    /** The text size is fixed per process in the app; the test re-creates the tree when it changes it. */
    private val textSize = mutableStateOf(TextSize.DEFAULT)
    private val machine = FakeSettingsUiMachine()
    private val res: Resources get() = rule.activity.resources
    private fun str(id: Int) = res.getString(id)

    /** A stand-in for the playing video: a bright, busy gradient so the panel's .86 fill and the text contrast show. */
    @Composable
    private fun Video() {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF2E6FA8), Color(0xFFE0A050), Color(0xFF3B8F4A), Color(0xFFF2F2F2)), Offset.Zero, Offset(1920f, 1080f)),
            ),
        )
    }

    private fun show(ui: SettingsUiState) {
        machine.state.value = ui
        textSize.value = UiTextScale.running
        if (shown.value == null) {
            shown.value = ui
            rule.setContent {
                BoxTheme {
                    androidx.compose.runtime.key(textSize.value) {
                        Box(Modifier.fillMaxSize()) {
                            Video()
                            QuickBar(PanelFixtures.quickBar, Modifier.offset(64.dp, 952.dp))
                            SettingsPanel(machine, lowEnd = true)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return null
        val results = ArrayList<TextLayoutResult>()
        action.action!!.invoke(results)
        return results.firstOrNull()
    }

    private fun tagged(prefix: String): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("tag $prefix*") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()

    /** The §5 invariants of whatever the panel shows now. */
    private fun assertPanel(what: String, expectFocus: Boolean = true) {
        val box = bounds(SETTINGS_PANEL_TAG)
        assertEquals("$what: box", Rect(1376f, 48f, 1872f, 928f), box)
        val band = bounds(SETTINGS_PANEL_BAND_TAG)
        val bandH = SettingsPanelUi.bandH()
        assertEquals("$what: band height", bandH, band.height, 1f)
        assertEquals("$what: band ends at the inner bottom", 912f, band.bottom, 0.5f)
        val header = bounds(SETTINGS_PANEL_HEADER_TAG)
        assertTrue("$what: header at y 68 ($header)", header.top in 67.5f..68.5f && header.height >= 40f - 0.5f)
        val hair = bounds(SETTINGS_PANEL_HAIRLINE_TAG)
        assertTrue("$what: hairline 12 below the header", hair.top >= header.bottom + 12f - 0.5f)
        val rowsArea = bounds(SETTINGS_PANEL_ROWS_TAG)
        assertTrue("$what: rows area above the band", rowsArea.bottom <= band.top)
        val rows = tagged(SETTINGS_ROW_TAG) + tagged(SETTINGS_PANEL_ROOT_ROW_TAG) + tagged(SETTINGS_OPTION_TAG) + tagged(SETTINGS_PLACEHOLDER_ROW_TAG)
        assertTrue("$what: rows shown", rows.isNotEmpty())
        for (r in rows) {
            // Unclipped: the node's own position and size (boundsInRoot is clipped by the rows area).
            val b = Rect(r.positionInRoot, androidx.compose.ui.geometry.Size(r.size.width.toFloat(), r.size.height.toFloat()))
            val tag = r.config.getOrNull(SemanticsProperties.TestTag)
            assertTrue("$what: $tag ($b) whole inside the rows area ($rowsArea)", b.top >= rowsArea.top - 0.5f && b.bottom <= rowsArea.bottom + 0.5f)
            assertTrue("$what: $tag ($b) inside x 1392…1856", b.left >= 1392f - 0.5f && b.right <= 1856f + 0.5f)
            assertTrue("$what: $tag no wider than 464", b.width <= 464.5f)
        }
        if (expectFocus) assertTrue("$what: the focused row is shown", rows.any { it.config.getOrNull(SemanticsProperties.Selected) == true })
        // Labels are whole: no overflow, no ellipsis.
        for (l in tagged(SETTINGS_PANEL_LABEL_TAG)) {
            val lr = layoutOf(l) ?: continue
            assertFalse("$what: label '${text(l)}' overflows", lr.hasVisualOverflow)
            assertFalse("$what: label '${text(l)}' is ellipsised", lr.isLineEllipsized(lr.lineCount - 1))
        }
        // Nothing under 22 design px, and every text inside the box (or a centred overlay).
        val minSp = 22f * UiTextScale.factor - 0.01f
        val all = rule.onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        for (n in all) {
            val lr = layoutOf(n) ?: continue
            val b = n.boundsInRoot
            if (b.left < 1376f || b.top >= 928f) continue // the quick bar
            assertTrue("$what: '${text(n)}' at ${lr.layoutInput.style.fontSize}", lr.layoutInput.style.fontSize.value >= minSp)
            assertTrue("$what: '${text(n)}' ($b) inside the box", b.right <= box.right + 0.5f && b.bottom <= box.bottom + 0.5f)
        }
        // The band's texts stay inside the band (not clipped by its fixed height).
        for (t in tagged(SETTINGS_PANEL_BAND_TIMING_TAG) + tagged(SETTINGS_PANEL_BAND_TEXT_TAG) + tagged(SETTINGS_PANEL_NOTICE_TAG)) {
            val top = t.positionInRoot.y
            val h = t.size.height
            assertTrue("$what: band text '${text(t)}' inside the band ($top + $h in $band)", top >= band.top && top + h <= band.bottom - 11.5f)
        }
    }

    /** The band's first line is the timing line for [row] (or there is none for a LIVE row). */
    private fun assertTimingFirst(what: String, row: SettingsRow?) {
        val key = row?.let { SettingsPanelRules.timingText(SettingsPanelRules.timing(it)) }
        val timing = tagged(SETTINGS_PANEL_BAND_TIMING_TAG)
        if (key == null) { assertTrue("$what: no timing line", timing.isEmpty()); return }
        assertEquals("$what: one timing line", 1, timing.size)
        assertEquals("$what: timing text", str(ResourceSettingsTexts.idOf(key)), text(timing.single()))
        val tTop = timing.single().boundsInRoot.top
        for (o in tagged(SETTINGS_PANEL_BAND_TEXT_TAG)) assertTrue("$what: the timing line comes first", o.boundsInRoot.top >= tTop + 1f)
        val tl = layoutOf(timing.single())!!
        assertFalse("$what: the timing line is whole (S8)", tl.isLineEllipsized(tl.lineCount - 1))
        if (UiTextScale.running == TextSize.DEFAULT) assertEquals("$what: the timing line is one line at Default", 1, tl.lineCount)
        val lines = tagged(SETTINGS_PANEL_BAND_TEXT_TAG).sumOf { layoutOf(it)!!.lineCount } + tl.lineCount
        assertTrue("$what: at most 4 band lines ($lines)", lines <= SettingsPanelUi.BAND_LINES)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val sizes = listOf(TextSize.DEFAULT, TextSize.LARGEST)

    @Test
    fun rootListAtEveryFocus() {
        for (size in sizes) {
            UiTextScale.running = size
            for (f in PanelFixtures.sections.indices) {
                show(PanelFixtures.root(f))
                assertPanel("$size root $f")
                assertTrue("$size root $f: no timing line at Root", tagged(SETTINGS_PANEL_BAND_TIMING_TAG).isEmpty())
            }
            show(PanelFixtures.root(0))
            assertTrue("root header names Settings", rule.onAllNodes(SemanticsMatcher("t") { text(it) == str(R.string.settings_panel_full_screen_hint) }, true).fetchSemanticsNodes().isNotEmpty())
            capture("root-${size.name.lowercase()}")
            show(PanelFixtures.root(10)); capture("root-${size.name.lowercase()}-bottom")
        }
    }

    @Test
    fun everyWpaSectionPageAtEveryFocus() {
        for (size in sizes) {
            UiTextScale.running = size
            for (id in WpaPages.HANDLED) {
                val page = PanelFixtures.wpa(res, id)
                for (c in page.rows.indices) {
                    show(PanelFixtures.onPage(page, c))
                    assertPanel("$size $id $c")
                    assertTimingFirst("$size $id $c (${page.rows[c].id.key})", page.rows[c])
                }
            }
        }
    }

    @Test
    fun playbackWithAFocusedReprepareRow() {
        for (size in sizes) {
            UiTextScale.running = size
            val page = PanelFixtures.wpa(res, SectionId.PLAYBACK)
            val i = page.rows.indexOfFirst { it.id == PlaybackRows.DECODER }
            show(PanelFixtures.onPage(page, i))
            assertPanel("$size decoder")
            assertTimingFirst("$size decoder", page.rows[i])
            assertEquals(str(R.string.settings_panel_timing_reprepare), text(tagged(SETTINGS_PANEL_BAND_TIMING_TAG).single()))
            capture("playback-decoder-${size.name.lowercase()}")
            val b = page.rows.indexOfFirst { it.id == PlaybackRows.BUFFER }
            show(PanelFixtures.onPage(page, b)); capture("playback-buffer-${size.name.lowercase()}")
            // The aspect row adds the quick-bar hint after its timing line.
            val a = page.rows.indexOfFirst { it.id == PlaybackRows.ASPECT }
            show(PanelFixtures.onPage(page, a))
            assertPanel("$size aspect")
            assertTimingFirst("$size aspect", page.rows[a])
            assertTrue("$size aspect: quick-bar hint", tagged(SETTINGS_PANEL_BAND_TEXT_TAG).any { text(it).startsWith(str(R.string.settings_panel_quick_bar_hint)) })
        }
    }

    @Test
    fun playlistsLikeTheMockUp() {
        for (size in sizes) {
            UiTextScale.running = size
            for (c in PanelFixtures.playlists.rows.indices) {
                show(PanelFixtures.onPage(PanelFixtures.playlists, c))
                assertPanel("$size playlists $c")
            }
            show(PanelFixtures.onPage(PanelFixtures.playlists, 0))
            val header = rule.onAllNodes(SemanticsMatcher("header text") { true }, true).fetchSemanticsNodes()
                .filter { it.boundsInRoot.top < 120f && it.boundsInRoot.left >= 1376f }.map { text(it) }.filter { it.isNotEmpty() }
            assertEquals("$size breadcrumb", listOf("←", str(R.string.settings_ui_title), "Playlists"), header)
            capture("playlists-${size.name.lowercase()}")
            // The playlist page under it: the parent is the section.
            val p = PanelFixtures.playlist(str(R.string.setup_effect_next_tune))
            for (c in p.rows.indices) {
                show(PanelFixtures.onPage(p, c, stack = listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.PLAYLISTS), p.route)))
                assertPanel("$size playlist $c")
                assertTimingFirst("$size playlist $c", p.rows[c])
            }
            show(PanelFixtures.onPage(p, 2, stack = listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.PLAYLISTS), p.route)))
            assertTrue("$size: the NEXT_TUNE sentence is not repeated under the timing line", tagged(SETTINGS_PANEL_BAND_TEXT_TAG).isEmpty())
            capture("playlist-${size.name.lowercase()}")
        }
    }

    @Test
    fun optionListsAndTheContextMenu() {
        for (size in sizes) {
            UiTextScale.running = size
            val tag = size.name.lowercase()
            val page = PanelFixtures.wpa(res, SectionId.PLAYBACK)
            val b = page.rows.indexOfFirst { it.id == PlaybackRows.BUFFER }
            val n = (page.rows[b].kind as RowKind.Choice).options.size
            for (o in 0 until n) {
                show(PanelFixtures.onPage(page, b).copy(choice = ChoiceState(PlaybackRows.BUFFER, o)))
                assertPanel("$size buffer option $o")
                assertTrue("option $o shown focused", rule.onNodeWithTag("${SETTINGS_OPTION_TAG}${PlaybackRows.BUFFER.key}:$o", true).fetchSemanticsNode().config[SemanticsProperties.Selected])
            }
            show(PanelFixtures.onPage(page, b).copy(choice = ChoiceState(PlaybackRows.BUFFER, 1)))
            capture("option-list-$tag")
            // A long list (guide shift, 49 options): the window follows the focused option.
            val guide = PanelFixtures.wpa(res, SectionId.GUIDE)
            val s = guide.rows.indexOfFirst { it.id == GuideRows.SHIFT }
            for (o in listOf(0, 10, 24, 40, 48)) {
                show(PanelFixtures.onPage(guide, s).copy(choice = ChoiceState(GuideRows.SHIFT, o)))
                assertPanel("$size shift option $o")
                assertTrue("shift option $o shown", tagged("${SETTINGS_OPTION_TAG}${GuideRows.SHIFT.key}:$o").isNotEmpty())
            }
            capture("option-list-long-$tag")
            // The context menu: right-aligned inside the rows area, at every row.
            val menu = MenuState(RowId("playlists.item.1"), listOf(ChoiceOption("refresh", "Refresh now"), ChoiceOption("edit", "Edit"), ChoiceOption("test", "Test connection"), ChoiceOption("remove", "Remove")), 1)
            for (c in PanelFixtures.groups.rows.indices) {
                show(PanelFixtures.onPage(PanelFixtures.groups, c).copy(menu = menu.copy(row = PanelFixtures.groups.rows[c].id)))
                assertPanel("$size menu $c", expectFocus = false)
                val m = bounds(SETTINGS_MENU_TAG)
                val area = bounds(SETTINGS_PANEL_ROWS_TAG)
                assertTrue("$size menu $c ($m) inside the rows area ($area)", m.top >= area.top - 0.5f && m.bottom <= area.bottom + 0.5f && m.right <= 1856.5f && m.left >= 1392f - 0.5f)
            }
            show(PanelFixtures.onPage(PanelFixtures.playlists, 0).copy(menu = menu)); capture("menu-$tag")
        }
    }

    @Test
    fun moveModeFitsThePanelWithItsHintInTheBand() {
        for (size in sizes) {
            UiTextScale.running = size
            val g = PanelFixtures.groups
            for ((from, to) in listOf(0 to 0, 2 to 5, 7 to 1, 9 to 9)) {
                show(PanelFixtures.onPage(g, to).copy(move = MoveState(g.rows[from].id, from, to)))
                assertPanel("$size move $from→$to", expectFocus = false)
                val row = rule.onNodeWithTag(MOVE_ROW_TAG, true).fetchSemanticsNode()
                val w = row.size.width
                assertEquals("$size move: the lifted row is 464 wide", 464, w)
                val rb = row.boundsInRoot
                assertTrue("$size move: inside x 1392…1856 ($rb)", rb.left >= 1391.5f && rb.right <= 1856.5f)
                val hint = str(R.string.backup_move_hint)
                assertTrue("$size move: the hint is in the band", tagged(SETTINGS_PANEL_BAND_TEXT_TAG).any { text(it) == hint })
                assertTrue("$size move: no inline hint", rule.onAllNodes(SemanticsMatcher("hint") { text(it) == hint }, true).fetchSemanticsNodes().size == 1)
            }
            show(PanelFixtures.onPage(g, 5).copy(move = MoveState(g.rows[2].id, 2, 5)))
            capture("move-${size.name.lowercase()}")
        }
    }

    @Test
    fun aNoticeTakesTheWholeBand() {
        val page = PanelFixtures.wpa(res, SectionId.PLAYBACK)
        val i = page.rows.indexOfFirst { it.id == PlaybackRows.DECODER }
        show(PanelFixtures.onPage(page, i).copy(notice = str(R.string.settings_panel_notice_repreparing)))
        assertPanel("notice")
        assertTrue(tagged(SETTINGS_PANEL_BAND_TIMING_TAG).isEmpty())
        val n = tagged(SETTINGS_PANEL_NOTICE_TAG).single()
        assertFalse("notice whole", layoutOf(n)!!.isLineEllipsized(layoutOf(n)!!.lineCount - 1))
        capture("notice-default")
        show(PanelFixtures.root(0, notice = str(R.string.settings_panel_notice_card_shown)))
        assertNotNull(tagged(SETTINGS_PANEL_NOTICE_TAG).singleOrNull())
    }

    @Test
    fun paneTitleIsSettings() {
        show(PanelFixtures.root(0))
        assertEquals(str(R.string.settings_ui_title), rule.onNodeWithTag(SETTINGS_PANEL_TAG).fetchSemanticsNode().config[SemanticsProperties.PaneTitle])
        assertEquals(1, rule.onAllNodesWithTag(SETTINGS_PANEL_TAG).fetchSemanticsNodes().size)
    }
}
