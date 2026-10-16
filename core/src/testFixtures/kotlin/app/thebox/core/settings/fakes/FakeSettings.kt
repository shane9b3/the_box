package app.thebox.core.settings.fakes

import app.thebox.core.catchup.archive.LiveSettingsApplier
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The store's JSON (docs/design/m5-setup-settings.md §2.2): unknown keys ignored, defaults not encoded. */
val SETTINGS_JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/**
 * In-memory [SettingsStore] for JVM tests (docs/design/m5-setup-settings.md §1.9 item 8). [ready] starts as [readyAtOnce];
 * [markReady] opens it later (startup-wait tests). [export]/[importInTransaction] go through [SETTINGS_JSON] for the
 * domains that were read or written ([register] adds one up front). Records updates, flushes and loads.
 */
class FakeSettingsStore(readyAtOnce: Boolean = true) : SettingsStore {
    private val _ready = MutableStateFlow(readyAtOnce)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val domains = LinkedHashMap<String, SettingsDomain<*>>()
    private val flows = HashMap<String, MutableStateFlow<Any?>>()
    val updates = ArrayList<String>()
    var flushes = 0
    var loads = 0
    val imports = ArrayList<Pair<Map<String, JsonElement>, Boolean>>()

    fun markReady() { _ready.value = true }
    fun register(domain: SettingsDomain<*>) { state(domain) }

    override suspend fun awaitReady() { ready.first { it } }
    override fun load() { loads++ }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(domain: SettingsDomain<T>): MutableStateFlow<T> {
        domains.getOrPut(domain.name) { domain }
        return flows.getOrPut(domain.name) { MutableStateFlow<Any?>(domain.default) } as MutableStateFlow<T>
    }

    override fun <T> flow(domain: SettingsDomain<T>): StateFlow<T> = state(domain).asStateFlow()
    override fun <T> update(domain: SettingsDomain<T>, transform: (T) -> T) {
        val s = state(domain)
        s.value = transform(s.value)
        updates += domain.name
    }
    override suspend fun flush() { flushes++ }

    @Suppress("UNCHECKED_CAST")
    override suspend fun export(): Map<String, JsonElement> = domains.values.filter { it.backedUp }.associate { d ->
        d as SettingsDomain<Any?>
        d.name to SETTINGS_JSON.encodeToJsonElement(d.serializer, state(d).value)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun importInTransaction(documents: Map<String, JsonElement>, replace: Boolean) {
        imports += documents to replace
        for ((name, json) in documents) {
            val d = domains[name] as SettingsDomain<Any?>? ?: continue
            state(d).value = SETTINGS_JSON.decodeFromJsonElement(d.serializer, json)
        }
    }
}

/** Records every [SettingsEffects] call as a string ("loadPage(Root)", "onClosed([REPREPARE])", …). */
class RecordingSettingsEffects : SettingsEffects {
    val calls = ArrayList<String>()
    val intents = ArrayList<RowIntent>()
    val closed = ArrayList<Set<EffectTiming>>()
    override fun loadPage(route: SettingsRoute) { calls += "loadPage($route)" }
    override fun loadItems(route: SettingsRoute, from: Int, to: Int) { calls += "loadItems($route,$from,$to)" }
    override fun onIntent(intent: RowIntent) { intents += intent; calls += "onIntent($intent)" }
    override fun onClosed(changedTimings: Set<EffectTiming>) { closed += changedTimings; calls += "onClosed($changedTimings)" }
    override fun armCapture(armed: Boolean) { calls += "armCapture($armed)" }
    override fun exitApp() { calls += "exitApp" }
    override fun announce(text: String) { calls += "announce($text)" }
}

/** [PinGate] for tests: answers [pinSet] (true = "a prompt opened") and records every purpose. */
class FakePinGate(var pinSet: Boolean = false) : PinGate {
    val requests = ArrayList<PinPurpose>()
    override fun request(purpose: PinPurpose): Boolean { requests += purpose; return pinSet }
}

/**
 * A [SettingsMachine] that records what the guide machine asks of it (composition tests, §3.15). [consume] decides what
 * [act] answers (default: everything but BACK, OPEN_GUIDE and EXIT, like the real machine at its root).
 */
class RecordingSettingsMachine(
    var consume: (GuideEvent.Act) -> Boolean = { it.action != BoxAction.BACK && it.action != BoxAction.OPEN_GUIDE && it.action != BoxAction.EXIT },
) : SettingsMachine {
    private val _ui = MutableStateFlow(SettingsUiState())
    override val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()
    override val nextDeadlineMs: StateFlow<Long> = GuideStateMachine.NO_DEADLINE
    override var pinGate: PinGate? = null
    override var textInputActive: Boolean = false
    val opened = ArrayList<Triple<SettingsRoute, Boolean, Boolean>>()
    val acts = ArrayList<GuideEvent.Act>()
    val events = ArrayList<SettingsEvent>()
    var leaves = 0

    override fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean) {
        opened += Triple(route, fromFull, detached)
        _ui.value = SettingsUiState(route = route)
    }
    override fun act(action: GuideEvent.Act): Boolean { acts += action; return consume(action) }
    override fun onLeave() { leaves++ }
    override fun dispatch(event: SettingsEvent) { events += event }
}

/** Counts [LiveSettingsApplier.apply] calls (ReapplyOnExitTest, S9). */
class FakeLiveSettingsApplier : LiveSettingsApplier {
    var applied = 0
    override fun apply() { applied++ }
}
