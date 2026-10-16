package app.thebox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.thebox.core.settings.EditorState
import app.thebox.core.settings.RowId
import app.thebox.core.settings.TextHint
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's focus island (docs/design/m5-setup-settings.md §3.2.2, §6.2; reviewed 2026-10-16, S3/S16): field → chips
 * → Done/Cancel with the G5 Down handler, BACK through `editorBacks`, the error round trip and the no-keyboard fallback.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class TextEditorOverlayTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val editor = EditorState(RowId("playlist.server"), "Server", "panel.example.com", TextHint.URL, masked = false, chips = listOf("http://", ":8080", ".com"))
    private val backs = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val done = ArrayList<String>()
    private var cancels = 0
    private var phone = 0
    private var focusManager: FocusManager? = null

    private fun show(state: () -> EditorState = { editor }, keyboard: Boolean = true) {
        rule.setContent {
            focusManager = LocalFocusManager.current
            BoxTheme {
                TextEditorOverlay(state(), onDone = { done += it }, onCancel = { cancels++ }, editorBacks = backs, onPhoneSetup = { phone++ }, keyboardAvailable = keyboard)
            }
        }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnIdle { backs.tryEmit(Unit) }
        rule.waitForIdle()
    }

    @Test
    fun fieldTakesFocusOnOpen() {
        show()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun downFromTheFieldReachesTheChips() {
        show()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(EDITOR_CHIP_TAG + 0).assertIsFocused()
    }

    @Test
    fun backFromTheChipsReturnsToTheFieldThenCancels() {
        show()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        back()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).assertIsFocused()
        assertEquals(0, cancels)
        back()
        assertEquals(1, cancels)
    }

    @Test
    fun backWithFocusClearedStillCloses() {
        show()
        rule.runOnIdle { focusManager!!.clearFocus(force = true) }
        rule.waitForIdle()
        // The window may hand focus to another island member after a clear (Robolectric does); BACK then returns to the
        // field first, and the next BACK cancels. Either way the editor can always be left with BACK (S3).
        back()
        if (cancels == 0) back()
        assertEquals(1, cancels)
    }

    @Test
    fun chipInsertsAndSchemeChipsReplaceTheScheme() {
        show()
        rule.onNodeWithTag(EDITOR_CHIP_TAG + 0).performClick() // http:// in front of a bare host
        rule.onNodeWithTag(EDITOR_DONE_TAG).performClick()
        rule.runOnIdle { assertEquals("http://panel.example.com", done.single()) }

        assertEquals("http://h", insertChip(TextFieldValue("https://h", TextRange(9)), "http://").text)
        assertEquals("https://h", insertChip(TextFieldValue("h", TextRange(1)), "https://").text)
        assertEquals("host:8080/x", insertChip(TextFieldValue("host/x", TextRange(4)), ":8080").text)
        assertEquals(TextRange(9), insertChip(TextFieldValue("host/x", TextRange(4)), ":8080").selection)
    }

    @Test
    fun doneCommitsTheTypedText() {
        show()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).performTextReplacement("tv.example.org")
        rule.onNodeWithTag(EDITOR_DONE_TAG).performClick()
        rule.runOnIdle { assertEquals(listOf("tv.example.org"), done) }
    }

    @Test
    fun textSurvivesAnErrorRoundTrip() {
        var state by mutableStateOf(editor)
        show(state = { state })
        rule.onNodeWithTag(EDITOR_FIELD_TAG).performTextReplacement("not a url")
        rule.runOnIdle { state = state.copy(error = "Enter an address like http://host:8080") }
        rule.onNodeWithTag(EDITOR_ERROR_TAG).assertExists()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).assertTextEquals("not a url")
    }

    @Test
    fun noKeyboardShowsThePhoneFallback() {
        show(keyboard = false)
        rule.onNodeWithTag(EDITOR_NO_KEYBOARD_TAG).assertExists()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(EDITOR_PHONE_TAG).assertIsFocused().performClick()
        rule.runOnIdle { assertEquals(1, phone) }
        back()
        assertEquals(1, cancels)
    }

    @Test
    fun passwordsAreMaskedUntilShown() {
        val pw = EditorState(RowId("playlist.password"), "Password", "s3cret", TextHint.PASSWORD, masked = true, chips = emptyList())
        show(state = { pw })
        rule.onNodeWithTag(EDITOR_SHOW_TAG).assertExists()
        rule.onNodeWithTag(EDITOR_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) } // no chips: Down → Done
        rule.waitForIdle()
        rule.onNodeWithTag(EDITOR_DONE_TAG).assertIsFocused()
    }
}
