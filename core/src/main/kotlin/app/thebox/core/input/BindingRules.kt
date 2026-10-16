package app.thebox.core.input

import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain

/** The `remote` settings domain (§4.1): the user's overrides. Backed up. WP-A references it, never redefines it. */
val RemoteDomain: SettingsDomain<KeyBindingOverrides> = SettingsDomain(
    name = Domains.REMOTE,
    serializer = KeyBindingOverrides.serializer(),
    default = KeyBindingOverrides(),
)

/**
 * Button-mapping rules (docs/design/m5-setup-settings.md §3.8.2), pure and table-tested (`BindingRulesTest`).
 *
 * Reserved keys can never be bound or unbound: D-pad short in both contexts, D-pad long in GUIDE and UP/DOWN long in
 * PLAYER (LEFT/RIGHT long in PLAYER, the group cycle, may be rebound), the OK family short (SELECT) and long
 * (CONTEXT_MENU: the five-key rule), Back/Escape/B, digits, the system keys and KEYCODE_UNKNOWN (S14). D-pad and OK
 * stay reserved inside the player too (recorded deviation S14): the only D-pad remap in the player is "Up/Down in
 * player". The later binding wins.
 */
object BindingRules {
    const val KEY_OVERRIDES_MAX = 200
    const val KEYCODE_UNKNOWN = 0

