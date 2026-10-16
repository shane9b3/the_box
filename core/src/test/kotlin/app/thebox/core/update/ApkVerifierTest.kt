package app.thebox.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * docs/design/m5-setup-settings.md §3.12.3, §6.1 WP-E. `:app` has no Robolectric, so the rules run here on
 * [ArchiveFacts] fixtures (the "seam with fixtures" of §6.1); the platform extraction (`getPackageArchiveInfo`) is
 * exercised on the emulator by I-M5-19.
 */
class ApkVerifierTest {
    private val ours = "AB".repeat(32)
    private val other = "CD".repeat(32)
    private val manifest = UpdateManifest(1, 1, "app.thebox", "stable", 11, "1.0.11", 25, "", "", emptyList(), listOf(ours))

    private fun verify(facts: ArchiveFacts?, installed: List<String> = listOf(ours), sdk: Int = 30) = ApkVerifier.verifyArchive(facts, manifest, installed, sdk)

    @Test
    fun matchingArchivePasses() {
        assertEquals(ApkVerdict.Ok(certChecked = true), verify(ArchiveFacts("app.thebox", 11, listOf(ours))))
        // Colon form (keytool) is the same digest.
        assertEquals(ApkVerdict.Ok(true), verify(ArchiveFacts("app.thebox", 11, listOf(ours.chunked(2).joinToString(":").lowercase()))))
    }

    @Test
    fun unreadableArchiveIsWrongPackage() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, assertIs<ApkVerdict.Rejected>(verify(null)).failure)
        assertEquals(UpdateFailure.WRONG_PACKAGE, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts(null, null, null))).failure)
    }

    @Test
    fun otherPackageIsWrongPackage() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts("app.thebox.debug", 11, listOf(ours)))).failure)
    }

    @Test
    fun versionCodeMustBeTheManifests() {
        assertEquals(UpdateFailure.WRONG_PACKAGE, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts("app.thebox", 10, listOf(ours)))).failure)
    }

    @Test
    fun signerNotInTheManifestIsWrongCert() {
        assertEquals(UpdateFailure.WRONG_CERT, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts("app.thebox", 11, listOf(other)))).failure)
    }

    @Test
    fun signerMustAlsoBeTheInstalledAppsSigner() {
        val m = manifest.copy(signingCertSha256 = listOf(ours, other))
        val v = ApkVerifier.verifyArchive(ArchiveFacts("app.thebox", 11, listOf(other)), m, listOf(ours), 30)
        assertEquals(UpdateFailure.WRONG_CERT, assertIs<ApkVerdict.Rejected>(v).failure)
    }

    @Test
    fun api27WithNullSignaturesSkipsTheCertCheckInsteadOfWrongCert() {
        // S7: GET_SIGNATURES may return nothing for an archive on API 25–27; the manifest's SHA-256 authenticates the bytes.
        assertEquals(ApkVerdict.Ok(certChecked = false), verify(ArchiveFacts("app.thebox", 11, null), sdk = 27))
        assertEquals(ApkVerdict.Ok(certChecked = false), verify(ArchiveFacts("app.thebox", 11, emptyList()), sdk = 25))
    }

    @Test
    fun api28WithNoSignersIsWrongCert() {
        assertEquals(UpdateFailure.WRONG_CERT, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts("app.thebox", 11, null), sdk = 28)).failure)
    }

    @Test
    fun api27StillChecksSignersWhenTheyArePresent() {
        assertEquals(UpdateFailure.WRONG_CERT, assertIs<ApkVerdict.Rejected>(verify(ArchiveFacts("app.thebox", 11, listOf(other)), sdk = 25)).failure)
    }

    @Test
    fun downloadSizeAndHash() {
        val bytes = "apk bytes".encodeToByteArray()
        val sha = Sha256Counter.of(bytes)
        assertNull(ApkVerifier.verifyDownload(sha.lowercase(), bytes.size.toLong(), sha, bytes.size.toLong()))
        assertEquals(UpdateFailure.HASH_MISMATCH, assertIs<ApkVerdict.Rejected>(ApkVerifier.verifyDownload(sha, 10, sha, 9)).failure)
        assertEquals(UpdateFailure.HASH_MISMATCH, assertIs<ApkVerdict.Rejected>(ApkVerifier.verifyDownload("00".repeat(32), 9, sha, 9)).failure)
    }

    @Test
    fun streamingHashEqualsTheOneShotHash() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val (hex, n) = Sha256Counter.of(bytes.inputStream())
        assertEquals(Sha256Counter.of(bytes), hex)
        assertEquals(200_000L, n)
    }
}
