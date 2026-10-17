package app.thebox.core.settings.tree

import app.thebox.core.input.ActionCatalog
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyOverride
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.parental.LockedInGuide
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.StayUnlocked
import app.thebox.core.settings.Badge
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.Visibility
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Page-builder tables of WP-C's rows (§3.3.8, §3.8.3): values, visibility, enabled/disabled, EffectTiming, intents. */
class WpcRowsTest {
    private val texts = RowTexts { key, args -> if (args.isEmpty()) key else "$key(${args.joinToString()})" }
    private fun state(pin: Boolean, unlocked: Boolean = false, auto: Boolean = true, lost: Boolean = false, policy: ParentalPolicy = ParentalPolicy(autoLockAdult = auto)) =
        ParentalState(pin, unlocked, policy, lost)

    @Test fun registeredRowsMatchTheSpec() {
        val specs = ParentalRows.rows.associateBy { it.id }
        assertEquals(4, ParentalRows.rows.count { it.visibility == Visibility.SIMPLE }, "§3.3.13: Parental 4")
        assertTrue(ParentalRows.rows.all { it.effect == EffectTiming.LIVE })
        assertEquals(Visibility.ADVANCED, specs.getValue(ParentalRows.STAY_UNLOCKED).visibility)
        // The registry accepts both sources (no duplicate ids).
        assertNotNull(SettingsTree.spec(ParentalRows.PIN))
        assertNotNull(SettingsTree.spec(ButtonMappingRows.RESET_ALL))
    }

    @Test fun noPinPage() {
        val p = ParentalRows.page(state(pin = false), texts, 1, advanced = true)
        val rows = p.rows.associateBy { it.id }
        assertEquals(ParentalRows.T.SET_PIN, rows.getValue(ParentalRows.PIN).label)
        assertEquals(ParentalRows.T.NOT_PROTECTED, rows.getValue(ParentalRows.AUTO_LOCK).value, "B3: On · not protected")
        val lock = rows.getValue(ParentalRows.LOCK_SETTINGS)
        assertFalse(lock.enabled); assertEquals(ParentalRows.T.NEEDS_PIN_FIRST, lock.disabledReason)
        assertNull(rows[ParentalRows.LOCK_NOW]); assertNull(rows[ParentalRows.REMOVE_PIN])
        assertIs<RowKind.Info>(rows.getValue(ParentalRows.FORGOT).kind)
        assertEquals(SettingsRoute.Section(app.thebox.core.settings.SectionId.PARENTAL), p.route)
    }

    @Test fun pinSetUnlockedAdvancedPage() {
        val p = ParentalRows.page(state(pin = true, unlocked = true), texts, 1, advanced = true)
        val rows = p.rows.associateBy { it.id }
        assertEquals(ParentalRows.T.CHANGE_PIN, rows.getValue(ParentalRows.PIN).label)
        // C-S3: Change PIN is a plain action; the CreatePin(change) flow verifies once.
        assertTrue(!(rows.getValue(ParentalRows.PIN).kind as RowKind.Action).needsPin)
        assertEquals(ParentalRows.T.ON, rows.getValue(ParentalRows.AUTO_LOCK).value)
        assertTrue(rows.getValue(ParentalRows.LOCK_SETTINGS).enabled)
        assertNotNull(rows[ParentalRows.LOCK_NOW])
        val remove = rows.getValue(ParentalRows.REMOVE_PIN).kind as RowKind.Action
        assertTrue(remove.destructive); assertNotNull(remove.confirm)
        // Simple view: the advanced rows are absent (not dimmed, §3.2.3).
        val simple = ParentalRows.page(state(pin = true, unlocked = true), texts, 1, advanced = false).rows.map { it.id }
        assertFalse(ParentalRows.STAY_UNLOCKED in simple || ParentalRows.LOCK_NOW in simple || ParentalRows.REMOVE_PIN in simple)
    }

    @Test fun keyResetInfoLine() {
        val p = ParentalRows.page(state(pin = false, lost = true, auto = false), texts, 1, advanced = false)
        val info = p.rows.first { it.id == ParentalRows.FORGOT }.kind as RowKind.Info
        assertTrue(ParentalRows.T.KEY_RESET in info.lines)
        assertEquals(ParentalRows.T.NOT_PROTECTED.let { ParentalRows.T.OFF }, p.rows.first { it.id == ParentalRows.AUTO_LOCK }.value)
    }

