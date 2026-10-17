package app.thebox.core.backup

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** docs/design/m5-setup-settings.md §3.10.3, §6.1 WP-D `BackupCryptoTest` (lead ruling S15: the `Mac` loop). */
class BackupCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val header = """{"format":"thebox-backup","schema":1}""".encodeToByteArray()
    private val pass = "correct horse".toCharArray()

    @Test
    fun rfc7914Vector1() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(BackupCrypto.pbkdf2("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)),
        )
    }

    @Test
    fun rfc7914Vector2() {
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(BackupCrypto.pbkdf2("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64)),
        )
    }

    @Test
    fun utf8OfThePassphraseMatchesStringEncoding() {
        val p = "pässwörd ✓".toCharArray()
        assertContentEquals("pässwörd ✓".encodeToByteArray(), BackupCrypto.utf8(p))
    }

    @Test
    fun roundTripStoresKdfAndIterations() {
        val s = BackupCrypto.seal("secret".encodeToByteArray(), pass, header, iterations = 1_000)
        assertEquals(BACKUP_KDF_NAME, s.kdf)
        assertEquals(1_000, s.iterations)
        assertEquals("secret", BackupCrypto.open(s, pass, header).decodeToString())
        // Fresh salt and nonce each time.
        val again = BackupCrypto.seal("secret".encodeToByteArray(), pass, header, iterations = 1_000)
        assertNotEquals(s.salt, again.salt)
        assertNotEquals(s.nonce, again.nonce)
    }

    @Test
    fun defaultIterationsAreTheBenchmarkedCount() {
        val s = BackupCrypto.seal(ByteArray(1), pass, header)
        assertEquals(BACKUP_KDF_ITERATIONS, s.iterations)
    }

    @Test
    fun wrongPassphraseFailsTheTag() {
        val s = BackupCrypto.seal("secret".encodeToByteArray(), pass, header, iterations = 100)
        val e = assertFailsWith<BackupCryptoException> { BackupCrypto.open(s, "wrong horse".toCharArray(), header) }
        assertEquals(RestoreFailure.WRONG_PASSPHRASE, e.failure)
    }

    @Test
    fun tamperedCiphertextOrHeaderFails() {
        val s = BackupCrypto.seal("secret".encodeToByteArray(), pass, header, iterations = 100)
        val ct = Base64.Default.decode(s.ciphertext)
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertFailsWith<BackupCryptoException> { BackupCrypto.open(s.copy(ciphertext = Base64.Default.encode(ct)), pass, header) }
        val otherHeader = """{"format":"thebox-backup","schema":1,"x":1}""".encodeToByteArray()
        assertEquals(RestoreFailure.WRONG_PASSPHRASE, assertFailsWith<BackupCryptoException> { BackupCrypto.open(s, pass, otherHeader) }.failure)
    }

    @Test
    fun malformedRecordsAreCorruptNotWrongPassphrase() {
        val s = BackupCrypto.seal("x".encodeToByteArray(), pass, header, iterations = 10)
        assertEquals(RestoreFailure.CORRUPT_SECRETS, assertFailsWith<BackupCryptoException> { BackupCrypto.open(s.copy(kdf = "scrypt"), pass, header) }.failure)
        assertEquals(RestoreFailure.CORRUPT_SECRETS, assertFailsWith<BackupCryptoException> { BackupCrypto.open(s.copy(salt = "!!"), pass, header) }.failure)
        assertEquals(RestoreFailure.CORRUPT_SECRETS, assertFailsWith<BackupCryptoException> { BackupCrypto.open(s.copy(iterations = 0), pass, header) }.failure)
        assertEquals(
            RestoreFailure.CORRUPT_SECRETS,
            assertFailsWith<BackupCryptoException> { BackupCrypto.open(s.copy(iterations = BACKUP_KDF_MAX_ITERATIONS + 1), pass, header) }.failure,
        )
    }

    /** C-L9 = D-L10 (M5 code review 2026-10-16): a file asking for more than 10 × the default iterations is refused. */
    @Test
    fun kdfIterationsAreCappedAtTenTimesTheDefault() {
        assertEquals(10 * BACKUP_KDF_ITERATIONS, BACKUP_KDF_MAX_ITERATIONS)
        val header = "h".encodeToByteArray()
        val sealed = BackupCrypto.seal("x".encodeToByteArray(), "fake-passphrase".toCharArray(), header, iterations = 10)
        val e = assertFailsWith<BackupCryptoException> { BackupCrypto.open(sealed.copy(iterations = BACKUP_KDF_MAX_ITERATIONS + 1), "fake-passphrase".toCharArray(), header) }
        assertEquals(RestoreFailure.CORRUPT_SECRETS, e.failure)
    }

    /** C-L9: a cancelled derivation stops inside the loop (checked every KDF_CHECK_EVERY iterations). */
    @Test
    fun derivationStopsWhenCancelled() {
        var asked = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            BackupCrypto.pbkdf2("p".encodeToByteArray(), ByteArray(16), 1_000_000, 32) { asked++; asked < 3 }
        }
        assertEquals(3, asked, "stopped at the third check, not after a million iterations")
    }
}
