package app.thebox.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.player.api.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-7 (audio picker on the `multi` TS clip), I-M4-8 (WebVTT subtitles on the `multi` HLS channel) and I-M4-23
 * (catch-up audio with five keys), docs/design/m4-player-ux.md §3.4.2, §3.6.3, §5.5.
 * Panel: `player_fixtures` (multi_ts channel 7: AAC eng + MP2 deu, archive; multi_hls channel 8: master playlist with a
 * WebVTT rendition). Depends on WP-A (quick bar, pickers), WP-B (archive, I-M4-23), WP-C (`cues`, track menu),
 * WP-D (SubtitleLayer, mappers).
 */
@RunWith(AndroidJUnit4::class)
class TracksTest : PlayerTestBase() {

    private fun selectedAudioLang(): String? = controller.tracks.value.audio.firstOrNull { it.selected }?.language

    private fun awaitTwoAudio() {
        val ok = runBlocking { withTimeoutOrNull(15_000) { controller.tracks.first { t -> t.audio.size >= 2 } } }
        assertNotNull("the multi clip exposes two audio tracks: ${controller.tracks.value}", ok)
    }

    /** Up → quick bar → Audio → the other language; returns that language. */
    private fun switchAudioWithFiveKeys(): String {
        val before = selectedAudioLang()
        openQuickBar()
        quickSelect(QuickItem.AUDIO) // WP-A behaviour (AUDIO shown with ≥ 2 choices, first item)
        val chosen = pickerSelect(PickerKind.AUDIO) { it is PickerChoice.Audio && !it.track.selected && it.track.language != before } as PickerChoice.Audio
        val lang = chosen.track.language
        val deadline = System.currentTimeMillis() + 5_000
        while (selectedAudioLang() != lang && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals("tracks.audio.selected follows the picker", lang, selectedAudioLang()) // WP-C/WP-D behaviour
        assertEquals("the picker closes to video", PlayerLayer.NONE, pui().layer) // WP-A behaviour
        return lang ?: "?"
    }

    /** I-M4-7: two languages listed; the second becomes selected; a re-tune of the channel keeps it (per-channel memory). */
    @Test
    fun audioPicker() {
        val ch = fullOn(fx.multiTs)
        awaitController("multi playing") { it is PlaybackState.Playing }
        awaitTwoAudio()
        val langs = controller.tracks.value.audio.map { it.language }.toSet()
        assertTrue("eng and deu: $langs", langs.containsAll(listOf("eng", "deu")) || langs.containsAll(listOf("en", "de")))
        val lang = switchAudioWithFiveKeys()
        press(K_CH_UP)
        awaitController("zapped away") { it is PlaybackState.Playing && it.target.identity != ch.identity }
        press(K_CH_DOWN)
        awaitController("back on the multi channel") { it is PlaybackState.Playing && it.target.identity == ch.identity }
        awaitTwoAudio()
        Thread.sleep(1_000)
        assertEquals("the audio language is remembered per channel", lang, selectedAudioLang()) // M2 channel memory + WP-C
    }

    /** I-M4-8: Subtitles → WebVTT track on → cues within 5 s, SubtitleView inside the video rect; Off clears. */
    @Test
    fun webVttSubtitles() {
        fullOn(fx.multiHls)
        awaitController("multi HLS playing") { it is PlaybackState.Playing }
        val tracks = runBlocking { withTimeoutOrNull(15_000) { controller.tracks.first { it.text.isNotEmpty() } } }
        assertNotNull("a text track is exposed: ${controller.tracks.value}", tracks)
        openQuickBar()
        quickSelect(QuickItem.SUBTITLES) // WP-A behaviour
        pickerSelect(PickerKind.SUBTITLES) { it is PickerChoice.Text && !it.track.speculative && (it.track.kind.contains("VTT", true) || it.track.language?.startsWith("en") == true) }
        val cues = runBlocking { withTimeoutOrNull(5_000) { controller.cues.first { it.isNotEmpty() } } }
        assertNotNull("cues within 5 s of switching subtitles on", cues) // WP-C behaviour
        val subs = subtitleViews()
        assertEquals("one SubtitleView in FULL", 1, subs.size) // WP-D behaviour
        val surface = surfaceViews().single()
        val r = screenRect(subs[0])
        val v = screenRect(surface)
        log("I-M4-8 SubtitleView ${r.toList()} video ${v.toList()}")
        assertTrue("SubtitleView bounds inside videoRect", r[0] >= v[0] - 1 && r[1] >= v[1] - 1 && r[2] <= v[2] + 1 && r[3] <= v[3] + 1) // WP-D behaviour
        openQuickBar()
        quickSelect(QuickItem.SUBTITLES)
        pickerSelect(PickerKind.SUBTITLES) { it is PickerChoice.TextOff }
        val cleared = runBlocking { withTimeoutOrNull(3_000) { controller.cues.first { it.isEmpty() } } }
        assertNotNull("Off clears the cues", cleared) // WP-C behaviour
    }

    /** I-M4-23: `multi` archive: Back, Up → quick bar → Audio → second language, D-pad/OK/Back only (B4, owner ruling). */
    @Test
    fun catchupAudioWithFiveKeys() {
        playPast(fx.multiTs, endedMinAgo = 1)
        awaitArchive("archive playing", 20_000) { it.phase == app.thebox.core.catchup.archive.ArchivePhase.PLAYING } // WP-B behaviour
        awaitTwoAudio()
        // "Back" closes the auto-shown scrub bar when it holds the keys; never BACK on bare video (that leaves FULL).
        toBareVideo()
        switchAudioWithFiveKeys()
        assertTrue("still in catch-up after the switch: ${archive().brief()}", archive().active() && controller.state.value.target?.isArchive == true) // WP-B behaviour
    }
}
