package app.thebox.settings

import android.os.SystemClock
import android.view.KeyEvent
import app.thebox.core.backup.BACKUP_KDF_ITERATIONS
import app.thebox.core.backup.BackupCrypto
import app.thebox.core.guide.ScreenMode
import app.thebox.core.parental.PIN_KDF_BUDGET_MS
import app.thebox.core.parental.PIN_KDF_ITERATIONS
import app.thebox.data.parental.PinVerifier
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (M5 integration 2026-10-16) The §6.7 gates the emulator can measure that no I-M5 class records: one PIN check
 * (PBKDF2 over `Mac("HmacSHA256")`, [PIN_KDF_ITERATIONS]), the backup key derivation ([BACKUP_KDF_ITERATIONS]) with an
 * AES-256-GCM seal/open round trip, Settings open → first page built, and the first frame after a REPREPARE setting
 * changed (leaving Settings re-prepares once). Emulator numbers: proxies for M6's hardware gates. Every figure is logged
 * (`M5Suite: gate …`).
 */
class GatesTest : SettingsTestBase() {

    private fun timed(n: Int, block: () -> Unit): List<Long> = (0 until n).map {
        val t0 = SystemClock.elapsedRealtimeNanos()
        block()
        (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
    }

    @Test
    fun pinCheckAndBackupDerivation() {
        val blob = PinVerifier.create("2580".toCharArray(), PIN_KDF_ITERATIONS)
        val parsed = PinVerifier.parse(blob)!!
        timed(2) { PinVerifier.matches("2580".toCharArray(), parsed) } // warm-up (JIT)
        val pin = timed(5) { assertTrue(PinVerifier.matches("2580".toCharArray(), parsed)) }
        log("gate pin check ms=$pin (iterations $PIN_KDF_ITERATIONS, budget $PIN_KDF_BUDGET_MS)")
        // The median is the gate on the emulator (a busy host adds outliers; the max is logged and reported).
        assertTrue("PIN check ≤ $PIN_KDF_BUDGET_MS ms (median): $pin", pin.sorted()[pin.size / 2] <= PIN_KDF_BUDGET_MS)

        val salt = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }
        timed(1) { BackupCrypto.deriveKey("correct horse 42".toCharArray(), salt, BACKUP_KDF_ITERATIONS) }
        val kdf = timed(3) { BackupCrypto.deriveKey("correct horse 42".toCharArray(), salt, BACKUP_KDF_ITERATIONS) }
        log("gate backup derivation ms=$kdf (iterations $BACKUP_KDF_ITERATIONS)")
        assertTrue("backup derivation ≤ 1 s: $kdf", kdf.max() <= 1_000)

        // AES-256-GCM through the platform provider (the same calls BackupCrypto.seal/open make).
        val key = BackupCrypto.deriveKey("correct horse 42".toCharArray(), salt, BACKUP_KDF_ITERATIONS)
        val nonce = ByteArray(BackupCrypto.NONCE_BYTES) { (it * 7).toByte() }
        val plain = "{\"logins\":[]}".encodeToByteArray()
        val enc = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(BackupCrypto.TAG_BITS, nonce))
            updateAAD("header".encodeToByteArray())
        }.doFinal(plain)
        val dec = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(BackupCrypto.TAG_BITS, nonce))
            updateAAD("header".encodeToByteArray())
        }.doFinal(enc)
        assertArrayEquals(plain, dec)
        log("gate aes-gcm round trip ok on API ${android.os.Build.VERSION.SDK_INT}")
    }

    @Test
    fun settingsOpenAndFirstFrameAfterAChange() {
        val pid = seedXtream()
        launch()
        awaitGui("guide", 30_000) { it.mode == ScreenMode.GUIDE && it.empty == null }
        // Settings open → first page built (the machine's page; drawing is the benchmark's, not measured here).
        val opens = (0 until 3).map {
            val t0 = SystemClock.elapsedRealtime()
            press(KeyEvent.KEYCODE_SETTINGS, intervalMs = 0)
            awaitSettings("first page") { it.page != null && it.page?.placeholder == null }
            val ms = SystemClock.elapsedRealtime() - t0
            leaveSettings()
            Thread.sleep(500)
            ms
        }
        log("gate settings open → first page ms=$opens")
        // First frame after a REPREPARE row changed (one re-prepare when Settings closes, §0.6).
        val ch = runBlocking { ep.channels().browseAll(pid, unlocked = true).first() }.first()
        onMain { controller.tune(TuneTarget.Channel(app.thebox.core.refresh.ChannelIdentity(ch.playlistId, ch.stableKey))) }
        runBlocking { withTimeoutOrNull(20_000) { controller.state.first { it is PlaybackState.Playing } } }
        Thread.sleep(2_000)
        openSettings("playback")
        choose("playback.audio_output", "STEREO")
        // The re-prepare keeps the surface (no new "first frame" count): time BACK → Playing again after it left Playing.
        val t0 = SystemClock.elapsedRealtime()
        val left = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = Thread {
            runBlocking {
                withTimeoutOrNull(15_000) {
                    controller.state.first { it !is PlaybackState.Playing }
                    left.set(SystemClock.elapsedRealtime())
                    controller.state.first { it is PlaybackState.Playing }
                }
            }
        }.also { it.start() }
        leaveSettings()
        watcher.join(16_000)
        val ms = SystemClock.elapsedRealtime() - t0
        log("gate first frame after a settings change: Settings closed → playing again ms=$ms (left playing at +${if (left.get() > 0) left.get() - t0 else -1} ms)")
        assertTrue("left Playing for the re-prepare", left.get() > 0)
        assertTrue("playing again", controller.state.value is PlaybackState.Playing)
        openSettings("playback")
        choose("playback.audio_output", "AUTO")
        leaveSettings()
    }
}
