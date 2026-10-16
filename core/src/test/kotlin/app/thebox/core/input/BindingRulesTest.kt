package app.thebox.core.input

import app.thebox.core.input.BoxAction.*
import app.thebox.core.input.KeyContext.GUIDE
import app.thebox.core.input.KeyContext.PLAYER
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.8.2: reserved keys, later binding wins, LosesRepeat, resets, unknown ids. */
class BindingRulesTest {
    private val defaults = DefaultKeyMap()
    private fun key(code: Int) = CapturedKey(code, "KEYCODE_$code", "Remote", 3, isVirtual = false)

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource(
        "GUIDE,19,SHORT,NAVIGATION", "PLAYER,22,SHORT,NAVIGATION", "GUIDE,21,LONG,NAVIGATION", "PLAYER,19,LONG,NAVIGATION",
        "PLAYER,20,LONG,NAVIGATION", "PLAYER,21,LONG,", "PLAYER,22,LONG,",
        "GUIDE,23,SHORT,OK", "PLAYER,66,SHORT,OK", "GUIDE,160,LONG,CONTEXT_MENU_LONG_OK", "PLAYER,96,LONG,CONTEXT_MENU_LONG_OK",
        "GUIDE,4,SHORT,BACK", "PLAYER,111,LONG,BACK", "GUIDE,97,SHORT,BACK",
        "GUIDE,7,SHORT,DIGIT", "PLAYER,16,LONG,DIGIT", "GUIDE,144,SHORT,DIGIT", "PLAYER,153,SHORT,DIGIT",
        "GUIDE,24,SHORT,SYSTEM", "GUIDE,25,SHORT,SYSTEM", "PLAYER,164,SHORT,SYSTEM", "GUIDE,91,SHORT,SYSTEM", "GUIDE,3,SHORT,SYSTEM",
        "GUIDE,26,SHORT,SYSTEM", "GUIDE,177,SHORT,SYSTEM", "GUIDE,223,LONG,SYSTEM", "GUIDE,224,SHORT,SYSTEM",
        "GUIDE,0,SHORT,UNKNOWN_CODE", "PLAYER,0,LONG,UNKNOWN_CODE",
        "GUIDE,183,SHORT,", "PLAYER,166,LONG,", "GUIDE,82,SHORT,", "GUIDE,84,SHORT,", "PLAYER,176,SHORT,",
    )
    fun reserved(context: KeyContext, code: Int, press: Press, reason: ReservedReason?) =
        assertEquals(reason, BindingRules.reserved(context, code, press))

    @Test fun reservedRefusedForEveryContextOfTheAction() {
        assertEquals(BindingVerdict.Reserved(ReservedReason.NAVIGATION), BindingRules.verdict(defaults, AUDIO, Keys.DPAD_UP, Press.SHORT))
        // GROUP_NEXT is PLAYER only: LEFT/RIGHT long may carry it there.
        assertEquals(BindingVerdict.Replaces(GROUP_PREV, setOf(PLAYER)), BindingRules.verdict(defaults, GROUP_NEXT, Keys.DPAD_LEFT, Press.LONG))
        // ZAP_UP is in both contexts: LEFT long is reserved in the guide.
        assertEquals(BindingVerdict.Reserved(ReservedReason.NAVIGATION), BindingRules.verdict(defaults, ZAP_UP, Keys.DPAD_LEFT, Press.LONG))
        val o = BindingRules.save(KeyBindingOverrides(), AUDIO, key(Keys.DPAD_CENTER), Press.SHORT, 1)
        assertTrue(o.entries.isEmpty(), "save refuses a reserved key")
    }

