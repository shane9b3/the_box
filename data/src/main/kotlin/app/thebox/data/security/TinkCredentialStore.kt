package app.thebox.data.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.Converters
import app.thebox.data.db.dao.CredentialDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.entity.CredentialEntity
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tink AEAD + Keystore credential store (docs/design/m1-data-layer.md §3.9). One blob per playlist or EPG
 * source: `AEAD(JSON(PlaylistSecret))` with associated data = ref.
 *
 * Failure policy (S16):
 * - **Permanent** key loss only (KeyPermanentlyInvalidatedException, UnrecoverableKeyException, or the
 *   keyset itself failing with AEADBadTagException): delete the keyset and every credential row, flag every
 *   playlist `needs_reauth`, return KEY_INVALIDATED.
 * - Anything else while building the primitive: retry once after 1 s, then UNAVAILABLE with nothing deleted.
 * - One row that does not decrypt: CORRUPT for that ref.
 */
@Singleton
class TinkCredentialStore @Inject constructor(
    private val provider: AeadProvider,
    private val credentialDao: CredentialDao,
    private val playlistDao: PlaylistDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val clock: WallClock,
) : CredentialStore {
    /** Test hook: delay before the single retry of a transient Keystore failure. */
    internal var retryDelayMs: Long = 1_000

    override fun newRef(): String = "cred-" + UUID.randomUUID()

    override suspend fun put(ref: String, secret: PlaylistSecret): Outcome<Unit> = withContext(io) {
        when (val a = obtainAead()) {
            is Outcome.Err -> a
            is Outcome.Ok -> try {
                val plain = Converters.json.encodeToString(PlaylistSecret.serializer(), secret).encodeToByteArray()
                val ct = a.value.encrypt(plain, ref.encodeToByteArray())
                credentialDao.upsert(CredentialEntity(ref, ct, KEY_VERSION, clock.now()))
                register(secret)
                Outcome.Ok(Unit)
            } catch (e: GeneralSecurityException) {
                BoxLog.w(TAG, "encrypt failed", e)
                Outcome.Err(SourceError.Credentials(CredentialFailure.UNAVAILABLE))
            }
        }
    }

    override suspend fun get(ref: String): Outcome<PlaylistSecret> = withContext(io) {
        val row = credentialDao.get(ref) ?: return@withContext Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        when (val a = obtainAead()) {
            is Outcome.Err -> a
            is Outcome.Ok -> try {
                val plain = a.value.decrypt(row.ciphertext, ref.encodeToByteArray())
                val secret = Converters.json.decodeFromString(PlaylistSecret.serializer(), plain.decodeToString())
                register(secret)
                Outcome.Ok(secret)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "credential $ref does not decrypt: ${e.javaClass.simpleName}")
                Outcome.Err(SourceError.Credentials(CredentialFailure.CORRUPT))
            }
        }
    }

    override suspend fun delete(ref: String) = withContext(io) { credentialDao.delete(ref) }

    /** Diagnostics: false when the keyset is not Keystore-wrapped. */
    val usingKeystore: Boolean get() = provider.usingKeystore

    private suspend fun obtainAead(): Outcome<Aead> {
        var attempt = 0
        while (true) {
            try {
                return Outcome.Ok(provider.aead())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isPermanent(e)) {
                    BoxLog.e(TAG, "credential key permanently lost; wiping credentials", e)
                    wipe()
                    return Outcome.Err(SourceError.Credentials(CredentialFailure.KEY_INVALIDATED))
                }
                if (attempt++ >= 1) {
                    BoxLog.w(TAG, "keystore unavailable: ${e.javaClass.simpleName}")
                    return Outcome.Err(SourceError.Credentials(CredentialFailure.UNAVAILABLE))
                }
                delay(retryDelayMs)
            }
        }
    }

    private suspend fun wipe() {
        runCatching { provider.reset() }.onFailure { BoxLog.w(TAG, "keyset reset failed", it) }
        credentialDao.deleteAll()
        playlistDao.setNeedsReauthAll()
    }

    private fun register(secret: PlaylistSecret) {
        Redact.registerSecret(secret.username)
        Redact.registerSecret(secret.password)
        for (v in secret.headers.values) Redact.registerSecret(v)
    }

    companion object {
        private const val TAG = "TinkCredentialStore"
        const val KEY_VERSION = 1

        fun isPermanent(e: Throwable): Boolean {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth++ < 10) {
                if (t is KeyPermanentlyInvalidatedException || t is UnrecoverableKeyException || t is AEADBadTagException) return true
                t = t.cause
            }
            return false
        }
    }
}
