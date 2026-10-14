package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.PlaybackNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** I-10 405 flip, I-11 HTML splash, I-12 UA whitelist, I-17 expired account, I-18 404. */
@RunWith(AndroidJUnit4::class)
class ErrorPathTest : PanelTestBase() {

    @Test
    fun i10FormatFlipFromHlsToTs() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(reject405OnM3u8 = true)))
        runBlocking {
            val p = ep.playlists().get(playlistId)!!
            ep.playlists().update(p.copy(xtreamOutput = "HLS"))
        }
        launch(ch(1))
        awaitPlaying(15_000)
        Thread.sleep(500)
        assertTrue("notices: $notices", notices.contains(PlaybackNotice.SwitchedFormat(StreamFormat.TS)))

        // AUTO with only m3u8 allowed: the first request is already .m3u8.
        panel.config(FakePanelAdmin.Config(allowedOutputFormats = listOf("m3u8"), faults = FakePanelAdmin.Faults(reject405OnM3u8 = false)))
        runBlocking {
            val p = ep.playlists().get(playlistId)!!
            ep.playlists().update(p.copy(xtreamOutput = "AUTO", xtreamOutputLearned = null))
        }
        refreshPlaylist()
        launch(ch(2))
        awaitPlayingOn(ch(2), 15_000)
        val first = panel.stats().streamRequests(2).first().optString("path")
        log("I-10 first ch2 request: $first")
        assertTrue(first.endsWith(".m3u8"))
    }

    @Test
    fun i11HtmlSplash() {
        panel.config(FakePanelAdmin.Config(streamFaults = mapOf(2 to FakePanelAdmin.Faults(htmlSplash = true))))
        launch(ch(2))
        val (err, ms) = awaitError(10_000)
        log("I-11 WEB_PAGE_INSTEAD after $ms ms (design ≤ 3 s)")
        assertEquals(PlaybackErrorCode.WEB_PAGE_INSTEAD, err.error.code)
        assertTrue(panel.stats().streamRequests(2).size <= 2)
    }

    @Test
    fun i12UserAgentFallbackOnAndOff() {
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(uaWhitelist = listOf("VLC"))))
        ep.settings().update { it.copy(autoUserAgentFallback = true) }
        launch(ch(1))
        awaitPlaying(15_000)
        Thread.sleep(500)
        assertTrue("notices: $notices", notices.any { it is PlaybackNotice.UserAgentSwitched })

        onMain { controller.stop() }
        ep.settings().update { it.copy(autoUserAgentFallback = false) }
        launch(ch(2))
        val (err, _) = awaitError(15_000)
        assertEquals(PlaybackErrorCode.FORBIDDEN, err.error.code)
        assertTrue(NextStep.CHANGE_USER_AGENT in err.error.actions)
    }

    @Test
    fun i12M3uPipeUserAgentIsUsed() {
        val m3u = addM3uPlaylist()
        panel.config(FakePanelAdmin.Config(faults = FakePanelAdmin.Faults(uaWhitelist = listOf("Mozilla/5.0 (Fake)"))))
        ep.settings().update { it.copy(autoUserAgentFallback = false) }
        launch(m3uChannel(m3u, 6))
        awaitPlaying(15_000)
        val ua = panel.stats().streamRequests(6).last().optString("ua")
        log("I-12 ch6 UA: $ua")
        assertTrue(ua.contains("Mozilla/5.0 (Fake)"))
    }

    @Test
    fun i17ExpiredAccount() {
        panel.config(FakePanelAdmin.Config(status = "Expired"))
        launch(ch(1))
        val (err, ms) = awaitError(15_000)
        log("I-17 ${err.error.code} after $ms ms")
        assertEquals(PlaybackErrorCode.ACCOUNT_EXPIRED, err.error.code)
        assertTrue(panel.stats().streamRequests(1).size <= 2)
    }

    @Test
    fun i18UnknownStreamIsOffline() {
        launchUrl("$base/live/test/test/999.ts")
        val (err, ms) = awaitError(10_000)
        log("I-18 CHANNEL_OFFLINE after $ms ms (design ≤ 2 s)")
        assertEquals(PlaybackErrorCode.CHANNEL_OFFLINE, err.error.code)
    }
}
