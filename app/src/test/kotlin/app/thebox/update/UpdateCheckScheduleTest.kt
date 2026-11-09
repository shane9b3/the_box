package app.thebox.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-L14 (M5 code review 2026-10-16): no daily update worker while the build's endpoint is disabled (debug until the harness sets a URL). */
class UpdateCheckScheduleTest {
    @Test
    fun noWorkerWhileTheEndpointIsDisabled() {
        assertNull(UpdateCheckSchedulerImpl.firstRunDelay(60_000L, endpointEnabled = false))
        assertEquals(60_000L, UpdateCheckSchedulerImpl.firstRunDelay(60_000L, endpointEnabled = true))
        assertNull("checks off", UpdateCheckSchedulerImpl.firstRunDelay(null, endpointEnabled = true))
        assertEquals("the release build schedules its daily check (Q8 resolved)", 60_000L, UpdateCheckSchedulerImpl.firstRunDelay(60_000L, ReleaseUpdateConfig.endpoint.enabled))
    }
}
