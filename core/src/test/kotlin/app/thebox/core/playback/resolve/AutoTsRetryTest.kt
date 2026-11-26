package app.thebox.core.playback.resolve

import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.AutoTsRetry.Result
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/decisions/2026-11-25-auto-format-retries-ts.md: when an Auto playlist that learned HLS tries TS again, the
 * back-off (24 h → 3 days → 7 days), the hold-off after an inconclusive retry, the allowed-formats gate, the stored form.
 * A virtual wall clock throughout ([now]).
 */
class AutoTsRetryTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private var now = 1_795_000_000_000L // 2026-11-17-ish

    private fun account(vararg formats: String) =
        XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, allowedOutputFormats = formats.toList(), measuredAtDeviceMs = 0)

    private fun due(learned: LearnedOutput?, setting: XtreamOutput = XtreamOutput.AUTO, account: XtreamAccountInfo? = null) =
        AutoTsRetry.due(setting, learned, account, now)

    /** Store and read back, as a restart does: the state lives only in the column's text. */
    private fun restart(l: LearnedOutput?): LearnedOutput? = LearnedOutput.parse(l?.encode())

    @Test
    fun aValueStoredBeforeTheRetryExistedIsDueAtOnce() {
        // The emulator's playlist: "HLS" since 2026-11-17, no time stored.
        val legacy = LearnedOutput.parse("HLS")!!
        assertEquals(LearnedOutput(XtreamOutput.HLS), legacy)
        assertTrue(due(legacy))
        assertEquals("HLS", legacy.encode(), "a plain value stays plain")
    }

    @Test
    fun notDueUntil24HoursAfterHlsWasLearned() {
        val learned = AutoTsRetry.afterLearn(null, XtreamOutput.HLS, now)!!
        assertEquals(LearnedOutput(XtreamOutput.HLS, learnedAtMs = now), learned)
        assertFalse(due(learned))
        now += day - 1
        assertFalse(due(learned))
        now += 1
        assertTrue(due(learned))
    }

    @Test
    fun refusalsBackOffThreeDaysThenSevenAndStayAtSeven() {
        var l: LearnedOutput? = AutoTsRetry.afterLearn(null, XtreamOutput.HLS, now)
        val waits = ArrayList<Long>()
        repeat(4) {
            now = AutoTsRetry.nextRetryAtMs(l, now)!!
            assertTrue(due(l))
            l = restart(AutoTsRetry.afterResult(l, Result.REFUSED, now))
            assertFalse(due(l), "refused: not due again at once")
            waits += AutoTsRetry.nextRetryAtMs(l, now)!! - now
            assertEquals(now, l!!.learnedAtMs, "re-learned now")
        }
        assertEquals(listOf(3 * day, 7 * day, 7 * day, 7 * day), waits)
        assertEquals(2, l!!.step)
        assertEquals(7, AutoTsRetry.daysUntilNext(l, now))
    }

    @Test
    fun aRefusalOfTheLegacyValueStepsToThreeDays() {
        val l = AutoTsRetry.afterResult(LearnedOutput.parse("HLS"), Result.REFUSED, now)!!
        assertEquals(1, l.step)
        assertEquals(now + 3 * day, AutoTsRetry.nextRetryAtMs(l, now))
    }

    @Test
    fun aConfirmedRetryClearsTheLearnedValue() {
        val l = AutoTsRetry.afterResult(LearnedOutput(XtreamOutput.HLS, now - 9 * day, step = 2), Result.CONFIRMED, now)
        assertNull(l)
        assertNull(LearnedOutput.parse(l?.encode()))
        assertFalse(due(l), "plain Auto: nothing to retry")
        // Learned again later: the back-off starts over at 24 h.
        val again = AutoTsRetry.afterLearn(l, XtreamOutput.HLS, now)!!
        assertEquals(0, again.step)
        assertEquals(now + day, AutoTsRetry.nextRetryAtMs(again, now))
    }

    @Test
    fun anInconclusiveRetryHoldsOffAnHourAndKeepsTheStep() {
        val start = LearnedOutput(XtreamOutput.HLS, learnedAtMs = now - 4 * day, step = 1)
        assertTrue(due(start))
        val held = restart(AutoTsRetry.afterResult(start, Result.INCONCLUSIVE, now))!!
        assertEquals(1, held.step)
        assertEquals(start.learnedAtMs, held.learnedAtMs)
        assertFalse(due(held))
        now += hour - 1
        assertFalse(due(held), "a dead channel does not cost a retry on every zap")
        now += 1
        assertTrue(due(held))
    }

    @Test
    fun theHoldOffNeverShortensTheBackOff() {
        val l = AutoTsRetry.afterResult(LearnedOutput(XtreamOutput.HLS, learnedAtMs = now), Result.INCONCLUSIVE, now)!!
        assertEquals(now + day, AutoTsRetry.nextRetryAtMs(l, now))
    }

    @Test
    fun anAccountWhoseFormatsLackTsNeverRetries() {
        val legacy = LearnedOutput(XtreamOutput.HLS)
        assertFalse(due(legacy, account = account("m3u8")))
        assertFalse(due(legacy, account = account("m3u8", "rtmp")))
        assertTrue(due(legacy, account = account("m3u8", "ts")))
        assertTrue(due(legacy, account = account(" TS ")))
        assertTrue(due(legacy, account = account("mpegts")))
        assertTrue(due(legacy, account = account()), "formats unknown")
        assertTrue(due(legacy, account = null), "no account card")
    }

    @Test
    fun manualModesAndOtherLearnedValuesNeverRetry() {
        val legacy = LearnedOutput(XtreamOutput.HLS)
        assertFalse(due(legacy, setting = XtreamOutput.TS))
        assertFalse(due(legacy, setting = XtreamOutput.HLS))
        assertFalse(due(LearnedOutput(XtreamOutput.TS)))
        assertFalse(due(null))
        assertNull(AutoTsRetry.nextRetryAtMs(LearnedOutput(XtreamOutput.TS), now))
    }

    @Test
    fun learningKeepsARefusalsStepAndStoresTsPlainly() {
        val refused = AutoTsRetry.afterResult(LearnedOutput(XtreamOutput.HLS), Result.REFUSED, now)
        // The flip's first media saves HLS after the refusal was recorded: the step is kept.
        assertEquals(refused, AutoTsRetry.afterLearn(refused, XtreamOutput.HLS, now + 2_000))
        assertEquals("TS", AutoTsRetry.afterLearn(refused, XtreamOutput.TS, now)!!.encode())
        assertNull(AutoTsRetry.afterLearn(refused, null, now))
        assertNull(AutoTsRetry.afterLearn(refused, XtreamOutput.AUTO, now))
    }

    @Test
    fun theStoredFormRoundTripsAndToleratesOtherText() {
        val l = LearnedOutput(XtreamOutput.HLS, learnedAtMs = now, step = 2, holdUntilMs = now + hour)
        assertEquals("HLS;t=$now;n=2;h=${now + hour}", l.encode())
        assertEquals(l, LearnedOutput.parse(l.encode()))
        assertEquals(LearnedOutput(XtreamOutput.HLS, learnedAtMs = now), LearnedOutput.parse("hls;t=$now"))
        assertEquals(LearnedOutput(XtreamOutput.TS), LearnedOutput.parse("TS"))
        assertEquals(2, LearnedOutput.parse("HLS;n=9")!!.step, "step capped")
        assertEquals(LearnedOutput(XtreamOutput.HLS), LearnedOutput.parse("HLS;t=x;zz=1"))
        assertNull(LearnedOutput.parse("AUTO"))
        assertNull(LearnedOutput.parse("RTMP"))
        assertNull(LearnedOutput.parse(""))
        assertNull(LearnedOutput.parse(null))
    }

    @Test
    fun aTimeStoredUnderAWrongClockDoesNotBlockTheRetry() {
        // Stored while the device clock was a year ahead, read after it was corrected.
        val l = LearnedOutput(XtreamOutput.HLS, learnedAtMs = now + 365 * day, holdUntilMs = now + 365 * day)
        assertTrue(due(l))
    }

    @Test
    fun theNumbersAreTheDecidedOnes() {
        assertEquals(listOf(day, 3 * day, 7 * day), AutoTsRetry.BACKOFF_MS.toList())
        assertEquals(hour, AutoTsRetry.HOLD_OFF_MS)
        assertEquals(30_000L, AutoTsRetry.CONFIRM_MS)
        assertEquals(8_000L, AutoTsRetry.FIRST_MEDIA_BUDGET_MS)
    }
}
