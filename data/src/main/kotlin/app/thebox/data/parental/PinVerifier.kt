package app.thebox.data.parental

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 as the RFC 8018 §5.2 loop over `Mac("HmacSHA256")` (docs/design/m5-setup-settings.md §3.9.6; lead
 * ruling S15: the platform `Mac`, no hand-written HMAC; `SecretKeyFactory("PBKDF2WithHmacSHA256")` is absent on API 25,
 * F10). Tested against the RFC 7914 §11 vectors.
 */
object Pbkdf2 {
    private const val H_LEN = 32

    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1 && dkLen >= 1) { "bad PBKDF2 parameters" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val blocks = (dkLen + H_LEN - 1) / H_LEN
        val out = ByteArray(dkLen)
        val u = ByteArray(H_LEN)
        val t = ByteArray(H_LEN)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (j in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until H_LEN) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (i - 1) * H_LEN
            t.copyInto(out, off, 0, minOf(H_LEN, dkLen - off))
        }
        u.fill(0)
        t.fill(0)
        return out
    }
}

/**
 * The PIN verifier blob (§3.9.6): version (1 byte) ‖ salt (16) ‖ iterations (4, big-endian) ‖
 * PBKDF2-HMAC-SHA256(PIN, salt, iterations) (32), stored through `SecureBlobStore` (AEAD, AD = ref). The iteration count
 * travels with the blob, so raising `PIN_KDF_ITERATIONS` later needs no migration.
 */
object PinVerifier {
    const val VERSION: Byte = 1
    const val SALT_LEN = 16
    const val HASH_LEN = 32
    const val BLOB_LEN = 1 + SALT_LEN + 4 + HASH_LEN

    class Parsed(val salt: ByteArray, val iterations: Int, val hash: ByteArray)

    private val random = SecureRandom()

    /** A new verifier for [pin] (exactly 4 digits). The caller zeroes [pin]. */
    fun create(pin: CharArray, iterations: Int): ByteArray {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val pw = bytesOf(pin)
        try {
            val hash = Pbkdf2.hmacSha256(pw, salt, iterations, HASH_LEN)
            return ByteBuffer.allocate(BLOB_LEN).put(VERSION).put(salt).putInt(iterations).put(hash).array().also { hash.fill(0) }
        } finally {
            pw.fill(0)
        }
    }

    fun parse(blob: ByteArray): Parsed? {
        if (blob.size != BLOB_LEN || blob[0] != VERSION) return null
        val b = ByteBuffer.wrap(blob)
        b.get()
        val salt = ByteArray(SALT_LEN).also { b.get(it) }
        val iterations = b.int
        if (iterations < 1) return null
        val hash = ByteArray(HASH_LEN).also { b.get(it) }
        return Parsed(salt, iterations, hash)
    }

    /** Constant-time comparison of [pin]'s derivation with [v] (`MessageDigest.isEqual`). The caller zeroes [pin]. */
    fun matches(pin: CharArray, v: Parsed): Boolean {
        val pw = bytesOf(pin)
        try {
            val hash = Pbkdf2.hmacSha256(pw, v.salt, v.iterations, v.hash.size)
            val ok = MessageDigest.isEqual(hash, v.hash)
            hash.fill(0)
            return ok
        } finally {
            pw.fill(0)
        }
    }

    /** True for exactly four ASCII digits. */
    fun valid(pin: CharArray): Boolean = pin.size == 4 && pin.all { it in '0'..'9' }

    private fun bytesOf(pin: CharArray): ByteArray = ByteArray(pin.size) { pin[it].code.toByte() }
}
