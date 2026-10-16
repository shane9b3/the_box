package app.thebox.data.input

import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.input.RemoteDomain
import app.thebox.core.settings.fakes.FakeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/m5-setup-settings.md §2.7, §3.8: the overrides live in the `remote` domain and follow BindingRules. */
class KeyBindingStoreImplTest {
    private val settings = FakeSettingsStore()
    private val store = KeyBindingStoreImpl(settings)
    private fun key(code: Int) = CapturedKey(code, "KEYCODE_$code", "Remote", 1, false)

    @Test fun bindUnbindAndResetsPersistInTheRemoteDomain() {
        store.bind(BoxAction.AUDIO, key(Keys.PROG_RED), Press.SHORT, 5)
        assertEquals(store.overrides.value, settings.flow(RemoteDomain).value)
        assertEquals(listOf(BoxAction.AUDIO.id), store.overrides.value.entries.map { it.action })
        store.bind(BoxAction.AUDIO, key(Keys.DPAD_UP), Press.SHORT, 6)
        assertEquals("a reserved key is refused", 1, store.overrides.value.entries.size)
        store.unbind(KeyContext.GUIDE, Keys.PROG_GREEN, Press.SHORT, 7)
        assertEquals(2, store.overrides.value.entries.size)
        store.resetAction(BoxAction.AUDIO)
        assertEquals(listOf(Keys.PROG_GREEN), store.overrides.value.entries.map { it.keyCode })
        store.resetKey(Keys.PROG_GREEN)
        assertTrue(store.overrides.value.entries.isEmpty())
        store.bind(BoxAction.ZAP_UP, key(Keys.PROG_BLUE), Press.LONG, 8)
        assertEquals(2, store.overrides.value.entries.size)
        store.resetAll()
        assertTrue(store.overrides.value.entries.isEmpty())
    }
}
