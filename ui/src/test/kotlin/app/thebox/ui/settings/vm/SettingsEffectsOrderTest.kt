package app.thebox.ui.settings.vm

import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.ui.settings.FakeSettingsUiMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

/** U-L1 (M5 code review 2026-10-16): intents of one effects instance run in the order given. */
class SettingsEffectsOrderTest {
    @Test
    fun aSlowFirstIntentDoesNotOverwriteTheSecond() {
        val test = TestScope()
        val d = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(Job() + d)
        val route = SettingsRoute.Diagnostics
        var stored: Boolean? = null
        val source = object : SettingsPageSource {
            override fun handles(route: SettingsRoute) = route == SettingsRoute.Diagnostics
            override suspend fun page(route: SettingsRoute, ctx: PageContext) = PageModel(route, "", rows = emptyList(), version = 1)
            override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
                val on = (intent as RowIntent.Toggle).on
                if (on) delay(100) // a DAO write that takes longer the first time
                stored = on
                return true
            }
        }
        val effects = SettingsEffectsImpl(listOf(source), FakeSettingsStore(), LiveSettingsApplier { }, SettingsTexts.KEYS, null, scope, d, d) { 0L }
        effects.machine = FakeSettingsUiMachine(SettingsUiState(route = route, page = PageModel(route, "", rows = emptyList(), version = 1)))
        effects.onIntent(RowIntent.Toggle(RowId("diag.x"), on = true))
        effects.onIntent(RowIntent.Toggle(RowId("diag.x"), on = false))
        test.advanceUntilIdle()
        assertEquals("OK twice on a toggle stores the second value", false, stored)
    }
}
