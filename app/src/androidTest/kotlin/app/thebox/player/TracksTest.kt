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
        // U-B2 / U-S8 (d): where cues are drawn. The view's bottom padding shrinks the box the cues are laid out in (its
        // one child, the canvas output): 8 % of the video above the bottom with no bar, 16 design px above the info bar.
        toBareVideo()
        Thread.sleep(500)
        val scale = v[2].let { (it - v[0]) / 1920f }
        val box = cueBox(subs[0])
        log("I-M4-8 cue box without a bar ${box.toList()}")
        assertTrue("no bar: cue box ends ≥ 8 % above the video bottom (${box[3]} vs ${v[3]})", box[3] <= v[3] - 0.08f * (v[3] - v[1]) + 1)
        press(K_RIGHT) // live without archive: RIGHT opens the info bar
        awaitPlayer("info bar") { it.layer == PlayerLayer.INFO }
        Thread.sleep(500)
        val boxBar = cueBox(subs[0])
        log("I-M4-8 cue box with the info bar ${boxBar.toList()}")
        assertTrue("info bar: cue box ends 16 px above y 820 (${boxBar[3]})", boxBar[3] <= (804f * scale) + 1)
        toBareVideo()
        openQuickBar()
        quickSelect(QuickItem.SUBTITLES)
        pickerSelect(PickerKind.SUBTITLES) { it is PickerChoice.TextOff }
        val cleared = runBlocking { withTimeoutOrNull(3_000) { controller.cues.first { it.isEmpty() } } }
        assertNotNull("Off clears the cues", cleared) // WP-C behaviour
    }

    /** The screen rect of the box cues are laid out in: the SubtitleView's child (its padding shrinks it, U-B2). */
    private fun cueBox(subtitleView: android.view.View): IntArray {
        val child = main { (subtitleView as android.view.ViewGroup).getChildAt(0) }
        assertNotNull("SubtitleView has its output child", child)
        return screenRect(child!!)
    }

    /**
     * V8: channel 7 (`multi` TS: CC1 in H.264 SEI, no caption descriptor) offers a speculative CEA-608 track, so the quick
     * bar has a Subtitles item; choosing it decodes the clip's captions.
     */
    @Test
    fun speculativeCea608OnTsWithoutDescriptor() {
        fullOn(fx.multiTs)
        awaitController("multi TS playing") { it is PlaybackState.Playing }
        val tracks = runBlocking { withTimeoutOrNull(15_000) { controller.tracks.first { t -> t.text.isNotEmpty() } } }
        assertNotNull("a speculative CC track is exposed: ${controller.tracks.value}", tracks)
        log("V8 text tracks: ${controller.tracks.value.text}")
        openQuickBar()
        assertTrue("the quick bar has a Subtitles item: ${pui().quick?.items}", pui().quick?.items?.contains(QuickItem.SUBTITLES) == true)
        quickSelect(QuickItem.SUBTITLES)
        val chosen = pickerSelect(PickerKind.SUBTITLES) { it is PickerChoice.Text } as PickerChoice.Text
        assertTrue("the CC track is labelled speculative: ${chosen.track}", chosen.track.speculative)
        val cues = runBlocking { withTimeoutOrNull(10_000) { controller.cues.first { it.isNotEmpty() } } }
        log("V8 cues: ${cues?.map { it.text }}")
        assertNotNull("CC1 captions decode within 10 s", cues)
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
