package app.thebox.core.update

import java.io.InputStream
import java.security.MessageDigest

/**
 * What the platform reports for the downloaded archive (`PackageManager.getPackageArchiveInfo`), as plain data so the
 * rules are JVM-tested (`ApkVerifierTest`). [signerSha256] is null or empty when the platform returned no signatures
 * (API 25–27 `GET_SIGNATURES` may, S7); each entry is the SHA-256 of one signing certificate, any hex form.
 */
data class ArchiveFacts(val packageName: String?, val versionCode: Long?, val signerSha256: List<String>?)

sealed interface ApkVerdict {
    /** [certChecked] false: API 25–27 returned no signatures, so the certificate check was skipped and logged (S7). */
    data class Ok(val certChecked: Boolean) : ApkVerdict
    data class Rejected(val failure: UpdateFailure, val detail: String) : ApkVerdict
}

/**
 * The checks after the download (§3.12.3), in order: SHA-256 and size equal the manifest's (done while streaming, see
 * [Sha256Counter]); the archive parses; its package is ours; its `versionCode` is the manifest's; the signer
 * certificate's SHA-256 is in `signingCertSha256` **and** equals the installed app's signer.
 * (reviewed 2026-10-16, S7) On API 25–27 a null or empty signature list skips the certificate check (never WRONG_CERT):
 * the signed manifest's SHA-256 already authenticates the bytes and the platform rejects another signer at install.
 * From API 28 (`GET_SIGNING_CERTIFICATES`) an empty list is WRONG_CERT.
 */
object ApkVerifier {
    const val SIGNING_CERTIFICATES_SDK = 28

    fun verifyDownload(expectedSha256: String, expectedSize: Long, actualSha256: String, actualSize: Long): ApkVerdict? = when {
        actualSize != expectedSize -> ApkVerdict.Rejected(UpdateFailure.HASH_MISMATCH, "size $actualSize, manifest $expectedSize")
        ManifestVerifier.normaliseHex(actualSha256) != ManifestVerifier.normaliseHex(expectedSha256) -> ApkVerdict.Rejected(UpdateFailure.HASH_MISMATCH, "SHA-256 differs")
        else -> null
    }

    fun verifyArchive(
        archive: ArchiveFacts?,
        manifest: UpdateManifest,
        installedSignerSha256: List<String>,
        sdkInt: Int,
    ): ApkVerdict {
        if (archive?.packageName == null) return ApkVerdict.Rejected(UpdateFailure.WRONG_PACKAGE, "not a readable APK")
        if (archive.packageName != manifest.packageName) return ApkVerdict.Rejected(UpdateFailure.WRONG_PACKAGE, "package ${archive.packageName}")
        if (archive.versionCode != manifest.versionCode) return ApkVerdict.Rejected(UpdateFailure.WRONG_PACKAGE, "versionCode ${archive.versionCode}, manifest ${manifest.versionCode}")
        val signers = archive.signerSha256.orEmpty().map(ManifestVerifier::normaliseHex).filter { it.isNotEmpty() }.toSet()
        if (signers.isEmpty()) {
            return if (sdkInt < SIGNING_CERTIFICATES_SDK) ApkVerdict.Ok(certChecked = false)
            else ApkVerdict.Rejected(UpdateFailure.WRONG_CERT, "no signer reported")
        }
        val allowed = manifest.signingCertSha256.map(ManifestVerifier::normaliseHex).toSet()
        if (!allowed.containsAll(signers)) return ApkVerdict.Rejected(UpdateFailure.WRONG_CERT, "signer not in the manifest")
        val installed = installedSignerSha256.map(ManifestVerifier::normaliseHex).filter { it.isNotEmpty() }.toSet()
        if (installed.isNotEmpty() && installed != signers) return ApkVerdict.Rejected(UpdateFailure.WRONG_CERT, "signer differs from the installed app")
        return ApkVerdict.Ok(certChecked = true)
    }
}

/** SHA-256 and byte count of a stream as it is written (the download hashes while streaming, §3.12.3). */
class Sha256Counter {
    private val md = MessageDigest.getInstance("SHA-256")
    var bytes: Long = 0
        private set

    fun update(buf: ByteArray, off: Int, len: Int) {
        check(digest == null) { "update after hex()" }
        md.update(buf, off, len)
        bytes += len
    }

    private var digest: String? = null

    /** The digest (upper-case hex). Idempotent: `MessageDigest.digest()` resets, so the first result is kept. */
    fun hex(): String = digest ?: md.digest().joinToString("") { "%02X".format(it) }.also { digest = it }

    companion object {
        fun of(input: InputStream): Pair<String, Long> {
            val c = Sha256Counter()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
            return c.hex() to c.bytes
        }

        fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
