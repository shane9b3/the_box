package app.thebox.core.backup

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** Why the `Secrets` record could not be opened. */
class BackupCryptoException(val failure: RestoreFailure, message: String) : Exception(message)

/**
 * Logins in a backup (docs/design/m5-setup-settings.md §3.10.3): key = PBKDF2-HMAC-SHA256(passphrase as UTF-8, 16-byte
 * random salt, iterations, 32 bytes), implemented as the RFC 8018 §5.2 loop over `Mac("HmacSHA256")` because the
 * `PBKDF2WithHmacSHA256` key factory is missing on API 25 (F10); lead ruling S15: the `Mac` loop at
 * [BACKUP_KDF_ITERATIONS], no hand-written HMAC in M5. Cipher AES-256-GCM, 12-byte random nonce, 128-bit tag, associated
 * data = the exact bytes of the header line, so a `Secrets` record cannot be moved into another file. The KDF name and
 * the iteration count are stored in the record, so readers follow the file. A wrong passphrase fails the tag check
 * ([RestoreFailure.WRONG_PASSPHRASE]) before anything is written. Key material is zeroed after use.
 */
object BackupCrypto {
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    const val TAG_BITS = 128

    private val b64 = Base64.Default

    /** RFC 8018 PBKDF2 with HMAC-SHA256 as the PRF. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1 && dkLen >= 1)
        val mac = Mac.getInstance("HmacSHA256")
        // An empty password is legal in PBKDF2 but SecretKeySpec refuses an empty key; HMAC pads a short key with zeros.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (j in 1 until iterations) {
                mac.update(u, 0, hLen)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * hLen, 0, minOf(hLen, dkLen - (i - 1) * hLen))
        }
        u.fill(0)
        t.fill(0)
        return out
    }

    /** UTF-8 bytes of [passphrase] without an intermediate String (the caller zeroes the result). */
    fun utf8(passphrase: CharArray): ByteArray {
        val bb: ByteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(passphrase))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pw = utf8(passphrase)
        try {
            return pbkdf2(pw, salt, iterations, KEY_BYTES)
        } finally {
            pw.fill(0)
        }
    }

    /** Encrypts [plaintext] for the file whose header line is [headerLine]. */
    fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
        headerLine: ByteArray,
        iterations: Int = BACKUP_KDF_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): BackupRecord.Secrets {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(headerLine)
            val ct = cipher.doFinal(plaintext)
            return BackupRecord.Secrets(BACKUP_KDF_NAME, iterations, b64.encode(salt), b64.encode(nonce), b64.encode(ct))
        } finally {
            key.fill(0)
        }
    }

    /** Decrypts; [RestoreFailure.WRONG_PASSPHRASE] on a tag failure (wrong passphrase or tampering), CORRUPT_SECRETS otherwise. */
    fun open(secrets: BackupRecord.Secrets, passphrase: CharArray, headerLine: ByteArray): ByteArray {
        if (secrets.kdf != BACKUP_KDF_NAME) throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "unknown kdf")
        if (secrets.iterations !in 1..BACKUP_KDF_MAX_ITERATIONS) throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "iterations out of range")
        val salt: ByteArray
        val nonce: ByteArray
        val ct: ByteArray
        try {
            salt = b64.decode(secrets.salt)
            nonce = b64.decode(secrets.nonce)
            ct = b64.decode(secrets.ciphertext)
        } catch (e: IllegalArgumentException) {
            throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "bad base64")
        }
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES || ct.size < TAG_BITS / 8) {
            throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, "bad sizes")
        }
        val key = deriveKey(passphrase, salt, secrets.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(headerLine)
            return cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw BackupCryptoException(RestoreFailure.WRONG_PASSPHRASE, "tag check failed")
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException(RestoreFailure.CORRUPT_SECRETS, e.javaClass.simpleName)
        } finally {
            key.fill(0)
        }
    }
}
