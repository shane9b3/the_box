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
    // ---- M4 (docs/design/m4-player-ux.md §3.7, §5.4): the full-screen card

    @Test
    fun detailLineForEveryCodeEndsWithItsKey() {
        for (e in app.thebox.core.full.fakes.PlayerFixtures.allErrors()) {
            val line = ErrorText.detailLine(ctx.resources, e.code, e.detail)
            assertTrue("${e.code}: $line", line.endsWith(e.code.key))
            assertFalse("no URL or query in the line: $line", line.contains("://") || line.contains('?'))
        }
    }

    @Test
    fun detailLineCombinations() {
        val r = ctx.resources
        fun line(d: app.thebox.core.playback.ErrorDetail) = ErrorText.detailLine(r, PlaybackErrorCode.FORBIDDEN, d)
        assertEquals("play.forbidden", line(app.thebox.core.playback.ErrorDetail()))
        assertEquals("HTTP 403 · panel:80 · play.forbidden", line(app.thebox.core.playback.ErrorDetail(httpStatus = 403, host = "panel:80")))
        assertEquals("video/hevc Main10 · c2.hevc · play.forbidden", line(app.thebox.core.playback.ErrorDetail(codec = "video/hevc Main10", decoderName = "c2.hevc")))
        assertEquals("HTTP 458 · panel:80 · 1/1 connections · play.forbidden", line(app.thebox.core.playback.ErrorDetail(httpStatus = 458, host = "panel:80", activeConnections = 1, maxConnections = 1)))
        assertEquals("one count alone is not shown", "play.forbidden", line(app.thebox.core.playback.ErrorDetail(activeConnections = 1)))
        assertEquals("HTTP 1 · h · c · d · 2/3 connections · play.forbidden",
            line(app.thebox.core.playback.ErrorDetail(httpStatus = 1, host = "h", codec = "c", decoderName = "d", activeConnections = 2, maxConnections = 3, media3ErrorCode = 2001, signal = "Http")))
    }

    @Test
    fun everyButtonHasADistinctLabel() {
        val labels = app.thebox.core.full.ErrorButton.entries.map { ctx.getString(ErrorText.button(it)) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    /**
     * WP-C finding: the only card the controller shows with RetryInfo is NO_DATA_TIMEOUT before first media
     * (maxAttempts = 0, unlimited, refreshed per retry); terminal errors have retry = null.
     */
    @Test
    fun retryLine() {
        val r = ctx.resources
        val unlimited = app.thebox.core.playback.RetryInfo(attemptsMade = 3, maxAttempts = 0, nextAttemptInMs = 12_000)
        val card = PlaybackError(PlaybackErrorCode.NO_DATA_TIMEOUT, retry = unlimited)
        assertEquals("Still trying… next attempt in 12 s", ErrorText.retryLine(r, card.retry))
        assertEquals("counts down", "Still trying… next attempt in 7 s", ErrorText.retryLine(r, card.retry, elapsedMs = 5_000))
        assertEquals("Still trying… next attempt in 0 s", ErrorText.retryLine(r, card.retry, elapsedMs = 60_000))
        assertEquals("Still trying… attempt 4", ErrorText.retryLine(r, unlimited.copy(nextAttemptInMs = null)))
        assertEquals("Still trying… attempt 2 of 5", ErrorText.retryLine(r, app.thebox.core.playback.RetryInfo(1, 5, 3_000)))
        assertEquals(null, ErrorText.retryLine(r, null))
    }

    @Test
    fun everyArchiveFailureHasAReasonAndAStepAndTheStoppedHintExists() {
        for (f in app.thebox.core.catchup.archive.ArchiveFailure.entries) {
            val h = ctx.getString(ErrorText.archiveHeadline(f))
            val st = ctx.getString(ErrorText.archiveStep(f))
            assertTrue("$f", h.isNotBlank() && st.isNotBlank())
            assertNotEquals(h, st)
            val toast = app.thebox.ui.full.PlayerText.toast(ctx.resources, app.thebox.core.full.PlayerToast.CATCHUP_FAILED, f.name)
            assertTrue("toast carries both: $toast", toast.contains(h) && toast.contains(st))
        }
        assertEquals("Stopped to free the connection. Press OK to play again.", ctx.getString(ErrorText.stoppedHint()))
    }
}
