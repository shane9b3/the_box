package app.thebox.data.security

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.CredentialEntity
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** §3.9 with a fake AEAD on the JVM (the real Keystore is I-2): round trip, AD binding, corrupt row, S16 failure policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TinkCredentialStoreTest {
    /** Not crypto: XOR + an associated-data tag, enough to test binding and corruption. */
    private class FakeAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            tag(associatedData) + ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (ciphertext.size < 4 || !ciphertext.copyOfRange(0, 4).contentEquals(tag(associatedData))) throw GeneralSecurityException("decryption failed")
            return ByteArray(ciphertext.size - 4) { (ciphertext[it + 4].toInt() xor 0x5A).toByte() }
        }

        private fun tag(ad: ByteArray): ByteArray { val h = ad.contentHashCode(); return byteArrayOf((h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte()) }
    }

    private class FakeProvider : AeadProvider {
        var failures = ArrayDeque<Exception>()
        var resets = 0
        var calls = 0
        override fun aead(): Aead { calls++; failures.removeFirstOrNull()?.let { throw it }; return FakeAead() }
        override fun reset() { resets++ }
        override val usingKeystore: Boolean = true
    }

    private lateinit var db: LibraryDatabase
    private val provider = FakeProvider()
    private lateinit var store: TinkCredentialStore

    @Before
    fun setUp() {
        db = TestDb.create()
        store = TinkCredentialStore(provider, db.credentialDao(), db.playlistDao(), Dispatchers.IO, FakeClock()).apply { retryDelayMs = 1 }
    }

    @After fun tearDown() { db.close() }

    private val secret = PlaylistSecret(listOf("http://h/get.php?username=u1&password=pw-xyz"), "u1x", "pw-xyz", mapOf("Cookie" to "tok-123"))

    @Test
    fun roundTripRegistersSecretsForRedaction() = runBlocking {
        val ref = store.newRef()
        assertTrue(ref.startsWith("cred-"))
        assertEquals(Outcome.Ok(Unit), store.put(ref, secret))
        assertEquals(Outcome.Ok(secret), store.get(ref))
        val blob = db.credentialDao().get(ref)!!.ciphertext.decodeToString()
        assertFalse("ciphertext is not the JSON", blob.contains("pw-xyz"))
        assertEquals("x *** y", Redact.text("x pw-xyz y"))
        assertEquals("c=***", Redact.text("c=tok-123"))
        store.delete(ref)
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING)), store.get(ref))
    }

    @Test
    fun associatedDataBindsTheRowToItsRef() = runBlocking {
        store.put("cred-a", secret)
        val stolen = db.credentialDao().get("cred-a")!!
        db.credentialDao().upsert(CredentialEntity("cred-b", stolen.ciphertext, updatedAt = 1))
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.CORRUPT)), store.get("cred-b"))
        db.credentialDao().upsert(CredentialEntity("cred-c", byteArrayOf(1, 2), updatedAt = 1))
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.CORRUPT)), store.get("cred-c"))
        assertEquals(Outcome.Ok(secret), store.get("cred-a"))
    }

    @Test
    fun transientKeystoreFailureRetriesOnceThenUnavailableAndDeletesNothing() = runBlocking {
        // S16
        store.put("cred-a", secret)
        provider.failures += KeyStoreException("busy")
        assertEquals(Outcome.Ok(secret), store.get("cred-a"))
        provider.failures += KeyStoreException("busy"); provider.failures += java.io.IOException("still busy")
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.UNAVAILABLE)), store.get("cred-a"))
        assertEquals(1, db.credentialDao().count())
        assertEquals(0, provider.resets)
    }

    @Test
    fun permanentKeyLossWipesEverythingAndFlagsReauth() = runBlocking {
        val p = db.newPlaylist()
        store.put("cred-a", secret); store.put("cred-b", secret)
        provider.failures += GeneralSecurityException("keyset", AEADBadTagException("bad tag"))
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.KEY_INVALIDATED)), store.get("cred-a"))
        assertEquals(0, db.credentialDao().count())
        assertEquals(1, provider.resets)
        assertTrue(db.playlistDao().get(p)!!.needsReauth)
        provider.failures += UnrecoverableKeyException("gone")
        assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.KEY_INVALIDATED)), store.put("cred-c", secret))
    }

    @Test
    fun permanentClassification() {
        assertTrue(TinkCredentialStore.isPermanent(RuntimeException(UnrecoverableKeyException())))
        assertTrue(TinkCredentialStore.isPermanent(android.security.keystore.KeyPermanentlyInvalidatedException()))
        assertFalse(TinkCredentialStore.isPermanent(KeyStoreException("x")))
        assertFalse(TinkCredentialStore.isPermanent(java.security.ProviderException("x")))
    }
}
