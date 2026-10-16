package app.thebox.core.settings.tree

import app.thebox.core.input.ActionCatalog
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BindingRules
import app.thebox.core.input.BindingVerdict
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.EffectiveKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Visibility

/**
 * The button-mapping pages (docs/design/m5-setup-settings.md §3.8.3; WP-C): ButtonMapping (Test a key, Reset all, the
 * five groups with one Item per action) and ActionBindings(action) (one Item per key, Add a button press/hold, Reset).
 * The fixed rows are registered; the per-action and per-key Items are list rows of those pages (sub-pages of Remote
 * control, never counted in §3.3.13). [command] turns an intent into a [Command] the host runs over `KeyBindingStore`
 * and the capture hook.
 */
object ButtonMappingRows : RowSource {
    val TEST = RowId("mapping.test")
    val RESET_ALL = RowId("mapping.reset_all")
    val ADD_SHORT = RowId("binding.add_short")
    val ADD_LONG = RowId("binding.add_long")
    val RESET_ACTION = RowId("binding.reset_action")
    /** The inline note under the Watching group (S14 recorded deviation). */
    val DPAD_NOTE = RowId("mapping.dpad_note")

    fun actionRow(action: BoxAction) = RowId("mapping.action.${action.id}")
    fun keyRow(context: KeyContext, keyCode: Int, press: Press) = RowId("binding.${context.name.lowercase()}.$keyCode.${press.name.lowercase()}")

