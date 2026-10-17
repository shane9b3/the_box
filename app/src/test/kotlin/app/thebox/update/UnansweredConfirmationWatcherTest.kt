package app.thebox.update

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D-B4 (M5 code review 2026-10-16; from the data reviewer's `M5ReviewUpdaterLifecycleTest`): the updater's watcher for
 * an unanswered install confirmation. `addObserver` replays ON_START at once while the app is in front; the session
 * must not be abandoned then, only on a real return (a start after a stop).
 */
class UnansweredConfirmationWatcherTest {
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun pendingConfirmationIsNotAbandonedWhileTheAppIsInFront() {
        owner.registry.currentState = Lifecycle.State.RESUMED // PENDING_USER_ACTION arrives with the app in front
        var abandoned = 0
        UnansweredConfirmationWatcher(owner.lifecycle) { abandoned++ }.arm()
        assertEquals("onStart replayed inside addObserver must not abandon the session", 0, abandoned)
        owner.registry.currentState = Lifecycle.State.CREATED // the system confirmation covers the app
        assertEquals(0, abandoned)
        owner.registry.currentState = Lifecycle.State.RESUMED // back without answering it
        assertEquals(1, abandoned)
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("once", 1, abandoned)
    }

    @Test
    fun armedInTheBackgroundActsOnTheNextStart() {
        owner.registry.currentState = Lifecycle.State.CREATED
        var abandoned = 0
        UnansweredConfirmationWatcher(owner.lifecycle) { abandoned++ }.arm()
        assertEquals(0, abandoned)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(1, abandoned)
    }
}
