package app.thebox.core.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Verifies a detached Ed25519 signature (RFC 8032). `:data` implements it with Tink's pure-Java `Ed25519Verify` (F11). */
fun interface SignatureVerifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * Where a build reads its manifest and which keys it trusts (§3.12.1; reviewed 2026-10-16, S7: **per build type**).
 * Release builds carry only the owner's keys, debug builds only the debug test key (plus a key the debug harness
 * registers at run time for the fake panel). An endpoint without a URL or without keys disables the updater
 * ([UpdateFailure.DISABLED]): that is the release build until the owner names the repository and the key (§8 Q7/Q8).
 */
class UpdateEndpoint(
    val manifestUrl: String?,
    val publicKeys: List<ByteArray>,
    /** Debug builds talk to the fake panel over plain HTTP on any host; release builds use [UpdateTimings.REDIRECT_HOSTS]. */
    val anyHost: Boolean = false,
    val label: String,
) {
    val enabled: Boolean get() = !manifestUrl.isNullOrBlank() && publicKeys.isNotEmpty()
    val signatureUrl: String? get() = manifestUrl?.let { "$it.sig" }
    override fun toString(): String = "UpdateEndpoint($label, keys=${publicKeys.size})"
}

/** Facts of the running build that the manifest is checked against (§3.12.2 step 2: the running build's package id). */
data class RunningBuild(val packageName: String, val versionCode: Long, val sdkInt: Int, val supportedAbis: List<String>)

/** Why an accepted manifest offers nothing. */
enum class NoOffer { NOT_NEWER, WITHDRAWN, SKIPPED, MIN_SDK, ROLLOUT }

sealed interface ManifestVerdict {
    /** A newer release for this device; store [UpdateManifest.sequence] as `highestSequence`. */
    data class Offer(val manifest: UpdateManifest, val apk: ApkEntry) : ManifestVerdict
    /** The manifest is genuine and accepted (store its sequence) but offers nothing to this install. */
    data class Nothing(val manifest: UpdateManifest, val reason: NoOffer) : ManifestVerdict
    data class Rejected(val failure: UpdateFailure, val detail: String) : ManifestVerdict
}

/**
 * The manifest checks in the order of §3.12.2 (`ManifestVerifierTest`):
 * 1. Ed25519 signature over the exact bytes against one of the endpoint's keys → else BAD_SIGNATURE;
 * 2. JSON, `schema == 1`, `packageName ==` the running build's, at least one signing certificate → else BAD_MANIFEST;
 * 2a. `sequence` not lower than the highest accepted (S7) → else REPLAYED. An equal sequence is the same release seen
 *     again (the daily check) and is accepted: the contract's KDoc says "rejects lower ones";
 * 3. `versionCode >` installed, not withdrawn, not skipped (automatic checks only), `minSdk ≤ SDK_INT`, the install's
 *    bucket `< rolloutPercent` → else [ManifestVerdict.Nothing];
 * 4. the APK: the first entry whose `abi` is in `Build.SUPPORTED_ABIS` order, else `universal` → else BAD_MANIFEST.
 *
 * Details are developer text for the log (no URL, no key); the UI shows a reason by [UpdateFailure].
 */
class ManifestVerifier(private val signatures: SignatureVerifier) {
    fun verify(
        manifestBytes: ByteArray,
        signature: ByteArray,
        endpoint: UpdateEndpoint,
        running: RunningBuild,
        prefs: UpdatePrefs,
        bucket: Int,
        manual: Boolean,
    ): ManifestVerdict {
        if (!endpoint.enabled) return ManifestVerdict.Rejected(UpdateFailure.DISABLED, "no manifest URL or no key for ${endpoint.label}")
        if (manifestBytes.size > UpdateTimings.MANIFEST_MAX_BYTES) return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "manifest of ${manifestBytes.size} bytes")
        if (signature.size != UpdateTimings.SIGNATURE_BYTES) return ManifestVerdict.Rejected(UpdateFailure.BAD_SIGNATURE, "signature of ${signature.size} bytes")
        val signed = endpoint.publicKeys.any { key -> key.size == 32 && runCatching { signatures.verify(key, manifestBytes, signature) }.getOrDefault(false) }
        if (!signed) return ManifestVerdict.Rejected(UpdateFailure.BAD_SIGNATURE, "no trusted key verifies the manifest")

        val m = try {
            JSON.decodeFromString(UpdateManifest.serializer(), manifestBytes.decodeToString())
        } catch (e: SerializationException) {
            return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "unreadable manifest: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "unreadable manifest: ${e.javaClass.simpleName}")
        }
        if (m.schema != UpdateTimings.SCHEMA) return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "schema ${m.schema}")
        if (m.packageName != running.packageName) return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "manifest for ${m.packageName}, running ${running.packageName}")
        if (m.signingCertSha256.none { normaliseHex(it).length == 64 }) return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "no signing certificate digest")
        if (m.sequence < prefs.highestSequence) return ManifestVerdict.Rejected(UpdateFailure.REPLAYED, "sequence ${m.sequence} < ${prefs.highestSequence}")

        when {
            m.versionCode <= running.versionCode -> return ManifestVerdict.Nothing(m, NoOffer.NOT_NEWER)
            m.versionCode in m.withdrawn -> return ManifestVerdict.Nothing(m, NoOffer.WITHDRAWN)
            !manual && prefs.skippedVersionCode == m.versionCode -> return ManifestVerdict.Nothing(m, NoOffer.SKIPPED)
            m.minSdk > running.sdkInt -> return ManifestVerdict.Nothing(m, NoOffer.MIN_SDK)
            bucket >= m.rolloutPercent.coerceIn(0, 100) -> return ManifestVerdict.Nothing(m, NoOffer.ROLLOUT)
        }
        val apk = pickApk(m.apks, running.supportedAbis)
            ?: return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "no APK for ${running.supportedAbis}")
        if (normaliseHex(apk.sha256).length != 64 || apk.sizeBytes <= 0) return ManifestVerdict.Rejected(UpdateFailure.BAD_MANIFEST, "APK entry without a SHA-256 or a size")
        return ManifestVerdict.Offer(m, apk)
    }

    companion object {
        const val UNIVERSAL = "universal"

        /** `ignoreUnknownKeys`: a later manifest may carry fields this client does not know (schema 1 stays readable). */
        val JSON: Json = Json { ignoreUnknownKeys = true }

        /** First entry whose ABI appears earliest in [supportedAbis] (device preference order), else the universal APK. */
        fun pickApk(apks: List<ApkEntry>, supportedAbis: List<String>): ApkEntry? {
            for (abi in supportedAbis) apks.firstOrNull { it.abi == abi }?.let { return it }
            return apks.firstOrNull { it.abi == UNIVERSAL }
        }

        /** Upper-case hex without colons or spaces (the manifest's form, §2.10). */
        fun normaliseHex(s: String): String = s.filter { it != ':' && !it.isWhitespace() }.uppercase()
    }
}
