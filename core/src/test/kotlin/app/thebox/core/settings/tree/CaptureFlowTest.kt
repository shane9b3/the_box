package app.thebox.core.settings.tree

import app.thebox.core.input.BindingVerdict
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.input.ReservedReason
import app.thebox.core.settings.tree.CaptureFlow.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** docs/design/m5-setup-settings.md §3.8.3 capture phases (S14): waiting, captured, buttons, timeout, test a key. */
class CaptureFlowTest {
    private val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(KeyBindingOverrides()) }
    private fun flow() = CaptureFlow { map }
    private fun key(code: Int, device: String? = "Amazon Fire TV Remote") = CapturedKey(code, "KEYCODE_$code", device, 5, isVirtual = device == null)

    @Test fun captureThenSave() {
        val f = flow()
        assertEquals(Result.Arm(true), f.start(BoxAction.AUDIO, Press.SHORT, nowMs = 100))
        val s = assertNotNull(f.state)
        assertEquals(BoxAction.AUDIO, s.action); assertEquals(100, s.startedAtMs)
        assertEquals(Result.Arm(false), f.onKey(key(Keys.PROG_RED)), "the router disarms after one key")
        assertEquals(BindingVerdict.Replaces(BoxAction.START_OVER, setOf(app.thebox.core.input.KeyContext.PLAYER)), f.state!!.verdict)
        assertEquals(0, f.focusedButton, "focus on Save")
        val r = assertIs<Result.Save>(f.act(BoxAction.SELECT, 200))
        assertEquals(Keys.PROG_RED, r.key.keyCode); assertEquals(Press.SHORT, r.press); assertEquals(BoxAction.AUDIO, r.action)
        assertNull(f.state)
    }

    @Test fun reservedFocusesTryAnotherAndCannotSave() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.SHORT, 0)
        f.onKey(key(Keys.DPAD_UP))
        assertEquals(BindingVerdict.Reserved(ReservedReason.NAVIGATION), f.state!!.verdict)
        assertEquals(1, f.focusedButton)
        f.act(BoxAction.LEFT, 1)
        assertEquals(Result.None, f.act(BoxAction.SELECT, 2), "Save does nothing on a reserved key")
        f.act(BoxAction.RIGHT, 3)
        assertEquals(Result.Arm(true), f.act(BoxAction.SELECT, 4), "Try another re-arms")
        assertNull(f.state!!.captured)
    }

    @Test fun backCancelsInEveryPhase() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.LONG, 0)
        assertEquals(Result.Closed(timedOut = false), f.onKey(key(Keys.BACK)), "BACK while waiting is cancel")
        f.start(BoxAction.AUDIO, Press.LONG, 0)
        assertEquals(Result.Closed(timedOut = false), f.onKey(key(Keys.BUTTON_B)))
        f.start(BoxAction.AUDIO, Press.LONG, 0)
        f.onKey(key(Keys.PROG_GREEN))
        assertEquals(Result.Closed(timedOut = false), f.act(BoxAction.BACK, 1))
        f.start(BoxAction.AUDIO, Press.LONG, 0)
        f.onKey(key(Keys.PROG_GREEN))
        f.act(BoxAction.RIGHT, 1); f.act(BoxAction.RIGHT, 1)
        assertEquals(Result.Closed(timedOut = false), f.act(BoxAction.SELECT, 1), "Cancel")
    }

    @Test fun timeoutClosesWithTheNotice() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.SHORT, 0)
        val token = f.state!!.token
        assertEquals(Result.None, f.onTimeout(token + 1), "a stale timer")
        assertEquals(Result.Closed(timedOut = true), f.onTimeout(token))
        // A timer after a capture does nothing.
        f.start(BoxAction.AUDIO, Press.SHORT, 0)
        f.onKey(key(Keys.PROG_GREEN))
        assertEquals(Result.None, f.onTimeout(f.state!!.token))
    }

    @Test fun repeatsAfterTheCaptureAreIgnoredByTheFlow() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.SHORT, 0)
        f.onKey(key(Keys.PROG_GREEN))
        assertEquals(Result.None, f.onKey(key(Keys.PROG_BLUE)), "one key per capture")
        assertEquals(Keys.PROG_GREEN, f.state!!.captured!!.keyCode)
    }

    @Test fun longOnARepeatingKeyWarns() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.LONG, 0)
        f.onKey(key(Keys.CHANNEL_UP))
        assertIs<BindingVerdict.LosesRepeat>(f.state!!.verdict)
    }

    @Test fun testAKeyReportsWhatItDoes() {
        val f = flow()
        f.start(BoxAction.AUDIO, Press.SHORT, 0, test = true)
        f.onKey(key(Keys.PROG_RED, device = null))
        assertEquals(BoxAction.START_OVER to BoxAction.START_OVER, f.testReport)
        assertEquals(1, f.focusedButton)
        assertEquals(Result.Arm(true), f.act(BoxAction.SELECT, 1), "Try another")
        f.onKey(key(999))
        assertEquals(null to null, f.testReport)
        f.act(BoxAction.LEFT, 1)
        assertEquals(1, f.focusedButton, "no Save in test mode")
    }
}
