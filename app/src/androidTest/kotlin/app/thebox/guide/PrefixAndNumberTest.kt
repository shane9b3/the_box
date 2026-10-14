package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.Overlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-13: synthetic `UK: `/`[DE] `/`FR | ` names show stripped and search "uk" finds them (search keeps the raw name).
 * Synthetic channel i is lineup row i − 1 (provider order) and is named "<theme> i" with a prefix by i % 10.
 */
@RunWith(AndroidJUnit4::class)
class PrefixTest : GuideTestBase() {

    @Test
    fun prefixesStrippedAndSearchable() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        awaitFrame("rows 0–9 loaded") { f -> (0..9).all { f.rows.channel(it) != null } }
        val names = (0..9).map { channelAt(it)!!.displayName }
        log("I-13 rows 0–9: $names")
        assertEquals("UK: News 3 → News 3", "News 3", names[2])
        assertEquals("[DE] News 5 → News 5", "News 5", names[4])
        assertEquals("FR | News 7 → News 7", "News 7", names[6])
        assertEquals("unprefixed unchanged", "News 2", names[1])
        val hits = runBlocking { ep.search().search("uk", System.currentTimeMillis(), unlocked = false) }.channels
        log("I-13 search \"uk\": ${hits.take(5).map { it.displayName }} (${hits.size})")
        assertTrue("search \"uk\" finds UK-prefixed channels (shown stripped)", hits.any { it.number == 3 && it.displayName == "News 3" })
    }
}

/** I-14: "1", "2", "3" → row of channel 123 focused at now after the 1.5 s commit; OK tunes it. */
@RunWith(AndroidJUnit4::class)
class NumberEntryTest : GuideTestBase() {

    @Test
    fun digitsFocusThenOkTunes() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        digits("123", intervalMs = 300)
        awaitUi("number overlay 123") { (it.overlay as? Overlay.Number)?.digits == "123" }
        awaitUi("auto-commit closes the overlay", 3_000) { it.overlay == null }
        awaitFrame("channel 123 focused at now") { f ->
            val fo = f.focus
            fo != null && f.rows.channel(fo.row)?.number == 123 && nowMin() in fo.cellStart until fo.cellEnd
        }
        val ch = tuneFocusedRow()
        assertEquals(123, ch.number)
    }
}
