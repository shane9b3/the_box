package app.thebox.core.setup.pages

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.setup.NowAiring
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.UnmatchedChannel
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * FixGuide() (S13, §3.6.3): the channels without guide data, most watched first, then (after a divider) the ones
 * matched to a guide channel that has no programmes. Paged (`PagedList`): the list can hold thousands of channels.
 */
class FixUnmatchedPage(d: SetupPageDeps) : SetupPage(SettingsRoute.FixGuide(null), d) {
    private var unmatched: List<UnmatchedChannel>? = null
    private var noData: List<UnmatchedChannel> = emptyList()
    /** The flat list the pager pages over: unmatched, then (when any) a divider and the no-data channels. */
    private var entries: List<Entry> = emptyList()

    private sealed interface Entry {
        data class Channel(val c: UnmatchedChannel, val noData: Boolean) : Entry
        data object Divider : Entry
    }

    override fun start() {
        publish()
        launch {
            val access = d.parental.value.access
            d.matching.unmatched(access).combine(d.matching.matchedWithoutData(access)) { a, b -> a to b }.collect { (a, b) ->
                unmatched = a
                noData = b
                entries = buildList {
                    a.forEach { add(Entry.Channel(it, false)) }
                    if (b.isNotEmpty()) { add(Entry.Divider); b.forEach { add(Entry.Channel(it, true)) } }
                }
                publish()
            }
        }
    }

    override fun build(): PageModel {
        val u = unmatched ?: return page(t(SetupText.FIX_TITLE), emptyList(), placeholder = t(SetupText.LOADING))
        if (entries.isEmpty()) return page(t(SetupText.FIX_TITLE), emptyList(), placeholder = t(SetupText.FIX_NONE))
        val first = rowsFor(0, PagedList.PAGE_SIZE)
        return page(
            t(SetupText.FIX_TITLE), emptyList(),
            subtitle = t(SetupText.FIX_SUBTITLE, num(u.size)),
            list = PagedList(count = entries.size, loaded = mapOf(0 to first)),
        )
    }

    /** Rows [from, to) of the list (`SettingsEffects.loadItems`). */
    fun rowsFor(from: Int, to: Int): List<SettingsRow> {
        val out = ArrayList<SettingsRow>((to - from).coerceAtLeast(0))
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(entries.size)) {
            out += when (val e = entries[i]) {
                Entry.Divider -> info(ROW_DIVIDER, t(SetupText.FIX_DIVIDER), emptyList())
                is Entry.Channel -> row(
                    channelRow(e.c.identity), e.c.name,
                    RowKind.Item(route = SettingsRoute.FixGuide(e.c.identity)),
                    value = e.c.groupName,
                    description = when {
                        e.noData -> t(SetupText.FIX_NO_PROGRAMMES)
                        e.c.bestScore != null -> t(SetupText.FIX_NO_DATA_SUGGESTED, pct(e.c.bestScore))
                        else -> t(SetupText.FIX_NO_DATA)
                    },
                )
            }
        }
        return out
    }

    fun publishItems(from: Int, to: Int, items: (Int, List<SettingsRow>) -> Unit) = items(from, rowsFor(from, to))

    private fun pct(score: Float): String = ((score * 100).toInt().coerceIn(0, 100)).toString()

    companion object {
        const val ROW_CHANNEL_PREFIX = "fix.ch."
        const val ROW_DIVIDER = "fix.divider"

        fun channelRow(c: ChannelIdentity) = "$ROW_CHANNEL_PREFIX${c.playlistId}.${c.stableKey.value}"
    }
}

/** FixGuide(channel) (§3.6.3): search row, suggestions with confidence, search results, "Remove my choice", time shift. */
class FixPickerPage(private val r: SettingsRoute.FixGuide, d: SetupPageDeps) : SetupPage(r, d) {
    private val channel: ChannelIdentity = r.channel!!
    private var name: String? = null
    private var suggestions: List<Candidate> = emptyList()
    private var results: List<Candidate>? = null
    private var query = ""
    private var override: EpgChannelRef? = null
    private var shift = 0
    private var now: Map<EpgChannelRef, NowAiring> = emptyMap()
    private var loaded = false
    private var busy = false

    data class Candidate(val ref: EpgChannelRef, val name: String, val score: Float?)

    override fun start() {
        publish()
        launch {
            name = d.matching.channelName(channel)
            suggestions = d.matching.suggestions(channel, SetupConstants.PICKER_SUGGEST_LIMIT).map { Candidate(it.ref, it.displayName, it.score) }
            override = d.matching.override(channel)
            shift = d.matching.channelShift(channel)
            loaded = true
            refreshNow()
            publish()
        }
    }

    /** "Now: {title} · 19:30–20:00" for every visible candidate: one batched EpgReader lookup (deviation from per-focus). */
    private suspend fun refreshNow() {
        val refs = (suggestions + results.orEmpty()).map { it.ref }.distinct()
        now = if (refs.isEmpty()) emptyMap() else d.matching.nowAiring(refs, d.nowMs())
    }

