package app.thebox.core.update

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The card's phases and Later / BACK hiding (docs/design/m5-update-visible-install.md §1, §5; review S2, §6). */
class UpdateCardModelTest {
    private val manifest = UpdateManifest(1, 1, "app.thebox", "stable", 2, "1.0.5", 25, "", "Fixes catch-up", emptyList(), listOf("A"))
    private val apk = ApkEntry("universal", "https://x/a.apk", 10, "0".repeat(64))
    private val failText: (UpdateFailure) -> String? = { "The download was damaged. Try again." }

    /** The updater's real fold ([UpdateCardModel.Fold]), with Later as a switch. */
    private class Fold(var later: Boolean = false, failedText: (UpdateFailure) -> String? = { "reason" }) {
        private val fold = UpdateCardModel.Fold(failedText)
        fun next(s: UpdateState): UpdateCardState? = fold.next(s, later)
    }

    @Test
    fun everyStateHasItsPhase() {
        val f = Fold()
        assertEquals(UpdateCardPhase.OFFER, f.next(UpdateState.Available(manifest, apk))?.phase)
        assertEquals(UpdateCardPhase.DOWNLOADING, f.next(UpdateState.Downloading("1.0.5", 0, 10))?.phase)
        assertEquals(UpdateCardPhase.VERIFYING, f.next(UpdateState.Verifying("1.0.5"))?.phase)
        assertEquals(UpdateCardPhase.OPENING_INSTALLER, f.next(UpdateState.ReadyToInstall("1.0.5"))?.phase)
        assertEquals(UpdateCardPhase.OPENING_INSTALLER, f.next(UpdateState.Installing)?.phase)
        assertEquals(UpdateCardPhase.NOT_INSTALLED, f.next(UpdateState.NotInstalled("1.0.5"))?.phase)
        assertEquals(UpdateCardPhase.NEEDS_PERMISSION, f.next(UpdateState.NeedsPermission("1.0.5"))?.phase)
        val failed = f.next(UpdateState.Failed(UpdateFailure.HASH_MISMATCH, null))
        assertEquals(UpdateCardPhase.FAILED, failed?.phase)
        assertEquals("reason", failed?.failed)
        for (s in listOf(UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate(1))) assertNull(Fold().next(s))
    }

    @Test
    fun percentAtZeroAndAtTheTotal() {
        val f = Fold()
        f.next(UpdateState.Available(manifest, apk))
        assertEquals(0f, f.next(UpdateState.Downloading("1.0.5", 0, 10))?.progress)
        assertEquals(1f, f.next(UpdateState.Downloading("1.0.5", 10, 10))?.progress)
        assertEquals(0f, f.next(UpdateState.Downloading("1.0.5", 0, 0))?.progress, "no total: 0, not NaN")
    }

    @Test
    fun verifyingKeepsVersionAndNotesWithoutABar() {
        val f = Fold()
        f.next(UpdateState.Available(manifest, apk))
        f.next(UpdateState.Downloading("1.0.5", 10, 10))
        val v = assertNotNull(f.next(UpdateState.Verifying("1.0.5")))
        assertEquals("1.0.5", v.versionName)
        assertEquals("Fixes catch-up", v.notes)
        assertNull(v.progress)
        assertTrue(v.phase.busy)
    }

    @Test
    fun notInstalledBringsTheButtonsBackWithUpdateFocused() {
        val offer = assertNotNull(UpdateCardModel.baseCard(UpdateState.Available(manifest, apk), null, failText)).copy(focused = 2)
        val installing = assertNotNull(UpdateCardModel.baseCard(UpdateState.Installing, offer, failText))
        val n = assertNotNull(UpdateCardModel.baseCard(UpdateState.NotInstalled("1.0.5"), installing, failText))
        assertEquals(0, n.focused)
        assertEquals(false, n.phase.busy)
        assertEquals("Fixes catch-up", n.notes)
        // Within the phase the focus is kept.
        assertEquals(1, UpdateCardModel.baseCard(UpdateState.NotInstalled("1.0.5"), n.copy(focused = 1), failText)?.focused)
    }