    @Test fun intents() {
        val none = state(pin = false)
        val set = state(pin = true)
        assertEquals(ParentalRows.Command.SetupPin(change = false), ParentalRows.command(RowIntent.Run(ParentalRows.PIN), none))
        assertEquals(ParentalRows.Command.SetupPin(change = true), ParentalRows.command(RowIntent.Run(ParentalRows.PIN), set))
        val auto = assertIs<ParentalRows.Command.Policy>(ParentalRows.command(RowIntent.Toggle(ParentalRows.AUTO_LOCK, false), set))
        assertFalse(auto.transform(ParentalPolicy()).autoLockAdult)
        val hide = assertIs<ParentalRows.Command.Policy>(ParentalRows.command(RowIntent.Choose(ParentalRows.LOCKED_IN_GUIDE, "hide"), set))
        assertEquals(LockedInGuide.HIDE, hide.transform(ParentalPolicy()).lockedInGuide)
        assertNull(ParentalRows.command(RowIntent.Toggle(ParentalRows.LOCK_SETTINGS, true), none), "no Lock settings without a PIN")
        assertTrue(assertIs<ParentalRows.Command.Policy>(ParentalRows.command(RowIntent.Toggle(ParentalRows.LOCK_SETTINGS, true), set)).transform(ParentalPolicy()).lockSettings)
        val stay = assertIs<ParentalRows.Command.Policy>(ParentalRows.command(RowIntent.Choose(ParentalRows.STAY_UNLOCKED, "ask"), set))
        assertEquals(StayUnlocked.ASK_EVERY_TIME, stay.transform(ParentalPolicy()).stayUnlocked)
        assertEquals(ParentalRows.Command.LockNow, ParentalRows.command(RowIntent.Run(ParentalRows.LOCK_NOW), set))
        assertEquals(ParentalRows.Command.RemovePin, ParentalRows.command(RowIntent.Run(ParentalRows.REMOVE_PIN), set))
        assertNull(ParentalRows.command(RowIntent.Run(ParentalRows.REMOVE_PIN), none))
        assertNull(ParentalRows.command(RowIntent.Run(app.thebox.core.settings.RowId("playback.tunneling")), set))
    }

    private val names = object : ButtonMappingRows.Names {
        override fun key(keyCode: Int) = "k$keyCode"
        override fun action(action: BoxAction) = action.id
        override fun group(group: ActionGroup) = group.name
    }

    @Test fun mappingPage() {
        val o = KeyBindingOverrides(entries = listOf(KeyOverride(KeyContext.PLAYER, Keys.PROG_RED, Press.SHORT, BoxAction.AUDIO.id, "Remote", 1)))
        val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(o) }
        val p = ButtonMappingRows.mappingPage(map, o, names, texts, 3)
        assertEquals(SettingsRoute.ButtonMapping, p.route)
        val ids = p.rows.map { it.id }
        assertEquals(ButtonMappingRows.TEST, ids[0]); assertEquals(ButtonMappingRows.RESET_ALL, ids[1])
        for (info in ActionCatalog.all) assertTrue(ButtonMappingRows.actionRow(info.action) in ids, "${info.action} listed")
        val audio = p.rows.first { it.id == ButtonMappingRows.actionRow(BoxAction.AUDIO) }.kind as RowKind.Item
        assertEquals(SettingsRoute.ActionBindings(BoxAction.AUDIO), audio.route)
        assertTrue(Badge.DEFAULT !in audio.badges, "changed")
        val guide = p.rows.first { it.id == ButtonMappingRows.actionRow(BoxAction.OPEN_GUIDE) }.kind as RowKind.Item
        assertTrue(Badge.DEFAULT in guide.badges)
        assertTrue(ButtonMappingRows.DPAD_NOTE in ids, "S14 note under Watching")
        assertTrue(p.rows.first { it.id == ButtonMappingRows.RESET_ALL }.enabled)
        assertFalse(ButtonMappingRows.mappingPage(map, KeyBindingOverrides(), names, texts, 3).rows.first { it.id == ButtonMappingRows.RESET_ALL }.enabled)
    }

    @Test fun actionPageAndIntents() {
        val o = KeyBindingOverrides(entries = listOf(KeyOverride(KeyContext.PLAYER, Keys.PROG_RED, Press.SHORT, BoxAction.AUDIO.id, "Remote", 1)))
        val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(o) }
        val p = ButtonMappingRows.actionPage(BoxAction.AUDIO, map, o, names, texts, 1)
        val red = p.rows.first { it.id == ButtonMappingRows.keyRow(KeyContext.PLAYER, Keys.PROG_RED, Press.SHORT) }
        assertEquals("${ButtonMappingRows.T.CAPTURED_FROM}(Remote)", red.value)
        val yellow = p.rows.first { it.id == ButtonMappingRows.keyRow(KeyContext.PLAYER, Keys.PROG_YELLOW, Press.SHORT) }
        assertEquals(ButtonMappingRows.T.DEFAULT, yellow.value)
        assertEquals(listOf(ButtonMappingRows.ADD_SHORT, ButtonMappingRows.ADD_LONG, ButtonMappingRows.RESET_ACTION), p.rows.takeLast(3).map { it.id })
        assertEquals(ButtonMappingRows.Command.Capture(BoxAction.AUDIO, Press.LONG), ButtonMappingRows.command(RowIntent.Run(ButtonMappingRows.ADD_LONG), BoxAction.AUDIO))
        assertEquals(ButtonMappingRows.Command.ResetAction(BoxAction.AUDIO), ButtonMappingRows.command(RowIntent.Run(ButtonMappingRows.RESET_ACTION), BoxAction.AUDIO))
        assertEquals(ButtonMappingRows.Command.Unbind(KeyContext.PLAYER, Keys.PROG_RED, Press.SHORT), ButtonMappingRows.command(RowIntent.Menu(red.id, ButtonMappingRows.MENU_REMOVE), BoxAction.AUDIO))
        assertEquals(ButtonMappingRows.Command.ResetKey(Keys.PROG_RED), ButtonMappingRows.command(RowIntent.Menu(red.id, ButtonMappingRows.MENU_RESET_KEY), BoxAction.AUDIO))
        assertEquals(ButtonMappingRows.Command.TestKey, ButtonMappingRows.command(RowIntent.Run(ButtonMappingRows.TEST), null))
        assertEquals(ButtonMappingRows.Command.ResetAll, ButtonMappingRows.command(RowIntent.Run(ButtonMappingRows.RESET_ALL), null))
    }
}
