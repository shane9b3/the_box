package app.thebox.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.EditorState
import app.thebox.core.settings.TextHint
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import kotlinx.coroutines.flow.Flow

const val EDITOR_TAG = "settings_editor"
const val EDITOR_FIELD_TAG = "settings_editor_field"
const val EDITOR_CHIP_TAG = "settings_editor_chip:"
const val EDITOR_DONE_TAG = "settings_editor_done"
const val EDITOR_CANCEL_TAG = "settings_editor_cancel"
const val EDITOR_SHOW_TAG = "settings_editor_show"
const val EDITOR_ERROR_TAG = "settings_editor_error"
const val EDITOR_NO_KEYBOARD_TAG = "settings_editor_no_keyboard"
const val EDITOR_PHONE_TAG = "settings_editor_phone"

/** Scheme chips replace an existing scheme instead of inserting (§3.2.2). */
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * Inserts [chip] into [value] at the cursor (replacing the selection); `http://` and `https://` replace an existing scheme
 * at the start, or are put in front. Returns the new value with the cursor after the inserted text.
 */
fun insertChip(value: TextFieldValue, chip: String): TextFieldValue {
    val text = value.text
    if (SCHEME.matches(chip)) {
        val existing = SCHEME.find(text)?.value
        val rest = if (existing != null) text.substring(existing.length) else text
        val out = chip + rest
        val shift = chip.length - (existing?.length ?: 0)
        val cursor = (value.selection.end + shift).coerceIn(chip.length, out.length)
        return TextFieldValue(out, TextRange(cursor))
    }
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(0, text.length)
    val out = text.substring(0, start) + chip + text.substring(end)
    return TextFieldValue(out, TextRange(start + chip.length))
}

/**
 * Whether a keyboard app or a hardware keyboard exists (§3.2.2, §9 F5). No IME and `KEYBOARD_NOKEYS` → the editor shows
 * the phone fallback. Tests pass the answer to [TextEditorOverlay] directly.
 */
fun keyboardAvailable(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java)
    val imes = try {
        imm?.enabledInputMethodList.orEmpty()
    } catch (e: RuntimeException) {
        emptyList()
    }
    return imes.isNotEmpty() || context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
}

/** The keyboard check [TextEditorOverlay] uses by default (tests provide `{ true }` or `{ false }`). */
val LocalKeyboardCheck = androidx.compose.runtime.staticCompositionLocalOf<(Context) -> Boolean> { ::keyboardAvailable }

/**
 * The text editor overlay (§3.1, §3.2.2; reviewed 2026-10-16, S3). While it is open the router passes every key except
 * BACK to Compose, so this is the one settings surface on real Compose focus: field → chips → Done/Cancel, with M3's G5
 * Down handler on the single-line field (it would swallow D-pad Down). BACK reaches it as [editorBacks]: from the chips
 * or buttons focus returns to the field, from the field (or with focus lost) it cancels. The text is remembered per
 * [EditorState.row], so an error round trip (Done → the machine keeps the editor with [EditorState.error]) keeps it.
 * In-tree (no Dialog); the card sits in the top 45 % so the IME does not cover it.
 */
