package app.thebox.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.full.ScrubFocus
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-20: the five-key journey on device (D-pad, OK, Back only; docs/design/m4-player-ux.md §3.1.6, §5.5): quick bar by
 * Up, Pause / rewind, rewind, jump to live, start over, previous channel; in catch-up, Back then Up reaches the quick
 * bar (and Audio when the stream has two languages). End states are logged next to the JVM table
 * (`PlayerFiveKeyReachabilityTest`, WP-A). Depends on WP-A (all key tables), WP-B (pauseLive, rewind, start over,
 * jump to live), WP-D (glue).
 */
@RunWith(AndroidJUnit4::class)
class FiveKeyJourneyTest : PlayerTestBase() {
    // These journeys assert M4's provider-archive pause; with the local buffer on they would pause in place (m6-timeshift.md §17.4).
    override val playerSwitches: String = "timeshift=off"

    @Test
    fun everyPlayerFunctionWithFiveKeys() {
        val start = fullOn(1)
        awaitController("live playing") { it is PlaybackState.Playing && !it.target.isArchive }
        val steps = ArrayList<String>()

        // 1–2. Quick bar by Up → Pause. (Integration 2026-10-19, M6 §11.3, U-B3: the TIME_SHIFT item is "Pause" and OK on
        // it pauses at once, opening the focused scrub bar at the pause point; before M6 it opened the bar at live and a
        // second OK paused.) With the buffer Off this catch-up channel pauses through the provider (PAUSED_LIVE).
        openQuickBar()
        quickSelect(QuickItem.TIME_SHIFT) // WP-A behaviour (§7 item 16a), M6 §11.3
        awaitArchive("PAUSED_LIVE by Pause", 5_000) { it.phase == ArchivePhase.PAUSED_LIVE } // WP-A/WP-B behaviour
        awaitPlayer("scrub focused at the pause point") { it.layer == PlayerLayer.SCRUB && it.scrub?.focused == true && it.scrub?.focus == ScrubFocus.BAR }
        steps += "quick bar → Pause: ${archive().phase} ${pui().brief()}"
        Thread.sleep(5_000)
        // 3. LEFT rewinds and commits: time-shift. (Code review 2026-10-16, P-S1: the live snap is direction-aware, so
        // the one step back (−10 s) from a 5 s pause requests the archive; the integration pass needed four, T9/L-6.)
        press(K_LEFT)
        // Integration 2026-10-15 (test fix): a seek while paused stays paused (S2, ArchivePlaybackImpl.seekRerequest with
        // wasPaused), so the rewind from PAUSED_LIVE lands in a PAUSED time-shift.
        awaitArchive("time-shift after the rewind commit", 20_000) { it.timeShift && (it.phase == ArchivePhase.PLAYING || it.phase == ArchivePhase.PAUSED) } // WP-A/WP-B behaviour
        steps += "LEFT → ${archive().brief()}"
        // 4. UP in the scrub bar → jump to live.
        if (pui().layer != PlayerLayer.SCRUB || pui().scrub?.focused != true) {
            toBareVideo()
            press(K_DOWN)
            awaitPlayer("scrub focused") { it.layer == PlayerLayer.SCRUB && it.scrub?.focused == true }
        }
        press(K_UP)
        awaitController("UP → live", 15_000) { it is PlaybackState.Playing && !it.target.isArchive } // WP-A/WP-B behaviour
        steps += "UP → live"
        // 5. Quick bar → Start over: the programme on now from its start (archive).
        openQuickBar()
        quickSelect(QuickItem.START_OVER)
        awaitArchive("start over playing", 20_000) { it.phase == ArchivePhase.PLAYING } // WP-B behaviour
        steps += "Start over → ${archive().brief()}"
        // 6. In catch-up: Back then Up reaches the quick bar (owner ruling; B4). Audio only with two languages.
        toBareVideo()
        press(K_UP)
        val q = awaitPlayer("quick bar in catch-up") { it.layer == PlayerLayer.QUICK_BAR }.quick!! // WP-A behaviour
        steps += "catch-up quick bar items ${q.items}"
        assertTrue("Back to live offered in catch-up", q.items.contains(QuickItem.JUMP_TO_LIVE)) // WP-A behaviour
        quickSelect(QuickItem.JUMP_TO_LIVE)
        awaitController("Back to live", 15_000) { it is PlaybackState.Playing && !it.target.isArchive }
        // Integration 2026-10-15 (test fix): the live cycle must play PREVIOUS_CHANNEL_MIN_PLAY_MS (1 s) before a zap away
        // makes it "previous" (M2 §3.1.1); without the wait the quick bar has no Previous channel.
        Thread.sleep(2_000)
        // 7. Previous channel: zap through the channel list (OK, DOWN, RIGHT), then quick bar → Previous channel.
        toBareVideo()
        press(K_OK)
        awaitPlayer("channel list") { it.layer == PlayerLayer.CHANNELS }
        press(K_DOWN)
        press(K_RIGHT)
        awaitController("list tune", 15_000) { it is PlaybackState.Playing && it.target.identity != start.identity }
        press(K_BACK)
        openQuickBar()
        quickSelect(QuickItem.PREVIOUS_CHANNEL)
        awaitController("previous channel", 15_000) { it is PlaybackState.Playing && it.target.identity == start.identity } // WP-A behaviour
        steps += "previous channel → ${start.displayName}"
        log("I-M4-20 journey:\n${steps.joinToString("\n")}")
        assertEquals(PlayerLayer.NONE, pui().layer)
    }
}
