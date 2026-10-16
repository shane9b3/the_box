package app.thebox.core.settings.tree

import app.thebox.core.parental.LockedInGuide
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.StayUnlocked
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
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility

/**
 * User text for WP-C's pages, looked up by key in `:ui` (`strings_parental.xml`); :core holds no user text. Keys are the
 * constants of [ParentalRows.T] and [ButtonMappingRows.T].
 */
fun interface RowTexts {
    fun text(key: String, vararg args: Any): String
}

/**
 * The Parental controls page (docs/design/m5-setup-settings.md §3.3.8, §3.9; WP-C). [rows] registers the row ids with
 * [SettingsTree]; [page] builds the page from the session state; [command] turns a row intent into a [Command] the host
 * runs (`ui/.../parental/ParentalPageHost`). The page itself is PIN-gated while a PIN is set (§3.9.2: the Section link's
 * `needsPin`, WP-A's sections pane).
 */
object ParentalRows : RowSource {
    val PIN = RowId("parental.pin")
    val AUTO_LOCK = RowId("parental.auto_lock")
    val LOCKED_IN_GUIDE = RowId("parental.locked_in_guide")
    val LOCK_SETTINGS = RowId("parental.lock_settings")
    val STAY_UNLOCKED = RowId("parental.stay_unlocked")
    val LOCK_NOW = RowId("parental.lock_now")
    val REMOVE_PIN = RowId("parental.remove_pin")
    /** The closing Info line ("Forgot the PIN?"); not a setting, so not registered or counted (§3.3.8, §3.3.13). */
    val FORGOT = RowId("parental.forgot")

