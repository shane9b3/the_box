package app.thebox.settings

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.update.Sha256Counter
import app.thebox.core.update.UpdateDomain
import app.thebox.core.update.UpdateFailure
import app.thebox.core.update.UpdatePrefs
import app.thebox.core.update.UpdateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * I-M5-19 "Updater refusals" (docs/design/m5-setup-settings.md §6.3, §3.12): the fake panel serves manifest + sig + APK
 * signed with the panel's debug update key (registered at run time through `thebox-debug://guide?update_key=…`, S7).
 *
 * - bad signature → ignored (automatic) / reason (manual);
 * - SHA mismatch → refused, file deleted;
 * - an APK whose signer is not the manifest's / the installed one → WRONG_CERT;
 * - valid (the debug APK rebuilt with versionCode + 1, signed with the debug key; the manifest names `app.thebox.debug`)
 *   → the session reaches `STATUS_PENDING_USER_ACTION`, the test cancels;
 * - a replayed manifest with a lower `sequence` → REPLAYED.
 *
 * Fixtures from `tools/fakepanel/run-instrumented.sh --update-fixtures`: `thebox-update-v2.apk` (versionCode 2),
 * `thebox-update-other.apk` (versionCode 2, another signing key) and `thebox-update-installed.apk` (the installed APK, the
 * not-newer/wrong-hash payload). U-S14 (code review 2026-10-16): the panel loads them on the host (`--update-apk-dir`) and
 * each case selects one by name (`update_apk`); the copies pushed to /data/local/tmp are the upload fallback. Runs on commit 0 + WP-E (no settings UI involved).
 */
class UpdaterTest : SettingsTestBase() {
    private val updater get() = probe.updater
    private val args = InstrumentationRegistry.getArguments()
    private val v2Path get() = args.getString("update_apk_v2") ?: "/data/local/tmp/thebox-update-v2.apk"
    private val otherPath get() = args.getString("update_apk_other") ?: "/data/local/tmp/thebox-update-other.apk"

    @Before
    fun setUpUpdater() {
        updater.resetSession()
        resetPrefs()
        debugLink("update_manifest=" + android.net.Uri.encode("$base/update/update.json") + "&update_key=" + panel.updatePublicKey())
    }

    private fun resetPrefs(highestSequence: Long = 0) {
        probe.store.update(UpdateDomain) { UpdatePrefs(highestSequence = highestSequence, bucket = 0) }
    }

