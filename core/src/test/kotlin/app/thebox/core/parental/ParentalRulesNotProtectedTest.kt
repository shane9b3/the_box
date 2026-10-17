package app.thebox.core.parental

import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import kotlin.test.Test
import kotlin.test.assertEquals

/** U-S11 (M5 code review 2026-10-16, §3.9.1): locked groups in FILTERED_NO_PIN are marked "not protected" (amber 18+). */
class ParentalRulesNotProtectedTest {
    private val locked = GuideSourceEntry(GuideSource.Group(1, "late"), "Late", 4, SourceSection.GROUP, null, locked = true)
    private val plain = GuideSourceEntry(GuideSource.Group(1, "news"), "News", 9, SourceSection.GROUP, null)

    @Test
    fun filteredNoPinMarksLockedEntriesNotProtected() {
        val noPin = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy(autoLockAdult = true))
        assertEquals(listOf(true, false), ParentalRules.visibleEntries(listOf(locked, plain), noPin).map { it.notProtected })
        val withPin = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy())
        assertEquals(listOf(false, false), ParentalRules.visibleEntries(listOf(locked.copy(notProtected = true), plain), withPin).map { it.notProtected })
    }
}
