package app.thebox.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.source.PlaylistType
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.di.WallClock
import app.thebox.data.security.KeystoreAeadProvider
import app.thebox.data.security.TinkCredentialStore
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * I-2 (§5.7): TinkCredentialStore with the real Android Keystore. Round trip; then the master key alias is
 * deleted (what a Keystore reset does) → KEY_INVALIDATED, every credential row wiped, `needs_reauth` set.
 */
@RunWith(AndroidJUnit4::class)
class I2KeystoreCredentialTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roundTripThenKeyLoss() = runBlocking {
        context.deleteDatabase("i2.db")
        val db = LibraryDatabase.build(context, Dispatchers.IO, BundledSQLiteDriver(), "i2.db")
        val provider = KeystoreAeadProvider(context)
        provider.reset()
        try {
            val store = TinkCredentialStore(provider, db.credentialDao(), db.playlistDao(), Dispatchers.IO, WallClock.SYSTEM)
            val p = db.playlistDao().insert(PlaylistEntity(name = "P", type = PlaylistType.XTREAM, credentialRef = "cred-x", displaySource = "h", sortIndex = 0, createdAt = 1))
            val secret = PlaylistSecret(listOf("http://panel"), "user", "pass")
            assertEquals(Outcome.Ok(Unit), store.put("cred-x", secret))
            assertEquals(Outcome.Ok(secret), store.get("cred-x"))

            // Simulate a Keystore reset: the master key disappears, the wrapped keyset stays in prefs.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreAeadProvider.MASTER_KEY_ALIAS)
            val fresh = TinkCredentialStore(KeystoreAeadProvider(context), db.credentialDao(), db.playlistDao(), Dispatchers.IO, WallClock.SYSTEM)
            val r = fresh.get("cred-x")
            assertEquals(Outcome.Err(SourceError.Credentials(CredentialFailure.KEY_INVALIDATED)), r)
            assertEquals(0, db.credentialDao().count())
            assertTrue(db.playlistDao().get(p)!!.needsReauth)
        } finally {
            provider.reset()
            db.close()
            context.deleteDatabase("i2.db")
        }
    }
}
