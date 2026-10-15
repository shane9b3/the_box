package app.thebox.player

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.PlayerLayer
import app.thebox.core.guide.ScreenMode
import app.thebox.core.playback.PlaybackErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-11 (probe and persist on a 404), I-M4-12 (every form rejected) and I-M4-21 (probe on a 400, S3),
 * docs/design/m4-player-ux.md §3.8.8, §3.7, §5.5.
 * Panel: `timeshift_reject_forms` (`path` = xc-path, `php` = xc-php, `path_sec` = xc-path-sec; the probe order for an
 * Xtream-shaped `xc` channel is [xc-path, xc-php, xc-path-sec], API notes §6). Each test clears the stored winner first
 * (`playlist.catchup_probe_winner`, read back through the repository).
 * Depends on WP-B (probing, persistence, ArchiveVisibility invariant), WP-C (probe store, ladder L13a for 400), WP-A
 * (error card, suspect ↺), WP-D (VM forwarding through ArchiveVisibility).
 */
@RunWith(AndroidJUnit4::class)
class ProbeTest : PlayerTestBase() {

    private fun winner(): String? = runBlocking { ep.playlists().get(playlistId)?.catchupProbeWinner }

    @Before
    fun clearWinner() {
        runBlocking {
            val p = ep.playlists().get(playlistId) ?: return@runBlocking
            if (p.catchupProbeWinner != null) ep.playlists().update(p.copy(catchupProbeWinner = null))
        }
    }

    /** I-M4-11: xc-path 404 → plays through xc-php; winner "xc-php"; the next catch-up asks xc-php first; no card ever visible (B1). */
    @Test
    fun probeAndPersistOn404() {
        pp.rejectForms(mapOf("path" to 404))
        val t0 = pp.now().unix
        val layers = Sampler(25) { pui().layer }
        try {
            playPast(1, endedMinAgo = 5)
            val st = awaitArchive("playing through the next variant", 30_000) { it.phase == ArchivePhase.PLAYING } // WP-B behaviour
            Thread.sleep(1_000)
            val forms = pp.timeshiftSince(t0).filter { !it.ranged }.map { "${it.form}:${it.status}" }
            log("I-M4-11 forms $forms variant=${st.variant}")
            assertEquals("first try xc-path → 404", "path:404", forms.first())
            assertTrue("then xc-php → 200: $forms", forms.contains("php:200")) // WP-B behaviour (probe order)
            assertEquals("variant playing", "xc-php", st.variant) // WP-B behaviour
        } finally {
            layers.close()
        }
        assertFalse("no error card was ever visible during probing (B1): ${layers.snapshot().distinct()}", layers.snapshot().contains(PlayerLayer.ERROR)) // WP-B/WP-D behaviour
        val deadline = System.currentTimeMillis() + 5_000
        while (winner() == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertEquals("winner persisted", "xc-php", winner()) // WP-B/WP-C behaviour
        // The next catch-up asks xc-php first.
        toBareVideo()
        press(KeyEvent.KEYCODE_BACK) // bare archive video → guide
        val t1 = pp.now().unix
        playPast(1, endedMinAgo = 30)
        awaitArchive("second catch-up playing", 30_000) { it.phase == ArchivePhase.PLAYING }
        val second = pp.timeshiftSince(t1).filter { !it.ranged }
        log("I-M4-11 second catch-up: $second")
        assertEquals("the stored winner is tried first", "php", second.first().form) // WP-B behaviour (winner first, §3.8.2)
    }

    /** I-M4-12: every form rejected → CATCHUP_NOT_IN_ARCHIVE card with Watch live; the guide shows the hollow ↺; no winner stored. */
    @Test
    fun allVariantsFail() {
        pp.rejectForms(mapOf("path" to 404, "php" to 404, "path_sec" to 404, "shift" to 404, "flussonic_range" to 404, "flussonic_now" to 404, "timeshift_abs" to 404, "timeshift_rel" to 404))
        val (ch, _) = playPast(1, endedMinAgo = 5)
        val card = awaitPlayer("error card", 40_000) { it.layer == PlayerLayer.ERROR && it.error != null }.error!! // WP-A/WP-B behaviour
        log("I-M4-12 card ${card.error.code} buttons=${card.buttons} archiveFailed=${card.archiveFailed} text=${card.textStep}")
        assertEquals(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, card.error.code) // WP-A behaviour (§3.7 last paragraph)
        assertTrue("Watch live offered", card.buttons.contains(ErrorButton.WATCH_LIVE)) // WP-A behaviour
        assertFalse("no other format left to try", card.buttons.contains(ErrorButton.TRY_OTHER_CATCHUP_FORMAT)) // WP-A/WP-B behaviour
        assertEquals(ArchivePhase.FAILED, archive().phase) // WP-B behaviour
        press(KeyEvent.KEYCODE_BACK) // ERROR: BACK goes to the guide (product S8)
        awaitUi("guide after BACK on the card", 5_000) { it.mode == ScreenMode.GUIDE }
        awaitFrame("hollow ↺ on ${ch.displayName}", 5_000) { f -> f.suspectArchive.contains(ch.identityHash) } // WP-A behaviour (§0.6)
        assertNull("no winner stored", winner()) // WP-B behaviour
    }

    /** I-M4-21: xc-path 400 → playback through the next variant, winner stored (S3: 400 is a variant failure, ladder L13a). */
    @Test
    fun probeOn400() {
        pp.rejectForms(mapOf("path" to 400))
        val t0 = pp.now().unix
        playPast(1, endedMinAgo = 5)
        val st = awaitArchive("playing after a 400", 30_000) { it.phase == ArchivePhase.PLAYING } // WP-B/WP-C behaviour
        val forms = pp.timeshiftSince(t0).filter { !it.ranged }.map { "${it.form}:${it.status}" }
        log("I-M4-21 forms $forms variant=${st.variant}")
        assertEquals("path:400", forms.first())
        assertTrue("next variant served: $forms", forms.drop(1).any { it.endsWith(":200") })
        val deadline = System.currentTimeMillis() + 5_000
        while (winner() == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertEquals("winner stored", st.variant, winner()) // WP-B/WP-C behaviour
    }
}
