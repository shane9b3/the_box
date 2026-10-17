package app.thebox.ui.settings.vm

import app.thebox.core.settings.SettingsRoute
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-S1 (M5 code review 2026-10-16; adopted from the shell reviewer's `firstRealPageWithin150msOfOpen`): §6.7 "Settings
 * open → first page" ≤ 150 ms. A push-model page publishes its placeholder at once and its data 10 ms later; the old
 * trailing 250 ms rebuild throttle (started by the placeholder's own change) held the data back to 250 ms.
 */
class SettingsViewModelLatencyTest {
    @Test
    fun firstRealPageWithin150msOfOpen() {
        val r = SettingsVmRig(firstDataMs = 10)
        val t0 = r.test.testScheduler.currentTime
        r.machine.open(SettingsRoute.Root, fromFull = false)
        var at = -1L
        for (t in 0..1_000) {
            if (r.shownLabel() != null) { at = r.test.testScheduler.currentTime - t0; break }
            r.advance(1)
        }
        assertTrue("the real Playlists page reached the machine at $at ms (data ready at 10 ms); gate 150 ms", at in 0..150)
    }

    /** U-S1: a burst of data changes is still spaced (the throttle is leading-edge, not removed). */
    @Test
    fun aBurstOfChangesIsSpaced() {
        val r = SettingsVmRig(firstDataMs = 0)
        r.machine.open(SettingsRoute.Root, fromFull = false)
        r.advance(1_000)
        val opensBefore = r.provider.opens
        var shown = 0
        var last: String? = r.shownLabel()
        for (i in 1..20) {
            r.data.value = "Main · $i"
            r.advance(10)
            val now = r.shownLabel()
            if (now != last) { shown++; last = now }
        }
        r.advance(1_000)
        assertTrue("20 changes in 200 ms rebuilt the page $shown times (spaced by 250 ms: at most 2)", shown <= 2)
        assertTrue("the last value is shown after the burst: ${r.shownLabel()}", r.shownLabel() == "Main · 20")
        assertTrue(r.provider.opens == opensBefore)
    }

    /**
     * U-S1 follow-up (device pass, GatesTest): the data publish lands while the 100 ms wait has just given up and the
     * placeholder is on its way to the machine (no page shown yet, so the change's rebuild has nothing to rebuild). The
     * page must not stay "Loading…": a change during an in-flight build rebuilds the page once it arrives.
     */
    @Test
    fun dataLandingAsThePlaceholderIsDispatchedIsShown() {
        for (ms in listOf(95L, 99L, 100L, 101L, 105L)) {
            val r = SettingsVmRig(firstDataMs = ms, splitWork = true)
            r.machine.open(SettingsRoute.Root, fromFull = false)
            r.advance(2_000)
            assertTrue("data at $ms ms: the page stayed a placeholder", r.shownLabel() != null)
        }
    }
}
