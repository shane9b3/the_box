package app.thebox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness check: the device reaches the fake panel and [FakePanelAdmin]'s nested config is accepted (an unknown or
 * mis-nested key would be HTTP 400, review S13). Needs only the panel, not the playback stack.
 */
@RunWith(AndroidJUnit4::class)
class PanelAdminTest {
    private lateinit var panel: FakePanelAdmin

    @Before fun setUp() {
        val arg = InstrumentationRegistry.getArguments().getString(PanelTestBase.ARG)
        assumeTrue(!arg.isNullOrBlank())
        panel = FakePanelAdmin(arg!!.trimEnd('/'))
        assumeTrue(panel.reachable())
        panel.reset()
    }

    @Test
    fun nestedFaultConfigIsAcceptedAndStatsAreReadable() {
        val cfg = panel.config(
            FakePanelAdmin.Config(
                maxConnections = 1,
                faults = FakePanelAdmin.Faults(linger = 2, lingerStatus = "close", closeEvery = 15, reconnectMode = "jump", stallAfter = 10 to 8, uaWhitelist = listOf("VLC")),
                streamFaults = mapOf(1 to FakePanelAdmin.Faults(redirect = true, tokenTtl = 5), 2 to FakePanelAdmin.Faults(htmlSplash = true, reject405OnM3u8 = true)),
            ),
        )
        assertEquals(1, cfg.getInt("max_connections"))
        assertEquals("close", cfg.getJSONObject("faults").getString("linger_status"))
        assertTrue(cfg.getJSONObject("stream_faults").getJSONObject("2").getBoolean("html_splash"))
        val stats = panel.stats()
        assertEquals(0, stats.rangeRequestsSeen)
        assertEquals(0, stats.activeConnections)
        panel.kick()
        panel.reset()
        assertTrue(panel.stats().requestLog.isEmpty())
    }
}
