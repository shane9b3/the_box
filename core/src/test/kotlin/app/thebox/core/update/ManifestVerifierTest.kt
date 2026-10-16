package app.thebox.core.update

import app.thebox.core.update.Ed25519TestKeys.hex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.12.2, §6.1 WP-E (incl. the S7 rows). */
class ManifestVerifierTest {
    private val release = Ed25519TestKeys.generate()
    private val debugKey = Ed25519TestKeys.generate()
    private val verifier = ManifestVerifier(Ed25519TestKeys.verifier)
    private val releaseEndpoint = UpdateEndpoint("https://github.com/o/r/releases/latest/download/update.json", listOf(release.publicRaw), label = "release")
    private val debugEndpoint = UpdateEndpoint("http://10.0.2.2:18080/update/update.json", listOf(debugKey.publicRaw), anyHost = true, label = "debug")
    private val running = RunningBuild("app.thebox", versionCode = 10, sdkInt = 30, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))
    private val cert = "AB".repeat(32)

    private fun manifest(
        versionCode: Long = 11, sequence: Long = 5, packageName: String = "app.thebox", minSdk: Int = 25, schema: Int = 1,
        withdrawn: List<Long> = emptyList(), rollout: Int = 100,
        apks: List<ApkEntry> = listOf(
            ApkEntry("armeabi-v7a", "https://github.com/o/r/releases/download/v1/v7a.apk", 100, "11".repeat(32)),
            ApkEntry("arm64-v8a", "https://github.com/o/r/releases/download/v1/v8a.apk", 100, "22".repeat(32)),
            ApkEntry("universal", "https://github.com/o/r/releases/download/v1/all.apk", 200, "33".repeat(32)),
        ),
        certs: List<String> = listOf(cert),
    ) = UpdateManifest(schema, sequence, packageName, "stable", versionCode, "1.0.$versionCode", minSdk, "2026-10-16T12:00:00Z",
        "- Fixes catch-up on Flussonic servers\n- Faster zapping", apks, certs, withdrawn, rollout)

    private fun bytes(m: UpdateManifest) = Json.encodeToString(UpdateManifest.serializer(), m).encodeToByteArray()

    private fun check(
        m: UpdateManifest, key: Ed25519TestKeys.Pair = release, endpoint: UpdateEndpoint = releaseEndpoint, run: RunningBuild = running,
        prefs: UpdatePrefs = UpdatePrefs(highestSequence = 4), bucket: Int = 10, manual: Boolean = false,
        tamper: (ByteArray) -> ByteArray = { it },
    ): ManifestVerdict {
        val b = bytes(m)
        val sig = key.sign(b)
        return verifier.verify(tamper(b), sig, endpoint, run, prefs, bucket, manual)
    }

    // ---------------------------------------------------------------- RFC 8032 §7.1 through the seam

    @Test
    fun rfc8032VectorsVerifyThroughTheSeam() {
        val vectors = listOf(
            Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            Triple("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
        )
        for ((pk, msg, sig) in vectors) {
            assertTrue(Ed25519TestKeys.verifier.verify(hex(pk), hex(msg), hex(sig)), "vector pk=$pk")
            val flipped = hex(sig).also { it[10] = (it[10].toInt() xor 1).toByte() }
            assertFalse(Ed25519TestKeys.verifier.verify(hex(pk), hex(msg), flipped))
        }
    }

    // ---------------------------------------------------------------- signature

    @Test
    fun validManifestOffersThePreferredAbi() {
        val v = assertIs<ManifestVerdict.Offer>(check(manifest()))
        assertEquals("arm64-v8a", v.apk.abi)
        assertEquals(11, v.manifest.versionCode)
    }

    @Test
    fun wrongKeyIsBadSignature() {
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(), key = Ed25519TestKeys.generate()))
        assertEquals(UpdateFailure.BAD_SIGNATURE, v.failure)
    }

    @Test
    fun flippedByteIsBadSignature() {
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(), tamper = { b -> b.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() } }))
        assertEquals(UpdateFailure.BAD_SIGNATURE, v.failure)
    }

    @Test
    fun truncatedSignatureIsBadSignature() {
        val b = bytes(manifest())
        val v = assertIs<ManifestVerdict.Rejected>(verifier.verify(b, release.sign(b).copyOf(63), releaseEndpoint, running, UpdatePrefs(), 0, true))
        assertEquals(UpdateFailure.BAD_SIGNATURE, v.failure)
    }

    @Test
    fun secondKeyOfTheEndpointIsAcceptedForRotation() {
        val next = Ed25519TestKeys.generate()
        val endpoint = UpdateEndpoint(releaseEndpoint.manifestUrl, listOf(release.publicRaw, next.publicRaw), label = "release")
        assertIs<ManifestVerdict.Offer>(check(manifest(), key = next, endpoint = endpoint))
    }

    @Test
    fun debugKeyIsRefusedByTheReleaseEndpointAndReleaseKeyByTheDebugEndpoint() {
        // S7: release builds carry only the owner's keys, debug builds only the debug key.
        assertEquals(UpdateFailure.BAD_SIGNATURE, assertIs<ManifestVerdict.Rejected>(check(manifest(), key = debugKey)).failure)
        val dbg = running.copy(packageName = "app.thebox.debug")
        assertEquals(UpdateFailure.BAD_SIGNATURE,
            assertIs<ManifestVerdict.Rejected>(check(manifest(packageName = "app.thebox.debug"), key = release, endpoint = debugEndpoint, run = dbg)).failure)
        assertIs<ManifestVerdict.Offer>(check(manifest(packageName = "app.thebox.debug"), key = debugKey, endpoint = debugEndpoint, run = dbg))
    }

    @Test
    fun endpointWithoutKeysOrUrlIsDisabled() {
        val none = UpdateEndpoint(null, emptyList(), label = "release (owner has not named the repo)")
        assertEquals(UpdateFailure.DISABLED, assertIs<ManifestVerdict.Rejected>(check(manifest(), endpoint = none)).failure)
        val noKey = UpdateEndpoint("https://x/update.json", emptyList(), label = "r")
        assertEquals(UpdateFailure.DISABLED, assertIs<ManifestVerdict.Rejected>(check(manifest(), endpoint = noKey)).failure)
    }

    // ---------------------------------------------------------------- schema and package

    @Test
    fun otherSchemaIsBadManifest() {
        assertEquals(UpdateFailure.BAD_MANIFEST, assertIs<ManifestVerdict.Rejected>(check(manifest(schema = 2))).failure)
    }

    @Test
    fun packageMustBeTheRunningBuildsId() {
        // S7: the debug build (app.thebox.debug) refuses a release manifest and vice versa.
        assertEquals(UpdateFailure.BAD_MANIFEST, assertIs<ManifestVerdict.Rejected>(check(manifest(packageName = "app.thebox.debug"))).failure)
        assertEquals(UpdateFailure.BAD_MANIFEST, assertIs<ManifestVerdict.Rejected>(check(manifest(packageName = "com.other"))).failure)
    }

    @Test
    fun signedGarbageIsBadManifest() {
        val b = "not json".encodeToByteArray()
        val v = assertIs<ManifestVerdict.Rejected>(verifier.verify(b, release.sign(b), releaseEndpoint, running, UpdatePrefs(), 0, false))
        assertEquals(UpdateFailure.BAD_MANIFEST, v.failure)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = String(bytes(manifest())).dropLast(1) + ",\"futureField\":{\"x\":1}}"
        val b = json.encodeToByteArray()
        assertIs<ManifestVerdict.Offer>(verifier.verify(b, release.sign(b), releaseEndpoint, running, UpdatePrefs(), 0, false))
    }

    @Test
    fun manifestWithoutCertificateDigestIsBadManifest() {
        assertEquals(UpdateFailure.BAD_MANIFEST, assertIs<ManifestVerdict.Rejected>(check(manifest(certs = emptyList()))).failure)
    }

    // ---------------------------------------------------------------- sequence (S7)

    @Test
    fun lowerSequenceIsReplayed() {
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(sequence = 3), prefs = UpdatePrefs(highestSequence = 4)))
        assertEquals(UpdateFailure.REPLAYED, v.failure)
    }

    @Test
    fun equalSequenceIsTheSameReleaseSeenAgain() {
        assertIs<ManifestVerdict.Offer>(check(manifest(sequence = 4), prefs = UpdatePrefs(highestSequence = 4)))
    }

    @Test
    fun replayedOlderManifestCannotReofferAWithdrawnRelease() {
        // seq 6 withdrew 11; an attacker replays the genuine seq 5 that offered it.
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(sequence = 5), prefs = UpdatePrefs(highestSequence = 6)))
        assertEquals(UpdateFailure.REPLAYED, v.failure)
    }

    // ---------------------------------------------------------------- offer rules

    @Test
    fun notNewer() {
        assertEquals(NoOffer.NOT_NEWER, assertIs<ManifestVerdict.Nothing>(check(manifest(versionCode = 10))).reason)
        assertEquals(NoOffer.NOT_NEWER, assertIs<ManifestVerdict.Nothing>(check(manifest(versionCode = 9))).reason)
    }

    @Test
    fun withdrawn() {
        assertEquals(NoOffer.WITHDRAWN, assertIs<ManifestVerdict.Nothing>(check(manifest(withdrawn = listOf(11)))).reason)
    }

    @Test
    fun skippedOnlyForAutomaticChecks() {
        val prefs = UpdatePrefs(skippedVersionCode = 11, highestSequence = 4)
        assertEquals(NoOffer.SKIPPED, assertIs<ManifestVerdict.Nothing>(check(manifest(), prefs = prefs, manual = false)).reason)
        assertIs<ManifestVerdict.Offer>(check(manifest(), prefs = prefs, manual = true))
        // A newer release than the skipped one is offered again.
        assertIs<ManifestVerdict.Offer>(check(manifest(versionCode = 12), prefs = prefs, manual = false))
    }

    @Test
    fun minSdk() {
        assertEquals(NoOffer.MIN_SDK, assertIs<ManifestVerdict.Nothing>(check(manifest(minSdk = 31))).reason)
        assertIs<ManifestVerdict.Offer>(check(manifest(minSdk = 30)))
    }

    @Test
    fun rolloutBucket() {
        assertIs<ManifestVerdict.Offer>(check(manifest(rollout = 20), bucket = 19))
        assertEquals(NoOffer.ROLLOUT, assertIs<ManifestVerdict.Nothing>(check(manifest(rollout = 20), bucket = 20)).reason)
        assertEquals(NoOffer.ROLLOUT, assertIs<ManifestVerdict.Nothing>(check(manifest(rollout = 0), bucket = 0)).reason)
        assertIs<ManifestVerdict.Offer>(check(manifest(rollout = 100), bucket = 99))
    }

    // ---------------------------------------------------------------- ABI pick

    @Test
    fun abiPickFollowsTheDeviceOrderThenUniversal() {
        val apks = manifest().apks
        assertEquals("arm64-v8a", ManifestVerifier.pickApk(apks, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("armeabi-v7a", ManifestVerifier.pickApk(apks, listOf("armeabi-v7a", "armeabi"))?.abi)
        assertEquals("universal", ManifestVerifier.pickApk(apks, listOf("x86"))?.abi)
        assertEquals(null, ManifestVerifier.pickApk(apks.filter { it.abi != "universal" }, listOf("x86")))
    }

    @Test
    fun noApkForTheDeviceIsBadManifest() {
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(apks = listOf(ApkEntry("arm64-v8a", "https://x/a.apk", 1, "11".repeat(32)))),
            run = running.copy(supportedAbis = listOf("x86"))))
        assertEquals(UpdateFailure.BAD_MANIFEST, v.failure)
    }

    @Test
    fun apkEntryWithoutHashIsBadManifest() {
        val v = assertIs<ManifestVerdict.Rejected>(check(manifest(apks = listOf(ApkEntry("universal", "https://x/a.apk", 1, "abc")))))
        assertEquals(UpdateFailure.BAD_MANIFEST, v.failure)
    }

    @Test
    fun hexIsNormalised() {
        assertEquals("ABCD", ManifestVerifier.normaliseHex("ab:cd"))
        assertEquals("ABCD", ManifestVerifier.normaliseHex(" ab cd "))
    }
}
