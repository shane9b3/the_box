package app.thebox.core.update

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * JDK Ed25519 (Java 15+) for the JVM tests: signs test manifests and verifies through the [SignatureVerifier] seam. The
 * app verifies with Tink's `Ed25519Verify` (`:data`, `TinkEd25519VerifierTest` runs the same RFC 8032 vectors there).
 */
object Ed25519TestKeys {
    private val X509_PREFIX = hex("302a300506032b6570032100")

    val verifier = SignatureVerifier { publicKey, message, signature ->
        // The JDK throws on an undecodable point; a verifier answers false (Tink does).
        try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    class Pair(val keys: KeyPair) {
        val publicRaw: ByteArray get() = keys.public.encoded.takeLast(32).toByteArray()
        fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(message)
            sign()
        }
    }

    fun generate(): Pair = Pair(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())

    fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
