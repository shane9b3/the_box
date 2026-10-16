package app.thebox.ui.input

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeKeyCapabilities
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.input.KeyOverride
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.input.RemappedKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m5-setup-settings.md §3.8.4 / §2.12 item 5 (S14) `KeyRouterImplTest` capture rows: armed → the DOWN goes
 * to the listener, its UP is consumed, repeats are ignored, BACK is delivered, disarm restores routing, focus loss
 * disarms, a held captured key is swallowed after the disarm, KEYCODE_UNKNOWN is captured (and refused by the rules).
 * Also: the router reads the remapped key map (no second path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyRouterCaptureTest {
    private class Timers : KeyRouterImpl.Timers {
        val pending = LinkedHashMap<Any, () -> Unit>()
        override fun post(delayMs: Long, block: () -> Unit): Any = Any().also { pending[it] = block }
        override fun cancel(token: Any) { pending.remove(token) }
    }

    private val machine = FakeGuideStateMachine()
    private val map = RemappedKeyMap(DefaultKeyMap(), null, null)
    private val router = KeyRouterImpl(map, KeyGestureDetectorImpl(), FakeKeyCapabilities(), Timers()) { 0L }.also { it.attach(machine) { true } }
    private val captured = ArrayList<CapturedKey>()

    private fun down(code: Int, repeat: Int = 0, device: String? = "Fire TV Remote") =
        router.onNative(code, true, repeat, 1_000, 1_000 + repeat * 50L, deviceId = 4, deviceName = device, isVirtual = device == null)
    private fun up(code: Int) = router.onNative(code, false, 0, 1_000, 1_500)
    private fun acts() = machine.events.filterIsInstance<GuideEvent.Act>()

    @Test fun armedDeliversTheNextDownWithItsDevice() {
        router.capture { captured += it }
        assertTrue(router.captureArmed)
        assertTrue(down(Keys.PROG_RED))
        assertFalse("disarmed after one key", router.captureArmed)
        assertEquals(1, captured.size)
        val k = captured.single()
        assertEquals(Keys.PROG_RED, k.keyCode); assertEquals("Fire TV Remote", k.deviceName); assertEquals(4, k.deviceId); assertFalse(k.isVirtual)
        assertTrue("the platform name (Robolectric may give the number)", k.keyName == "KEYCODE_PROG_RED" || k.keyName == Keys.PROG_RED.toString())
        assertTrue("its UP consumed", up(Keys.PROG_RED))
        assertTrue("the key map was never asked", machine.events.isEmpty())
    }

    @Test fun heldCapturedKeyIsSwallowedUntilItsUpEvenAfterTheDisarm() {
        router.capture { captured += it }
        down(Keys.CHANNEL_UP)
        for (r in 1..5) assertTrue(down(Keys.CHANNEL_UP, repeat = r))
        assertTrue(up(Keys.CHANNEL_UP))
        assertTrue("no repeat re-entered routing as a fresh press", acts().isEmpty())
        assertEquals(1, captured.size)
        // After the UP the key routes again.
        assertTrue(down(Keys.CHANNEL_UP)); up(Keys.CHANNEL_UP)
        assertEquals(BoxAction.ZAP_UP, acts().single().action)
    }

    @Test fun repeatsWhileArmedAreIgnoredAndBackIsDelivered() {
        router.capture { captured += it }
        assertTrue(down(Keys.PROG_GREEN, repeat = 3)) // a repeat of a key pressed before arming that we did not route
        assertTrue(router.captureArmed)
        assertTrue(down(Keys.BACK))
        assertEquals(Keys.BACK, captured.single().keyCode)
        up(Keys.BACK)
        assertTrue(machine.events.isEmpty())
    }

    @Test fun disarmAndFocusLossRestoreRouting() {
        router.capture { captured += it }
        router.capture(null)
        assertTrue(down(Keys.DPAD_DOWN)); up(Keys.DPAD_DOWN)
        assertEquals(BoxAction.DOWN, acts().single().action)
        router.capture { captured += it }
        router.onFocusLost()
        assertFalse(router.captureArmed)
        assertTrue(down(Keys.DPAD_UP)); up(Keys.DPAD_UP)
        assertTrue(captured.isEmpty())
    }

    @Test fun keyHeldFromBeforeArmingFinishesOnTheNormalPath() {
        assertTrue(down(Keys.DPAD_DOWN))
        router.capture { captured += it }
        assertTrue(up(Keys.DPAD_DOWN))
        assertTrue(captured.isEmpty())
        assertTrue(router.captureArmed)
    }

    @Test fun unknownCodeAndVirtualDevicesAreCaptured() {
        router.capture { captured += it }
        down(0, device = null)
        val k = captured.single()
        assertEquals(0, k.keyCode); assertTrue(k.isVirtual)
        assertEquals(app.thebox.core.input.ReservedReason.UNKNOWN_CODE, app.thebox.core.input.BindingRules.reserved(KeyContext.GUIDE, 0, Press.SHORT))
    }

    @Test fun theRouterReadsTheRemappedMap() {
        map.apply(KeyBindingOverrides(entries = listOf(KeyOverride(KeyContext.GUIDE, Keys.PROG_RED, Press.SHORT, BoxAction.GROUPS_PANEL.id, null, 1))))
        assertTrue(down(Keys.PROG_RED)); up(Keys.PROG_RED)
        assertEquals(BoxAction.GROUPS_PANEL, acts().single().action)
        map.apply(KeyBindingOverrides(entries = listOf(KeyOverride(KeyContext.GUIDE, Keys.PROG_RED, Press.SHORT, null, null, 2))))
        assertFalse("unbound: on to the framework", down(Keys.PROG_RED))
    }
}