    @Test fun laterBindingWinsInEveryContextOfTheAction() {
        // Red is START_OVER in both contexts; giving it to SUBTITLES (PLAYER only) replaces it there only.
        assertEquals(BindingVerdict.Replaces(START_OVER, setOf(PLAYER)), BindingRules.verdict(defaults, SUBTITLES, Keys.PROG_RED, Press.SHORT))
        var o = BindingRules.save(KeyBindingOverrides(), SUBTITLES, key(Keys.PROG_RED), Press.SHORT, 10)
        assertEquals(listOf(KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, SUBTITLES.id, "Remote", 10)), o.entries)
        // ZAP_UP (GUIDE + PLAYER) on Red: both contexts.
        o = BindingRules.save(o, ZAP_UP, key(Keys.PROG_RED), Press.SHORT, 20)
        assertEquals(setOf(GUIDE to ZAP_UP.id, PLAYER to ZAP_UP.id), o.entries.map { it.context to it.action }.toSet(), "the later binding replaced SUBTITLES")
        // A free key.
        assertEquals(BindingVerdict.Free, BindingRules.verdict(defaults, AUDIO, 999, Press.SHORT))
        // Binding an action to the key it already has is not a replacement.
        assertEquals(BindingVerdict.Free, BindingRules.verdict(defaults, AUDIO, Keys.MEDIA_AUDIO_TRACK, Press.SHORT))
    }

    @Test fun longPressCostsTheRepeat() {
        val v = BindingRules.verdict(defaults, AUDIO, Keys.CHANNEL_UP, Press.LONG)
        val lr = assertIs<BindingVerdict.LosesRepeat>(v)
        assertEquals(ZAP_UP, lr.shortAction)
        assertEquals(BindingVerdict.Free, lr.then)
        // A key whose short does not repeat: no warning.
        assertEquals(BindingVerdict.Free, BindingRules.verdict(defaults, AUDIO, Keys.PROG_GREEN, Press.LONG))
    }

    @Test fun unbindResetsAndCap() {
        var o = BindingRules.unbind(KeyBindingOverrides(), PLAYER, Keys.PROG_RED, Press.SHORT, 1)
        assertEquals(listOf(KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, null, null, 1)), o.entries)
        assertEquals(o, BindingRules.unbind(o, PLAYER, Keys.DPAD_UP, Press.SHORT, 2), "a reserved key cannot be unbound")
        o = BindingRules.save(o, AUDIO, key(Keys.PROG_GREEN), Press.SHORT, 3)
        // Reset AUDIO: its own bindings go; the Red unbinding (START_OVER's default) stays.
        val reset = BindingRules.resetAction(o, AUDIO, defaults)
        assertEquals(listOf(Keys.PROG_RED), reset.entries.map { it.keyCode })
        // Reset START_OVER: the override that unbound one of its default keys goes.
        assertTrue(BindingRules.resetAction(o, START_OVER, defaults).entries.none { it.keyCode == Keys.PROG_RED })
        assertTrue(BindingRules.resetKey(o, Keys.PROG_GREEN).entries.none { it.keyCode == Keys.PROG_GREEN })
        assertTrue(BindingRules.resetAll(o).entries.isEmpty())
        var big = KeyBindingOverrides()
        for (i in 0 until 250) big = BindingRules.save(big, AUDIO, key(1000 + i), Press.SHORT, i.toLong())
        assertEquals(BindingRules.KEY_OVERRIDES_MAX, big.entries.size)
        assertEquals(1000 + 50, big.entries.minOf { it.keyCode }, "the oldest go first")
    }

    @Test fun catalogListsEveryRemappableActionOnce() {
        val listed = ActionCatalog.all.map { it.action }
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(BoxAction.entries.filter { it.remappable }.toSet(), listed.toSet())
        for (g in ActionGroup.entries) assertTrue(ActionCatalog.inGroup(g).isNotEmpty(), "$g has actions")
        for (a in listed) assertTrue(ActionCatalog.contexts(a).isNotEmpty())
        assertEquals(setOf(GUIDE, PLAYER), ActionCatalog.contexts(ZAP_UP))
        assertEquals(setOf(PLAYER), ActionCatalog.contexts(AUDIO))
        assertEquals(setOf(GUIDE), ActionCatalog.contexts(DAY_STRIP))
        assertNull(ActionCatalog.actionOf("from_a_newer_app"))
        assertEquals(OPEN_SEARCH, ActionCatalog.actionOf("open_search"))
    }
}
