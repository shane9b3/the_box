package app.thebox.data.diag

import app.thebox.core.update.SignatureVerifier
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * Ed25519 verification of the update manifest (docs/design/m5-setup-settings.md §3.12.2 step 1, §9 F11) with Tink's
 * pure-Java `Ed25519Verify`: no platform provider needed, so it works on API 25 where Conscrypt has no Ed25519.
 * Lives in `:data` because Tink is a `:data` dependency; the `:app` updater gets it through `DiagModule`.
 * `TinkEd25519VerifierTest` runs the RFC 8032 §7.1 vectors.
 */
class TinkEd25519Verifier : SignatureVerifier {
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519Verify.PUBLIC_KEY_LEN || signature.size != Ed25519Verify.SIGNATURE_LEN) return false
        return try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }
}
