package app.thebox.ui.settings.vm

import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SettingsMachineImpl
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.setup.pages.PageHost
import app.thebox.core.setup.pages.SetupPageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * (Adopted from the M5 code review's shell reviewer, `ReviewSettingsVmLifecycleTest`, U-S1…U-S3.) The real
 * [SettingsViewModel], [SettingsMachineImpl] and [SetupPagesSource] with a push-model provider that behaves like WP-B's
 * `PlaylistsSectionPage`: `open` publishes a placeholder at once, then one page per value of [data] after [firstDataMs].
 */
internal class PushProvider(
    private val scope: CoroutineScope,
    private val data: MutableStateFlow<String>,
    private val firstDataMs: Long,
    private val immediatePlaceholder: Boolean = true,
) : SetupPageProvider {
    val jobs = HashMap<SettingsRoute, Job>()
    var opens = 0
    var closes = 0
    val intents = ArrayList<Pair<SettingsRoute, RowIntent>>()
    private var v = 0L
    override fun handles(route: SettingsRoute) = route is SettingsRoute.Section && route.id == SectionId.PLAYLISTS || route is SettingsRoute.AddSource
    override fun open(route: SettingsRoute, host: PageHost) {
        opens++
        if (immediatePlaceholder) host.publish(PageModel(route, "Playlists", rows = emptyList(), version = ++v, placeholder = "Loading…"))
        jobs[route] = scope.launch {
            delay(firstDataMs) // the Room query + sync status of PlaylistAdmin.observeAll
            data.collect { d -> host.publish(PageModel(route, "Playlists", rows = listOf(SettingsRow(RowId("pl.1"), d, kind = RowKind.Info(emptyList()))), version = ++v)) }
        }
    }
    override fun close(route: SettingsRoute) { closes++; jobs.remove(route)?.cancel() }
    override fun onIntent(route: SettingsRoute, intent: RowIntent, host: PageHost) { intents += route to intent }
}

internal class SettingsVmRig(firstDataMs: Long = 10, immediatePlaceholder: Boolean = true, extra: List<SettingsPageSource> = emptyList(), splitWork: Boolean = false) {
    val test = TestScope()
    private val d = StandardTestDispatcher(test.testScheduler)
    /** A separate "work" dispatcher: a build's return to main is then queued like on the device. */
    private val w = if (splitWork) StandardTestDispatcher(test.testScheduler) else d
    val scope = CoroutineScope(Job() + d)
    val data = MutableStateFlow("Main · 2,140 channels")
    val provider = PushProvider(scope, data, firstDataMs, immediatePlaceholder)
    lateinit var machine: SettingsMachine
    val source = SetupPagesSource(provider, { machine as? SettingsShellLink }, scope, d)
    val vm = SettingsViewModel(
        SettingsMachineImpl.FACTORY, setOf<SettingsPageSource>(source) + extra, FakeSettingsStore(), LiveSettingsApplier { }, SettingsTexts.KEYS,
        null, null, null, null, scope, d, w, { test.testScheduler.currentTime },
    ).also { machine = it.machine }

    init {
        // As in the app: the view model (and its change collectors) exist long before Settings opens.
        test.advanceTimeBy(1_000); test.runCurrent()
    }

    fun advance(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
    fun shownLabel(): String? = machine.ui.value.page?.takeIf { it.route == SettingsRoute.Section(SectionId.PLAYLISTS) && it.placeholder == null }?.rows?.firstOrNull()?.label
}
