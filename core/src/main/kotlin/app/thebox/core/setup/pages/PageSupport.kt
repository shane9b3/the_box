package app.thebox.core.setup.pages

import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.setup.GroupsReviewSource
import app.thebox.core.setup.GuideMatching
import app.thebox.core.setup.PlaylistAdmin
import app.thebox.core.setup.SetupDraftStore
import app.thebox.core.setup.SetupResultMapper
import app.thebox.core.setup.SetupStrings
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.SourceInputNormalizer
import app.thebox.core.setup.SourceSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Everything WP-B's page builders need (all ports; JVM tests pass fakes). */
class SetupPageDeps(
    val strings: SetupStrings,
    val normalizer: SourceInputNormalizer,
    val setup: SourceSetup,
    val admin: PlaylistAdmin,
    val groups: GroupsReviewSource,
    val matching: GuideMatching,
    val store: SettingsStore,
    val parental: StateFlow<ParentalState>,
    /** Main-thread scope (the shell's); page state changes run here, the ports do their own IO. */
    val scope: CoroutineScope,
    val nowMs: () -> Long,
    val drafts: SetupDraftStore = SetupDraftStore(),
    val locale: () -> Locale = { Locale.getDefault() },
    val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
) {
    val mapper: SetupResultMapper = SetupResultMapper(strings, zone, locale)
}

/** One open page: builds its [PageModel] from its own state and republishes on every change. */
abstract class SetupPage(val route: SettingsRoute, protected val d: SetupPageDeps) {
    lateinit var host: PageHost
    private val jobs = ArrayList<Job>()

    /** Starts observers; publishes at least once. */
    abstract fun start()
    abstract fun build(): PageModel
    open fun onIntent(intent: RowIntent) = Unit
    open fun onBack(): Boolean = false
    open fun onFilePicked(uri: String?, displayName: String?) = Unit

    /** Version of the last published page (paged lists answer `loadItems` for it). */
    var lastVersion: Long = 0
        private set

    fun publish() {
        val p = build()
        lastVersion = p.version
        host.publish(p)
    }

    open fun close() {
        for (j in jobs) j.cancel()
        jobs.clear()
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = d.scope.launch(block = block).also { jobs += it }

    internal fun act(a: PageAction) = host.act(a)

    // ------------------------------------------------------------------ text and rows

    internal fun t(key: SetupText, vararg args: Any): String = d.strings.text(key, *args)

    internal fun num(n: Int): String = String.format(d.locale(), "%,d", n)

    internal fun page(title: String, rows: List<SettingsRow>, subtitle: String? = null, placeholder: String? = null, list: app.thebox.core.settings.PagedList? = null, focus: String? = null) =
        PageModel(route, title, subtitle, rows, list, VERSION.incrementAndGet(), placeholder, focus = focus?.let(::RowId))

    /** A row whose visibility and effect come from its [SettingsTree] spec when it has one (§3.3). */
    internal fun row(
        id: String, label: String, kind: RowKind, value: String? = null, description: String? = null,
        enabled: Boolean = true, disabledReason: String? = null,
        visibility: Visibility? = null, effect: EffectTiming? = null,
    ): SettingsRow {
        val rid = RowId(id)
        val spec = SettingsTree.spec(rid) ?: parentSpec(id)
        return SettingsRow(
            id = rid, label = label, value = value, description = description, kind = kind, enabled = enabled,
            disabledReason = if (enabled) null else disabledReason,
            visibility = visibility ?: spec?.visibility ?: Visibility.SIMPLE,
            effect = effect ?: spec?.effect ?: EffectTiming.LIVE,
        )
    }

    internal fun info(id: String, label: String, lines: List<String>, tone: Tone = Tone.NORMAL) = row(id, label, RowKind.Info(lines, tone))

    internal fun action(id: String, label: String, value: String? = null, description: String? = null, enabled: Boolean = true, disabledReason: String? = null, destructive: Boolean = false, confirm: ConfirmSpec? = null, needsPin: Boolean = false) =
        row(id, label, RowKind.Action(destructive, confirm, needsPin), value, description, enabled, disabledReason)

    internal fun link(id: String, label: String, route: SettingsRoute, value: String? = null, description: String? = null, needsPin: Boolean = false) =
        row(id, label, RowKind.Link(route, needsPin), value, description)

    internal fun text(id: String, label: String, value: String, hint: TextHint, masked: Boolean = false, chips: List<String> = emptyList(), shown: String? = null, description: String? = null) =
        row(id, label, RowKind.Text(value, hint, masked, chips), value = shown ?: if (masked) mask(value) else value.ifEmpty { null }, description = description)

    internal fun toggle(id: String, label: String, on: Boolean, description: String? = null) = row(id, label, RowKind.Toggle(on), description = description)

    internal fun choice(id: String, label: String, options: List<ChoiceOption>, selectedId: String, description: String? = null): SettingsRow {
        val sel = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        return row(id, label, RowKind.Choice(options, sel), value = options.getOrNull(sel)?.label, description = description)
    }

    internal fun mask(v: String): String? = if (v.isEmpty()) null else "•".repeat(v.length.coerceAtMost(12))

    companion object {
        /** A sub-row (`playlist.headers.h.Referer`) takes the spec of its nearest registered parent. */
        fun parentSpec(id: String): app.thebox.core.settings.tree.RowSpec? {
            var k = id
            while (true) {
                val dot = k.lastIndexOf('.')
                if (dot <= 0) return null
                k = k.substring(0, dot)
                SettingsTree.spec(RowId(k))?.let { return it }
            }
        }

        /** Page versions are global and monotonic, so a republished page always supersedes the last one. */
        val VERSION = AtomicLong()

        /** URL chips of the editor (§3.4.3). */
        val SERVER_CHIPS = listOf("http://", "https://", ":8080", ":80", ":25461", ".com", ".net", ".tv", "/")
        val M3U_CHIPS = SERVER_CHIPS + listOf(".m3u", ".m3u8", "get.php?")
    }
}
