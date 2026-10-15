package app.thebox.data.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (M3 code review 2026-10-14, O-7) Opens once the first activity frame has drawn (`MainActivity` opens it). Work that
 * is not needed for that frame (the reminder book's Room query and the reminder runtime) waits for it, so the cold start
 * spends its main-thread and disk time on the guide first.
 */
@Singleton
class StartupGate @Inject constructor() {
    private val state = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = state.asStateFlow()

    fun open() { state.value = true }

    suspend fun awaitOpen() { state.first { it } }

    companion object {
        /** An already open gate (tests, tools). */
        fun opened(): StartupGate = StartupGate().also { it.open() }
    }
}
