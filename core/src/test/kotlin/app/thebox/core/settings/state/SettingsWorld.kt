package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakePinGate
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.fakes.SettingsFixtures
import app.thebox.core.settings.tree.SettingsTree
import kotlinx.coroutines.runBlocking

/** A fixed [WpaPageEnv] for JVM tests. */
class FakePageEnv(
    override var sdkInt: Int = 30,
    override var deviceLowEnd: Boolean = false,
    var is24h: Boolean = true,
    var players: List<ExternalPlayerApp> = listOf(ExternalPlayerApp("org.videolan.vlc", "VLC")),
    var running: Boolean? = true,
    var overrides: Int = 0,
) : WpaPageEnv {
    var restarts = 0
    override fun system24h() = is24h
    override fun externalPlayers() = players
    override fun runningSoftwareAudioFallback() = running
    override fun restartNow() { restarts++ }
    override fun keyOverrideCount() = overrides
}

/**
 * Pages the other WPs own, standing in for them in the machine tests (the production effects show a placeholder page
 * for routes nobody builds; this one adds a CHANNELS section page and a management page with every row kind, so the
 * machine's generic paths — items with menus and move mode, paged lists, numbers, text, multi choice, confirm, PIN — are
 * enumerated too). Rows ids are prefixed `fx.` and are not in [SettingsTree].
 */
class FixturePages : SettingsPageSource {
    val channel = SettingsFixtures.channels.first().identity
    override fun handles(route: SettingsRoute) = true

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel = build(route)

    fun build(route: SettingsRoute): PageModel = when {
        route == SettingsRoute.Section(SectionId.CHANNELS) -> PageModel(route, "Channels", rows = listOf(
            SettingsRow(RowId("fx.lists"), "Favourites", kind = RowKind.Link(SettingsRoute.FavouriteLists)),
            SettingsRow(RowId("fx.channel"), "Channel options", kind = RowKind.Link(SettingsRoute.Channel(channel))),
            SettingsRow(RowId("fx.hidden"), "Hidden", kind = RowKind.Link(SettingsRoute.HiddenChannels(1))),
        ), version = 1)
        route == SettingsRoute.FavouriteLists -> PageModel(route, "Lists", rows = listOf(
            SettingsRow(RowId("fx.number"), "Number", kind = RowKind.Number(5, 1, 10)),
            SettingsRow(RowId("fx.text"), "Name", value = "x", kind = RowKind.Text("x", TextHint.NAME)),
            SettingsRow(RowId("fx.multi"), "Many", kind = RowKind.MultiChoice(listOf(ChoiceOption("a", "A"), ChoiceOption("b", "B"), ChoiceOption("c", "C", enabled = false)), setOf(0))),
            SettingsRow(RowId("fx.confirm"), "Delete", kind = RowKind.Action(destructive = true, confirm = ConfirmSpec("T", "B", "Delete", "Cancel"))),
            SettingsRow(RowId("fx.pin"), "Reset", kind = RowKind.Action(needsPin = true)),
            SettingsRow(RowId("fx.info"), "Info", kind = RowKind.Info(listOf("line"))),
            SettingsRow(RowId("fx.disabled"), "Disabled", kind = RowKind.Toggle(false), enabled = false, disabledReason = "why"),
            SettingsRow(RowId(SettingsMachineImpl.CAPTURE_PREFIX + "open_search:SHORT"), "Bind a key", kind = RowKind.Action()),
        ) +SettingsFixtures.favouriteLists.map { l ->
            SettingsRow(RowId("fx.list.${l.id}"), l.name, kind = RowKind.Item(
                route = SettingsRoute.FavouriteList(l.id),
                menu = listOf(ChoiceOption("open", "Open"), ChoiceOption(SettingsMachineImpl.MOVE_OPTION, "Move"), ChoiceOption("rename", "Rename")),
                movable = true,
            ))
        } + listOf(
            SettingsRow(RowId("fx.list.move"), "Move a list", kind = RowKind.Item(primary = "move")),
            SettingsRow(RowId("fx.list.rename"), "Rename a list", kind = RowKind.Item(primary = "rename")),
        ), version = 1)
        route is SettingsRoute.HiddenChannels -> PageModel(route, "Hidden", rows = emptyList(),
            list = PagedList(count = SettingsFixtures.hidden.size, pageSize = 8, loaded = emptyMap()), version = 1)
        route is SettingsRoute.Channel -> ChannelOptionsPage.build(route, snapshot(), SettingsTexts.KEYS, 1)
        else -> PageModel(route, "", rows = emptyList(), version = 1, placeholder = "PAGE_NOT_AVAILABLE")
    }

    fun items(route: SettingsRoute, from: Int, to: Int): List<SettingsRow> =
        (from until to).map { i -> SettingsRow(RowId("fx.hidden.$i"), SettingsFixtures.hidden[i].name, kind = RowKind.Item(primary = "unhide")) }

    fun snapshot() = ChannelOptionsSnapshot(
        name = "Channel 1.1", number = 1, group = "Group 1.0", playlistName = "Provider A", identityKind = IdentityKind.PROVIDER_ID,
        streamHost = "panel.example.com", format = "ts", archiveMode = "xc", archiveDays = 3, catchupWinner = null, epgMatch = "tvg-id",
        fixedOffsetZone = true, hidden = false, locked = false, shiftMin = null, aspect = null, decoder = null, buffer = null,
        audioLanguage = "de", subtitleLanguage = null, favouriteLists = SettingsFixtures.favouriteLists.map { it.id to it.name }, memberOf = setOf(1L),
    )
}

