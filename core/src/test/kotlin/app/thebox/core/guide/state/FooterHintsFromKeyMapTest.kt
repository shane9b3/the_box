package app.thebox.core.guide.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.input.Keys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.8.4: footer hints from the effective key map ∩ the keys seen. */
class FooterHintsFromKeyMapTest {
    private fun keys(w: GuideWorld) = w.ui.footer.map { it.keys }
    private fun labels(w: GuideWorld) = w.ui.footer.map { it.label }

    @Test fun defaultsMatchTheM3Footer() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.CHANNEL_UP)))
        val before = w.ui.footer
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.START_OVER to listOf(Keys.PROG_RED), BoxAction.ZAP_UP to listOf(Keys.CHANNEL_UP))))
        assertEquals(before, w.ui.footer, "default bindings: the same hints as M3")
    }

    @Test fun remappedRedStopsAdvertisingAndTheNewKeyIsNamed() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.PROG_RED, Keys.PROG_GREEN)))
        // Red moved to another action; Green now starts over.
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.START_OVER to listOf(Keys.PROG_GREEN))))
        assertFalse(GuideTexts.KEYS_RED in keys(w))
        assertTrue("Green" in keys(w))
        assertTrue(GuideTexts.HINT_FROM_START in labels(w) || GuideTexts.HINT_REMIND in labels(w))
        // Nothing bound: no hint.
        w.send(GuideEvent.KeyHints(emptyMap()))
        assertFalse(keys(w).any { it == "Green" || it == GuideTexts.KEYS_RED })
    }

    @Test fun pageHintFollowsZapUp() {
        val w = GuideWorld()
        w.send(GuideEvent.KeysSeen(setOf(Keys.CHANNEL_UP, Keys.PROG_BLUE)))
        w.send(GuideEvent.KeyHints(mapOf(BoxAction.ZAP_UP to listOf(Keys.PROG_BLUE))))
        assertTrue("Blue" in keys(w)); assertTrue(GuideTexts.HINT_PAGE in labels(w))
        assertFalse(GuideTexts.KEYS_CH in keys(w))
    }
}