    private val DPAD = setOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)
    private val OK = DefaultKeyMap.OK_KEYS.toSet()
    private val BACKS = setOf(Keys.BACK, Keys.ESCAPE, Keys.BUTTON_B)
    private val SYSTEM = setOf(
        Keys.VOLUME_UP, Keys.VOLUME_DOWN, Keys.VOLUME_MUTE, Keys.MUTE, Keys.HOME, Keys.POWER, Keys.TV_POWER, Keys.SLEEP, Keys.WAKEUP,
    )
    /** Short actions that auto-repeat when held today (§3.8.2 "Long press costs repeat"). */
    private val REPEATING = setOf(
        BoxAction.ZAP_UP, BoxAction.ZAP_DOWN, BoxAction.PAGE_UP, BoxAction.PAGE_DOWN, BoxAction.SEEK_BACK, BoxAction.SEEK_FORWARD,
        BoxAction.PREV_PROGRAMME, BoxAction.NEXT_PROGRAMME,
    )

    fun isDigit(code: Int): Boolean = code in Keys.DIGIT_0..Keys.DIGIT_0 + 9 || code in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9

    /** Why ([context], [keyCode], [press]) can never be remapped, or null when it can. */
    fun reserved(context: KeyContext, keyCode: Int, press: Press): ReservedReason? = when {
        keyCode == KEYCODE_UNKNOWN -> ReservedReason.UNKNOWN_CODE
        keyCode in DPAD -> when {
            press == Press.SHORT -> ReservedReason.NAVIGATION
            context == KeyContext.GUIDE -> ReservedReason.NAVIGATION
            keyCode == Keys.DPAD_UP || keyCode == Keys.DPAD_DOWN -> ReservedReason.NAVIGATION
            else -> null // PLAYER LEFT/RIGHT long: the default group cycle may be rebound or unbound
        }
        keyCode in OK -> if (press == Press.SHORT) ReservedReason.OK else ReservedReason.CONTEXT_MENU_LONG_OK
        keyCode in BACKS -> ReservedReason.BACK
        isDigit(keyCode) -> ReservedReason.DIGIT
        keyCode in SYSTEM -> ReservedReason.SYSTEM
        else -> null
    }

    /** Reserved in any context of [action] (the screen checks before saving). */
    fun reservedFor(action: BoxAction, keyCode: Int, press: Press): ReservedReason? {
        val ctx = ActionCatalog.contexts(action).ifEmpty { setOf(KeyContext.GUIDE, KeyContext.PLAYER) }
        for (c in KeyContext.entries) if (c in ctx) reserved(c, keyCode, press)?.let { return it }
        return null
    }

    /**
     * What saving ([keyCode], [press]) → [action] would do with the effective [map] (§3.8.3 verdict line). Reserved
     * first; then the actions it replaces (later binding wins); a LONG binding on a key whose SHORT repeats today costs
     * the repeat ([BindingVerdict.LosesRepeat] wrapping the rest).
     */
    fun verdict(map: KeyMap, action: BoxAction, keyCode: Int, press: Press): BindingVerdict {
        reservedFor(action, keyCode, press)?.let { return BindingVerdict.Reserved(it) }
        val contexts = ActionCatalog.contexts(action)
        var current: BoxAction? = null
        val replaced = LinkedHashSet<KeyContext>()
        for (c in KeyContext.entries) {
            if (c !in contexts) continue
            val now = if (press == Press.SHORT) map.short(c, keyCode) else map.long(c, keyCode)
            if (now != null && now != action) {
                if (current == null) current = now
                if (now == current) replaced += c
            }
        }
        val base: BindingVerdict = if (current != null) BindingVerdict.Replaces(current, replaced) else BindingVerdict.Free
        if (press == Press.LONG) {
            for (c in KeyContext.entries) {
                if (c !in contexts) continue
                val short = map.short(c, keyCode)
                if (short != null && short in REPEATING && map.long(c, keyCode) == null) return BindingVerdict.LosesRepeat(short, base)
            }
        }
        return base
    }

    /**
     * Saves ([key], [press]) → [action] in every context of the action (later binding wins: an existing override of the
     * same (context, keyCode, press) is replaced). Reserved combinations are refused (unchanged result). Capped at
     * [KEY_OVERRIDES_MAX] (the oldest go first).
     */
    fun save(o: KeyBindingOverrides, action: BoxAction, key: CapturedKey, press: Press, nowMs: Long): KeyBindingOverrides {
        if (!action.remappable || reservedFor(action, key.keyCode, press) != null) return o
        val contexts = ActionCatalog.contexts(action)
        if (contexts.isEmpty()) return o
        val rest = o.entries.filterNot { it.keyCode == key.keyCode && it.press == press && it.context in contexts }
        val added = contexts.sortedBy { it.ordinal }.map { KeyOverride(it, key.keyCode, press, action.id, key.deviceName, nowMs) }
        return capped(o.copy(entries = rest + added))
    }

    /** "Remove": ([context], [keyCode], [press]) does nothing any more (an override with a null action). */
    fun unbind(o: KeyBindingOverrides, context: KeyContext, keyCode: Int, press: Press, nowMs: Long): KeyBindingOverrides {
        if (reserved(context, keyCode, press) != null) return o
        val rest = o.entries.filterNot { it.context == context && it.keyCode == keyCode && it.press == press }
        return capped(o.copy(entries = rest + KeyOverride(context, keyCode, press, null, null, nowMs)))
    }

    /** Per key: every override with that code goes. */
    fun resetKey(o: KeyBindingOverrides, keyCode: Int): KeyBindingOverrides = o.copy(entries = o.entries.filterNot { it.keyCode == keyCode })

    /** Per action: overrides that bind [action], and overrides that unbound one of its default keys. */
    fun resetAction(o: KeyBindingOverrides, action: BoxAction, defaults: KeyMap): KeyBindingOverrides = o.copy(
        entries = o.entries.filterNot { e ->
            e.action == action.id ||
                (e.action == null && (if (e.press == Press.SHORT) defaults.short(e.context, e.keyCode) else defaults.long(e.context, e.keyCode)) == action)
        },
    )

    fun resetAll(o: KeyBindingOverrides): KeyBindingOverrides = o.copy(entries = emptyList())

    private fun capped(o: KeyBindingOverrides): KeyBindingOverrides =
        if (o.entries.size <= KEY_OVERRIDES_MAX) o
        else o.copy(entries = o.entries.sortedBy { it.atMs }.takeLast(KEY_OVERRIDES_MAX))
}
