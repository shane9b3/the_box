package app.thebox.core.editor

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction

/** A channel of the fake playlist. */
data class FC(
    val key: String, val name: String, val number: Int?, val customNumber: Int? = null,
    val hidden: Boolean = false, val ruleHidden: Boolean = false, val locked: Boolean = false, val favorite: Boolean = false,
)

/** A group of the fake playlist. */
data class FG(
    val key: String, val name: String, val channels: List<FC>,
    val hidden: Boolean = false, val ruleHidden: Boolean = false, val locked: Boolean = false, val customOrder: Boolean = false,
)

/**
 * The editor machine over a fake library (docs/design/m6-channel-editor.md §6.1): every effect is queued and answered
 * by [pump] after the key or event that asked for it, the way the view model answers on IO. Data are frozen (writes
 * answer `Done` and change nothing) unless a test edits [groups]. [hold] keeps an effect's answers back.
 */
class EditorWorld(
    var groups: List<FG> = DEFAULT_GROUPS,
    var pinSet: Boolean = false,
    var unlocked: Boolean = true,
    val pid: Long = 7L,
    val editorTexts: EditorTexts = EditorTexts.KEYS,
) {
    var now = 1_000L
    val calls = ArrayList<String>()
    val writes = ArrayList<EditorWrite>()
    val texts = ArrayList<EditorText>()
    val numbers = ArrayList<EditorText>()
    var pinRequests = 0
    val announced = ArrayList<String>()
    val summaries = ArrayList<Pair<Long, ChannelTarget>>()
    val groupRowLoads = ArrayList<Pair<Int, Int>>()
    val channelRowLoads = ArrayList<Pair<Int, Int>>()
    val channelLoads = ArrayList<Triple<EditorList, EditorFilter, String?>>()
    val groupLoads = ArrayList<Pair<Boolean, String?>>()

    /** Effects whose answers are held back (by name: "summarize", "write", "locate", "loadChannels", …). */
    val hold = HashSet<String>()
    /** Effects that answer with a failure (LoadFailed for loads, SummaryFailed for summarize) while named here. */
    val fail = HashSet<String>()
    /** Answers for the text editor / number pad / PIN prompt. */
    var textAnswer: (EditorText) -> String? = { if (it == EditorText.EDIT_FIND) "s" else "Renamed" }
    var numberAnswer: Int? = 555
    var pinGrants = true
    var writeResult: (EditorWrite) -> WriteResult = { WriteResult.Done(1) }

    private val queue = ArrayDeque<Pair<String, () -> ChannelEditorEvent?>>()
    val held = ArrayList<Pair<String, () -> ChannelEditorEvent?>>()

    private var groupVersion = 0L
    private var channelVersion = 100L
    private val groupKeys = HashMap<Long, List<String>>()
    private val channelKeys = HashMap<Long, List<String>>()

    private val effects = object : ChannelEditorEffects {
        override fun loadGroups(playlistId: Long, showHidden: Boolean, keepKey: String?) {
            calls += "loadGroups"; groupLoads += showHidden to keepKey
            val v = ++groupVersion
            enqueue("loadGroups") {
                if ("loadGroups" in fail) return@enqueue ChannelEditorEvent.LoadFailed(EditorPane.GROUPS)
                val keys = groups.filter { showHidden || (!it.hidden && !it.ruleHidden) }.map { it.key }
                groupKeys[v] = keys
                ChannelEditorEvent.Groups(v, keys.size, groups.count { it.hidden || it.ruleHidden }, keepKey?.let { k -> keys.indexOf(k).takeIf { it >= 0 } })
            }
        }

        override fun loadGroupRows(version: Long, from: Int, to: Int) {
            calls += "loadGroupRows"; groupRowLoads += from to to
            enqueue("loadGroupRows") {
                val keys = groupKeys[version] ?: return@enqueue null
                ChannelEditorEvent.GroupRows(version, from, keys.subList(from, minOf(to, keys.size)).map { k -> groupRow(groups.first { it.key == k }) })
            }
        }

        override fun loadChannels(playlistId: Long, list: EditorList, filter: EditorFilter, keepKey: String?) {
            calls += "loadChannels"; channelLoads += Triple(list, filter, keepKey)
            val v = ++channelVersion
            enqueue("loadChannels") {
                if ("loadChannels" in fail) return@enqueue ChannelEditorEvent.LoadFailed(EditorPane.CHANNELS)
                val r = lineup(list, filter)
                channelKeys[v] = r.keys
                ChannelEditorEvent.Channels(list, filter, v, r.keys.size, r.hidden, r.lockedOut, r.listLocked, keepKey?.let { k -> r.keys.indexOf(k).takeIf { it >= 0 } })
            }
        }

        override fun loadChannelRows(version: Long, from: Int, to: Int) {
            calls += "loadChannelRows"; channelRowLoads += from to to
            enqueue("loadChannelRows") {
                val keys = channelKeys[version] ?: return@enqueue null
                ChannelEditorEvent.ChannelRows(version, from, keys.subList(from, minOf(to, keys.size)).map { channelRow(it) })
            }
        }

        override fun summarize(token: Long, playlistId: Long, target: ChannelTarget) {
            calls += "summarize"; summaries += token to target
            enqueue("summarize") { if ("summarize" in fail) ChannelEditorEvent.SummaryFailed(token) else ChannelEditorEvent.Summary(token, summary(target)) }
        }

        override fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, number: Int) {
            calls += "locate"
            enqueue("locate") {
                val keys = lineup(list, filter).keys
                ChannelEditorEvent.Located(number, keys.indexOfFirst { k -> channel(k).let { (it.customNumber ?: it.number) == number } }.takeIf { it >= 0 })
            }
        }

        override fun write(write: EditorWrite) {
            calls += "write"; writes += write
            enqueue("write") { ChannelEditorEvent.Written(write, writeResult(write)) }
        }

        override fun editText(label: EditorText, initial: String, allowEmpty: Boolean) {
            calls += "editText"; texts += label
            enqueue("editText") { ChannelEditorEvent.TextResult(textAnswer(label)) }
        }

        override fun editNumber(label: EditorText, value: Int?) {
            calls += "editNumber"; numbers += label
            enqueue("editNumber") { ChannelEditorEvent.NumberResult(numberAnswer) }
        }

        override fun requestPin() {
            calls += "requestPin"; pinRequests++
            enqueue("requestPin") { ChannelEditorEvent.PinResult(pinGrants) }
            if (pinGrants && pinSet) enqueue("requestPin") { unlocked = true; ChannelEditorEvent.Access(pinSet, true) }
        }

        override fun announce(text: String) { announced += text }
    }

    val machine = ChannelEditorMachineImpl(effects, editorTexts) { now }
    val ui: ChannelEditorUiState get() = machine.ui.value

    private fun enqueue(name: String, answer: () -> ChannelEditorEvent?) { queue.addLast(name to answer) }

    /** Answers every queued effect (in order), and what those answers ask for in turn. */
    fun pump() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(guard++ < 100_000) { "effects loop" }
            val (name, answer) = queue.removeFirst()
            if (name in hold) { held += name to answer; continue }
            answer()?.let { machine.dispatch(it) }
        }
    }

    /** Releases the held answers of [name] (all when null), in order. */
    fun release(name: String? = null) {
        val out = held.filter { name == null || it.first == name }
        held.removeAll(out.toSet())
        if (name != null) hold -= name else hold.clear()
        for ((_, a) in out) a()?.let { machine.dispatch(it) }
        pump()
    }

    fun open(groupKey: String? = null): EditorWorld {
        machine.dispatch(ChannelEditorEvent.Access(pinSet, unlocked))
        machine.open(pid, groupKey)
        pump()
        settle()
        return this
    }

    fun press(a: BoxAction, repeat: Int = 0, digit: Int = -1, heldMs: Long = 0): Boolean {
        val r = machine.act(GuideEvent.Act(a, repeat, digit, heldMs))
        pump()
        return r
    }

    fun send(e: ChannelEditorEvent) { machine.dispatch(e); pump() }

    /** Runs every deadline due within [withinMs] (the follow debounce, a digit timeout; notices fade after 3 s). */
    fun settle(withinMs: Long = 200) {
        val until = now + withinMs
        var guard = 0
        while (true) {
            val d = machine.nextDeadlineMs.value
            if (d == Long.MAX_VALUE || d > until || guard++ > 20) break
            now = maxOf(now, d)
            machine.dispatch(ChannelEditorEvent.Tick(now))
            pump()
        }
        now = maxOf(now, until)
    }

    fun tick(ms: Long) { now += ms; machine.dispatch(ChannelEditorEvent.Tick(now)); pump() }

    // ---------------------------------------------------------------- the fake library

    private fun sessionLocked() = pinSet && !unlocked

    fun channel(key: String): FC = groups.firstNotNullOf { g -> g.channels.firstOrNull { it.key == key } }
    fun groupOf(key: String): FG = groups.first { g -> g.channels.any { it.key == key } }

    class ListResult(val keys: List<String>, val hidden: Int, val lockedOut: Int, val listLocked: Boolean)

    fun lineup(list: EditorList, filter: EditorFilter): ListResult {
        val locked = sessionLocked()
        if (list is EditorList.Group) {
            val g = groups.firstOrNull { it.key == list.groupKey } ?: return ListResult(emptyList(), 0, 0, false)
            if (locked && g.locked) return ListResult(emptyList(), 0, 0, true)
        }
        val all = when (list) {
            EditorList.All -> groups.flatMap { it.channels }
            is EditorList.Group -> groups.first { it.key == list.groupKey }.channels
            is EditorList.Find -> groups.flatMap { it.channels }.filter { it.name.contains(list.query, ignoreCase = true) }
        }
        val access = all.filter { c -> !locked || !(c.locked || groupOf(c.key).locked) }
        val filtered = access.filter {
            when (filter) {
                EditorFilter.ALL -> true
                EditorFilter.SHOWN -> !it.hidden && !it.ruleHidden
                EditorFilter.HIDDEN -> it.hidden || it.ruleHidden
            }
        }
        return ListResult(filtered.map { it.key }, filtered.count { it.hidden || it.ruleHidden }, all.size - access.size, false)
    }

    fun groupRow(g: FG) = EditorGroupRow(
        groupKey = g.key, name = g.name, providerName = g.name, channels = g.channels.size,
        hiddenChannels = g.channels.count { it.hidden || it.ruleHidden }, hidden = g.hidden, ruleHidden = g.ruleHidden,
        locked = g.locked, adult = false, notProtected = g.locked && !pinSet, renamed = false, customOrder = g.customOrder,
    )

    fun channelRow(key: String): EditorChannelRow {
        val c = channel(key)
        val g = groupOf(key)
        return EditorChannelRow(
            stableKey = c.key, name = c.name, providerName = c.name, number = c.customNumber ?: c.number, customNumber = c.customNumber,
            logoUrl = null, hidden = c.hidden, ruleHidden = c.ruleHidden, rulePattern = if (c.ruleHidden) "xxx" else null,
            locked = c.locked, lockedByGroup = if (g.locked) g.name else null, favorite = c.favorite, movedFrom = null, renamed = false,
        )
    }

    fun summary(target: ChannelTarget): SelectionSummary {
        val keys = when (target) {
            is ChannelTarget.Keys -> target.keys.toList()
            is ChannelTarget.AllOf -> lineup(target.list, target.filter).keys.filter { it !in target.except }
        }
        val rows = keys.map { channelRow(it) }
        return SelectionSummary(
            total = rows.size, hidden = rows.count { it.hidden }, ruleHidden = rows.count { it.ruleHidden && !it.hidden },
            locked = rows.count { it.locked || it.lockedByGroup != null }, lockedByGroup = rows.count { it.lockedByGroup != null },
            favorite = rows.count { it.favorite }, fromLockedGroup = rows.count { it.lockedByGroup != null },
        )
    }

    // ---------------------------------------------------------------- state names (key table, tests)

    fun layer(): String {
        val u = ui
        return when {
            u.menu != null -> if (u.menu.loading) "loading" else "menu"
            u.picker != null -> "picker"
            u.confirm != null -> "confirm"
            u.choice != null -> "choice:" + u.choice.kind.name
            u.move != null -> "move"
            u.toolbar != null -> "toolbar"
            else -> "none"
        }
    }

    fun listName(l: EditorList = ui.list): String = when (l) {
        EditorList.All -> "All"
        is EditorList.Find -> "Find:" + l.query
        is EditorList.Group -> "G:" + l.groupKey
    }

    fun menuActions(): List<EditorAction> = ui.menu?.lines?.map { it.action } ?: emptyList()

    /** Focus the menu line of [action] and run it. */
    fun runLine(action: EditorAction) {
        val lines = menuActions()
        val i = lines.indexOf(action)
        require(i >= 0) { "$action not in $lines" }
        repeat(i - ui.menu!!.focused) { press(BoxAction.DOWN) }
        press(BoxAction.SELECT)
    }

    fun selected(): Int = EditorMenus.selectedCount(ui.channelSelection, ui.channels?.count ?: 0)
    fun groupSelected(): Int = EditorMenus.selectedCount(ui.groupSelection, ui.groups.groups?.count ?: 0)

    companion object {
        val NEWS = FG("g:news", "News", listOf(
            FC("n1", "BBC One", 101),
            FC("n2", "CNN", 102, hidden = true),
            FC("n3", "Sky News", 103, favorite = true),
            FC("n4", "Dave", 104, customNumber = 900),
        ))
        val SPORT = FG("g:sport", "Sport", listOf(
            FC("s1", "ESPN", 201, locked = true),
            FC("s2", "Eurosport", 202, ruleHidden = true),
        ))
        val ADULT = FG("g:adult", "Adult", listOf(FC("a1", "Late", 301)), locked = true)
        val DEFAULT_GROUPS = listOf(NEWS, SPORT, ADULT)

        /** [n] channels in one group (paging tests). */
        fun big(n: Int, groupsN: Int = 1): List<FG> = (0 until groupsN).map { g ->
            FG("g:$g", "Group $g", (0 until n).map { FC("c$g-$it", "Channel $g-$it", 1000 * g + it) })
        }
    }
}
