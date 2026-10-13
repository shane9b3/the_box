package app.thebox.data.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/** Source of the credential AEAD primitive; a seam so the store's failure handling is testable on the JVM. */
interface AeadProvider {
    /** Builds (or returns the cached) primitive. Throws on Keystore/keyset failures. */
    fun aead(): Aead

    /** Deletes the keyset after a permanent key loss; the next [aead] creates a fresh one. */
    fun reset()

    /** False when Tink fell back to an unencrypted keyset (Fire OS Keystore flakiness): diagnostics only. */
    val usingKeystore: Boolean
}

/** §3.9: AES256-GCM keyset in shared prefs, wrapped by an Android Keystore master key. */
class KeystoreAeadProvider(private val context: Context) : AeadProvider {
    @Volatile private var cached: Aead? = null
    @Volatile private var keystore = true

    override val usingKeystore: Boolean get() = keystore

    @Synchronized
    override fun aead(): Aead {
        cached?.let { return it }
        AeadConfig.register()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        keystore = manager.isUsingKeystore
        val aead = manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        cached = aead
        return aead
    }

    @Synchronized
    override fun reset() {
        cached = null
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().remove(KEYSET_NAME).commit()
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    companion object {
        const val KEYSET_NAME = "credentials_keyset"
        const val PREF_FILE = "thebox_keysets"
        const val MASTER_KEY_ALIAS = "thebox_credentials_master"
        const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
    }
}
