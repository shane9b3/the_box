package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.data.repo.Trigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * I-6: an EPG rebuild while scrolling (the panel's `epg_version` bump changes every synthetic title to "… v2"): no
 * crash, the new generation is drawn, the old `epg-<gen>.db` is deleted, reminders unchanged.
 */
@RunWith(AndroidJUnit4::class)
class GenerationSwapTest : GuideTestBase() {

    @Test
    fun rebuildWhileScrolling() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        // A reminder that must survive the swap: a future programme on row 0, set with the REMIND key (MEDIA_RECORD).
        press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        press(KeyEvent.KEYCODE_MEDIA_RECORD)
        Thread.sleep(500)
        val before = ep.reminderBook().pending.value
        log("I-6 reminders before: ${before.size}")
        val oldGen = ep.epgStore().info.value
        assertNotNull(oldGen)
        val oldFile = File(File(context.noBackupFilesDir, "epg"), "epg-${oldGen!!.generation}.db")

        panel.setEpgVersion(1)
        val rebuild = runBlocking(Dispatchers.Default) {
            val job = async(Dispatchers.IO) { ep.epgImporter().build(Trigger.MANUAL, force = true) }
            // Scroll while the build runs (and across the publish).
            while (!job.isCompleted) {
                press(KeyEvent.KEYCODE_DPAD_DOWN, times = 5, intervalMs = 40)
                press(KeyEvent.KEYCODE_DPAD_UP, times = 5, intervalMs = 40)
            }
            job.await()
        }
        assertNull("rebuild error: ${rebuild.error}", rebuild.error)
        val newGen = ep.epgStore().info.value!!
        assertTrue("generation advanced (${oldGen.generation} → ${newGen.generation})", newGen.generation > oldGen.generation)

        awaitFrame("new generation drawn", 10_000) { f -> f.data?.generation == newGen.generation }
        awaitFrame("v2 titles on screen", 10_000) { f -> f.data?.cellTitle?.any { it?.endsWith(" v2") == true } == true }
        var gone = false
        repeat(50) { if (!gone) { gone = !oldFile.exists(); if (!gone) Thread.sleep(200) } }
        assertFalse("old ${oldFile.name} deleted", oldFile.exists())
        assertEquals("reminders unchanged", before.map { it.id to it.rawStartMin }, ep.reminderBook().pending.value.map { it.id to it.rawStartMin })
        panel.setEpgVersion(0)
    }
}