    override val rows: List<RowSpec> = listOf(
        RowSpec(PIN, SectionId.PARENTAL, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(AUTO_LOCK, SectionId.PARENTAL, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(LOCKED_IN_GUIDE, SectionId.PARENTAL, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(LOCK_SETTINGS, SectionId.PARENTAL, Visibility.SIMPLE, EffectTiming.LIVE, "C"),
        RowSpec(STAY_UNLOCKED, SectionId.PARENTAL, Visibility.ADVANCED, EffectTiming.LIVE, "C"),
        RowSpec(LOCK_NOW, SectionId.PARENTAL, Visibility.ADVANCED, EffectTiming.LIVE, "C"),
        RowSpec(REMOVE_PIN, SectionId.PARENTAL, Visibility.ADVANCED, EffectTiming.LIVE, "C"),
    )

    /** Text keys (`strings_parental.xml`: `parental_<key>`). */
    object T {
        const val TITLE = "page_title"
        const val SET_PIN = "row_set_pin"
        const val CHANGE_PIN = "row_change_pin"
        const val PIN_NOT_SET = "value_not_set"
        const val PIN_SET = "value_set"
        const val AUTO_LOCK = "row_auto_lock"
        const val AUTO_LOCK_DESC = "row_auto_lock_desc"
        const val ON = "value_on"
        const val OFF = "value_off"
        const val NOT_PROTECTED = "value_not_protected"
        const val LOCKED_IN_GUIDE = "row_locked_in_guide"
        const val SHOW_WITH_LOCK = "option_show_with_lock"
        const val HIDE = "option_hide"
        const val LOCK_SETTINGS = "row_lock_settings"
        const val NEEDS_PIN_FIRST = "reason_set_pin_first"
        const val STAY_UNLOCKED = "row_stay_unlocked"
        const val UNTIL_CLOSE = "option_until_close"
        const val ONE_HOUR = "option_one_hour"
        const val ASK_EVERY_TIME = "option_ask_every_time"
        const val LOCK_NOW = "row_lock_now"
        const val REMOVE_PIN = "row_remove_pin"
        const val REMOVE_TITLE = "confirm_remove_title"
        const val REMOVE_BODY = "confirm_remove_body"
        const val REMOVE_CONFIRM = "confirm_remove_ok"
        const val CANCEL = "confirm_cancel"
        const val FORGOT = "info_forgot"
        const val THREAT = "info_threat"
        const val KEY_RESET = "info_key_reset"
    }

    /** What a row intent asks the host to do. */
    sealed interface Command {
        /** Set a PIN (none yet), or Change PIN (verify, then create twice), through the guide's one prompt. */
        data class SetupPin(val change: Boolean) : Command
        data class Policy(val transform: (ParentalPolicy) -> ParentalPolicy) : Command
        data object LockNow : Command
        data object RemovePin : Command
    }

    private val LOCKED_IN_GUIDE_IDS = listOf("show_with_lock", "hide")
    private val STAY_IDS = listOf("until_close", "one_hour", "ask")

    fun page(state: ParentalState, texts: RowTexts, version: Long, advanced: Boolean): PageModel {
        val p = state.policy
        val out = ArrayList<SettingsRow>(8)
        out += SettingsRow(
            id = PIN, label = texts.text(if (state.pinSet) T.CHANGE_PIN else T.SET_PIN),
            value = texts.text(if (state.pinSet) T.PIN_SET else T.PIN_NOT_SET),
            kind = RowKind.Action(needsPin = state.pinSet),
        )
        out += SettingsRow(
            id = AUTO_LOCK, label = texts.text(T.AUTO_LOCK),
            value = when {
                state.unprotected && p.autoLockAdult -> texts.text(T.NOT_PROTECTED)
                p.autoLockAdult -> texts.text(T.ON)
                else -> texts.text(T.OFF)
            },
            description = texts.text(T.AUTO_LOCK_DESC),
            kind = RowKind.Toggle(p.autoLockAdult),
        )
        out += SettingsRow(
            id = LOCKED_IN_GUIDE, label = texts.text(T.LOCKED_IN_GUIDE),
            value = texts.text(if (p.lockedInGuide == LockedInGuide.HIDE) T.HIDE else T.SHOW_WITH_LOCK),
            kind = RowKind.Choice(
                listOf(ChoiceOption(LOCKED_IN_GUIDE_IDS[0], texts.text(T.SHOW_WITH_LOCK)), ChoiceOption(LOCKED_IN_GUIDE_IDS[1], texts.text(T.HIDE))),
                selected = p.lockedInGuide.ordinal,
            ),
        )
        out += SettingsRow(
            id = LOCK_SETTINGS, label = texts.text(T.LOCK_SETTINGS),
            value = texts.text(if (p.lockSettings) T.ON else T.OFF),
            kind = RowKind.Toggle(p.lockSettings),
            enabled = state.pinSet,
            disabledReason = if (state.pinSet) null else texts.text(T.NEEDS_PIN_FIRST),
        )
        if (advanced) {
            out += SettingsRow(
                id = STAY_UNLOCKED, label = texts.text(T.STAY_UNLOCKED),
                value = texts.text(stayKey(p.stayUnlocked)),
                kind = RowKind.Choice(StayUnlocked.entries.map { ChoiceOption(STAY_IDS[it.ordinal], texts.text(stayKey(it))) }, selected = p.stayUnlocked.ordinal),
                visibility = Visibility.ADVANCED,
            )
            if (state.pinSet && state.unlocked) {
                out += SettingsRow(id = LOCK_NOW, label = texts.text(T.LOCK_NOW), kind = RowKind.Action(), visibility = Visibility.ADVANCED)
            }
            if (state.pinSet) {
                out += SettingsRow(
                    id = REMOVE_PIN, label = texts.text(T.REMOVE_PIN),
                    kind = RowKind.Action(
                        destructive = true,
                        confirm = ConfirmSpec(texts.text(T.REMOVE_TITLE), texts.text(T.REMOVE_BODY), texts.text(T.REMOVE_CONFIRM), texts.text(T.CANCEL)),
                        needsPin = true,
                    ),
                    visibility = Visibility.ADVANCED,
                )
            }
        }
        val info = ArrayList<String>(3)
        if (state.pinLostToKeyReset && !state.pinSet) info += texts.text(T.KEY_RESET)
        info += texts.text(T.FORGOT)
        info += texts.text(T.THREAT)
        out += SettingsRow(id = FORGOT, label = "", kind = RowKind.Info(info, if (state.unprotected) Tone.WARN else Tone.NORMAL), effect = EffectTiming.NONE)
        return PageModel(route = SettingsRoute.Section(SectionId.PARENTAL), title = texts.text(T.TITLE), rows = out, version = version)
    }

    private fun stayKey(s: StayUnlocked) = when (s) {
        StayUnlocked.UNTIL_APP_CLOSES -> T.UNTIL_CLOSE
        StayUnlocked.ONE_HOUR -> T.ONE_HOUR
        StayUnlocked.ASK_EVERY_TIME -> T.ASK_EVERY_TIME
    }

    /** The command behind [intent] on this page, or null when the intent is not one of these rows. */
    fun command(intent: RowIntent, state: ParentalState): Command? = when (intent.row) {
        PIN -> if (intent is RowIntent.Run) Command.SetupPin(change = state.pinSet) else null
        AUTO_LOCK -> (intent as? RowIntent.Toggle)?.let { t -> Command.Policy { it.copy(autoLockAdult = t.on) } }
        LOCKED_IN_GUIDE -> (intent as? RowIntent.Choose)?.let { c ->
            val v = when (c.optionId) { LOCKED_IN_GUIDE_IDS[1] -> LockedInGuide.HIDE; else -> LockedInGuide.SHOW_WITH_LOCK }
            Command.Policy { it.copy(lockedInGuide = v) }
        }
        LOCK_SETTINGS -> (intent as? RowIntent.Toggle)?.takeIf { state.pinSet }?.let { t -> Command.Policy { it.copy(lockSettings = t.on) } }
        STAY_UNLOCKED -> (intent as? RowIntent.Choose)?.let { c ->
            val v = StayUnlocked.entries.getOrNull(STAY_IDS.indexOf(c.optionId)) ?: return null
            Command.Policy { it.copy(stayUnlocked = v) }
        }
        LOCK_NOW -> if (intent is RowIntent.Run) Command.LockNow else null
        REMOVE_PIN -> if (intent is RowIntent.Run && state.pinSet) Command.RemovePin else null
        else -> null
    }
}
