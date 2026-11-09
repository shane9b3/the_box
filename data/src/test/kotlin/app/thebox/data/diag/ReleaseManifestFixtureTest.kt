package app.thebox.data.diag

import app.thebox.core.update.ApkVerdict
import app.thebox.core.update.ApkVerifier
import app.thebox.core.update.ArchiveFacts
import app.thebox.core.update.ManifestVerdict
import app.thebox.core.update.ManifestVerifier
import app.thebox.core.update.RunningBuild
import app.thebox.core.update.UpdateEndpoint
import app.thebox.core.update.UpdateFailure
import app.thebox.core.update.UpdatePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-check of the release script (tools/release/release.py) against the app's real parser and verifier: the fixture
 * in `src/test/resources/release/` is made by the script's `build_manifest` and signed with a throwaway test key
 * (`tools/release/test_release.py --regen`; that test also checks the fixture is still what the builder produces).
 * Never the release keys.
 */
class ReleaseManifestFixtureTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("release/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val manifest = resource("update.json")
    private val signature = resource("update.json.sig")
    private val testKey = resource("test_public_key.hex").decodeToString().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val endpoint = UpdateEndpoint("https://github.com/shane9b3/the_box/releases/latest/download/update.json", listOf(testKey), label = "release")
    private val verifier = ManifestVerifier(TinkEd25519Verifier())
    private val running = RunningBuild("app.thebox", versionCode = 6, sdkInt = 30, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))

    private fun verify(bytes: ByteArray = manifest, run: RunningBuild = running, prefs: UpdatePrefs = UpdatePrefs(highestSequence = 6)) =
        verifier.verify(bytes, signature, endpoint, run, prefs, bucket = 99, manual = false)

    @Test
    fun scriptManifestIsAcceptedAndOffersThePreferredAbi() {
        val v = verify() as ManifestVerdict.Offer
        assertEquals(7L, v.manifest.versionCode)
        assertEquals(7L, v.manifest.sequence)
        assertEquals("0.1.6", v.manifest.versionName)
        assertEquals(listOf(5L), v.manifest.withdrawn)
        assertEquals(100, v.manifest.rolloutPercent)
        assertEquals("arm64-v8a", v.apk.abi)
        assertEquals("https://github.com/shane9b3/the_box/releases/download/v0.1.6/TheBox-arm64-v8a.apk", v.apk.url)
        assertEquals(19L, v.apk.sizeBytes)
    }

    @Test
    fun universalIsTheFallbackAbi() {
        val v = verify(run = running.copy(supportedAbis = listOf("x86_64"))) as ManifestVerdict.Offer
        assertEquals("universal", v.apk.abi)
    }

    @Test
    fun theSequenceGuardsReplays() {
        assertEquals(UpdateFailure.REPLAYED, (verify(prefs = UpdatePrefs(highestSequence = 8)) as ManifestVerdict.Rejected).failure)
        assertTrue("equal sequence is the daily re-check", verify(prefs = UpdatePrefs(highestSequence = 7)) is ManifestVerdict.Offer)
    }

    @Test
    fun aChangedByteFailsTheSignature() {
        val tampered = manifest.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertEquals(UpdateFailure.BAD_SIGNATURE, (verify(tampered) as ManifestVerdict.Rejected).failure)
    }

    @Test
    fun theCertDigestFormMatchesWhatTheApkCheckCompares() {
        val m = (verify() as ManifestVerdict.Offer).manifest
        // apksigner prints lower-case hex; PackageManager-derived digests come from Sha256Counter (upper case).
        val signer = "22409319e01c49126ce4c46207f1eb505e2d2bfc469e01a7c0ea0417e33c4634"
        val ok = ApkVerifier.verifyArchive(ArchiveFacts("app.thebox", 7, listOf(signer)), m, installedSignerSha256 = listOf(signer.uppercase()), sdkInt = 30)
        assertEquals(ApkVerdict.Ok(certChecked = true), ok)
    }
}