    override val rows: List<RowSpec> = listOf(
        RowSpec(TEST, SectionId.REMOTE, Visibility.SIMPLE, EffectTiming.NONE, "C"),
        RowSpec(RESET_ALL, SectionId.REMOTE, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(ADD_SHORT, SectionId.REMOTE, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(ADD_LONG, SectionId.REMOTE, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(RESET_ACTION, SectionId.REMOTE, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
    )

    /** Text keys (`strings_parental.xml`: `mapping_<key>`); key names and action labels come from `:ui` tables. */
    object T {
        const val TITLE = "page_title"
        const val TEST = "row_test"
        const val TEST_DESC = "row_test_desc"
        const val RESET_ALL = "row_reset_all"
        const val RESET_ALL_TITLE = "confirm_reset_all_title"
        const val RESET_ALL_BODY = "confirm_reset_all_body"
        const val RESET_ALL_OK = "confirm_reset_all_ok"
        const val CANCEL = "confirm_cancel"
        const val NO_BUTTON = "value_no_button"
        const val HOLD = "value_hold"
        const val SEPARATOR = "value_separator"
        const val DPAD_NOTE = "info_dpad_note"
        const val ADD_SHORT = "row_add_short"
        const val ADD_LONG = "row_add_long"
        const val RESET_ACTION = "row_reset_action"
        const val DEFAULT = "value_default"
        const val CAPTURED_FROM = "value_captured_from"
        const val PRESS = "value_press"
        const val MENU_REMOVE = "menu_remove"
        const val MENU_RESET_KEY = "menu_reset_key"
        const val GROUP_PREFIX = "group_"
        const val ACTION_PREFIX = "action_"
    }

    /** Key and action names for the rows (`:ui` implements both from string tables, §3.8.3). */
    interface Names {
        fun key(keyCode: Int): String
        fun action(action: BoxAction): String
        fun group(group: ActionGroup): String
    }

    sealed interface Command {
        /** "Test a key": capture that only reports. */
        data object TestKey : Command
        data object ResetAll : Command
        data class Capture(val action: BoxAction, val press: Press) : Command
        data class ResetAction(val action: BoxAction) : Command
        data class Unbind(val context: KeyContext, val keyCode: Int, val press: Press) : Command
        data class ResetKey(val keyCode: Int) : Command
    }

    const val MENU_REMOVE = "remove"
    const val MENU_RESET_KEY = "reset_key"

    /** "CH+ · ⏭ · Page Up", long presses as "Hold ←", or "No button" (§3.8.3). */
    fun keysValue(map: EffectiveKeyMap, action: BoxAction, names: Names, texts: RowTexts): String {
        val keys = LinkedHashSet<Pair<Int, Press>>()
        for (c in KeyContext.entries) if (c in ActionCatalog.contexts(action)) keys += map.keysFor(c, action)
        if (keys.isEmpty()) return texts.text(T.NO_BUTTON)
        return keys.joinToString(texts.text(T.SEPARATOR)) { (code, press) ->
            if (press == Press.LONG) texts.text(T.HOLD, names.key(code)) else names.key(code)
        }
    }

    private fun untouched(o: KeyBindingOverrides, action: BoxAction, map: EffectiveKeyMap): Boolean =
        o.entries.none { e ->
            e.action == action.id || (e.action == null && (if (e.press == Press.SHORT) map.defaults.short(e.context, e.keyCode) else map.defaults.long(e.context, e.keyCode)) == action)
        }

    fun mappingPage(map: EffectiveKeyMap, overrides: KeyBindingOverrides, names: Names, texts: RowTexts, version: Long): PageModel {
        val out = ArrayList<SettingsRow>(ActionCatalog.all.size + 4)
        out += SettingsRow(id = TEST, label = texts.text(T.TEST), description = texts.text(T.TEST_DESC), kind = RowKind.Action(), effect = EffectTiming.NONE)
        out += SettingsRow(
            id = RESET_ALL, label = texts.text(T.RESET_ALL),
            kind = RowKind.Action(destructive = true, confirm = ConfirmSpec(texts.text(T.RESET_ALL_TITLE), texts.text(T.RESET_ALL_BODY), texts.text(T.RESET_ALL_OK), texts.text(T.CANCEL))),
            enabled = overrides.entries.isNotEmpty(),
        )
        for (g in ActionGroup.entries) {
            for (info in ActionCatalog.inGroup(g)) {
                out += SettingsRow(
                    id = actionRow(info.action), label = names.action(info.action),
                    value = keysValue(map, info.action, names, texts),
                    description = names.group(g),
                    kind = RowKind.Item(route = SettingsRoute.ActionBindings(info.action), badges = if (untouched(overrides, info.action, map)) setOf(Badge.DEFAULT) else emptySet()),
                )
            }
            if (g == ActionGroup.WATCHING) out += SettingsRow(id = DPAD_NOTE, label = "", kind = RowKind.Info(listOf(texts.text(T.DPAD_NOTE))), effect = EffectTiming.NONE)
        }
        return PageModel(SettingsRoute.ButtonMapping, texts.text(T.TITLE), rows = out, version = version)
    }

    fun actionPage(action: BoxAction, map: EffectiveKeyMap, overrides: KeyBindingOverrides, names: Names, texts: RowTexts, version: Long): PageModel {
        val out = ArrayList<SettingsRow>(8)
        val contexts = ActionCatalog.contexts(action)
        val seen = HashSet<Pair<Int, Press>>()
        for (c in KeyContext.entries) {
            if (c !in contexts) continue
            for ((code, press) in map.keysFor(c, action)) {
                if (!seen.add(code to press)) continue
                val o = overrides.entries.lastOrNull { it.keyCode == code && it.press == press && it.action == action.id }
                val label = if (press == Press.LONG) texts.text(T.HOLD, names.key(code)) else names.key(code)
                val value = when {
                    o == null -> texts.text(T.DEFAULT)
                    o.capturedFrom != null -> texts.text(T.CAPTURED_FROM, o.capturedFrom)
                    else -> texts.text(T.PRESS)
                }
                out += SettingsRow(
                    id = keyRow(c, code, press), label = label, value = value,
                    kind = RowKind.Item(
                        menu = listOf(ChoiceOption(MENU_REMOVE, texts.text(T.MENU_REMOVE)), ChoiceOption(MENU_RESET_KEY, texts.text(T.MENU_RESET_KEY))),
                        badges = if (o == null) setOf(Badge.DEFAULT) else emptySet(),
                    ),
                )
            }
        }
        out += SettingsRow(id = ADD_SHORT, label = texts.text(T.ADD_SHORT), kind = RowKind.Action())
        out += SettingsRow(id = ADD_LONG, label = texts.text(T.ADD_LONG), kind = RowKind.Action())
        out += SettingsRow(id = RESET_ACTION, label = texts.text(T.RESET_ACTION, names.action(action)), kind = RowKind.Action(), enabled = !untouched(overrides, action, map))
        return PageModel(SettingsRoute.ActionBindings(action), names.action(action), rows = out, version = version)
    }

    /** The command behind [intent] on ButtonMapping or ActionBindings([action]). */
    fun command(intent: RowIntent, action: BoxAction?): Command? {
        when (intent.row) {
            TEST -> return if (intent is RowIntent.Run) Command.TestKey else null
            RESET_ALL -> return if (intent is RowIntent.Run) Command.ResetAll else null
        }
        val a = action ?: return null
        return when (intent.row) {
            ADD_SHORT -> if (intent is RowIntent.Run) Command.Capture(a, Press.SHORT) else null
            ADD_LONG -> if (intent is RowIntent.Run) Command.Capture(a, Press.LONG) else null
            RESET_ACTION -> if (intent is RowIntent.Run) Command.ResetAction(a) else null
            else -> {
                val k = parseKeyRow(intent.row) ?: return null
                val menu = (intent as? RowIntent.Menu)?.optionId ?: MENU_REMOVE
                if (menu == MENU_RESET_KEY) Command.ResetKey(k.second) else Command.Unbind(k.first, k.second, k.third)
            }
        }
    }

    private fun parseKeyRow(id: RowId): Triple<KeyContext, Int, Press>? {
        val p = id.key.split('.')
        if (p.size != 4 || p[0] != "binding") return null
        val c = KeyContext.entries.firstOrNull { it.name.lowercase() == p[1] } ?: return null
        val code = p[2].toIntOrNull() ?: return null
        val press = Press.entries.firstOrNull { it.name.lowercase() == p[3] } ?: return null
        return Triple(c, code, press)
    }
}

/**
 * The capture overlay's flow (§3.8.3, S14), pure: WP-A's settings machine embeds it for [SettingsUiState.capture]
 * (`app.thebox.core.settings.SettingsUiState`); the router's capture hook delivers [onKey]; buttons are Save / Try
 * another / Cancel (focus on Save, or on Try another when the key is reserved).
 */
class CaptureFlow(private val map: () -> EffectiveKeyMap) {
    sealed interface Result {
        data object None : Result
        /** Arm (true) or disarm (false) the router's capture hook. */
        data class Arm(val on: Boolean) : Result
        /** Save [key] for the action; the flow is closed. */
        data class Save(val action: BoxAction, val key: CapturedKey, val press: Press) : Result
        /** Closed without saving ([timedOut]: show the "No button arrived" notice). */
        data class Closed(val timedOut: Boolean) : Result
    }

    var state: CaptureState? = null
        private set
    /** 0 = Save, 1 = Try another, 2 = Cancel (test mode: only Try another / Cancel matter). */
    var focusedButton: Int = 0
        private set
    /** "Test a key": capture reports only; [testReport] says what the key does now in the guide and the player. */
    var testMode: Boolean = false
        private set
    var testReport: Pair<BoxAction?, BoxAction?>? = null
        private set
    private var token = 0L

    fun start(action: BoxAction, press: Press, nowMs: Long, test: Boolean = false): Result {
        token++
        testMode = test
        testReport = null
        focusedButton = 0
        state = CaptureState(token, action, press, nowMs)
        return Result.Arm(true)
    }

    /** The router's capture listener. BACK / ESCAPE / BUTTON_B while waiting cancels. */
    fun onKey(key: CapturedKey): Result {
        val s = state ?: return Result.None
        if (s.captured != null) return Result.None
        if (key.keyCode == Keys.BACK || key.keyCode == Keys.ESCAPE || key.keyCode == Keys.BUTTON_B) {
            state = null
            return Result.Closed(timedOut = false)
        }
        val m = map()
        if (testMode) {
            testReport = m.short(KeyContext.GUIDE, key.keyCode) to m.short(KeyContext.PLAYER, key.keyCode)
            state = s.copy(captured = key, verdict = BindingVerdict.Free)
            focusedButton = 1
            return Result.Arm(false)
        }
        val verdict = BindingRules.verdict(m, s.action, key.keyCode, s.press)
        state = s.copy(captured = key, verdict = verdict)
        focusedButton = if (verdict is BindingVerdict.Reserved) 1 else 0
        return Result.Arm(false)
    }

    /** CAPTURE_TIMEOUT_MS passed with no key (the host posts it with the state's token). */
    fun onTimeout(token: Long): Result {
        val s = state ?: return Result.None
        if (s.token != token || s.captured != null) return Result.None
        state = null
        return Result.Closed(timedOut = true)
    }

    /** Keys while a key was captured (router disarmed): ←/→ buttons, OK the button, BACK cancels. */
    fun act(action: BoxAction, nowMs: Long): Result {
        val s = state ?: return Result.None
        val key = s.captured ?: return if (action == BoxAction.BACK) { state = null; Result.Closed(false) } else Result.None
        return when (action) {
            BoxAction.LEFT -> { focusedButton = (focusedButton - 1).coerceAtLeast(if (testMode) 1 else 0); Result.None }
            BoxAction.RIGHT -> { focusedButton = (focusedButton + 1).coerceAtMost(2); Result.None }
            BoxAction.BACK -> { state = null; Result.Closed(false) }
            BoxAction.SELECT -> when (focusedButton) {
                0 -> if (testMode || s.verdict is BindingVerdict.Reserved) Result.None else { state = null; Result.Save(s.action, key, s.press) }
                1 -> start(s.action, s.press, nowMs, testMode)
                else -> { state = null; Result.Closed(false) }
            }
            else -> Result.None
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 10_000L
    }
}
