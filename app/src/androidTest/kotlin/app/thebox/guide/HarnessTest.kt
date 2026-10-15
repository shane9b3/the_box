package app.thebox.guide

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.PerformanceMode
import app.thebox.debug.GuideDebugReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * The suite's own plumbing (WP-D), independent of WP-A/B/C: the probe entry point, the `thebox-debug://guide` seeding
 * route against the fake panel, the switch mapping, and the synthetic panel's facts. These must pass before the
 * guide exists.
 */
@RunWith(AndroidJUnit4::class)
class HarnessTest : GuideTestBase() {
    override val needsSynthetic = false

    @Test
    fun probeEntryPointResolves() {
        val probe = ep.probe()
        assertNotNull(probe)
        // No MainActivity was launched by this class, so no GuideViewModel can have registered a machine.
        assertNull("GuideDebugProbe.current before any guide exists", probe.current)
        // The other entry-point members resolve (construction only; stubbed methods are not called).
        assertNotNull(ep.keyCapabilities())
        assertNotNull(ep.reminderBook())
        assertNotNull(ep.guideSettings())
        assertNotNull(ep.epgStore())
    }

    @Test
    fun deepLinkSeedsPlaylistChannelsAndEpg() = runBlocking {
        val st = GuideDebugReceiver.status.value
        assertEquals(GuideDebugReceiver.SeedState.DONE, st.state)
        val playlist = ep.playlists().get(playlistId)
        assertNotNull("playlist row $playlistId", playlist)
        val channels = ep.channels().browseAll(playlistId, unlocked = true).first()
        assertTrue("channels imported (${channels.size})", channels.isNotEmpty())
        assertEquals("status channel count", channels.size, st.channels)
        val gen = ep.epgStore().info.value
        assertNotNull("EPG generation present", gen)
        assertTrue("EPG programmes imported (${gen!!.programmes})", gen.programmes > 0)
        assertTrue("epg-${gen.generation}.db on disk", File(File(context.noBackupFilesDir, "epg"), "epg-${gen.generation}.db").exists())
        panel.synthetic()?.let { syn ->
            assertEquals("synthetic lineup imported whole", syn.n, channels.size)
        }
        log("seeded ${channels.size} channels, generation ${gen.generation} with ${gen.programmes} programmes")
        // Idempotence: a second seed with refresh=0 reuses the playlist.
        val again = seed()
        assertEquals("same playlist on re-seed", playlistId, again.playlistId)
    }

    @Test
    fun switchesMapOntoGuideSettings() {
        val s = GuideDebugReceiver.applySwitches(GuideSettings(), Uri.parse("thebox-debug://guide?lowend=1&still=1&clock24=0&exit=ask&unlocked=1"))
        assertEquals(PerformanceMode.LOW, s.performance)
        assertTrue(s.miniStill)
        assertEquals(false, s.clock24)
        assertEquals(ExitConfirmation.ASK, s.exitConfirmation)
        assertTrue(s.debugUnlocked)
        val d = GuideDebugReceiver.applySwitches(s, Uri.parse("thebox-debug://guide?lowend=0&still=0&exit=none&unlocked=0"))
        assertEquals(PerformanceMode.HIGH, d.performance)
        assertEquals(ExitConfirmation.NONE, d.exitConfirmation)
        assertEquals(PerformanceMode.AUTO, GuideDebugReceiver.applySwitches(d, Uri.parse("thebox-debug://guide?lowend=1&perf=auto")).performance)
        // Device bug G2: per-class switches come after DEFAULT_SWITCHES; the last value wins.
        val dup = GuideDebugReceiver.applySwitches(GuideSettings(), Uri.parse("thebox-debug://guide?lowend=0&still=0&exit=twice&lowend=1&still=1&exit=ask"))
        assertEquals(PerformanceMode.LOW, dup.performance)
        assertTrue(dup.miniStill)
        assertEquals(ExitConfirmation.ASK, dup.exitConfirmation)
    }

    @Test
    fun switchesReachTheSettingsSource() {
        val st = seed("lowend=1&still=1&exit=ask")
        assumeTrue("GuideSettingsSource is still the WP-B stub: ${st.settingsError}", st.settingsApplied)
        val s = ep.guideSettings().settings.value
        assertEquals(PerformanceMode.LOW, s.performance)
        assertTrue(s.miniStill)
        assertEquals(ExitConfirmation.ASK, s.exitConfirmation)
        seed() // back to DEFAULT_SWITCHES
        assertEquals(PerformanceMode.HIGH, ep.guideSettings().settings.value.performance)
    }

    @Test
    fun syntheticPanelExposesLineupAndReminderProgramme() {
        val syn = panel.synthetic()
        assumeTrue("panel not started with --synthetic N", syn != null)
        syn!!
        assertTrue("synthetic n ${syn.n}", syn.n > 0)
        assertEquals(listOf(syn.startUnix + 120, syn.startUnix + 300), syn.reminderStarts)
        // The panel's own Xtream API agrees: channel 1's "Reminder Test Start" at S + 120 s.
        val body = OkHttpClient().newCall(
            Request.Builder().url("$base/player_api.php?username=test&password=test&action=get_simple_data_table&stream_id=1").build(),
        ).execute().use { it.body.string() }
        val listings = JSONObject(body).getJSONArray("epg_listings")
        val hit = (0 until listings.length()).map { listings.getJSONObject(it) }.firstOrNull { it.optString("start_timestamp") == syn.reminderStarts[0].toString() }
        assertNotNull("a listing starting at ${syn.reminderStarts[0]} on stream 1", hit)
        val title = hit!!.getString("title").let { t -> runCatching { String(Base64.getDecoder().decode(t)) }.getOrDefault(t) }
        assertEquals("Reminder Test Start", title)
        // And the imported guide has it (search may still be unimplemented; then this part only logs).
        runCatching {
            ep.epgStore().openReader()?.use { r -> r.search("Reminder Test Start", nowMin()) }
        }.onSuccess { hits ->
            log("imported EPG search hits for the reminder programme: ${hits?.size}")
            hits?.let { h -> assertTrue("imported reminder programme at raw ${syn.reminderStarts[0] / 60}", h.any { it.rawStartMin.toLong() == syn.reminderStarts[0] / 60 }) }
        }.onFailure { log("EpgReader.search unavailable: $it") }
    }
}
