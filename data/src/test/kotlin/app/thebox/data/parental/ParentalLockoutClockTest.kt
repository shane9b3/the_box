package app.thebox.data.parental

import android.content.Context
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.PinCheck
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.security.KeyResetFlag
import app.thebox.data.testutil.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security review 2026-11-09 F7 (fixed 2026-11-11): the PIN lockout through the real store and the real
 * `parental_attempts` SharedPreferences. The wall clock never shortens a wait; a reboot restarts it in full at the same
 * step; process death within a boot continues it; a pre-F7 record decodes and never unlocks earlier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalLockoutClockTest {
    private val blobs = object : SecureBlobStore {
        val map = HashMap<String, ByteArray>()
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> { map[ref] = bytes.copyOf(); return Outcome.Ok(Unit) }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> =
            map[ref]?.let { Outcome.Ok(it.copyOf()) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    }
    private val flag = object : KeyResetFlag {
        override fun markPinLost() = Unit
        override fun pinLost() = false
        override fun clearPinLost() = Unit
    }

    /** Elapsed realtime and the boot count as the device would report them. */
    private var elapsed = 50_000L
    private var boot: Int? = 4
    private val clock = LockoutClock { w -> LockoutPolicy.Clock(elapsed, w, boot) }
    private fun reboot(uptime: Long = 1_000L) { elapsed = uptime; boot = boot?.plus(1) }

    private val prefs get() = TestDb.context.getSharedPreferences(PrefsAttemptsStore.PREFS, Context.MODE_PRIVATE)
    /** A new process: a fresh store over the same SharedPreferences file. */
    private fun process() = ParentalPinStoreImpl(blobs, PrefsAttemptsStore(TestDb.context), clock, flag, Dispatchers.Unconfined)
        .also { it.iterations = 100 }

    private val right get() = charArrayOf('2', '5', '8', '0')
    private val wrong get() = charArrayOf('1', '1', '1', '1')
    private val wall = 1_760_000_000_000L

    @Before fun setUp() {
        prefs.edit().clear().commit()
        runBlocking { process().set(right) }
    }

    private fun lockAtStep3(): ParentalPinStoreImpl = runBlocking {
        val s = process()
        assertEquals(PinCheck.Wrong(2), s.verify(wrong, wall))
        assertEquals(PinCheck.Wrong(1), s.verify(wrong, wall))
        assertEquals(PinCheck.LockedOut(wall + 30_000), s.verify(wrong, wall))
        s
    }

    @Test fun wallClockMovedForwardOrBackDoesNotChangeTheWait() = runBlocking {
        val s = lockAtStep3()
        elapsed += 10_000
        val forward = wall + 365L * 86_400_000L
        assertEquals(forward + 20_000, s.lockedUntil(forward))
        assertEquals("the right PIN is refused while locked", PinCheck.LockedOut(forward + 20_000), s.verify(right, forward))
        val back = wall - 365L * 86_400_000L
        assertEquals(back + 20_000, s.lockedUntil(back))
        elapsed += 20_000
        assertNull("the wait ends normally on elapsed realtime", s.lockedUntil(back))
        assertEquals(PinCheck.Ok, s.verify(right, back))
    }

    @Test fun processDeathWithinOneBootContinuesTheWait() = runBlocking {
        lockAtStep3()
        elapsed += 12_000
        assertEquals(wall + 18_000, process().lockedUntil(wall))
        elapsed += 18_000
        assertNull(process().lockedUntil(wall))
        assertEquals("served: cleared, counter kept", 3, PrefsAttemptsStore(TestDb.context).read().failures)
        assertEquals(0L, PrefsAttemptsStore(TestDb.context).read().waitMs)
        assertEquals(PinCheck.Wrong(2), process().verify(wrong, wall))
    }

    @Test fun rebootRestartsTheFullWaitAtTheSameStep() = runBlocking {
        lockAtStep3()
        elapsed += 29_000 // one second left
        reboot()
        val later = wall + 86_400_000L // and the clock set a day ahead
        assertEquals("the full step again", later + 30_000, process().lockedUntil(later))
        val stored = PrefsAttemptsStore(TestDb.context).read()
        assertEquals(LockoutPolicy.Attempts(3, 1_000, 5, 30_000), stored)
        elapsed += 30_000
        assertNull(process().lockedUntil(later))
        // The ladder step survived: three more wrong PINs give the 2-minute step.
        val s = process()
        s.verify(wrong, later); s.verify(wrong, later)
        assertEquals(PinCheck.LockedOut(later + 120_000), s.verify(wrong, later))
    }

    @Test fun aServedWaitIsNotRestartedByALaterReboot() = runBlocking {
        lockAtStep3()
        elapsed += 31_000
        process().settleLockout(wall) // the session does this on start and every return to the foreground
        reboot()
        assertNull(process().lockedUntil(wall))
        assertEquals(PinCheck.Wrong(2), process().verify(wrong, wall))
    }

    @Test fun unknownBootCountStillRestartsAfterARebootAndKeepsTheWaitOtherwise() = runBlocking {
        boot = null
        lockAtStep3()
        elapsed += 10_000
        assertEquals("the wall clock moved forward changes nothing", wall + 999_999_999 + 20_000, process().lockedUntil(wall + 999_999_999))
        elapsed = 500 // rebooted: elapsed went back
        assertEquals(wall + 30_000, process().lockedUntil(wall))
    }

    /** A record written before F7 (with `locked_wall`) decodes; its wait never ends earlier than before. */
    @Test fun oldFormatRecordDecodesAndNeverUnlocksEarlier() = runBlocking {
        prefs.edit()
            .putInt("failures", 6).putLong("locked_elapsed", 40_000).putLong("locked_wall", wall - 10_000)
            .putInt("locked_boot", 4).putLong("wait_ms", 120_000).commit()
        val store = PrefsAttemptsStore(TestDb.context)
        assertEquals(LockoutPolicy.Attempts(6, 40_000, 4, 120_000), store.read())
        // Same boot: continues on elapsed (10 s passed), whatever the wall clock.
        assertEquals(wall + 86_400_000L + 110_000, process().lockedUntil(wall + 86_400_000L))
        // Another boot: the old code credited wall time (a day ahead would have ended it); now the full step.
        reboot()
        assertEquals(wall + 86_400_000L + 120_000, process().lockedUntil(wall + 86_400_000L))
        assertFalse("the legacy key is dropped on the first write", prefs.contains(PrefsAttemptsStore.K_WALL_LEGACY))
        assertEquals(6, store.read().failures)
    }

    /** Damaged values decode to safe defaults: no exception, no endless wait, no lost counter where readable. */
    @Test fun damagedRecordDecodesSafely() = runBlocking {
        prefs.edit()
            .putInt("failures", 9).putString("locked_elapsed", "x").putInt("locked_boot", 4)
            .putLong("wait_ms", Long.MAX_VALUE).commit()
        val a = PrefsAttemptsStore(TestDb.context).read()
        assertEquals(9, a.failures)
        assertNull(a.lockedAtElapsedMs)
        assertEquals("clamped to the top step", LockoutPolicy.WAIT_MAX_MS, a.waitMs)
        // No anchor: the wait restarts (top step at most), never ends at once.
        assertEquals(wall + LockoutPolicy.WAIT_MAX_MS, process().lockedUntil(wall))
        assertTrue(prefs.getLong("locked_elapsed", -1) >= 0)
    }
}
