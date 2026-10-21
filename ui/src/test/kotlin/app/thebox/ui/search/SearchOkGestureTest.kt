package app.thebox.ui.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchOkGestureTest {
    @Test fun aRemoteWithoutRepeatsOpensTheMenuOnceAndDoesNotClickOnRelease() = runTest {
        var clicks = 0
        var menus = 0
        val gesture = SearchOkGesture(this, { clicks++ }, { menus++ })
        gesture.down(repeated = false)
        advanceTimeBy(499); runCurrent()
        assertEquals(0, menus)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, menus)
        repeat(3) { gesture.down(repeated = true) }
        gesture.up()
        assertEquals(1, menus)
        assertEquals(0, clicks)
        gesture.down(repeated = false)
        gesture.up()
        assertEquals(1, clicks)
    }

    @Test fun cancelledOrFocusLostPressesNeverActivateAndStrayReleasesAreIgnored() = runTest {
        var actions = 0
        val gesture = SearchOkGesture(this, { actions++ }, { actions++ })
        gesture.down(repeated = false)
        advanceTimeBy(100)
        gesture.cancel() // focus loss / composition disposed
        advanceTimeBy(600); runCurrent()
        gesture.up()
        gesture.down(repeated = true)
        gesture.up()
        gesture.down(repeated = false)
        gesture.up(canceled = true)
        gesture.down(repeated = false, canceled = true)
        gesture.up()
        advanceTimeBy(600); runCurrent()
        assertEquals(0, actions)
    }
}
