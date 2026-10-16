package app.thebox.core.input

import app.thebox.core.input.BoxAction.*
import app.thebox.core.input.KeyContext.GUIDE
import app.thebox.core.input.KeyContext.PLAYER
import app.thebox.core.settings.tree.ButtonMappingRows
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.8.1, §3.8.4: defaults plus overrides, unbinding, unknown ids, the snapshot swap. */
class RemappedKeyMapTest {
    private val defaults = DefaultKeyMap()
    private fun map(vararg o: KeyOverride) = RemappedKeyMap(defaults, null, null).also { it.apply(KeyBindingOverrides(entries = o.toList())) }

    @Test fun noOverridesIsTheDefaultMap() {
        val m = map()
        for (c in KeyContext.entries) assertEquals(defaults.bindings(c), m.bindings(c))
        for (code in 0..300) for (c in KeyContext.entries) {
            assertEquals(defaults.short(c, code), m.short(c, code), "short $c $code")
            assertEquals(defaults.long(c, code), m.long(c, code), "long $c $code")
            assertEquals(defaults.digit(code), m.digit(code))
        }
    }

    @Test fun overrideBindsAndUnbinds() {
        val m = map(
            KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, SUBTITLES.id, "Remote", 1),
            KeyOverride(GUIDE, Keys.PROG_GREEN, Press.SHORT, null, null, 2),
            KeyOverride(PLAYER, Keys.PROG_BLUE, Press.LONG, STATS_OVERLAY.id, null, 3),
        )
        assertEquals(SUBTITLES, m.short(PLAYER, Keys.PROG_RED))
        assertEquals(START_OVER, m.short(GUIDE, Keys.PROG_RED), "the guide keeps Red")
        assertNull(m.short(GUIDE, Keys.PROG_GREEN), "unbound in the guide")
        assertEquals(FAVOURITES, m.short(PLAYER, Keys.PROG_GREEN))
        assertEquals(STATS_OVERLAY, m.long(PLAYER, Keys.PROG_BLUE))
        assertEquals(PREVIOUS_CHANNEL, m.short(PLAYER, Keys.PROG_BLUE))
        assertEquals(listOf(Keys.PROG_BLUE to Press.LONG), m.keysFor(PLAYER, STATS_OVERLAY))
        assertTrue(Keys.PROG_RED to Press.SHORT in m.keysFor(PLAYER, SUBTITLES))
        assertTrue(Keys.CAPTIONS to Press.SHORT in m.keysFor(PLAYER, SUBTITLES), "the default key still works")
    }

    @Test fun laterOverrideOfTheSameTripleWins() {
        val m = map(
            KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, AUDIO.id, null, 5),
            KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, ASPECT.id, null, 9),
        )
        assertEquals(ASPECT, m.short(PLAYER, Keys.PROG_RED))
    }

    @Test fun reservedAndUnknownOverridesAreIgnored() {
        val m = map(
            KeyOverride(GUIDE, Keys.DPAD_UP, Press.SHORT, AUDIO.id, null, 1),
            KeyOverride(PLAYER, Keys.BACK, Press.SHORT, null, null, 2),
            KeyOverride(GUIDE, Keys.DPAD_CENTER, Press.LONG, EXIT.id, null, 3),
            KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, "from_a_newer_app", null, 4),
            KeyOverride(PLAYER, Keys.PROG_YELLOW, Press.SHORT, SELECT.id, null, 5),
            KeyOverride(GUIDE, 0, Press.SHORT, AUDIO.id, null, 6),
        )
        assertEquals(UP, m.short(GUIDE, Keys.DPAD_UP))
        assertEquals(BACK, m.short(PLAYER, Keys.BACK))
        assertEquals(CONTEXT_MENU, m.long(GUIDE, Keys.DPAD_CENTER))
        assertEquals(START_OVER, m.short(PLAYER, Keys.PROG_RED), "an unknown id is ignored, not crashed on")
        assertEquals(AUDIO, m.short(PLAYER, Keys.PROG_YELLOW), "a non-remappable action is not bound")
        assertNull(m.short(GUIDE, 0))
    }

    @Test fun playerLeftRightLongMayBeRebound() {
        val m = map(KeyOverride(PLAYER, Keys.DPAD_LEFT, Press.LONG, null, null, 1))
        assertNull(m.long(PLAYER, Keys.DPAD_LEFT))
        assertEquals(LEFT, m.short(PLAYER, Keys.DPAD_LEFT))
    }

    @Test fun snapshotFollowsTheFlowOffMain() {
        val flow = MutableStateFlow(KeyBindingOverrides())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            val m = RemappedKeyMap(defaults, flow, scope)
            val before = m.version
            flow.value = KeyBindingOverrides(entries = listOf(KeyOverride(PLAYER, Keys.PROG_RED, Press.SHORT, AUDIO.id, null, 1)))
            val deadline = System.currentTimeMillis() + 5_000
            while (m.short(PLAYER, Keys.PROG_RED) != AUDIO && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(AUDIO, m.short(PLAYER, Keys.PROG_RED))
            assertTrue(m.version > before)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    /** §3.15 "Mapping cannot break the rule": unbinding every non-reserved key leaves the five keys exactly as before. */
    @Test fun mappingCannotBreakFiveKey() {
        val all = ArrayList<KeyOverride>()
        for (c in KeyContext.entries) for (b in defaults.bindings(c)) {
            if (b.short != null) all += KeyOverride(c, b.keyCode, Press.SHORT, null, null, 1)
            if (b.long != null) all += KeyOverride(c, b.keyCode, Press.LONG, null, null, 1)
        }
        val m = map(*all.toTypedArray())
        val five = intArrayOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT, Keys.DPAD_CENTER, Keys.ENTER, Keys.BACK)
        for (c in KeyContext.entries) for (code in five) {
            assertEquals(defaults.short(c, code), m.short(c, code), "$c $code short")
            if (code !in intArrayOf(Keys.DPAD_LEFT, Keys.DPAD_RIGHT) || c == GUIDE) assertEquals(defaults.long(c, code), m.long(c, code), "$c $code long")
        }
        for (i in 0..9) assertEquals(DIGIT, m.short(GUIDE, Keys.DIGIT_0 + i))
        // Every non-reserved default is gone (so the M3/M4 five-key proofs, which use only these keys, are unaffected).
        assertNull(m.short(PLAYER, Keys.PROG_RED)); assertNull(m.short(GUIDE, Keys.CHANNEL_UP))
    }

    @Test fun keysValueListsShortAndHeldKeys() {
        val m = map(KeyOverride(PLAYER, Keys.PROG_RED, Press.LONG, AUDIO.id, "Remote", 1))
        val names = object : ButtonMappingRows.Names {
            override fun key(keyCode: Int) = "k$keyCode"
            override fun action(action: BoxAction) = action.id
            override fun group(group: ActionGroup) = group.name
        }
        val texts = app.thebox.core.settings.tree.RowTexts { key, args -> if (args.isEmpty()) key else "$key(${args.joinToString()})" }
        val parts = ButtonMappingRows.keysValue(m, AUDIO, names, texts).split(ButtonMappingRows.T.SEPARATOR).toSet()
        assertEquals(setOf("k${Keys.PROG_YELLOW}", "k${Keys.MEDIA_AUDIO_TRACK}", "${ButtonMappingRows.T.HOLD}(k${Keys.PROG_RED})"), parts)
        assertEquals(ButtonMappingRows.T.NO_BUTTON, ButtonMappingRows.keysValue(m, TIME_SHIFT, names, texts))
    }
}
