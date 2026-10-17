package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Player machine fixes of the M5 code review (2026-10-16); each test names its finding. */
class PlayerReviewFixTest {
    private fun lockFromTheList(w: PlayerWorld) {
        w.press(BoxAction.SELECT) // the channel list
        check(w.ui.layer == PlayerLayer.CHANNELS) { "channel list: ${w.ui.layer}" }
        w.press(BoxAction.CONTEXT_MENU)
        val menu = w.ui.channels!!.menu!!
        repeat(menu.indexOf(app.thebox.core.full.ChannelMenuItem.LOCK_CHANNEL)) { w.press(BoxAction.DOWN) }
        w.press(BoxAction.SELECT)
    }

    /** U-S10 (§3.9.1, B3): Lock from the player's channel list toasts; without a PIN it says the lock does not protect. */
    @Test
    fun lockFromChannelListToasts() {
        val noPin = PlayerWorld()
        lockFromTheList(noPin)
        assertEquals(true, noPin.fx.calls.last { it.name == "setChannelLocked" }.args[1])
        assertEquals(PlayerToast.LOCKED_NOT_PROTECTED, noPin.fx.calls.last { it.name == "toast" }.args[0])

        val withPin = PlayerWorld()
        withPin.send(PlayerEvent.Parental(ParentalState(pinSet = true, unlocked = true, policy = ParentalPolicy())))
        lockFromTheList(withPin)
        assertEquals(PlayerToast.CHANNEL_LOCKED, withPin.fx.calls.last { it.name == "toast" }.args[0])
    }

    /** U-S12 (B2): with "Always passthrough" the AUDIO picker ends with Back to Auto, and OK runs it. */
    @Test
    fun audioPickerOffersBackToAutoWhenForced() {
        val w = PlayerWorld(tracks = PlayerFixtures.trackMenu().copy(audioOutputForced = true))
        w.press(BoxAction.AUDIO)
        val p = w.ui.picker!!
        assertEquals(PickerKind.AUDIO, p.kind)
        assertEquals(PickerChoice.AudioOutputAuto, p.choices.last())
        repeat(p.choices.size) { w.press(BoxAction.DOWN) }
        w.press(BoxAction.SELECT)
        assertTrue(w.fx.calls.any { it.name == "audioOutputAuto" }, "Back to Auto ran: ${w.fx.calls.takeLast(4).map { it.name }}")

        val plain = PlayerWorld()
        plain.press(BoxAction.AUDIO)
        assertFalse(PickerChoice.AudioOutputAuto in plain.ui.picker!!.choices, "only while forced")
    }
}
