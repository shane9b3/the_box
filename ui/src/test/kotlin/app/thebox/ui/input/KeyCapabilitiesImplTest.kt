package app.thebox.ui.input

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m3-guide.md §1.5, §5.4 (N7): `keys_seen` writes only newly seen codes; read off main at creation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyCapabilitiesImplTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences get() = context.getSharedPreferences(KeyCapabilitiesImpl.PREFS, Context.MODE_PRIVATE)
    private var writes = 0
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }

    @Before fun setUp() {
        prefs.edit().clear().commit()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        writes = 0
    }

    private fun create(): Pair<KeyCapabilitiesImpl, TestScope> {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        return KeyCapabilitiesImpl(context, scope, dispatcher) to scope
    }

    @Test
    fun startsEmptyUntilTheIoReadRuns() {
        prefs.edit().putStringSet(KeyCapabilitiesImpl.KEY, setOf("${KeyEvent.KEYCODE_GUIDE}")).commit()
        writes = 0
        val (caps, scope) = create()
        assertTrue("no read at construction", caps.seen.value.isEmpty())
        scope.testScheduler.advanceUntilIdle()
        assertEquals(setOf(KeyEvent.KEYCODE_GUIDE), caps.seen.value)
        assertEquals("reading writes nothing", 0, writes)
    }

    @Test
    fun recordWritesOnlyNewCodes() {
        val (caps, scope) = create()
        scope.testScheduler.advanceUntilIdle()
        caps.record(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(1, writes)
        caps.record(KeyEvent.KEYCODE_CHANNEL_UP)
        caps.record(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals("known code: no write", 1, writes)
        caps.record(KeyEvent.KEYCODE_INFO)
        assertEquals(2, writes)
        assertEquals(setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_INFO), caps.seen.value)
    }

    @Test
    fun persistedSetIsReReadByANewInstance() {
        val (first, s1) = create()
        s1.testScheduler.advanceUntilIdle()
        first.record(KeyEvent.KEYCODE_PROG_RED)
        first.record(KeyEvent.KEYCODE_LAST_CHANNEL)
        val (second, s2) = create()
        s2.testScheduler.advanceUntilIdle()
        assertEquals(setOf(KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_LAST_CHANNEL), second.seen.value)
        writes = 0
        second.record(KeyEvent.KEYCODE_PROG_RED)
        assertEquals(0, writes)
    }

    @Test
    fun codesRecordedBeforeTheReadAreMergedAndPersisted() {
        prefs.edit().putStringSet(KeyCapabilitiesImpl.KEY, setOf("${KeyEvent.KEYCODE_GUIDE}")).commit()
        val (caps, scope) = create()
        caps.record(KeyEvent.KEYCODE_INFO)
        assertEquals(setOf(KeyEvent.KEYCODE_INFO), caps.seen.value)
        scope.testScheduler.advanceUntilIdle()
        val expected = setOf(KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO)
        assertEquals(expected, caps.seen.value)
        assertEquals(expected.map { it.toString() }.toSet(), prefs.getStringSet(KeyCapabilitiesImpl.KEY, null))
    }

    @Test
    fun defaultConstructorStillWorks() {
        val caps = KeyCapabilitiesImpl(context)
        caps.record(KeyEvent.KEYCODE_MENU)
        assertTrue(KeyEvent.KEYCODE_MENU in caps.seen.value)
    }
}
