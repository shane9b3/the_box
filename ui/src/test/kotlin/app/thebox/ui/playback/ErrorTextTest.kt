package app.thebox.ui.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideToast
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.ui.R
import app.thebox.ui.root.toastText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CLAUDE.md: every error the user sees carries a reason and a next step (docs/design/m3-guide.md §3.13.4, §5.3
 * `ErrorTextTest`): every PlaybackErrorCode, NextStep, CatchupFailure and failure toast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorTextTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyCodeHasAHeadlineAndAStep() {
        val headlines = HashSet<String>()
        for (code in PlaybackErrorCode.entries) {
            val h = ctx.getString(ErrorText.headline(code))
            val s = ctx.getString(ErrorText.step(code))
            assertTrue("$code headline", h.isNotBlank())
            assertTrue("$code step", s.isNotBlank())
            assertNotEquals("$code: the step must not repeat the reason", h, s)
            headlines += h
        }
        assertEquals("headlines are distinct (one reason per code)", PlaybackErrorCode.entries.size, headlines.size)
    }

    @Test
    fun retryFirstCodesSayPressOkToRetry() {
        for (code in PlaybackErrorCode.entries) {
            val first = code.defaultSteps.first()
            if (first == NextStep.RETRY) assertEquals("$code", R.string.error_step_retry, ErrorText.step(code))
            else assertNotEquals("$code names where to go", R.string.error_step_retry, ErrorText.step(code))
        }
    }

    @Test
    fun everyNextStepHasText() {
        val texts = NextStep.entries.map { ctx.getString(ErrorText.step(it)) }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals("one line per step", NextStep.entries.size, texts.toSet().size)
    }

    @Test
    fun aConcreteErrorUsesItsOwnFirstAction() {
        val e = PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE, actions = listOf(NextStep.RETRY))
        assertEquals(R.string.error_step_retry, ErrorText.step(e))
        assertEquals(R.string.error_step_try_backup, ErrorText.step(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)))
        assertEquals(R.string.error_step_try_backup, ErrorText.step(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE, actions = emptyList())))
    }

    @Test
    fun everyCatchupFailureHasAReasonAndAStep() {
        for (f in CatchupFailure.entries) {
            val h = ctx.getString(ErrorText.catchupHeadline(f))
            val s = ctx.getString(ErrorText.catchupStep(f))
            assertTrue(h.isNotBlank() && s.isNotBlank())
            assertNotEquals(h, s)
            val toast = toastText(ctx.resources, GuideToast.CATCHUP_FAILED, f.name)
            assertTrue("toast carries both: $toast", toast.contains(h) && toast.contains(s))
        }
    }

    @Test
    fun everyToastHasText() {
        for (kind in GuideToast.entries) {
            assertFalse("$kind", toastText(ctx.resources, kind, null).isBlank())
            assertFalse("$kind with arg", toastText(ctx.resources, kind, if (kind == GuideToast.REMINDERS_MISSED) "3" else "Northline").isBlank())
        }
        assertEquals("Missed while The Box was closed: 3 reminders", toastText(ctx.resources, GuideToast.REMINDERS_MISSED, "3"))
        assertTrue(toastText(ctx.resources, GuideToast.REMINDER_CHANNEL_GONE, "Northline").startsWith("Northline is no longer"))
    }
}
