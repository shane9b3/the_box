package app.thebox.core.parental

import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The §2.6 contract body (B3/S8) and the WP-C access helpers (§3.9.1, S10). */
class ParentalAccessTest {
    private fun st(pin: Boolean, unlocked: Boolean, auto: Boolean, lost: Boolean = false, hide: Boolean = false, opened: Set<GuideSource> = emptySet()) =
        ParentalState(pin, unlocked, ParentalPolicy(autoLockAdult = auto, lockedInGuide = if (hide) LockedInGuide.HIDE else LockedInGuide.SHOW_WITH_LOCK), lost, opened)

    @Test fun truthTable() {
        for (pin in listOf(false, true)) for (unlocked in listOf(false, true)) for (auto in listOf(false, true)) for (lost in listOf(false, true)) {
            val expected = when {
                pin && unlocked -> ParentalAccess.OPEN
                pin && auto -> ParentalAccess.LOCKED_AUTO
                pin -> ParentalAccess.LOCKED_MANUAL_ONLY
                auto || lost -> ParentalAccess.FILTERED_NO_PIN
                else -> ParentalAccess.OPEN
            }
            val s = st(pin, unlocked, auto, lost)
            assertEquals(expected, s.access, "pin=$pin unlocked=$unlocked auto=$auto lost=$lost")
            assertEquals(expected == ParentalAccess.FILTERED_NO_PIN, s.unprotected)
        }
    }

    /** S8: a PIN lost to a Keystore reset keeps content filtered whatever the auto-lock toggle says. */
    @Test fun keyResetKeepsContentFiltered() {
        assertEquals(ParentalAccess.FILTERED_NO_PIN, st(pin = false, unlocked = false, auto = false, lost = true).access)
        assertEquals(ParentalAccess.OPEN, st(pin = false, unlocked = false, auto = false, lost = false).access)
    }

    @Test fun queryKeyChangesWithAccessAndHide() {
        val keys = ParentalAccess.entries.flatMap { a -> listOf(false, true).map { h -> a.ordinal * 2 + if (h) 1 else 0 } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(st(pin = true, unlocked = false, auto = true).queryKey + 1, st(pin = true, unlocked = false, auto = true, hide = true).queryKey)
    }

    @Test fun accessForAnOpenedSource() {
        val g = GuideSource.Group(1, "adult")
        val filtered = st(pin = false, unlocked = false, auto = true, opened = setOf(g))
        assertEquals(ParentalAccess.OPEN, ParentalRules.accessFor(filtered, g))
        assertEquals(ParentalAccess.FILTERED_NO_PIN, ParentalRules.accessFor(filtered, GuideSource.All(1)))
        assertEquals(ParentalAccess.FILTERED_NO_PIN, ParentalRules.accessFor(filtered, null))
        // openedAnyway only means something in FILTERED_NO_PIN.
        assertEquals(ParentalAccess.LOCKED_AUTO, ParentalRules.accessFor(st(pin = true, unlocked = false, auto = true, opened = setOf(g)), g))
    }

    private fun entry(key: String, locked: Boolean, section: SourceSection = SourceSection.GROUP) =
        GuideSourceEntry(GuideSource.Group(1, key), key, 5, section, null, locked)

    @Test fun hideLeavesLockedGroupsOutExceptWithoutAPin() {
        val entries = listOf(GuideSourceEntry(GuideSource.All(1), "All", 9, SourceSection.ALL, null), entry("news", false), entry("adult", true))
        assertEquals(entries, ParentalRules.visibleEntries(entries, st(pin = true, unlocked = false, auto = true)), "Show with a lock")
        assertEquals(listOf("All", "news"), ParentalRules.visibleEntries(entries, st(pin = true, unlocked = false, auto = true, hide = true)).map { it.label })
        assertEquals(entries, ParentalRules.visibleEntries(entries, st(pin = false, unlocked = false, auto = true, hide = true)), "FILTERED_NO_PIN lists them (the M3 trap)")
    }

    @Test fun doors() {
        val locked = entry("adult", true)
        assertTrue(ParentalRules.needsDoor(locked, st(pin = true, unlocked = false, auto = true)))
        assertTrue(ParentalRules.needsDoor(locked, st(pin = false, unlocked = false, auto = true)))
        assertFalse(ParentalRules.needsDoor(locked, st(pin = false, unlocked = false, auto = true, opened = setOf(locked.source))))
        assertFalse(ParentalRules.needsDoor(locked, st(pin = true, unlocked = true, auto = true)))
        assertFalse(ParentalRules.needsDoor(entry("news", false), st(pin = true, unlocked = false, auto = true)))
    }

    @Test fun pinMessages() {
        assertEquals("wrong:2", PinMessage.wrong(2)); assertEquals("wrong:1", PinMessage.wrong(1)); assertEquals("wrong", PinMessage.wrong(3))
        assertEquals(2, PinMessage.triesLeft("wrong:2")); assertEquals(null, PinMessage.triesLeft("wrong"))
    }
}
