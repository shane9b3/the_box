package app.thebox.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.PlayerLayer
import app.thebox.core.guide.ScreenMode
import app.thebox.core.playback.NextStep
import app.thebox.player.api.PlaybackState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-16: error cards for `auth_mode 403`, `status Expired`, `max_connections 0` with linger, `html_splash`, docs/design/
 * m4-player-ux.md §3.7, §5.5. Each card shows a reason (the ladder's code), a detail line (status/host), and buttons per
 * §3.7 (GUIDE last, RETRY only when the code's actions contain it); Retry re-tunes once the panel is healthy again; Back
 * goes to the guide. Depends on WP-A (ErrorCardModel, ERROR layer), WP-C (ladder, `errorDetail`), WP-D (TunedStateMapper).
 */
@RunWith(AndroidJUnit4::class)
class PlayerErrorCardTest : PlayerTestBase() {

    private class Case(val name: String, val config: JSONObject)

    private val cases = listOf(
        Case("auth_mode 403", JSONObject().put("auth_mode", "403")),
        Case("status Expired", JSONObject().put("status", "Expired")),
        Case("max_connections 0 linger", JSONObject().put("max_connections", 0).put("faults", JSONObject().put("linger", 5).put("linger_status", 458))),
        Case("html_splash", JSONObject().put("faults", JSONObject().put("html_splash", true))),
    )

    @Test
    fun errorCardsShowReasonDetailAndButtons() {
        val results = ArrayList<String>()
        for (c in cases) {
            panel.reset()
            pp.enableFixtures()
            val ch = fullOn(10)
            awaitController("${c.name}: healthy start") { it is PlaybackState.Playing && it.target.identity == ch.identity }
            pp.patch(c.config)
            press(K_CH_UP)
            val card = awaitPlayer("${c.name}: error card", 45_000) { it.layer == PlayerLayer.ERROR && it.error != null }.error!! // WP-A/WP-C behaviour
            val e = card.error
            results += "${c.name}: ${e.code} detail=${e.detail} buttons=${card.buttons} text=${card.textStep} actions=${e.actions}"
            log("I-M4-16 ${results.last()}")
            assertTrue("${c.name}: buttons present", card.buttons.isNotEmpty())
            assertEquals("${c.name}: GUIDE is always last", ErrorButton.GUIDE, card.buttons.last()) // WP-A behaviour (§3.7)
            assertEquals("${c.name}: RETRY only when the code's actions contain it", e.actions.contains(NextStep.RETRY), card.buttons.contains(ErrorButton.RETRY)) // WP-A behaviour
            assertTrue("${c.name}: a detail line (status or host)", e.detail.httpStatus != null || e.detail.host != null) // WP-C behaviour
            if (card.buttons.contains(ErrorButton.RETRY)) {
                panel.reset()
                pp.enableFixtures()
                val target = card.buttons.indexOf(ErrorButton.RETRY)
                var cur = card.focused
                while (cur != target) {
                    press(if (target > cur) K_RIGHT else K_LEFT, intervalMs = 100)
                    cur += if (target > cur) 1 else -1
                }
                press(K_OK)
                awaitController("${c.name}: Retry re-tunes", 30_000) { it is PlaybackState.Playing } // WP-A behaviour
                awaitPlayer("${c.name}: card gone") { it.layer != PlayerLayer.ERROR }
                // Provoke the card again for the Back check.
                pp.patch(c.config)
                press(K_CH_UP)
                awaitPlayer("${c.name}: card again", 45_000) { it.layer == PlayerLayer.ERROR }
            }
            press(K_BACK)
            awaitUi("${c.name}: Back goes to the guide", 5_000) { it.mode == ScreenMode.GUIDE } // WP-A behaviour (product S8)
        }
        log("I-M4-16 summary:\n${results.joinToString("\n")}")
    }
}
