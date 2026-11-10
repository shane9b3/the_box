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

    // ---------------------------------------------------------------- 2026-11-10 (docs/design/m5-update-visible-install.md)

    /** Review S1: one busy rule for runCheck, download() and the About row. */
    @Test
    fun busyIsDownloadingVerifyingReadyAndInstalling() {
        assertTrue(UpdateState.Downloading("1.0.4", 1, 10).isBusy)
        assertTrue(UpdateState.Verifying("1.0.4").isBusy)
        assertTrue(UpdateState.Installing.isBusy)
        assertTrue(UpdateState.ReadyToInstall("1.0.4").isBusy, "code review S5: no buttons, the hand-off is under way")
        for (s in listOf(UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate(1), UpdateState.Available(manifest, apk), UpdateState.NeedsPermission("1.0.4"),
            UpdateState.NotInstalled("1.0.4"), UpdateState.Failed(UpdateFailure.NETWORK, null))) {
            assertFalse(s.isBusy, "$s")
        }
    }

    /** Review S3: a downloaded update newer than the running version is kept; everything else goes. */
    @Test
    fun startKeepsTheNewestNewerApkAndDeletesTheRest() {
        val plan = UpdatePolicy.startupFiles(listOf("5.apk", "7.apk", "7.apk.part", "3.apk", "junk", "8.apk.part"), runningVersionCode = 5)
        assertEquals(7L, plan.keepVersionCode)
        assertEquals(listOf("5.apk", "7.apk.part", "3.apk", "junk", "8.apk.part"), plan.delete)
        val landed = UpdatePolicy.startupFiles(listOf("5.apk"), runningVersionCode = 5)
        assertNull(landed.keepVersionCode)
        assertEquals(listOf("5.apk"), landed.delete)
        assertEquals(UpdatePolicy.StartupFiles(null, emptyList()), UpdatePolicy.startupFiles(emptyList(), 5))
    }

    /** Review S3: the restart check for a waiting download runs whatever the cadence (once per process). */
    @Test
    fun aWaitingDownloadChecksAgainWhateverTheCadence() {
        val justChecked = UpdatePrefs(lastCheckMs = now - 1_000)
        assertFalse(UpdatePolicy.shouldRun(justChecked, now, manual = false, checkedThisProcess = false))
        assertTrue(UpdatePolicy.shouldRun(justChecked, now, manual = false, checkedThisProcess = false, resume = true))
        assertTrue(UpdatePolicy.shouldRun(UpdatePrefs(checkAutomatically = false), now, manual = false, checkedThisProcess = false, resume = true))
        assertFalse(UpdatePolicy.shouldRun(justChecked, now, manual = false, checkedThisProcess = true, resume = true), "the worker checked already")
    }

    @Test
    fun landedIsTheTargetOrHigher() {
        assertTrue(UpdatePolicy.landed(6, 6))
        assertTrue(UpdatePolicy.landed(7, 6))
        assertFalse(UpdatePolicy.landed(5, 6))
    }

    /** Found on the emulator (2026-11-10): a second hex() hashed nothing and refused every download as damaged. */
    @Test
    fun sha256HexIsIdempotent() {
        val c = Sha256Counter()
        val b = "abc".toByteArray()
        c.update(b, 0, b.size)
        val first = c.hex()
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", first)
        assertEquals(first, c.hex())
    }

    @Test
    fun sha256RefusesAWriteAfterTheDigest() {
        val c = Sha256Counter()
        c.hex()
        kotlin.test.assertFailsWith<IllegalStateException> { c.update(ByteArray(1), 0, 1) }
    }

    /**
     * Code review S4 (and the restart reuse of a file a previous process left, review S3): the waiting file is kept and
     * reused only while its version is offered or the server could not tell; otherwise it is deleted.
     */
    @Test
    fun aWaitingFileFromAPreviousProcess() {
        // Previous process: downloaded 7 while 6 ran, then died (the API 30 permission grant).
        val plan = UpdatePolicy.startupFiles(listOf("7.apk", "7.apk.part"), runningVersionCode = 6)
        assertEquals(7L, plan.keepVersionCode)
        assertEquals(listOf("7.apk.part"), plan.delete)
        assertTrue(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.Offered(7)))
        assertTrue(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.Unreachable), "offline: try again next start")
        assertTrue(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.NotRun))
        assertFalse(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.Offered(8)))
        assertFalse(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.Nothing), "withdrawn, skipped or up to date")
        assertFalse(UpdatePolicy.keepsWaitingFile(7, UpdatePolicy.CheckOutcome.Rejected))
    }

    /** Review S6: a file verified in the background waits for the next start; once. */
    @Test
    fun deferredHandOff() {
        val d = DeferredHandOff()
        assertTrue(d.onVerified(inForeground = true))
        assertFalse(d.onStart())
        assertFalse(d.onVerified(inForeground = false))
        assertTrue(d.pending)
        assertTrue(d.onStart())
        assertFalse(d.onStart(), "once")
        d.onVerified(inForeground = false)
        d.clear()
        assertFalse(d.onStart())
    }

    @Test
    fun restrictionFailureIsAlwaysShown() {
        assertTrue(UpdatePolicy.showsFailure(UpdateFailure.INSTALL_RESTRICTED, manual = false))
    }
}
