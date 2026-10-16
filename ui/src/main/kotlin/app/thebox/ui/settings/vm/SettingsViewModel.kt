package app.thebox.ui.settings.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.net.SecureDnsNotices
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsShellEvent
import app.thebox.core.settings.state.SettingsShellLink
import app.thebox.core.settings.state.SettingsText
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.guide.GuideStateMachine
import app.thebox.ui.input.KeyRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The settings screen's view model (docs/design/m5-setup-settings.md §2.13): creates the [SettingsMachine] (activity
 * scoped, like the player view model; MainActivity hands [machine] to the guide view model, S11) with
 * [SettingsEffectsImpl], and feeds it: the stored "Show advanced settings", rebuilds when a page's data changes, the
 * page sources' notices, the notice/capture deadline ticks, and the one-time secure-DNS fallback toast (§3.13).
 */
@HiltViewModel
class SettingsViewModel internal constructor(
    factory: SettingsMachineFactory,
    sources: Set<@JvmSuppressWildcards SettingsPageSource>,
    store: SettingsStore,
    applier: LiveSettingsApplier,
    texts: SettingsTexts,
    keyRouter: KeyRouter?,
    dnsNotices: SecureDnsNotices?,
    navigator: SettingsNavigatorImpl?,
    adapters: SettingsPageAdapters?,
    scope: CoroutineScope?,
    main: CoroutineDispatcher,
    work: CoroutineDispatcher,
    nowMs: () -> Long,
) : ViewModel() {
    @Inject constructor(
        factory: SettingsMachineFactory,
        sources: Set<@JvmSuppressWildcards SettingsPageSource>,
        store: SettingsStore,
        applier: LiveSettingsApplier,
        texts: SettingsTexts,
        keyRouter: KeyRouter,
        dnsNotices: SecureDnsNotices,
        navigator: SettingsNavigatorImpl,
        adapters: SettingsPageAdapters,
    ) : this(factory, sources, store, applier, texts, keyRouter, dnsNotices, navigator, adapters, null, Dispatchers.Main.immediate, Dispatchers.Default, System::currentTimeMillis)

    private val scope: CoroutineScope = scope ?: viewModelScope
    private var machineRef: SettingsMachine? = null
    /** (M5 integration) The pages of WP-B…WP-E hosted through adapters (`SettingsPageAdapters`), after WP-A's set. */
    private val built = adapters?.create(this.scope, main, work) { machineRef as? SettingsShellLink }
    private val ordered = sources.sortedBy { it.javaClass.name } + built?.sources.orEmpty()

    /** WP-B's pages: the activity answers their document-picker requests (`PageAction.PickM3uFile`). */
    val setupPages: SetupPagesSource? get() = built?.setup
    private val effects = SettingsEffectsImpl(ordered, store, applier, texts, keyRouter, this.scope, main, work, nowMs)

    val machine: SettingsMachine = factory.create(effects, nowMs)

    init {
        machineRef = machine
        effects.machine = machine
        built?.mapping?.let { m -> (machine as? SettingsShellLink)?.captureSaved = m::save }
        navigator?.attach(machine)
        val s = this.scope
        // BACK asks the page's source first (WP-B's import page confirms before it is left, §3.4.5).
        (machine as? SettingsShellLink)?.backHandler = { r -> ordered.firstOrNull { it.handles(r) }?.onBack(r) == true }
        // Routes that leave the open path (popped, replaced, Settings closed) let their source stop observing.
        s.launch(main) {
            var live = emptySet<app.thebox.core.settings.SettingsRoute>()
            machine.ui.collect { u ->
                built?.phone?.onRoute(u.route)
                val now = (u.stack + u.route + listOfNotNull(u.page?.route)).toSet()
                for (gone in live - now) ordered.firstOrNull { it.handles(gone) }?.closed(gone)
                live = now
            }
        }
        machine.dispatch(SettingsEvent.Advanced(store.flow(GuideDomain).value.advancedSettings))
        s.launch(main) {
            store.flow(GuideDomain).map { it.advancedSettings }.distinctUntilChanged().collect { machine.dispatch(SettingsEvent.Advanced(it)) }
        }
        s.launch(main) {
            merge(*ordered.map { it.changes }.toTypedArray()).conflate().collect {
                effects.rebuildDisplayed()
                delay(REBUILD_MIN_GAP_MS)
            }
        }
        s.launch(main) { merge(*ordered.map { it.notices }.toTypedArray()).collect { machine.dispatch(SettingsEvent.Notice(it)) } }
        s.launch(main) {
            machine.nextDeadlineMs.collectLatest { at ->
                if (at == GuideStateMachine.NO_DEADLINE.value) return@collectLatest
                delay((at - nowMs()).coerceAtLeast(0))
                machine.dispatch(SettingsEvent.Tick(nowMs()))
            }
        }
        if (dnsNotices != null) s.launch(main) {
            dnsNotices.fallbackUsed.collect { (machine as? SettingsShellLink)?.post(SettingsShellEvent.Toast(texts[SettingsText.DOH_FALLBACK])) }
        }
    }

    private companion object {
        /** Page rebuilds triggered by data changes are spaced (a refresh can write many rows). */
        const val REBUILD_MIN_GAP_MS = 250L
    }
}
