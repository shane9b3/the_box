package app.thebox.core.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The key map the router uses (docs/design/m5-setup-settings.md §3.8.4, decision §0.7): [defaults] (the
 * `DefaultKeyMap`) plus the user's [KeyBindingOverrides]. Four `HashMap<Int, BoxAction>` in an immutable snapshot,
 * rebuilt off the main thread (on [Dispatchers.Default] in [scope]) when the overrides flow emits and published through
 * a `@Volatile` field, so [short]/[long]/[digit] are O(1) reads that allocate nothing.
 *
 * An override on a reserved key ([BindingRules.reserved]) or with an unknown action id is ignored (a backup from a newer
 * app, or a hand-edited document, can never take Back, OK or the D-pad away: §3.15 "Mapping cannot break the rule").
 */
class RemappedKeyMap(
    override val defaults: KeyMap,
    overrides: StateFlow<KeyBindingOverrides>?,
    scope: CoroutineScope?,
) : EffectiveKeyMap {

    private class Snapshot(
        val guideShort: HashMap<Int, BoxAction>,
        val guideLong: HashMap<Int, BoxAction>,
        val playerShort: HashMap<Int, BoxAction>,
        val playerLong: HashMap<Int, BoxAction>,
    ) {
        fun of(context: KeyContext, press: Press): HashMap<Int, BoxAction> = when (context) {
            KeyContext.GUIDE -> if (press == Press.SHORT) guideShort else guideLong
            KeyContext.PLAYER -> if (press == Press.SHORT) playerShort else playerLong
        }
    }

    @Volatile private var snap: Snapshot = build(defaults, overrides?.value ?: KeyBindingOverrides())

    /** Bumped on every published snapshot (tests, and the hints feed). */
    @Volatile var version: Long = 0
        private set

    init {
        if (overrides != null && scope != null) {
            scope.launch(Dispatchers.Default) {
                overrides.collect { apply(it) }
            }
        }
    }

    /** Rebuilds and publishes the snapshot for [o] (the collector calls it; tests call it directly). */
    fun apply(o: KeyBindingOverrides) {
        snap = build(defaults, o)
        version++
    }

    override fun short(context: KeyContext, keyCode: Int): BoxAction? = snap.of(context, Press.SHORT)[keyCode]

    override fun long(context: KeyContext, keyCode: Int): BoxAction? = snap.of(context, Press.LONG)[keyCode]

    override fun digit(keyCode: Int): Int = defaults.digit(keyCode)

    override fun bindings(context: KeyContext): List<KeyBinding> {
        val s = snap
        val sh = s.of(context, Press.SHORT)
        val lo = s.of(context, Press.LONG)
        return (sh.keys + lo.keys).sorted().map { KeyBinding(it, sh[it], lo[it]) }
    }

    override fun keysFor(context: KeyContext, action: BoxAction): List<Pair<Int, Press>> {
        val s = snap
        val out = ArrayList<Pair<Int, Press>>(4)
        for ((code, a) in s.of(context, Press.SHORT)) if (a == action) out += code to Press.SHORT
        for ((code, a) in s.of(context, Press.LONG)) if (a == action) out += code to Press.LONG
        out.sortWith(compareBy({ it.second.ordinal }, { it.first }))
        return out
    }

    companion object {
        private fun build(defaults: KeyMap, o: KeyBindingOverrides): Snapshot {
            val s = Snapshot(HashMap(), HashMap(), HashMap(), HashMap())
            for (c in KeyContext.entries) {
                for (b in defaults.bindings(c)) {
                    b.short?.let { s.of(c, Press.SHORT)[b.keyCode] = it }
                    b.long?.let { s.of(c, Press.LONG)[b.keyCode] = it }
                }
            }
            // Oldest first, so the later binding wins when two overrides name the same triple.
            for (e in o.entries.sortedBy { it.atMs }) {
                if (BindingRules.reserved(e.context, e.keyCode, e.press) != null) continue
                val map = s.of(e.context, e.press)
                if (e.action == null) { map.remove(e.keyCode); continue }
                val a = ActionCatalog.actionOf(e.action) ?: continue
                if (!a.remappable) continue
                map[e.keyCode] = a
            }
            return s
        }
    }
}
