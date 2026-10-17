package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.PinPadMode
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.state.QueueEffects
import app.thebox.core.settings.state.SettingsMachineImpl
import app.thebox.core.settings.tree.ParentalRows
import app.thebox.core.settings.tree.RowTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C-S3 (M5 code review 2026-10-16, §3.3.8): the Parental page's Change PIN flow, composed with the guide's PIN prompt. */
class ParentalPageFlowTest {
    /**
     * C-S3 (adopted from the core reviewer): "Change PIN (set; verify, then create twice)". Entering the page asks once and
     * the change verifies once; OK on Change PIN no longer asks a third time on top.
     */
    @Test
    fun changePinAsksForTheCurrentPinOnce() {
        val w = GuideWorld()
        val effects = QueueEffects()
        val s = SettingsMachineImpl(effects, { w.nowMs })
        w.machine.settings = s
        s.attachGuide { w.send(it) }
        val unlocked = ParentalState(pinSet = true, unlocked = true, policy = ParentalPolicy())
        w.send(GuideEvent.Parental(unlocked))
        w.send(GuideEvent.OpenSettings)
        var verifies = 0
        fun answerPin() {
            val pin = w.ui.pin ?: return
            if (pin.mode != PinPadMode.VERIFY) return
            verifies++
            repeat(4) { w.press(BoxAction.DIGIT, digit = 1) }
            val token = w.calls.last("verifyPin")!!.args[0] as Long
            w.send(GuideEvent.PinChecked(token, PinCheck.Ok))
        }
        val idx = s.ui.value.sections.indexOf(SectionId.PARENTAL)
        repeat(idx) { w.press(BoxAction.DOWN) }
        w.press(BoxAction.SELECT) // enter the Parental page
        answerPin()
        val texts = RowTexts { k, _ -> k }
        s.dispatch(SettingsEvent.Page(ParentalRows.page(unlocked, texts, 1, advanced = false)))
        assertEquals(ParentalRows.PIN, s.ui.value.page?.rows?.getOrNull(s.ui.value.cursor)?.id)
        w.press(BoxAction.SELECT) // Change PIN
        answerPin()
        // The page source turns Run(parental.pin) into SetupPin(change = true) → SettingsShellLink.requestPinSetup(true).
        val cmd = ParentalRows.command(effects.intents.last(), unlocked)
        assertEquals(ParentalRows.Command.SetupPin(change = true), cmd)
        s.requestPinSetup(change = true)
        answerPin()
        assertTrue(verifies <= 2, "the current PIN was asked $verifies times for one change (design: page entry + one verify)")
        assertEquals(PinPadMode.CREATE_FIRST, w.ui.pin?.mode, "then create twice")
    }
}