    @Test
    fun failedAfterNotInstalledKeepsTheReason() {
        val f = Fold()
        f.next(UpdateState.Available(manifest, apk))
        f.next(UpdateState.NotInstalled("1.0.5"))
        val failed = assertNotNull(f.next(UpdateState.Failed(UpdateFailure.INSTALL_FAILED, null)))
        assertEquals("reason", failed.failed)
        assertEquals("1.0.5", failed.versionName)
    }

    @Test
    fun busyKeysBackHidesEverythingElseIsTheGuides() {
        for (phase in listOf(UpdateCardPhase.DOWNLOADING, UpdateCardPhase.VERIFYING, UpdateCardPhase.OPENING_INSTALLER)) {
            val c = UpdateCardState("1.0.5", "n", phase = phase)
            assertEquals(UpdateAction.LATER, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(c, BoxAction.BACK)).action, "$phase")
            for (a in listOf(BoxAction.LEFT, BoxAction.RIGHT, BoxAction.UP, BoxAction.DOWN, BoxAction.SELECT)) {
                assertIs<UpdateCardModel.KeyResult.NotMine>(UpdateCardModel.onAction(c, a), "$phase $a")
            }
        }
        // Phases with buttons keep the usual keys.
        val n = UpdateCardState("1.0.5", "n", phase = UpdateCardPhase.NOT_INSTALLED)
        assertEquals(UpdateAction.UPDATE, assertIs<UpdateCardModel.KeyResult.Pressed>(UpdateCardModel.onAction(n, BoxAction.SELECT)).action)
    }

    /** Review S2: BACK during the download → no card for the rest of it → the card on a failure (with its version). */
    @Test
    fun backDuringTheDownloadHidesTheCardUntilAFailure() {
        val f = Fold(failedText = failText)
        assertNotNull(f.next(UpdateState.Available(manifest, apk)))
        assertNotNull(f.next(UpdateState.Downloading("1.0.5", 0, 10)))
        f.later = true // BACK
        assertNull(f.next(UpdateState.Downloading("1.0.5", 3, 10)))
        assertNull(f.next(UpdateState.Downloading("1.0.5", 10, 10)))
        assertNull(f.next(UpdateState.Verifying("1.0.5")))
        val failed = assertNotNull(f.next(UpdateState.Failed(UpdateFailure.HASH_MISMATCH, null)))
        assertEquals("1.0.5", failed.versionName)
        assertEquals("Fixes catch-up", failed.notes)
    }

    @Test
    fun laterHidesTheInstallerPhasesButNotNotInstalled() {
        val f = Fold(later = true)
        assertNull(f.next(UpdateState.Available(manifest, apk)))
        assertNull(f.next(UpdateState.ReadyToInstall("1.0.5")))
        assertNull(f.next(UpdateState.Installing))
        assertEquals(UpdateCardPhase.NOT_INSTALLED, f.next(UpdateState.NotInstalled("1.0.5"))?.phase)
    }

    /** Code review S3: Later / BACK during the download, then the unknown-sources screen: the hint shows on return. */
    @Test
    fun needsPermissionShowsEvenAfterLater() {
        val f = Fold()
        f.next(UpdateState.Available(manifest, apk))
        f.next(UpdateState.Downloading("1.0.5", 1, 10))
        f.later = true
        assertNull(f.next(UpdateState.Verifying("1.0.5")))
        val p = assertNotNull(f.next(UpdateState.NeedsPermission("1.0.5")))
        assertEquals(UpdateCardPhase.NEEDS_PERMISSION, p.phase)
        assertEquals("Fixes catch-up", p.notes)
    }

    @Test
    fun aCheckFailureAfterAnEarlierCardShowsNoCard() {
        val f = Fold()
        f.next(UpdateState.Available(manifest, apk))
        f.next(UpdateState.Checking) // a new manual check
        assertNull(f.next(UpdateState.Failed(UpdateFailure.NETWORK, null)))
    }
}
