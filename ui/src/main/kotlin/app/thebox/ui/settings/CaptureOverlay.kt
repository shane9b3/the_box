package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.input.BindingVerdict
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.ReservedReason
import app.thebox.core.settings.tree.CaptureFlow
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.parental.Amber
import app.thebox.ui.parental.Buttons
import app.thebox.ui.parental.CardBorder
import app.thebox.ui.parental.CardFill
import app.thebox.ui.parental.KeyNames
import app.thebox.ui.parental.ParentalStrings
import kotlinx.coroutines.delay

const val CAPTURE_OVERLAY_TAG = "capture_overlay"

/**
 * "Press a key now" (docs/design/m5-setup-settings.md §3.8.3; S14): card 720 wide centred, the confirm card's fill;
 * "Press the button for {action} now" 30/600 with a mono countdown from CAPTURE_TIMEOUT_MS; on a key its name 34/600,
 * "from {InputDevice name}" 19 at .7 (or "from an unknown device"), the verdict line, then Save / Try another / Cancel
 * ([focusedButton]: CaptureFlow's). [testReport] ("Test a key"): what the key does now in the guide and the player.
 * In-tree (no Popup, §3.1); sizes are design px.
 */
@Composable
fun CaptureOverlay(
    state: CaptureState,
    focusedButton: Int,
    modifier: Modifier = Modifier,
    testReport: Pair<BoxAction?, BoxAction?>? = null,
    /** "Test a key" (CaptureFlow.testMode): the waiting title asks for any button. */
    testMode: Boolean = testReport != null,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val res = LocalContext.current.resources
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag(CAPTURE_OVERLAY_TAG), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.width(720.dp).background(CardFill, shape).border(1.dp, CardBorder, shape).padding(32.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val action = res.getString(ParentalStrings.actionLabel(state.action))
                val key = state.captured
                if (key == null) {
                    Line(
                        if (testMode) stringResource(R.string.capture_test_title)
                        else stringResource(R.string.capture_title, action),
                        GuideUi.sans(36, FontWeight.SemiBold), maxLines = 2,
                    )
                    Countdown(state.startedAtMs, nowMs)
                    Buttons(listOf(stringResource(R.string.capture_cancel)), focused = 0)
                    return@Column
                }
                Line(KeyNames.name(res, key.keyCode), GuideUi.sans(40, FontWeight.SemiBold))
                val device = key.deviceName?.takeIf { !key.isVirtual && it.isNotBlank() }
                Line(
                    if (device != null) stringResource(R.string.capture_from, device) else stringResource(R.string.capture_unknown_device),
                    GuideUi.sans(24, color = GuideUi.Fg70),
                )
                if (testReport != null) {
                    val nothing = stringResource(R.string.capture_test_nothing)
                    Line(stringResource(R.string.capture_test_guide, testReport.first?.let { res.getString(ParentalStrings.actionLabel(it)) } ?: nothing), GuideUi.sans(26))
                    Line(stringResource(R.string.capture_test_player, testReport.second?.let { res.getString(ParentalStrings.actionLabel(it)) } ?: nothing), GuideUi.sans(26))
                    Buttons(listOf(stringResource(R.string.capture_try_another), stringResource(R.string.capture_cancel)), focused = focusedButton - 1)
                    return@Column
                }
                VerdictLine(state.verdict, key.keyCode)
                Buttons(
                    listOf(stringResource(R.string.capture_save), stringResource(R.string.capture_try_another), stringResource(R.string.capture_cancel)),
                    focused = focusedButton,
                )
            }
        }
    }
}

@Composable
private fun Countdown(startedAtMs: Long, nowMs: () -> Long) {
    var now by remember(startedAtMs) { mutableLongStateOf(nowMs()) }
    LaunchedEffect(startedAtMs) {
        while (now - startedAtMs < CaptureFlow.CAPTURE_TIMEOUT_MS) {
            delay(1_000L - ((now - startedAtMs) % 1_000L).coerceAtLeast(0L))
            now = nowMs()
        }
    }
    val left = ((CaptureFlow.CAPTURE_TIMEOUT_MS - (now - startedAtMs)).coerceAtLeast(0L) + 999L) / 1000L
    Line(String.format(java.util.Locale.ROOT, "0:%02d", left), GuideUi.mono(28, color = GuideUi.Fg70))
}

@Composable
private fun VerdictLine(verdict: BindingVerdict?, keyCode: Int) {
    val res = LocalContext.current.resources
    val (text, warn) = verdictText(verdict, keyCode, res) ?: return
    Line(text, GuideUi.sans(26, color = if (warn) Amber else GuideUi.Fg.copy(alpha = 0.8f)), maxLines = 3)
}

/** The verdict sentence (and whether it is a warning) for [verdict]. Shared with the tests. */
internal fun verdictText(verdict: BindingVerdict?, keyCode: Int, res: android.content.res.Resources): Pair<String, Boolean>? = when (verdict) {
    null -> null
    BindingVerdict.Free -> res.getString(R.string.capture_verdict_free) to false
    is BindingVerdict.Replaces -> res.getString(R.string.capture_verdict_replaces, res.getString(ParentalStrings.actionLabel(verdict.current))) to true
    is BindingVerdict.Reserved -> res.getString(
        when (verdict.reason) {
            ReservedReason.NAVIGATION -> R.string.capture_reserved_navigation
            ReservedReason.OK -> R.string.capture_reserved_ok
            ReservedReason.BACK -> R.string.capture_reserved_back
            ReservedReason.DIGIT -> R.string.capture_reserved_digit
            ReservedReason.SYSTEM -> R.string.capture_reserved_system
            ReservedReason.CONTEXT_MENU_LONG_OK -> R.string.capture_reserved_long_ok
            ReservedReason.UNKNOWN_CODE -> R.string.capture_reserved_unknown
        },
    ) to true
    is BindingVerdict.LosesRepeat -> {
        val then = verdictText(verdict.then, keyCode, res)
        val line = res.getString(R.string.capture_verdict_loses_repeat, KeyNames.name(res, keyCode), res.getString(ParentalStrings.actionLabel(verdict.shortAction)))
        (if (then != null && verdict.then != BindingVerdict.Free) then.first + " " + line else line) to true
    }
}
