package app.thebox.core.update

import app.thebox.core.input.BoxAction
import app.thebox.core.update.UpdateTimings.UPDATE_CHECK_INTERVAL_MS
import app.thebox.core.update.UpdateTimings.UPDATE_FIRST_CHECK_DELAY_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cadence (docs/design/m5-setup-settings.md §3.12.2) and the S17 card's rules (§3.12.3). */
class UpdatePolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun firstEverCheckIsDue() {
        assertTrue(UpdatePolicy.isDue(UpdatePrefs(), now))
        assertEquals(UPDATE_FIRST_CHECK_DELAY_MS, UpdatePolicy.firstCheckDelayMs(UpdatePrefs(), now))
    }

    @Test
    fun aCheckWithinTwentyFourHoursIsNotDue() {
        val prefs = UpdatePrefs(lastCheckMs = now - UPDATE_CHECK_INTERVAL_MS + 1)
        assertFalse(UpdatePolicy.isDue(prefs, now))
        assertTrue(UpdatePolicy.isDue(prefs, now + 1))
        assertTrue(UpdatePolicy.isDue(UpdatePrefs(lastCheckMs = now - UPDATE_CHECK_INTERVAL_MS), now))
    }

    @Test
    fun firstDelayWaitsForTheRestOfTheIntervalButNeverLessThanSixtySeconds() {
        val prefs = UpdatePrefs(lastCheckMs = now - 20 * 3_600_000L)
        assertEquals(4 * 3_600_000L, UpdatePolicy.firstCheckDelayMs(prefs, now))
        val almost = UpdatePrefs(lastCheckMs = now - UPDATE_CHECK_INTERVAL_MS + 5_000)
        assertEquals(UPDATE_FIRST_CHECK_DELAY_MS, UpdatePolicy.firstCheckDelayMs(almost, now))
    }

    @Test
    fun automaticChecksOffMeansNoAutomaticCheckButManualStillRuns() {
        val off = UpdatePrefs(checkAutomatically = false)
        assertFalse(UpdatePolicy.isDue(off, now))
        assertNull(UpdatePolicy.firstCheckDelayMs(off, now))
        assertTrue(UpdatePolicy.shouldRun(off, now, manual = true, checkedThisProcess = true))
        assertFalse(UpdatePolicy.shouldRun(off, now, manual = false, checkedThisProcess = false))
    }

    @Test
    fun atMostOneAutomaticCheckPerProcess() {
        assertTrue(UpdatePolicy.shouldRun(UpdatePrefs(), now, manual = false, checkedThisProcess = false))
        assertFalse(UpdatePolicy.shouldRun(UpdatePrefs(), now, manual = false, checkedThisProcess = true))
    }

    @Test
    fun clockMovedBackCountsAsDue() {
        val prefs = UpdatePrefs(lastCheckMs = now + 3_600_000L)
        assertTrue(UpdatePolicy.isDue(prefs, now))
        assertEquals(UPDATE_FIRST_CHECK_DELAY_MS, UpdatePolicy.firstCheckDelayMs(prefs, now))
    }

    @Test
    fun bucketIsDrawnOnceAndKept() {
        val (b, stored) = UpdatePolicy.bucketOf(UpdatePrefs(), { 1_234 })
        assertEquals(34, b)
        assertEquals(34, stored.bucket)
        val (again, same) = UpdatePolicy.bucketOf(stored, { 99 })
        assertEquals(34, again)
        assertEquals(stored, same)
        assertEquals(1, UpdatePolicy.bucketOf(UpdatePrefs(), { -99 }).first)
    }

    @Test
    fun automaticCheckFailuresAreSilentDownloadFailuresAreNot() {
        assertFalse(UpdatePolicy.showsFailure(UpdateFailure.BAD_SIGNATURE, manual = false))
        assertTrue(UpdatePolicy.showsFailure(UpdateFailure.BAD_SIGNATURE, manual = true))
        assertFalse(UpdatePolicy.showsFailure(UpdateFailure.NETWORK, manual = false))
        assertTrue(UpdatePolicy.showsFailure(UpdateFailure.HASH_MISMATCH, manual = false))
        assertTrue(UpdatePolicy.showsFailure(UpdateFailure.WRONG_CERT, manual = false))
    }

    // ---------------------------------------------------------------- the S17 card

    private val manifest = UpdateManifest(1, 1, "app.thebox", "stable", 2, "1.0.4", 25, "", "\n- Fixes catch-up on Flussonic servers\n- More", emptyList(), listOf("A"))
    private val apk = ApkEntry("universal", "https://x/a.apk", 10, "0".repeat(64))

    @Test
    fun cardAppearsForAvailableAndNotAfterLater() {
        val c = UpdateCardModel.cardFor(UpdateState.Available(manifest, apk), later = false, previous = null) { null }
        assertEquals("1.0.4", c?.versionName)
        assertEquals("Fixes catch-up on Flussonic servers", c?.notes)
        assertNull(UpdateCardModel.cardFor(UpdateState.Available(manifest, apk), later = true, previous = null) { null })
        assertNull(UpdateCardModel.cardFor(UpdateState.UpToDate(1), later = false, previous = c) { null })
    }

    @Test
    fun cardShowsProgressAndKeepsTheFailureReason() {
        val c = UpdateCardModel.cardFor(UpdateState.Available(manifest, apk), false, null) { null }!!
        val d = UpdateCardModel.cardFor(UpdateState.Downloading("1.0.4", 5, 10), false, c) { null }!!
        assertEquals(0.5f, d.progress)
        assertEquals(c.notes, d.notes)
        val f = UpdateCardModel.cardFor(UpdateState.Failed(UpdateFailure.HASH_MISMATCH, null), false, d) { "The download was damaged. Try again." }!!
        assertEquals("The download was damaged. Try again.", f.failed)
        // A check failure (no card before) never shows a card.
        assertNull(UpdateCardModel.cardFor(UpdateState.Failed(UpdateFailure.NETWORK, null), false, null) { "x" })
    }

    @Test
    fun cardKeys() {
        val c = UpdateCardState("1.0.4", "n")
        assertEquals(UpdateAction.UPDATE, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(c, BoxAction.SELECT)).action)
        val right = assertIs<UpdateCardModel.KeyResult.Moved>(UpdateCardModel.onAction(c, BoxAction.RIGHT)).card
        assertEquals(1, right.focused)
        assertEquals(UpdateAction.LATER, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(right, BoxAction.SELECT)).action)
        val skip = assertIs<UpdateCardModel.KeyResult.Moved>(UpdateCardModel.onAction(right.copy(focused = 2), BoxAction.RIGHT)).card
        assertEquals(2, skip.focused)
        assertEquals(UpdateAction.SKIP, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(skip, BoxAction.SELECT)).action)
        assertEquals(0, assertIs<UpdateCardModel.KeyResult.Moved>(UpdateCardModel.onAction(c, BoxAction.LEFT)).card.focused)
        assertEquals(UpdateAction.LATER, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(c, BoxAction.BACK)).action)
        assertIs<UpdateCardModel.KeyResult.NotMine>(UpdateCardModel.onAction(c, BoxAction.ZAP_UP))
    }
}
