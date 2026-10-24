package app.thebox.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.Tone
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings kit fixes of review U (docs/review/2026-10-24-storage-locations-review-ui.md): next steps drawn whole
 * (U-S6), the subtitle above the rows on every page that has one (U-S7), the full breadcrumb (U-S10) and what TalkBack
 * reads (U-N3). 1 dp = 1 design px here (mdpi, a 1,920-wide screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
class StorageRowsUiTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun layoutOf(text: String): TextLayoutResult {
        val node = rule.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /**
     * U-S6: the TOO_SLOW verdict's next step and its details are whole lines on a row of the page pane (1,276 px inside):
     * none is cut by the row's height. (Robolectric's font is 1 px per character, so the width is held by
     * `StorageTextTest.uS6_everyStorageLineFitsARow`'s 80-character rule, not here.)
     */
    @Test fun uS6_theTooSlowNextStepIsDrawnWhole() {
        val host = "Synology-DS920-Living-Room"
        val steps = res.getString(R.string.storage_verdict_step_too_slow, host).split('\n')
        val details = res.getString(R.string.storage_verdict_details, "0.9", 35, res.getString(R.string.storage_wifi))
        val row = SettingsRow(RowId("storage.test.verdict"), res.getString(R.string.storage_status_too_slow), kind = RowKind.Info(steps + details, Tone.WARN))
        rule.setContent { BoxTheme { Box(Modifier.width((SettingsUi.PAGE_W - 2 * SettingsUi.PAGE_PAD_H).dp)) { SettingsRowView(row, focused = false, choice = null, moving = false) } } }
        for (line in steps + details) assertFalse("cut: $line", layoutOf(line).hasVisualOverflow)
    }

    /** U-S6: the long Find and sign-in next steps are whole too, next to nothing in the value column. */
    @Test fun uS6_theFindAndSignInLinesAreDrawnWhole() {
        val lines = res.getString(R.string.storage_find_none_step).split('\n') + res.getString(R.string.storage_sign_in_info).split('\n')
        val row = SettingsRow(RowId("storage.find.status"), res.getString(R.string.storage_find_none_title), kind = RowKind.Info(lines, Tone.WARN))
        rule.setContent { BoxTheme { Box(Modifier.width((SettingsUi.PAGE_W - 2 * SettingsUi.PAGE_PAD_H).dp)) { SettingsRowView(row, focused = false, choice = null, moving = false) } } }
        for (line in lines) assertFalse("cut: $line", layoutOf(line).hasVisualOverflow)
    }

    private fun state(page: PageModel, stack: List<SettingsRoute> = listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.PLAYBACK))) = SettingsUiState(
        route = page.route, stack = stack, pane = Pane.PAGE, sections = SectionId.entries.filter { it != SectionId.NETWORK },
        sectionCursor = 2, page = page, cursor = 0,
    )

    private val rows = listOf(
        SettingsRow(RowId("storage.loc.internal"), "This device", description = "Built-in storage · 8.5 GB free", kind = RowKind.Item(primary = "choose")),
        SettingsRow(RowId("storage.loc.add"), "Add a network folder", kind = RowKind.Link(SettingsRoute.FindComputers)),
    )

    /** U-S7: the subtitle ends above the first row, on Save to as on every page with a subtitle. */
    @Test fun uS7_theSubtitleIsNotOverlappedByTheFirstRow() {
        val page = PageModel(SettingsRoute.SaveLocation(), "Save to", subtitle = "Where rewind is saved while you watch.", rows = rows, version = 1)
        rule.setContent { BoxTheme { SettingsContent(state(page), clock24 = true) } }
        rule.waitForIdle()
        val sub = rule.onNodeWithTag(SETTINGS_SUBTITLE_TAG, useUnmergedTree = true).getBoundsInRoot()
        val first = rule.onNodeWithTag(SETTINGS_ROW_TAG + "storage.loc.internal").getBoundsInRoot()
        assertTrue("subtitle bottom ${sub.bottom} vs first row top ${first.top}", sub.bottom <= first.top)
    }

    /** U-S7: a page without a subtitle keeps its rows where M5 put them (96 px below the pane's top). */
    @Test fun uS7b_aPageWithoutASubtitleIsUnchanged() {
        val page = PageModel(SettingsRoute.Section(SectionId.PLAYBACK), "Playback", rows = rows, version = 1)
        rule.setContent { BoxTheme { SettingsContent(state(page, listOf(SettingsRoute.Root)), clock24 = true) } }
        rule.waitForIdle()
        val pane = rule.onNodeWithTag(SETTINGS_PAGE_TAG).getBoundsInRoot()
        val first = rule.onNodeWithTag(SETTINGS_ROW_TAG + "storage.loc.internal").getBoundsInRoot()
        assertEquals(SettingsUi.ROWS_TOP.toFloat(), (first.top - pane.top).value, 0.5f)
    }

    /** U-S7: with 36 px less room under a subtitle, the machine's 8-row window still shows the focused row whole (Find). */
    @Test fun uS7c_theFocusedRowStaysInsideThePaneUnderASubtitle() {
        val many = (0 until 10).map { SettingsRow(RowId("storage.find.pc.10.0.0.$it"), "Computer $it", value = "10.0.0.$it", kind = RowKind.Item(primary = "pick")) }
        val page = PageModel(SettingsRoute.FindComputers, "Find computers", subtitle = "Computers on your network that share folders.", rows = many, version = 1)
        rule.setContent { BoxTheme { SettingsContent(state(page).copy(cursor = 7, top = 1), clock24 = true) } }
        rule.waitForIdle()
        val pane = rule.onNodeWithTag(SETTINGS_PAGE_TAG).getBoundsInRoot()
        val row = rule.onNodeWithTag(SETTINGS_ROW_TAG + "storage.find.pc.10.0.0.7").assertIsDisplayed().getBoundsInRoot()
        assertTrue("row bottom ${row.bottom} inside the pane ${pane.bottom}", row.bottom <= pane.bottom)
    }

    /** U-S10: the breadcrumb names every page on the way (Pause and rewind was skipped before). */
    @Test fun uS10_theBreadcrumbNamesEveryPageOnTheWay() {
        val root = listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.PLAYBACK))
        val steps = listOf(
            PageModel(SettingsRoute.PauseRewind, "Pause and rewind", rows = rows, version = 1) to root,
            PageModel(SettingsRoute.SaveLocation(), "Save to", rows = rows, version = 1) to root + SettingsRoute.PauseRewind,
            PageModel(SettingsRoute.FindComputers, "Find computers", rows = rows, version = 1) to root + SettingsRoute.PauseRewind + SettingsRoute.SaveLocation(),
            PageModel(SettingsRoute.ComputerSignIn(1), "Sign in to NAS", rows = rows, version = 1) to
                root + SettingsRoute.PauseRewind + SettingsRoute.SaveLocation() + SettingsRoute.FindComputers,
        )
        var ui by mutableStateOf(state(steps[0].first, steps[0].second))
        rule.setContent { BoxTheme { SettingsContent(ui, clock24 = true) } }
        rule.onNodeWithTag(SETTINGS_BREADCRUMB_TAG, useUnmergedTree = true).assertTextEquals("Settings › Playback › Pause and rewind")
        for ((p, stack) in steps.drop(1)) { ui = state(p, stack); rule.waitForIdle() }
        rule.onNodeWithTag(SETTINGS_BREADCRUMB_TAG, useUnmergedTree = true)
            .assertTextEquals("Settings › Playback › Pause and rewind › Save to › Find computers › Sign in to NAS")
    }

    /** U-S10: the M5 three-part form is unchanged for a page one level deep, and the phone page is never a part. */
    @Test fun uS10b_threePartsOneLevelDeepAndNoPhonePage() {
        val two = listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.PLAYBACK))
        assertEquals(listOf("Settings", "Playback", "Pause and rewind"), breadcrumbParts("Settings", "Playback", two, emptyMap(), "Pause and rewind"))
        assertEquals(listOf("Settings", "Playback"), breadcrumbParts("Settings", "Playback", listOf(SettingsRoute.Root), emptyMap(), "Playback"))
        val phone = SettingsRoute.PhoneSetup(purpose = app.thebox.core.settings.PhonePurpose.NETWORK_FOLDER, browse = 1)
        assertEquals(
            listOf("Settings", "Playback", "Find computers", "NAS"),
            breadcrumbParts("Settings", "Playback", two + SettingsRoute.FindComputers + phone, mapOf(SettingsRoute.FindComputers to "Find computers", phone to "Add from your phone"), "NAS"),
        )
    }

    /** U-N3: TalkBack hears the next steps and the value, not the label only. */
    @Test fun uN3_theRowDescriptionCarriesTheLinesAndTheValue() {
        val row = SettingsRow(RowId("storage.test.verdict"), "Too slow for rewind", kind = RowKind.Info(listOf("Rewind would often stop.", "0.9 MB/s · 35 ms · Wi-Fi"), Tone.WARN))
        assertEquals("Too slow for rewind, Rewind would often stop., 0.9 MB/s · 35 ms · Wi-Fi", rowDescription(row))
        val link = SettingsRow(RowId("playback.pause_rewind"), "Pause and rewind", value = app.thebox.core.settings.state.StorageMarks.warn("1 hour · This device (NAS offline)"), description = "d", kind = RowKind.Link(SettingsRoute.PauseRewind))
        assertEquals("Pause and rewind, d, 1 hour · This device (NAS offline)", rowDescription(link))
        assertEquals("Testing, 9 of 16 MB", res.getString(R.string.storage_ui_testing_progress, "9 of 16 MB"))
    }
}
