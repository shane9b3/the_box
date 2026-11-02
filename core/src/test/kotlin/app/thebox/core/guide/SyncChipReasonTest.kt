package app.thebox.core.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Owner finding 2026-11-01 (docs/decisions/2026-11-01-guide-sync-chip.md): the sync chip's reason selection. */
class SyncChipReasonTest {
    private val epg = SyncFeedState.TARGET_EPG_BUILD
    private val built = 1_000_000L
    private val failedAt = 2_000_000L

    private fun failed(code: String?, target: String? = epg, builtAt: Long? = built, at: Long? = failedAt, count: Int = 1) =
        SyncFeedState(
            busy = false, progress = null, unacknowledgedFailures = count,
            lastFailureCode = code, lastFailureTarget = target, lastFailureAtMs = at, guideBuiltAtMs = builtAt,
        )

    @Test
    fun noFailureAndNotBusyShowsNoChip() {
        assertNull(syncChipReason(SyncFeedState(busy = false, progress = null, unacknowledgedFailures = 0)))
        // A stale code without an unacknowledged count is not shown.
        assertNull(syncChipReason(failed("http.500", count = 0)))
    }

    @Test
    fun busyWinsOverFailures() {
        assertEquals(SyncChipReason.Busy("Matching channels"), syncChipReason(failed("http.500").copy(busy = true, progress = "Matching channels")))
        assertEquals(SyncChipReason.Busy(null), syncChipReason(SyncFeedState(busy = true, progress = null, unacknowledgedFailures = 0)))
    }

    @Test
    fun transientWithGenerationIsStale() {
        assertEquals(SyncChipReason.Stale(500, built), syncChipReason(failed("http.500")))
        assertEquals(SyncChipReason.Stale(503, built), syncChipReason(failed("http.503")))
        assertEquals(SyncChipReason.Stale(408, built), syncChipReason(failed("http.408")))
        assertEquals(SyncChipReason.Stale(429, built), syncChipReason(failed("http.429")))
        assertEquals(SyncChipReason.Stale(null, built), syncChipReason(failed("net.timeout")))
        assertEquals(SyncChipReason.Stale(null, built), syncChipReason(failed("net.dns")))
        // No failure time recorded: still the guide on screen.
        assertEquals(SyncChipReason.Stale(500, built), syncChipReason(failed("http.500", at = null)))
    }

    @Test
    fun transientWithoutGenerationKeepsTheGenericWording() {
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.500", builtAt = null)))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("net.connect", builtAt = null)))
    }

    @Test
    fun aGenerationBuiltAfterTheFailureIsNotCalledStale() {
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.500", builtAt = failedAt + 1)))
    }

    @Test
    fun nonTransientKeepsAReason() {
        assertEquals(SyncChipReason.Refused(403), syncChipReason(failed("http.403")))
        assertEquals(SyncChipReason.Refused(404), syncChipReason(failed("http.404", builtAt = null)))
        assertEquals(SyncChipReason.SignIn, syncChipReason(failed("auth.bad_credentials")))
        assertEquals(SyncChipReason.Unreadable, syncChipReason(failed("format.html_page")))
        assertEquals(SyncChipReason.Unreadable, syncChipReason(failed("parse")))
        assertEquals(SyncChipReason.NoSpace, syncChipReason(failed("disk")))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("internal")))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("cred.unavailable")))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.garbage")))
    }

    @Test
    fun otherTargetsAndMissingCodesUseTheGenericWording() {
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.500", target = "PLAYLIST")))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.500", target = "EPG_PATCH")))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed("http.500", target = null)))
        assertEquals(SyncChipReason.Failed, syncChipReason(failed(null)))
    }
}