/** Effects that queue loads; [SettingsWorld.pump] answers them synchronously (no re-entrant dispatch inside act). */
class QueueEffects : SettingsEffects {
    val loads = ArrayList<SettingsRoute>()
    val itemLoads = ArrayList<Triple<SettingsRoute, Int, Int>>()
    val intents = ArrayList<RowIntent>()
    val closed = ArrayList<Set<EffectTiming>>()
    val announced = ArrayList<String>()
    var exits = 0
    var armed = false
    override fun loadPage(route: SettingsRoute) { loads += route }
    override fun loadItems(route: SettingsRoute, from: Int, to: Int) { itemLoads += Triple(route, from, to) }
    override fun onIntent(intent: RowIntent) { intents += intent }
    override fun onClosed(changedTimings: Set<EffectTiming>) { closed += changedTimings }
    override fun armCapture(armed: Boolean) { this.armed = armed }
    override fun exitApp() { exits++ }
    override fun announce(text: String) { announced += text }
}

/**
 * The machine fed by WP-A's real page builders ([WpaPages] over a [FakeSettingsStore]) and [FixturePages] for the
 * rest. [applyIntents] = false freezes the store (reachability enumerates UI states for one stored configuration).
 */
class SettingsWorld(
    val store: FakeSettingsStore = FakeSettingsStore(),
    val env: FakePageEnv = FakePageEnv(),
    val pinSet: Boolean = false,
    val applyIntents: Boolean = true,
    var now: Long = 1_000_000L,
    /** False: an intent does not rebuild the page, so the machine's optimistic row changes stay (reachability). */
    val reloadAfterIntent: Boolean = true,
) {
    val effects = QueueEffects()
    val wpa = WpaPages(store, SettingsTexts.KEYS, env)
    val fixtures = FixturePages()
    val machine = SettingsMachineImpl(effects, { now }, SettingsTexts.KEYS, HintValidator(SettingsTexts.KEYS, WpaPages.rowRules(SettingsTexts.KEYS)))
    val gate = FakePinGate(pinSet)
    var advanced = false
    private var version = 1000L

    init { machine.pinGate = gate }

    fun page(route: SettingsRoute): PageModel {
        val p = if (wpa.handles(route)) wpa.build(route, advanced) else fixtures.build(route)
        return p.copy(version = ++version)
    }

    fun pump() {
        var guard = 0
        while ((effects.loads.isNotEmpty() || effects.itemLoads.isNotEmpty() || pendingIntents < effects.intents.size) && guard++ < 50) {
            while (pendingIntents < effects.intents.size) {
                val i = effects.intents[pendingIntents++]
                if (i.row == SettingsMachineImpl.ADVANCED_ROW) advanced = (i as RowIntent.Toggle).on
                if (applyIntents) runBlocking { wpa.onIntent(machine.ui.value.route, i, PageContext(advanced, now)) }
                if (reloadAfterIntent || i.row == SettingsMachineImpl.ADVANCED_ROW) machine.ui.value.page?.route?.let { effects.loads += it }
            }
            val loads = effects.loads.toList(); effects.loads.clear()
            for (r in loads.distinct()) machine.dispatch(SettingsEvent.Page(page(r)))
            val items = effects.itemLoads.toList(); effects.itemLoads.clear()
            for ((r, from, to) in items) {
                val v = machine.ui.value.page?.takeIf { it.route == r }?.version ?: continue
                machine.dispatch(SettingsEvent.Items(r, v, from, fixtures.items(r, from, to)))
            }
        }
    }

    private var pendingIntents = 0

    fun open(route: SettingsRoute = SettingsRoute.Root, detached: Boolean = false): SettingsWorld {
        machine.open(route, fromFull = false, detached = detached)
        pump()
        return this
    }

    /** Acts and pumps; the machine's answer (false = the guide acts). */
    fun act(a: BoxAction, digit: Int = -1, repeat: Int = 0, heldMs: Long = 0): Boolean {
        val r = machine.act(GuideEvent.Act(a, repeat = repeat, digit = digit, heldMs = heldMs))
        pump()
        return r
    }

    val ui get() = machine.ui.value

    fun focusedRow(): SettingsRow? = ui.page?.let { SettingsMachineImpl.rowAt(it, ui.cursor) }

    /** Moves the cursor to [id] on the open page with DOWN/UP. */
    fun focus(id: RowId) {
        repeat(200) {
            val p = ui.page ?: return
            val target = SettingsMachineImpl.indexOf(p, id) ?: error("row ${id.key} not on ${p.route}")
            if (target == ui.cursor) return
            act(if (target > ui.cursor) BoxAction.DOWN else BoxAction.UP)
        }
        error("could not focus ${id.key}")
    }

    /** From Root: focus [section] in the sections pane and enter its page. */
    fun enter(section: SectionId) {
        val i = ui.sections.indexOf(section)
        require(i >= 0) { "$section not listed" }
        while (ui.sectionCursor > i) act(BoxAction.UP)
        while (ui.sectionCursor < i) act(BoxAction.DOWN)
        act(BoxAction.RIGHT)
    }

    companion object {
        fun visibleSpecs(section: SectionId, advanced: Boolean) =
            SettingsTree.rowsOf(section).filter { advanced || it.visibility == Visibility.SIMPLE }

        val ID: ChannelIdentity = ChannelIdentity(1, StableKey("xc:1-1"))
    }
}
