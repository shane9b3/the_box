package app.thebox.ui.settings.vm

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.PageContext
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 code review 2026-10-16 (adopted from the shell reviewer's `ReviewSettingsVmLifecycleTest`): the adapters' lifetime. */
class SettingsViewModelLifecycleTest {
    /** U-S2: a section page closed by the view model while the machine keeps it cached is rebuilt when the cursor returns. */
    @Test
    fun sectionPageRefreshesAfterTheCursorComesBack() {
        val r = SettingsVmRig(firstDataMs = 0)
        r.machine.open(SettingsRoute.Root, fromFull = false)
        r.advance(1_000)
        assertEquals("Main · 2,140 channels", r.shownLabel())
        r.machine.act(GuideEvent.Act(BoxAction.DOWN)); r.advance(500) // sections pane → TV guide
        r.machine.act(GuideEvent.Act(BoxAction.UP)); r.advance(500)   // back to Playlists (cached page shown)
        r.data.value = "Main · Refreshing · reading channels"
        r.advance(2_000)
        assertEquals("provider pages open vs closed: opens=${r.provider.opens} closes=${r.provider.closes}", "Main · Refreshing · reading channels", r.shownLabel())
    }

    /** U-S3: Settings left while the first page is still loading: the provider's page is closed and no page lands after the leave. */
    @Test
    fun leavingWhileThePageLoadsClosesItsSource() {
        val r = SettingsVmRig(firstDataMs = 100, immediatePlaceholder = false)
        r.machine.open(SettingsRoute.Root, fromFull = false)
        r.advance(10)
        r.machine.onLeave()
        r.advance(1_000)
        assertNull("no page published after onLeave: ${r.machine.ui.value.page?.route}", r.machine.ui.value.page)
        assertEquals("every provider page opened is closed (opens=${r.provider.opens})", r.provider.opens, r.provider.closes)
    }

    /** U-S3: a page() call waiting on a slot that is closed returns a placeholder instead of waiting forever. */
    @Test
    fun aWaitingPageCallEndsWhenItsRouteCloses() {
        val r = SettingsVmRig(firstDataMs = 10_000, immediatePlaceholder = false)
        val route = SettingsRoute.Section(SectionId.PLAYLISTS)
        var got: app.thebox.core.settings.PageModel? = null
        r.scope.launch { got = r.source.page(route, PageContext(false, 0)) }
        r.advance(10)
        r.source.closed(route)
        r.advance(10)
        assertTrue("the waiter got a placeholder: $got", got?.placeholder != null)
    }

    /** U-L2: an intent for a provider page the user has left is dropped, not delivered to a page reopened for it. */
    @Test
    fun anIntentForAPageNoLongerShownDoesNotReopenIt() {
        val r = SettingsVmRig(firstDataMs = 0)
        val route = SettingsRoute.AddSource(app.thebox.core.settings.SourceKind.XTREAM)
        r.machine.open(SettingsRoute.Root, fromFull = false)
        r.advance(1_000)
        val opens = r.provider.opens
        r.scope.launch { r.source.onIntent(route, RowIntent.Run(RowId("setup.xtream.connect")), PageContext(false, 0)) }
        r.advance(2_000)
        assertEquals("no page reopened for a route not shown", opens, r.provider.opens)
        assertTrue(r.provider.intents.isEmpty())
    }
}
