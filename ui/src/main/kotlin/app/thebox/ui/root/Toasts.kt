package app.thebox.ui.root

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideToast
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.playback.CatchupFailure
import app.thebox.ui.playback.ErrorText
import app.thebox.ui.theme.DesignTokens

const val TOAST_TAG = "toast"
const val TOAST_MS = 4_000L

/**
 * Text of a [GuideToast] (docs/design/m3-guide.md §2.5 S13, §3.8.3, §3.11.3, §3.13.6). Failures read
 * "reason. Next step." (CLAUDE.md). [arg]: the channel name, the count, or the `CatchupFailure` name.
 */
fun toastText(res: Resources, kind: GuideToast, arg: String?): String = when (kind) {
    GuideToast.REMINDER_SET_FIRST_TIME -> res.getString(R.string.toast_reminder_first)
    GuideToast.REMINDER_SET -> res.getString(R.string.toast_reminder_set)
    GuideToast.REMINDER_CANCELLED -> res.getString(R.string.toast_reminder_cancelled)
    GuideToast.REMINDER_CHANNEL_GONE ->
        if (arg.isNullOrBlank()) res.getString(R.string.toast_reminder_channel_gone_generic) else res.getString(R.string.toast_reminder_channel_gone, arg)
    GuideToast.ADDED_FAVOURITE ->
        if (arg.isNullOrBlank()) res.getString(R.string.toast_added_favourite_generic) else res.getString(R.string.toast_added_favourite, arg)
    GuideToast.REMOVED_FAVOURITE ->
        if (arg.isNullOrBlank()) res.getString(R.string.toast_removed_favourite_generic) else res.getString(R.string.toast_removed_favourite, arg)
    GuideToast.NO_FAVOURITES -> res.getString(R.string.toast_no_favourites)
    GuideToast.CHANNEL_HIDDEN ->
        if (arg.isNullOrBlank()) res.getString(R.string.toast_channel_hidden_generic) else res.getString(R.string.toast_channel_hidden, arg)
    GuideToast.NO_CATCHUP -> res.getString(R.string.toast_no_catchup)
    GuideToast.CATCHUP_FAILED -> {
        val f = CatchupFailure.entries.firstOrNull { it.name == arg } ?: CatchupFailure.NO_ARCHIVE
        res.getString(R.string.toast_reason_step, res.getString(ErrorText.catchupHeadline(f)), res.getString(ErrorText.catchupStep(f)))
    }
    GuideToast.REMINDERS_MISSED -> {
        val n = arg?.toIntOrNull() ?: 1
        res.getQuantityString(R.plurals.toast_reminders_missed, n, n)
    }
    GuideToast.EXIT_HINT -> res.getString(R.string.guide_exit_hint)
    // M5 integration (§3.9.1, B3)
    GuideToast.CHANNEL_LOCKED -> res.getString(R.string.guide_toast_channel_locked)
    GuideToast.CHANNEL_UNLOCKED -> res.getString(R.string.guide_toast_channel_unlocked)
    GuideToast.GROUP_LOCKED -> res.getString(R.string.guide_toast_group_locked)
    GuideToast.GROUP_UNLOCKED -> res.getString(R.string.guide_toast_group_unlocked)
    GuideToast.LOCKED_NOT_PROTECTED -> res.getString(R.string.guide_toast_locked_not_protected)
}

/** A toast at left 64 / bottom 96 (§3.15); never takes focus (keys stay with the root sink). */
@Composable
fun ToastView(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    Line(
        text,
        GuideUi.sans(24, FontWeight.Medium),
        modifier
            .testTag(TOAST_TAG)
            .widthIn(max = 900.dp)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        maxLines = 2,
    )
}