    private fun installedCertSha(): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val sig = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners.first() else info.signatures!!.first()
        return Sha256Counter.of(sig.toByteArray())
    }

    private fun installedApk(): ByteArray = File(context.applicationInfo.sourceDir).readBytes()

    private fun readFixture(path: String): ByteArray? {
        // /data/local/tmp is readable by the shell; copy through the UiAutomation shell so app permissions do not matter.
        val out = File(context.cacheDir, File(path).name)
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out.readBytes().takeIf { it.size > 1024 }
    }

    private fun manifest(versionCode: Long, sequence: Long, certs: List<String> = listOf(installedCertSha())) =
        panel.updateManifest(JSONObject().put("versionCode", versionCode).put("versionName", "0.0.$versionCode-test").put("sequence", sequence).put("signingCertSha256", JSONArray(certs)))

    private fun check(manual: Boolean): UpdateState = runBlocking { updater.runCheck(manual) }

    private fun awaitState(what: String, timeoutMs: Long = 60_000, p: (UpdateState) -> Boolean): UpdateState =
        runBlocking { withTimeoutOrNull(timeoutMs) { updater.state.first(p) } } ?: error("timed out waiting for $what; state=${updater.state.value}")

    @Test
    fun badSignatureIsIgnoredAutomaticallyAndExplainedManually() {
        assertTrue(panel.offerApk("installed") { installedApk() }) // U-S14
        manifest(versionCode = 99, sequence = 1)
        panel.updateMode("bad_signature")
        assertEquals("automatic check stays silent", UpdateState.Idle, check(manual = false))
        val s = check(manual = true)
        assertEquals(UpdateFailure.BAD_SIGNATURE, (s as UpdateState.Failed).reason)
        assertTrue(updater.failureText(s.reason).isNotBlank())
    }

    @Test
    fun shaMismatchIsRefusedAndTheFileDeleted() {
        assertTrue(panel.offerApk("installed") { installedApk() }) // U-S14
        manifest(versionCode = 99, sequence = 1)
        panel.updateMode("wrong_hash")
        val offer = check(manual = true)
        assertTrue("offer: $offer", offer is UpdateState.Available)
        updater.download()
        val s = awaitState("download refused") { it is UpdateState.Failed }
        assertEquals(UpdateFailure.HASH_MISMATCH, (s as UpdateState.Failed).reason)
        assertTrue("no file left: ${updater.updateFiles()}", updater.updateFiles().isEmpty())
    }

    @Test
    fun apkNotSignedWithTheManifestsCertificateIsWrongCert() {
        // U-S14 (code review 2026-10-16): the panel serves the host's fixture by name; reading it here is the fallback.
        assumeTrue("fixture v2 missing: run tools/fakepanel/run-instrumented.sh --update-fixtures", panel.offerApk("v2") { readFixture(v2Path) })
        manifest(versionCode = 2, sequence = 1)
        panel.updateMode("wrong_cert") // the manifest lists another certificate digest
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.download()
        val s = awaitState("download refused") { it is UpdateState.Failed }
        assertEquals(UpdateFailure.WRONG_CERT, (s as UpdateState.Failed).reason)
        assertTrue(updater.updateFiles().isEmpty())
    }

    @Test
    fun apkSignedWithAnotherKeyIsWrongCert() {
        assumeTrue("fixture other missing: run tools/fakepanel/run-instrumented.sh --update-fixtures", panel.offerApk("other") { readFixture(otherPath) }) // U-S14
        manifest(versionCode = 2, sequence = 1) // the genuine certificate digest
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.download()
        val s = awaitState("download refused") { it is UpdateState.Failed }
        assertEquals(UpdateFailure.WRONG_CERT, (s as UpdateState.Failed).reason)
    }

    @Test
    fun notNewerAndWrongPackage() {
        assertTrue(panel.offerApk("installed") { installedApk() }) // U-S14
        manifest(versionCode = 1, sequence = 1)
        assertTrue("same versionCode → up to date", check(manual = true) is UpdateState.UpToDate)
        updater.resetSession()
        // The installed APK offered as versionCode 99: the archive's versionCode (1) is not the manifest's.
        manifest(versionCode = 99, sequence = 2)
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.download()
        val s = awaitState("download refused") { it is UpdateState.Failed }
        assertEquals(UpdateFailure.WRONG_PACKAGE, (s as UpdateState.Failed).reason)
    }

    @Test
    fun replayedLowerSequenceIsRefused() {
        assertTrue(panel.offerApk("installed") { installedApk() }) // U-S14
        manifest(versionCode = 99, sequence = 5)
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.resetSession()
        manifest(versionCode = 99, sequence = 3)
        val s = check(manual = true)
        assertEquals(UpdateFailure.REPLAYED, (s as UpdateState.Failed).reason)
    }

    @Test
    fun validUpdateReachesThePendingUserActionAndTheTestCancels() {
        // U-S14 (code review 2026-10-16): the panel serves the host's fixture by name; reading it here is the fallback.
        assumeTrue("fixture v2 missing: run tools/fakepanel/run-instrumented.sh --update-fixtures", panel.offerApk("v2") { readFixture(v2Path) })
        manifest(versionCode = 2, sequence = 1)
        // "Install unknown apps" is granted by run-instrumented.sh --update-fixtures before the app starts (changing the
        // app-op kills the app process, and with it this instrumentation). Without it the verified download must stop at
        // NeedsPermission (§3.12.4 step 1), which is asserted instead, and the install half cannot run on this install.
        val granted = context.packageManager.canRequestPackageInstalls()
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.download()
        val ready = awaitState("verified download", 120_000) { it is UpdateState.ReadyToInstall || it is UpdateState.NeedsPermission || it is UpdateState.Failed }
        if (!granted) {
            assertTrue("without the permission: NeedsPermission, got $ready", ready is UpdateState.NeedsPermission)
            fail("REQUEST_INSTALL_PACKAGES not granted: run tools/fakepanel/run-instrumented.sh --update-fixtures for the install half")
        }
        assertTrue("ready: $ready", ready is UpdateState.ReadyToInstall)
        val before = probe.installer.statusesSeen.size
        updater.install()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline && probe.installer.statusesSeen.drop(before).none { it == PackageInstaller.STATUS_PENDING_USER_ACTION }) Thread.sleep(200)
        log("installer statuses: ${probe.installer.statusesSeen}")
        assertTrue("STATUS_PENDING_USER_ACTION reached", probe.installer.statusesSeen.drop(before).contains(PackageInstaller.STATUS_PENDING_USER_ACTION))
        Thread.sleep(1_500) // the system confirmation is on screen
        // The confirmation belongs to the system installer: only UiAutomator may press BACK there.
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
        // The platform sends nothing for an unanswered confirmation; the app abandons the session when its activity
        // starts again (no activity runs in this class, so the test does what that observer does).
        onMain { updater.cancelPendingInstall() }
        awaitState("cancelled → ready again", 30_000) { it is UpdateState.ReadyToInstall }
    }

    /**
     * D-B4 device check (code review 2026-10-16), run alone with `-e update_confirm 1`: with the app in front, the
     * pending install session is not abandoned while the system confirmation is up, and confirming it installs the
     * update. The package is replaced, which kills this process: the runner reports a crash, and the check is the
     * installed versionCode afterwards (`adb shell dumpsys package app.thebox.debug | grep versionCode` → 2).
     */
    @Test
    fun confirmInstallsTheUpdate() {
        assumeTrue("device check: -e update_confirm 1", args.getString("update_confirm") == "1")
        assumeTrue("fixture v2 missing: run tools/fakepanel/run-instrumented.sh --update-fixtures", panel.offerApk("v2") { readFixture(v2Path) })
        assumeTrue("REQUEST_INSTALL_PACKAGES not granted", context.packageManager.canRequestPackageInstalls())
        manifest(versionCode = 2, sequence = 1)
        launch() // the app in front, as when the user presses Install on the About page
        assertTrue(check(manual = true) is UpdateState.Available)
        updater.download()
        assertTrue(awaitState("verified download", 120_000) { it is UpdateState.ReadyToInstall || it is UpdateState.Failed } is UpdateState.ReadyToInstall)
        val before = probe.installer.statusesSeen.size
        updater.install()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline && probe.installer.statusesSeen.drop(before).none { it == PackageInstaller.STATUS_PENDING_USER_ACTION }) Thread.sleep(200)
        assertTrue("STATUS_PENDING_USER_ACTION reached", probe.installer.statusesSeen.drop(before).contains(PackageInstaller.STATUS_PENDING_USER_ACTION))
        Thread.sleep(5_000)
        log("D-B4: state with the confirmation up for 5 s: ${updater.state.value}")
        assertEquals("the pending session survives while the app is in front", UpdateState.Installing, updater.state.value)
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val button = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)^(update|install)$"))), 15_000)
        log("D-B4: confirmation button: ${button?.text} (package ${device.currentPackageName})")
        if (button != null) button.click() else { device.pressDPadRight(); device.pressEnter() }
        Thread.sleep(60_000) // the package is replaced here; this process ends
    }
}
