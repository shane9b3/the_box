package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/design/m6-sports-update-interval.md §3.4: the back-off takes a base; 20 s stays the default (NHL, 429, day list). */
class SportsTimingBackoffTest {
    @Test
    fun theDefaultBaseIsTodays() {
        assertEquals(listOf(20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), (1..6).map { SportsTiming.backoffMs(it, 0.0) })
        assertEquals(SportsTiming.backoffMs(3, 0.5), SportsTiming.backoffMs(3, 0.5, SportsConstants.BACKOFF_BASE_MS))
    }

    @Test
    fun theFastBaseSteps5To300Seconds() {
        val steps = (1..8).map { SportsTiming.backoffMs(it, 0.0, SportsConstants.BACKOFF_FAST_BASE_MS) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), steps)
        assertEquals(6_000L, SportsTiming.backoffMs(1, 1.0, SportsConstants.BACKOFF_FAST_BASE_MS), "+20 %")
        assertEquals(4_000L, SportsTiming.backoffMs(1, -1.0, SportsConstants.BACKOFF_FAST_BASE_MS), "−20 %")
    }

    @Test
    fun retryAfterIsAFloorOnEitherBase() {
        assertEquals(1_000L + 30_000L, SportsTiming.backoffAt(1_000L, 1, 30, 0.0, SportsConstants.BACKOFF_FAST_BASE_MS))
        assertEquals(1_000L + 5_000L, SportsTiming.backoffAt(1_000L, 1, 0, 0.0, SportsConstants.BACKOFF_FAST_BASE_MS))
    }
}