    override fun build(): PageModel {
        val rows = ArrayList<SettingsRow>(64)
        rows += text(ROW_SEARCH, t(SetupText.FIX_SEARCH), query, TextHint.NAME, description = t(SetupText.FIX_SEARCH_HINT))
        override?.let { rows += action(ROW_CLEAR, t(SetupText.FIX_REMOVE_CHOICE), value = it.xmltvId) }
        val res = results
        if (res != null) {
            if (res.isEmpty()) rows += info(ROW_NO_RESULTS, t(SetupText.FIX_NO_RESULTS, query), emptyList())
            res.forEachIndexed { i, c -> rows += candidateRow("$ROW_RESULT_PREFIX$i", c, suggested = false) }
        }
        if (suggestions.isNotEmpty()) rows += info(ROW_SUGGESTED_HEADER, t(SetupText.FIX_SUGGESTED_HEADER), emptyList())
        suggestions.forEachIndexed { i, c -> rows += candidateRow("$ROW_SUGGEST_PREFIX$i", c, suggested = true) }
        rows += choice(
            ROW_SHIFT, t(SetupText.FIX_SHIFT),
            SHIFT_STEPS_MIN.map { ChoiceOption(it.toString(), shiftLabel(it)) }, shift.toString(),
        )
        return page(
            name ?: t(SetupText.FIX_TITLE), rows,
            subtitle = t(SetupText.FIX_PICKER_SUBTITLE),
            placeholder = if (!loaded) t(SetupText.LOADING) else null,
        )
    }

    private fun candidateRow(id: String, c: Candidate, suggested: Boolean): SettingsRow {
        val n = now[c.ref]
        val nowLine = n?.let { t(SetupText.FIX_NOW, it.title, hm(it.startMs), hm(it.endMs)) }
        val label = if (suggested && c.score != null) t(SetupText.FIX_SUGGESTED, ((c.score * 100).toInt().coerceIn(0, 100)).toString()) else null
        return row(
            id, c.name, RowKind.Item(primary = PRIMARY_PICK),
            value = if (override == c.ref) t(SetupText.FIX_CURRENT) else label,
            description = nowLine ?: c.ref.xmltvId,
        )
    }

    private fun hm(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm", d.locale()).withZone(d.zone()).format(Instant.ofEpochMilli(ms))

    private fun shiftLabel(min: Int): String = when {
        min == 0 -> t(SetupText.FIX_SHIFT_NONE)
        else -> t(SetupText.FIX_SHIFT_HOURS, (if (min > 0) "+" else "−") + formatHours(kotlin.math.abs(min)))
    }

    private fun formatHours(min: Int): String = if (min % 60 == 0) (min / 60).toString() else String.format(d.locale(), "%.1f", min / 60.0)

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Edit -> if (intent.row.key == ROW_SEARCH) {
                // One editor commit = one search (§3.6.3): no search-as-you-type through the IME.
                query = d.normalizer.field(intent.text)
                launch {
                    results = if (query.isEmpty()) null else d.matching.search(query, SetupConstants.PICKER_SEARCH_LIMIT).map { Candidate(EpgChannelRef(it.sourceId, it.xmltvId), it.displayNames.firstOrNull() ?: it.xmltvId, null) }
                    refreshNow()
                    publish()
                }
            }
            is RowIntent.Run -> {
                val k = intent.row.key
                when {
                    k == ROW_CLEAR -> launch {
                        d.matching.clearChoice(channel)
                        override = null
                        act(PageAction.Notice(t(SetupText.FIX_CLEARED)))
                        publish()
                    }
                    k.startsWith(ROW_SUGGEST_PREFIX) -> pick(suggestions.getOrNull(k.removePrefix(ROW_SUGGEST_PREFIX).toIntOrNull() ?: -1))
                    k.startsWith(ROW_RESULT_PREFIX) -> pick(results?.getOrNull(k.removePrefix(ROW_RESULT_PREFIX).toIntOrNull() ?: -1))
                    else -> return
                }
            }
            is RowIntent.Choose -> if (intent.row.key == ROW_SHIFT) {
                val m = intent.optionId.toIntOrNull() ?: return
                shift = m
                launch { d.matching.setChannelShift(channel, m) }
            }
            else -> return
        }
        publish()
    }

    /** OK on a candidate: `setOverride` → incremental match + patch; the channel leaves the unmatched list. */
    private fun pick(c: Candidate?) {
        if (c == null || busy) return
        busy = true
        launch {
            d.matching.choose(channel, c.ref)
            override = c.ref
            busy = false
            act(PageAction.Notice(t(SetupText.FIX_CHOSEN, c.name)))
            act(PageAction.Pop)
        }
    }

    companion object {
        const val ROW_SEARCH = "fix.search"
        const val ROW_CLEAR = "fix.clear"
        const val ROW_NO_RESULTS = "fix.no_results"
        const val ROW_RESULT_PREFIX = "fix.result."
        const val ROW_SUGGESTED_HEADER = "fix.suggested"
        const val ROW_SUGGEST_PREFIX = "fix.suggest."
        const val ROW_SHIFT = "fix.shift"
        const val PRIMARY_PICK = "pick"

        /** −12 h … +12 h in 0.5 h steps (as `guide.shift`). */
        val SHIFT_STEPS_MIN: List<Int> = (-24..24).map { it * 30 }

        fun identityOf(rowKey: String): ChannelIdentity? {
            if (!rowKey.startsWith(FixUnmatchedPage.ROW_CHANNEL_PREFIX)) return null
            val rest = rowKey.removePrefix(FixUnmatchedPage.ROW_CHANNEL_PREFIX)
            val dot = rest.indexOf('.')
            if (dot <= 0) return null
            val pid = rest.substring(0, dot).toLongOrNull() ?: return null
            return ChannelIdentity(pid, StableKey(rest.substring(dot + 1)))
        }
    }
}
