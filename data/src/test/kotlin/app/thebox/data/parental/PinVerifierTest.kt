package app.thebox.data.parental

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.PIN_KDF_ITERATIONS
import app.thebox.core.parental.PinCheck
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.security.KeyResetFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors through the `Mac` loop (S15), the verifier blob and the PIN store (§3.9.4, §3.9.6). */
class PinVerifierTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun rfc7914Vectors() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(Pbkdf2.hmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(Pbkdf2.hmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80_000, 64)),
        )
    }

    @Test fun blobLayoutAndConstantTimeMatch() {
        val blob = PinVerifier.create(charArrayOf('2', '5', '8', '0'), 1_000)
        assertEquals(PinVerifier.BLOB_LEN, blob.size)
        assertEquals(PinVerifier.VERSION, blob[0])
        val v = PinVerifier.parse(blob)!!
        assertEquals(1_000, v.iterations)
        assertTrue(PinVerifier.matches(charArrayOf('2', '5', '8', '0'), v))
        assertFalse(PinVerifier.matches(charArrayOf('2', '5', '8', '1'), v))
        assertNull(PinVerifier.parse(blob.copyOf(10)))
        assertNull(PinVerifier.parse(blob.copyOf().also { it[0] = 9 }))
        // A new salt every time.
        assertFalse(PinVerifier.create(charArrayOf('2', '5', '8', '0'), 1_000).contentEquals(blob))
        assertEquals(10_000, PIN_KDF_ITERATIONS)
    }

    private class Blobs : SecureBlobStore {
        val map = HashMap<String, ByteArray>()
        var failure: CredentialFailure? = null
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> {
            failure?.let { return Outcome.Err(SourceError.Credentials(it)) }
            map[ref] = bytes.copyOf(); return Outcome.Ok(Unit)
        }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> {
            failure?.let { return Outcome.Err(SourceError.Credentials(it)) }
            return map[ref]?.copyOf()?.let { Outcome.Ok(it) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        }
        override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    }

    private class Attempts : AttemptsStore {
        var a = LockoutPolicy.Attempts()
        var writes = 0
        override fun read() = a
        override fun write(a: LockoutPolicy.Attempts) { this.a = a; writes++ }
    }

    private class Flag : KeyResetFlag {
        var lost = false
        override fun markPinLost() { lost = true }
        override fun pinLost() = lost
        override fun clearPinLost() { lost = false }
    }

    private class Clock : LockoutClock {
        var elapsed = 1_000L
        var boot: Int? = 3
        override fun now(wallMs: Long) = LockoutPolicy.Clock(elapsed, wallMs, boot)
    }

    private val blobs = Blobs()
    private val attempts = Attempts()
    private val flag = Flag()
    private val clock = Clock()
    private fun store() = ParentalPinStoreImpl(blobs, attempts, clock, flag, Dispatchers.Unconfined).also { it.iterations = 1_000 }

    @Test fun setVerifyWrongLockoutAndReset() = runBlocking {
        val s = store()
        assertFalse(s.isSet())
        flag.lost = true
        val pin = charArrayOf('2', '5', '8', '0')
        assertEquals(Outcome.Ok(Unit), s.set(pin))
        assertTrue("the caller's array is zeroed", pin.all { it == '\u0000' })
        assertTrue(s.isSet())
        assertFalse("a new PIN clears the key-reset flag", flag.lost)
        assertFalse("the blob is not the PIN", blobs.map.values.single().contentEquals("2580".toByteArray()))
        assertEquals(PinCheck.Ok, s.verify(charArrayOf('2', '5', '8', '0'), 10_000))
        assertEquals(PinCheck.Wrong(2), s.verify(charArrayOf('1', '1', '1', '1'), 10_000))
        assertEquals(PinCheck.Wrong(1), s.verify(charArrayOf('1', '1', '1', '1'), 10_000))
        val locked = s.verify(charArrayOf('1', '1', '1', '1'), 10_000)
        assertEquals(PinCheck.LockedOut(40_000), locked)
        // During the wait even the right PIN is refused, and a "restart" (a new store over the same prefs) keeps it.
        assertEquals(PinCheck.LockedOut(40_000), store().verify(charArrayOf('2', '5', '8', '0'), 10_000))
        assertEquals(40_000L, store().lockedUntil(10_000))
        clock.elapsed += 30_001
        assertNull(store().lockedUntil(40_001))
        assertEquals(PinCheck.Ok, store().verify(charArrayOf('2', '5', '8', '0'), 40_001))
        assertEquals(0, attempts.a.failures)
    }

    @Test fun keystoreFailuresFailClosed() = runBlocking {
        val s = store()
        s.set(charArrayOf('2', '5', '8', '0'))
        blobs.failure = CredentialFailure.UNAVAILABLE
        assertEquals(PinCheck.Unavailable, s.verify(charArrayOf('2', '5', '8', '0'), 1))
        assertTrue("unavailable still counts as set (nothing unlocks)", s.isSet())
        blobs.failure = CredentialFailure.KEY_INVALIDATED
        assertEquals(PinCheck.Unavailable, s.verify(charArrayOf('2', '5', '8', '0'), 1))
        assertFalse("a key reset wiped the PIN", s.isSet())
        blobs.failure = null
        blobs.map[SecureBlobStore.REF_PARENTAL_PIN] = byteArrayOf(1, 2, 3)
        assertEquals("an unreadable verifier fails closed", PinCheck.Unavailable, s.verify(charArrayOf('2', '5', '8', '0'), 1))
    }

    @Test fun refusesAnythingButFourDigitsAndClears() = runBlocking {
        val s = store()
        assertTrue(s.set(charArrayOf('1', '2', '3')) is Outcome.Err)
        assertTrue(s.set(charArrayOf('a', 'b', 'c', 'd')) is Outcome.Err)
        assertFalse(s.isSet())
        s.set(charArrayOf('2', '5', '8', '0'))
        s.clear()
        assertFalse(s.isSet())
        assertEquals(false, s.pinSet.value)
    }

    @Test fun verifierTimingAtTheShippedIterationCount() {
        // The commit-0 benchmark set 10,000 for ≤ 150 ms on the emulator; on the JVM it must be far below that.
        val t0 = System.nanoTime()
        PinVerifier.create(charArrayOf('2', '5', '8', '0'), PIN_KDF_ITERATIONS)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("one derivation took $ms ms", ms < 2_000)
    }
}
