package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-channel-editor.md §3.2 / §6.1: the shell seams a page drawn by another screen uses (editText,
 * editNumber, requestPin), each callback called exactly once on every outcome, and the keyHandler order.
 */
class SettingsShellLinkEditorTest {
    private val route = SettingsRoute.ChannelEditor(1)
    private val effects = QueueEffects()
    private val prompts = ArrayList<PinPurpose>()
    private var pinIsSet = true
    private val m = SettingsMachineImpl(effects, { 0L }).also {
        it.pinGate = PinGate { p -> prompts += p; pinIsSet }
        it.open(route, fromFull = false)
    }

    private fun press(a: BoxAction, digit: Int = -1) = m.act(GuideEvent.Act(a, digit = digit))
    private fun lastToken() = (prompts.last() as PinPurpose.SettingsAction).token

    /** Records every call of a callback, so "exactly once" is checked. */
    private class Calls<T> { val got = ArrayList<T>(); val cb: (T) -> Unit = { got += it } }

    @Nested inner class EditText {
        @Test fun emptyCommitsWithTheCallersValidator() {
            val c = Calls<String?>()
            m.editText("Name", "ESPN", TextHint.NAME, validate = { null }, onClosed = c.cb)
            assertEquals(SettingsMachineImpl.EXT_EDIT_ROW, m.ui.value.editor!!.row)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "", committed = true))
            assertEquals(listOf<String?>(""), c.got)
            assertNull(m.ui.value.editor)
            assertTrue(effects.intents.isEmpty(), "no RowIntent for an ext editor")
        }

        @Test fun theHintRuleAppliesWithoutAValidator() {
            val c = Calls<String?>()
            m.editText("Name", "ESPN", TextHint.NAME, onClosed = c.cb)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "  ", committed = true))
            assertEquals("ERR_EMPTY", m.ui.value.editor!!.error)
            assertTrue(c.got.isEmpty(), "still open")
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "ESPN 2", committed = true))
            assertEquals(listOf<String?>("ESPN 2"), c.got)
        }

        @Test fun theCallersValidatorCanRefuse() {
            val c = Calls<String?>()
            m.editText("Name", "", TextHint.NAME, validate = { if (it == "x") "bad" else null }, onClosed = c.cb)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "x", committed = true))
            assertEquals("bad", m.ui.value.editor!!.error)
            assertTrue(c.got.isEmpty())
        }

        @Test fun unchangedTextIsStillReported() {
            val c = Calls<String?>()
            m.editText("Name", "ESPN", TextHint.NAME, onClosed = c.cb)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "ESPN", committed = true))
            assertEquals(listOf<String?>("ESPN"), c.got)
        }

        @Test fun cancelReportsNull() {
            val c = Calls<String?>()
            m.editText("Name", "ESPN", TextHint.NAME, onClosed = c.cb)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, null, committed = false))
            assertEquals(listOf<String?>(null), c.got)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "late", committed = true))
            assertEquals(listOf<String?>(null), c.got, "exactly once")
        }

        @Test fun aSecondRequestClosesTheFirst() {
            val a = Calls<String?>(); val b = Calls<String?>()
            m.editText("A", "", TextHint.NAME, onClosed = a.cb)
            m.editText("B", "", TextHint.NAME, onClosed = b.cb)
            assertEquals(listOf<String?>(null), a.got)
            m.dispatch(SettingsEvent.EditorClosed(SettingsMachineImpl.EXT_EDIT_ROW, "b", committed = true))
            assertEquals(listOf<String?>("b"), b.got)
            assertEquals(listOf<String?>(null), a.got)
        }
    }

    /** Every closeOverlays cause reports null to a pending editText / editNumber and false to a pending PIN. */
    @Nested inner class CloseOverlays {
        private fun check(cause: () -> Unit) {
            val t = Calls<String?>()
            m.editText("Name", "x", TextHint.NAME, onClosed = t.cb)
            cause()
            assertEquals(listOf<String?>(null), t.got)
            assertNull(m.ui.value.editor)
            m.open(route, fromFull = false)
            val n = Calls<Int?>()
            m.editNumber("Number", 5, 1, 99999, n.cb)
            cause()
            assertEquals(listOf<Int?>(null), n.got)
            assertNull(m.ui.value.numberPad)
            m.open(route, fromFull = false)
            val p = Calls<Boolean>()
            m.requestPin(p.cb)
            val token = lastToken()
            cause()
            assertEquals(listOf(false), p.got)
            m.dispatch(SettingsEvent.PinResult(PinPurpose.SettingsAction(token), granted = true))
            assertEquals(listOf(false), p.got, "exactly once")
        }

        @Test fun openGuide() = check { assertEquals(false, press(BoxAction.OPEN_GUIDE)) }
        @Test fun exit() = check { assertEquals(false, press(BoxAction.EXIT)) }
        @Test fun push() = check { m.push(SettingsRoute.Groups(1)) }
        @Test fun replace() = check { m.replace(SettingsRoute.Groups(1)) }
        @Test fun pop() = check { m.pop() }
        @Test fun onLeave() = check { m.onLeave() }
    }

    @Nested inner class EditNumber {
        @Test fun okReportsTheNumber() {
            val c = Calls<Int?>()
            m.editNumber("Number", null, 1, 99999, c.cb)
            assertEquals(SettingsMachineImpl.EXT_NUMBER_ROW, m.ui.value.numberPad!!.row)
            press(BoxAction.DIGIT, digit = 5); press(BoxAction.DIGIT, digit = 0); press(BoxAction.DIGIT, digit = 1)
            m.act(GuideEvent.Act(BoxAction.DOWN)); m.act(GuideEvent.Act(BoxAction.DOWN)); m.act(GuideEvent.Act(BoxAction.RIGHT)) // OK key
            assertEquals(SettingsMachineImpl.PAD_OK, m.ui.value.numberPad!!.focusedKey)
            press(BoxAction.SELECT)
            assertEquals(listOf<Int?>(501), c.got)
            assertNull(m.ui.value.numberPad)
            assertTrue(effects.intents.isEmpty())
        }

        @Test fun outOfRangeStaysOpen() {
            val c = Calls<Int?>()
            m.editNumber("Number", 7, 10, 99, c.cb)
            assertEquals("7", m.ui.value.numberPad!!.digits, "starts on the current value")
            repeat(2) { press(BoxAction.DOWN) }; press(BoxAction.RIGHT)
            press(BoxAction.SELECT)
            assertEquals("ERR_NUMBER_RANGE(10,99)", m.ui.value.numberPad!!.error)
            assertTrue(c.got.isEmpty())
        }

        @Test fun backOnEmptyDigitsReportsNull() {
            val c = Calls<Int?>()
            m.editNumber("Number", null, 1, 99999, c.cb)
            press(BoxAction.BACK)
            assertEquals(listOf<Int?>(null), c.got)
            assertNull(m.ui.value.numberPad)
        }
    }

    @Nested inner class RequestPin {
        @Test fun granted() {
            val c = Calls<Boolean>()
            m.requestPin(c.cb)
            assertTrue(c.got.isEmpty(), "the prompt is open")
            m.dispatch(SettingsEvent.PinResult(PinPurpose.SettingsAction(lastToken()), granted = true))
            assertEquals(listOf(true), c.got)
        }

        @Test fun refused() {
            val c = Calls<Boolean>()
            m.requestPin(c.cb)
            m.dispatch(SettingsEvent.PinResult(PinPurpose.SettingsAction(lastToken()), granted = false))
            assertEquals(listOf(false), c.got)
        }

        @Test fun noPinSetProceedsAtOnce() {
            pinIsSet = false
            val c = Calls<Boolean>()
            m.requestPin(c.cb)
            assertEquals(listOf(true), c.got)
        }

        @Test fun aStaleTokenHearsFalse() {
            val a = Calls<Boolean>(); val b = Calls<Boolean>()
            m.requestPin(a.cb)
            val first = lastToken()
            m.requestPin(b.cb)
            assertEquals(listOf(false), a.got, "superseded")
            m.dispatch(SettingsEvent.PinResult(PinPurpose.SettingsAction(first), granted = true))
            assertEquals(listOf(false), a.got)
            assertTrue(b.got.isEmpty(), "a stale result does not answer the newer request")
            m.dispatch(SettingsEvent.PinResult(PinPurpose.SettingsAction(lastToken()), granted = true))
            assertEquals(listOf(true), b.got)
        }
    }

    @Test fun noGateRefuses() {
        // Review N7: with no PIN gate wired, a protection-lowering request is not granted.
        val bare = SettingsMachineImpl(QueueEffects(), { 0L }).also { it.open(route, fromFull = false) }
        val c = Calls<Boolean>()
        bare.requestPin(c.cb)
        assertEquals(listOf(false), c.got)
    }

    @Test fun closeOverlaysTakesEveryCallbackBeforeRunningAny() {
        // Review N8: a text callback that opens the pad keeps the pad and its callback.
        val n = Calls<Int?>()
        val t = Calls<String?>()
        m.editText("Name", "", TextHint.NAME) { v -> t.cb(v); m.editNumber("Number", null, 1, 9, n.cb) }
        m.onLeave()
        assertEquals(listOf<String?>(null), t.got)
        assertTrue(n.got.isEmpty(), "the new pad's callback is not fired by the same close")
        assertNotNull(m.ui.value.numberPad)
    }

    @Test fun theInterfaceDefaultsAnswer() {
        val link = object : SettingsShellLink {
            override fun attachGuide(dispatch: ((GuideEvent) -> Unit)?) = Unit
            override fun openFromFull(route: SettingsRoute, detached: Boolean) = Unit
            override fun push(route: SettingsRoute) = Unit
            override fun replace(route: SettingsRoute) = Unit
            override fun pop() = Unit
            override fun popTo(route: SettingsRoute) = Unit
            override fun leaveToGuide() = Unit
            override fun confirm(spec: ConfirmSpec, intent: RowIntent) = Unit
            override var backHandler: ((SettingsRoute) -> Boolean)? = null
            override val shellEvents: SharedFlow<SettingsShellEvent> = MutableSharedFlow()
            override fun post(event: SettingsShellEvent) = Unit
            override val editorBacks: SharedFlow<Unit> = MutableSharedFlow()
        }
        val t = Calls<String?>(); val n = Calls<Int?>(); val p = Calls<Boolean>()
        link.editText("x", "", TextHint.NAME, onClosed = t.cb)
        link.editNumber("x", null, 1, 9, n.cb)
        link.requestPin(p.cb)
        assertEquals(listOf<String?>(null), t.got)
        assertEquals(listOf<Int?>(null), n.got)
        assertEquals(listOf(false), p.got)
    }

    /** The keyHandler is consulted only when no settings overlay (pad, confirm, menu, move, choice) is open. */
    @Nested inner class KeyHandlerOrder {
        private val seen = ArrayList<BoxAction>()
        private val choiceRow = RowId("t.choice")
        private val itemRow = RowId("t.item")

        private fun withPage() {
            m.keyHandler = { r, a -> if (r == route) seen += a.action; false }
            m.dispatch(SettingsEvent.Page(PageModel(route, "Editor", rows = listOf(
                SettingsRow(choiceRow, "Choice", kind = RowKind.Choice(listOf(ChoiceOption("a", "A"), ChoiceOption("b", "B")), 0)),
                SettingsRow(itemRow, "Item", kind = RowKind.Item(menu = listOf(ChoiceOption(SettingsMachineImpl.MOVE_OPTION, "Move")), movable = true)),
            ), version = 1)))
        }

        @Test fun consultedWithNoOverlay() {
            withPage()
            press(BoxAction.LEFT)
            assertEquals(listOf(BoxAction.LEFT), seen)
        }

        @Test fun notWhileAPadIsOpen() {
            withPage()
            m.editNumber("n", null, 1, 9) { }
            seen.clear()
            press(BoxAction.DOWN); press(BoxAction.LEFT)
            assertTrue(seen.isEmpty())
        }

        @Test fun notWhileAConfirmIsOpen() {
            withPage()
            m.confirm(ConfirmSpec("t", "b", "ok", "cancel"), RowIntent.Run(itemRow))
            seen.clear()
            press(BoxAction.LEFT)
            assertTrue(seen.isEmpty())
            assertEquals(0, m.ui.value.confirm!!.focused)
        }

        @Test fun notWhileAChoiceMenuOrMoveIsOpen() {
            withPage()
            // The choice list (the handler sees the SELECT that opens it: no overlay yet).
            press(BoxAction.SELECT)
            assertNotNull(m.ui.value.choice)
            seen.clear()
            press(BoxAction.DOWN); press(BoxAction.LEFT)
            assertTrue(seen.isEmpty(), "choice: $seen")
            assertNull(m.ui.value.choice)
            // The menu, then move mode.
            press(BoxAction.DOWN)
            press(BoxAction.CONTEXT_MENU)
            assertNotNull(m.ui.value.menu)
            seen.clear()
            press(BoxAction.SELECT) // Move
            assertTrue(seen.isEmpty(), "menu: $seen")
            assertNotNull(m.ui.value.move)
            press(BoxAction.UP); press(BoxAction.LEFT); press(BoxAction.BACK)
            assertTrue(seen.isEmpty(), "move: $seen")
            assertNull(m.ui.value.move)
        }
    }

    @Test fun theSectionsPaneIgnoresExtRowsAfterAPop() {
        // A section page has no ext state; opening one after an ext editor leaves nothing pending.
        val c = Calls<String?>()
        m.editText("Name", "", TextHint.NAME, onClosed = c.cb)
        m.open(SettingsRoute.Section(SectionId.CHANNELS), fromFull = false)
        assertEquals(listOf<String?>(null), c.got)
    }
}