@Composable
fun TextEditorOverlay(
    editor: EditorState,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    editorBacks: Flow<Unit>,
    onPhoneSetup: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardAvailable: Boolean = LocalKeyboardCheck.current(LocalContext.current),
) {
    var value by remember(editor.row) { mutableStateOf(TextFieldValue(editor.initial, TextRange(editor.initial.length))) }
    var fieldFocused by remember(editor.row) { mutableStateOf(false) }
    /** Focus on a chip or a button (BACK then returns to the field). */
    var islandFocused by remember(editor.row) { mutableStateOf(false) }
    var reveal by remember(editor.row) { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    val done = remember { FocusRequester() }
    val phone = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val cancel by rememberUpdatedState(onCancel)
    val masked = editor.masked || editor.hint == TextHint.PASSWORD || editor.hint == TextHint.PASSPHRASE

    // (M5 integration) The keyboard goes with the overlay however it closes (Done, Cancel, BACK, or the machine closing it):
    // on the emulator a keyboard left "shown" after the field went away kept swallowing the D-pad keys.
    androidx.compose.runtime.DisposableEffect(editor.row) { onDispose { keyboard?.hide() } }
    LaunchedEffect(editor.row, keyboardAvailable) {
        runCatching { if (keyboardAvailable) field.requestFocus() else phone.requestFocus() }
        if (keyboardAvailable) keyboard?.show()
    }
    LaunchedEffect(editorBacks, editor.row) {
        editorBacks.collect {
            if (islandFocused && keyboardAvailable && !fieldFocused) runCatching { field.requestFocus() }.onFailure { cancel() } else cancel()
        }
    }

    val shape = RoundedCornerShape(SettingsUi.CARD_RADIUS.dp)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .padding(top = SettingsUi.EDITOR_TOP.dp)
                .width(SettingsUi.EDITOR_W.dp)
                .testTag(EDITOR_TAG)
                .background(SettingsUi.CardFill, shape)
                .border(1.dp, SettingsUi.CardBorder, shape)
                .padding(SettingsUi.CARD_PAD.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Line(editor.label, GuideUi.sans(22, FontWeight.SemiBold))
            if (!keyboardAvailable) {
                Column(Modifier.testTag(EDITOR_NO_KEYBOARD_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Line(stringResource(R.string.settings_ui_no_keyboard), GuideUi.sans(26, FontWeight.SemiBold), maxLines = 2)
                    Line(stringResource(R.string.settings_ui_no_keyboard_body), GuideUi.sans(20, color = SettingsUi.Fg70), maxLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    EditorButton(stringResource(R.string.settings_ui_phone_setup), EDITOR_PHONE_TAG, Modifier.focusRequester(phone), onFocus = { islandFocused = it }) { onPhoneSetup() }
                    EditorButton(stringResource(R.string.settings_ui_cancel), EDITOR_CANCEL_TAG, onFocus = { islandFocused = it }) { onCancel() }
                }
                return@Column
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SettingsUi.FIELD_H.dp)
                    .background(SettingsUi.White08, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = GuideUi.mono(26),
                    cursorBrush = SolidColor(SettingsUi.Accent),
                    visualTransformation = if (masked && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardTypeFor(editor.hint, masked), imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { onDone(value.text) }, onNext = { onDone(value.text) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // G5 (M3 §5.11, `SearchScreen`): a single-line field swallows D-pad Down; Down leaves it for the chips.
                        .onPreviewKeyEvent { e ->
                            if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) {
                                keyboard?.hide()
                                if (editor.chips.isNotEmpty()) runCatching { firstChip.requestFocus() } else runCatching { done.requestFocus() }
                                true
                            } else {
                                false
                            }
                        }
                        .testTag(EDITOR_FIELD_TAG),
                )
            }
            // The 2 px accent underline while the field is focused.
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (fieldFocused) SettingsUi.Accent else SettingsUi.White12))
            editor.error?.let { Line(it, GuideUi.sans(18, color = SettingsUi.Amber), Modifier.testTag(EDITOR_ERROR_TAG), maxLines = 2) }
            if (editor.chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    editor.chips.forEachIndexed { i, chip ->
                        val m = if (i == 0) Modifier.focusRequester(firstChip) else Modifier
                        EditorChip(chip, i, m.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionUp -> { runCatching { field.requestFocus() }; true }
                                Key.DirectionDown -> { runCatching { done.requestFocus() }; true }
                                else -> false
                            }
                        }, onFocus = { islandFocused = it }) { value = insertChip(value, chip) }
                    }
                }
            }
            Row(
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        if (editor.chips.isNotEmpty()) runCatching { firstChip.requestFocus() } else runCatching { field.requestFocus() }
                        true
                    } else {
                        false
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EditorButton(stringResource(R.string.settings_ui_done), EDITOR_DONE_TAG, Modifier.focusRequester(done), onFocus = { islandFocused = it }) { onDone(value.text) }
                EditorButton(stringResource(R.string.settings_ui_cancel), EDITOR_CANCEL_TAG, onFocus = { islandFocused = it }) { onCancel() }
                if (masked) {
                    EditorButton(
                        stringResource(if (reveal) R.string.settings_ui_hide_password else R.string.settings_ui_show_password),
                        EDITOR_SHOW_TAG,
                        onFocus = { islandFocused = it },
                    ) { reveal = !reveal }
                }
            }
        }
    }
}

private fun keyboardTypeFor(hint: TextHint, masked: Boolean): KeyboardType = when {
    masked && hint == TextHint.NUMBER -> KeyboardType.NumberPassword
    masked -> KeyboardType.Password
    hint == TextHint.URL -> KeyboardType.Uri
    hint == TextHint.NUMBER -> KeyboardType.Number
    else -> KeyboardType.Text
}

@Composable
private fun EditorChip(label: String, index: Int, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Chip(
        focused = focused,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clickable(onClick = onClick)
            .testTag(EDITOR_CHIP_TAG + index),
        fill = SettingsUi.White08,
        border = SettingsUi.White16,
        padding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { c -> Line(label, GuideUi.mono(18, color = c)) }
}

@Composable
private fun EditorButton(label: String, tag: String, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Chip(
        focused = focused,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clickable(onClick = onClick)
            .testTag(tag),
        fill = SettingsUi.White12,
        border = SettingsUi.White16,
        shape = RoundedCornerShape(14.dp),
        padding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
    ) { c -> Line(label, SettingsUi.Button.copy(color = c)) }
}
