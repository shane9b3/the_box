package app.thebox.core.playback.broker

import app.thebox.core.playback.ZapPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class ZapDebouncerTest {
    private val d = ZapDebouncerImpl()
    private val ms = ZapConstants.ZAP_DEBOUNCE_MS

    @Test
    fun singlePressTunesImmediately() {
        assertEquals(ZapPlan(true, 1_000), d.onZap(1_000, isRepeat = false))
        assertEquals(ZapPlan(true, 1_000 + ms + 1), d.onZap(1_000 + ms + 1, isRepeat = false))
    }

    @Test
    fun repeatSchedulesTrailingAtLastPressPlusDebounce() {
        assertEquals(ZapPlan(true, 0), d.onZap(0, false))
        assertEquals(ZapPlan(false, 100 + ms), d.onZap(100, isRepeat = true))
        assertEquals(ZapPlan(false, 200 + ms), d.onZap(200, isRepeat = true))
    }

    @Test
    fun aRepeatAloneIsDeferred() {
        assertEquals(ZapPlan(false, 5_000 + ms), d.onZap(5_000, isRepeat = true))
    }

    @Test
    fun fivePressesInOneSecondCostOneImmediateAndOneTrailing() {
        val plans = (0 until 5).map { d.onZap(it * 200L, isRepeat = false) }
        assertEquals(1, plans.count { it.immediate })
        assertEquals(ZapPlan(false, 800 + ms), plans.last())
        // The controller's scheduler fires the last plan; the next quiet press is immediate again.
        d.onFired()
        assertEquals(ZapPlan(true, 800 + ms + ms + 1), d.onZap(800 + ms + ms + 1, false))
    }

    @Test
    fun pressAtExactlyTheDebounceIsStillCoalesced() {
        d.onZap(0, false)
        assertEquals(ZapPlan(false, ms + ms), d.onZap(ms, false))
    }

    @Test
    fun pendingTrailingKeepsLaterPressesDeferred() {
        d.onZap(0, false)
        d.onZap(100, false)
        // 600 ms later the trailing has not been fired yet (e.g. the scheduler was cancelled): still deferred.
        assertEquals(ZapPlan(false, 700 + ms), d.onZap(700, false))
    }

    @Test
    fun resetForgetsEverything() {
        d.onZap(0, false)
        d.onZap(100, true)
        d.reset()
        assertEquals(ZapPlan(true, 150), d.onZap(150, false))
    }
}
