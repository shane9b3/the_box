package app.thebox.data.security

import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException

/** D-S3 (M5 code review 2026-10-16, adopted from the data reviewer): "PIN lost" only when a PIN existed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TinkCredentialStoreReviewFixTest {
    private lateinit var db: LibraryDatabase
    private val clock = FakeClock()
    private val flag = object : KeyResetFlag {
        var lost = false
        override fun markPinLost() { lost = true }
        override fun pinLost() = lost
        override fun clearPinLost() { lost = false }
    }
    private val provider = object : AeadProvider {
        var fail = false
        override fun aead(): Aead { if (fail) throw UnrecoverableKeyException("gone"); return XorAead() }
        override fun reset() = Unit
        override val usingKeystore: Boolean = true
    }

    @Before fun setUp() { db = TestDb.create() }

    @After fun tearDown() { db.close() }

    private fun store() = TinkCredentialStore(provider, db.credentialDao(), db.playlistDao(), Dispatchers.IO, clock, flag)

    /** D-S3: a household that never set a PIN keeps its access after a Keystore reset (no FILTERED_NO_PIN, no false notice). */
    @Test
    fun keyResetWithoutAPinDoesNotClaimAPinWasLost() = runBlocking {
        val store = store()
        store.put("cred-a", PlaylistSecret(listOf("http://h/list.m3u"), null, null))
        provider.fail = true
        store.get("cred-a") // permanent loss → wipe
        assertFalse("no parental-pin row existed, yet the flag says a PIN was lost", flag.lost)
    }

    /** D-S3: with a PIN row the flag is still set (and set before the rows go, S8). */
    @Test
    fun keyResetWithAPinMarksThePinLost() = runBlocking {
        val store = store()
        store.put("cred-a", PlaylistSecret(listOf("http://h/list.m3u"), null, null))
        store.putBlob(SecureBlobStore.REF_PARENTAL_PIN, byteArrayOf(1, 2, 3))
        provider.fail = true
        store.get("cred-a")
        assertTrue(flag.lost)
    }

    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (ciphertext.isEmpty()) throw GeneralSecurityException("empty")
            return ByteArray(ciphertext.size) { (ciphertext[it].toInt() xor 0x5A).toByte() }
        }
    }
}
