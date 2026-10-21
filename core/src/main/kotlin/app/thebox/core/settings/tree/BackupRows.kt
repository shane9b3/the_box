package app.thebox.core.settings.tree

import app.thebox.core.backup.MatchTarget
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestorePreview
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import kotlinx.coroutines.flow.Flow

/**
 * WP-D's Backup rows (docs/design/m5-setup-settings.md §3.3.9, §3.10) and the pieces WP-D's pages share with WP-A's
 * shell: [SectionPages] (what `SettingsEffectsImpl` delegates the CHANNELS and BACKUP routes to), [BackupTexts] (every
 * user string of WP-D's pages, from `strings_backup.xml`) and the pure Backup page builder [BackupPages].
 */
object BackupRows : RowSource {
    val NOW = RowId("backup.now")
    val RESTORE = RowId("backup.restore")
    val WEEKLY = RowId("backup.weekly")
    val PASSPHRASE = RowId("backup.passphrase")

    override val rows: List<RowSpec> = listOf(
        RowSpec(NOW, SectionId.BACKUP, Visibility.SIMPLE, EffectTiming.NONE, "D"),
        RowSpec(RESTORE, SectionId.BACKUP, Visibility.SIMPLE, EffectTiming.LIVE, "D"),
        RowSpec(WEEKLY, SectionId.BACKUP, Visibility.ADVANCED, EffectTiming.LIVE, "D"),
        RowSpec(PASSPHRASE, SectionId.BACKUP, Visibility.ADVANCED, EffectTiming.LIVE, "D"),
    )

    // Rows of the backup / restore flows on the same page and on RestorePreview (not settings: not in the registry).
    val TO_PHONE = RowId("backup.now.phone")
    val TO_FILE = RowId("backup.now.file")
    val TO_DOWNLOADS = RowId("backup.now.downloads")
    val INCLUDE_LOGINS = RowId("backup.now.logins")
    val PASS1 = RowId("backup.now.pass1")
    val PASS2 = RowId("backup.now.pass2")
    val GO = RowId("backup.now.go")
    val CANCEL = RowId("backup.flow.cancel")
    val STATUS = RowId("backup.flow.status")
    val FROM_PHONE = RowId("backup.restore.phone")
    val FROM_FILE = RowId("backup.restore.file")
    val FROM_DOWNLOADS = RowId("backup.restore.downloads")
    val NUDGE = RowId("backup.nudge")
    /** C-S7 (§3.3.9 "set / change / remove"): removes the stored passphrase (destructive, confirm). */
    val PASSPHRASE_REMOVE = RowId("backup.passphrase_remove")
    const val DOWNLOAD_FILE_PREFIX = "backup.restore.downloads/"

    val PREVIEW_SUMMARY = RowId("restore.summary")
    const val PREVIEW_PLAYLIST_PREFIX = "restore.playlist/"
    val PREVIEW_PASSPHRASE = RowId("restore.passphrase")
    val PREVIEW_MODE = RowId("restore.mode")
    val PREVIEW_GO = RowId("restore.go")
    val PREVIEW_PROBLEMS = RowId("restore.problems")
    val PREVIEW_RESULT = RowId("restore.result")
    /** C-S8 (§3.4.1 "Restore preview → GUIDE"): leaves Settings to the guide after a successful restore. */
    val PREVIEW_START = RowId("restore.start_watching")
    /** C-S8 (§3.4.6, B3): the PIN offer after a restore that carried locks while no PIN exists. */
    val PREVIEW_PIN_OFFER = RowId("restore.pin_offer")
    val PREVIEW_PIN_SET = RowId("restore.pin_set")
    val PREVIEW_PIN_LATER = RowId("restore.pin_later")
}

/** Every user string of WP-D's pages and screens (`strings_backup.xml`; `:ui` implements [BackupTexts]). */
enum class BackupText {
    // Channels section (§3.3.5)
    CHANNELS_TITLE, CHANNELS_PLAYLIST, CHANNELS_GROUPS, CHANNELS_GROUPS_VALUE, CHANNELS_HIDDEN, CHANNELS_FAVOURITES, CHANNELS_FAVOURITES_VALUE,
    CHANNELS_OPEN_GUIDE_ON, OPEN_ALL, OPEN_FAVOURITES, OPEN_LAST, OPEN_GUIDE_ON_EFFECT, CHANNELS_HIDE_RULES, CHANNELS_HIDE_RULES_VALUE,
    NO_PLAYLIST,
    // Channel editor row (docs/design/m6-channel-editor.md §3.1; append-only)
    CHANNELS_EDITOR, CHANNELS_EDITOR_VALUE,
    // Groups (§3.7.1)
    GROUPS_TITLE, GROUPS_SHOW_ALL, GROUP_VALUE, GROUP_SHOW, GROUP_HIDE, GROUP_LOCK, GROUP_UNLOCK, GROUP_MOVE, GROUP_RESET,
    GROUP_LOCK_NEEDS_PIN, NO_GROUPS, GROUPS_SHOWN_ALL,
    // Hidden channels (§3.7.2)
    HIDDEN_TITLE, HIDDEN_UNHIDE_ALL, HIDDEN_UNHIDE_ALL_CONFIRM_TITLE, HIDDEN_UNHIDE_ALL_CONFIRM_BODY, HIDDEN_UNHIDE, HIDDEN_BY_RULE,
    HIDDEN_BY_YOU, NO_HIDDEN, UNHIDDEN,
    // Hide rules (§3.7.3)
    RULES_TITLE, RULE_TARGET, RULE_TARGET_GROUP, RULE_TARGET_CHANNEL, RULE_PATTERN, RULE_KIND, RULE_KIND_CONTAINS, RULE_KIND_REGEX,
    RULE_PREVIEW, RULE_PREVIEW_EMPTY, RULE_SAVE, RULE_VALUE_CONTAINS, RULE_VALUE_REGEX, RULE_CHANNELS, RULE_DISABLE, RULE_ENABLE,
    RULE_DELETE, RULE_TOO_SLOW, RULE_TOO_LONG, RULE_BAD_REGEX, RULE_EMPTY, RULE_SAVED, RULE_DELETED, RULE_DUPLICATE, RULE_OFF,
    // Favourites (§3.7.4)
    FAV_TITLE, FAV_NEW, FAV_LIST_VALUE, FAV_LIST_NAME, FAV_LIST_DELETE, FAV_LIST_DELETE_CONFIRM_TITLE, FAV_LIST_DELETE_CONFIRM_BODY,
    FAV_LIST_ONLY_ONE, FAV_REMOVE, FAV_MOVE, NO_FAVOURITES, NO_LISTS, FAV_NAME_EMPTY,
    // Common
    CONFIRM, CANCEL, DELETE, MOVE_HINT,
    // Backup page (§3.3.9, §3.10)
    BACKUP_TITLE, BACKUP_NOW, BACKUP_NOW_DESC, BACKUP_TO_PHONE, BACKUP_TO_FILE, BACKUP_TO_DOWNLOADS, BACKUP_INCLUDE_LOGINS,
    BACKUP_INCLUDE_LOGINS_DESC, BACKUP_PASS1, BACKUP_PASS2, BACKUP_GO, BACKUP_RUNNING, BACKUP_DONE, BACKUP_FAILED,
    BACKUP_PASS_SHORT, BACKUP_PASS_MISMATCH, RESTORE, RESTORE_DESC, RESTORE_FROM_PHONE, RESTORE_FROM_FILE, RESTORE_FROM_DOWNLOADS,
    RESTORE_NONE_FOUND, RESTORE_NOT_LISTED, RESTORE_DOWNLOADS_UNREADABLE, WEEKLY, WEEKLY_VALUE, WEEKLY_NEVER, WEEKLY_DURABLE,
    WEEKLY_APP_ONLY, WEEKLY_NO_LOGINS_AFTER_RESET, PASSPHRASE_AUTO, PASSPHRASE_AUTO_SET, PASSPHRASE_AUTO_NOT_SET,
    PASSPHRASE_AUTO_DESC, PASSPHRASE_SAVED, PASSPHRASE_REMOVED, NUDGE, NUDGE_DESC, PHONE_ROUTE_DESC,
    // C-S7 (code review 2026-10-16)
    PASSPHRASE_REMOVE, PASSPHRASE_REMOVE_DESC, PASSPHRASE_REMOVE_CONFIRM_BODY, REMOVE,
    // Restore preview (§3.10.4)
    PREVIEW_TITLE, PREVIEW_SUMMARY, PREVIEW_WILL_UPDATE, PREVIEW_WILL_ADD, PREVIEW_PASSPHRASE, PREVIEW_PASSPHRASE_DESC, PREVIEW_MODE,
    PREVIEW_MERGE, PREVIEW_MERGE_DETAIL, PREVIEW_REPLACE, PREVIEW_REPLACE_DETAIL, PREVIEW_GO, PREVIEW_GONE, PREVIEW_RUNNING,
    RESTORE_DONE, RESTORE_NEEDS_LOGIN, PREVIEW_PROBLEMS,
    // C-S8 (code review 2026-10-16)
    RESTORE_START_WATCHING, RESTORE_PIN_OFFER, RESTORE_PIN_OFFER_CHANNELS, RESTORE_PIN_OFFER_BODY, RESTORE_PIN_SET, RESTORE_PIN_LATER,
    // Restore failures (reason + next step)
    FAIL_NOT_A_BACKUP, FAIL_NEWER_SCHEMA, FAIL_TRUNCATED, FAIL_WRONG_PASSPHRASE, FAIL_CORRUPT_SECRETS, FAIL_TOO_LARGE, FAIL_IO,
    // Phone setup screen (§3.5.1)
    PHONE_SCAN, PHONE_OR_OPEN, PHONE_AND_CODE, PHONE_WAITING, PHONE_CONNECTED, PHONE_RECEIVED, PHONE_FOOTER, PHONE_ISOLATION_HINT,
    PHONE_VPN, PHONE_TYPE_ON_TV, PHONE_NEW_CODE, PHONE_FAIL_NO_LAN, PHONE_FAIL_BIND, PHONE_FAIL_WRONG_CODES, PHONE_FAIL_EXPIRED,
    PHONE_STARTING, PHONE_CANT_SCAN, PHONE_TITLE_SETUP, PHONE_TITLE_RESTORE, PHONE_TITLE_BACKUP, PHONE_TITLE_LOGS, PHONE_BACK_HINT,
}

/** String lookup for [BackupText] (format args as in `strings_backup.xml`). */
fun interface BackupTexts {
    fun get(key: BackupText, vararg args: Any): String
}

/**
 * Where a page action leads after an intent: a notice, a reload of the current page, or another route.
 * (C-S8, code review 2026-10-16; additive) [leaveToGuide]: leave Settings to the guide ("Start watching",
 * `SettingsShellLink.leaveToGuide`); [setPin]: start "Set a PIN" (`SettingsShellLink.requestPinSetup(false)`).
 */
data class PageAction(
    val notice: String? = null,
    val reload: Boolean = true,
    val open: SettingsRoute? = null,
    val leaveToGuide: Boolean = false,
    val setPin: Boolean = false,
) {
    companion object { val NONE = PageAction(reload = false) }
}

/**
 * The pages WP-D builds (CHANNELS: Section(CHANNELS), Groups, HiddenChannels, HideRules, FavouriteLists, FavouriteList;
 * BACKUP: Section(BACKUP), Backup, RestorePreview). WP-A's `SettingsEffectsImpl` asks each bound provider (Hilt
 * `@IntoSet`) whether it [handles] a route and then delegates `loadPage`, `loadItems` and `onIntent` to it; when
 * [invalidations] emits a route (an async backup finished, a refresh merge recomputed rules), it reloads that page.
 */
interface SectionPages {
    fun handles(route: SettingsRoute): Boolean
    suspend fun page(route: SettingsRoute, advanced: Boolean): PageModel
    /** Rows `from until to` of the page's [app.thebox.core.settings.PagedList]. */
    suspend fun items(route: SettingsRoute, from: Int, to: Int): List<SettingsRow>
    suspend fun onIntent(route: SettingsRoute, intent: RowIntent): PageAction
    val invalidations: Flow<SettingsRoute>
}

/**
 * The backup side of the phone page (§3.5.3, §3.10.5): while the phone screen is open for RESTORE, BACKUP_DOWNLOAD or
 * SETUP (its Restore tab), uploaded backups are previewed, the phone's passphrase is taken, and the TV moves on to
 * RestorePreview through `SettingsNavigator`. The phone screen's view model calls [attach]/[detach] around the server.
 */
interface PhoneBackupFlow {
    fun attach(purpose: PhonePurpose)
    fun detach()
}

/**
 * A system document picker request (SAF, shown only when DocumentsUI is detected, F17): the `:ui` host launches
 * `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` and answers [DocumentPicker.picked] with the URI (null = cancelled).
 */
data class DocumentRequest(val id: Long, val create: Boolean, val suggestedName: String?)

interface DocumentPicker {
    val requests: kotlinx.coroutines.flow.StateFlow<DocumentRequest?>
    fun picked(requestId: Long, uri: String?)
}

/** Snapshot the Backup page is built from (the `:data` controller owns the state). */
data class BackupPageState(
    val stage: Stage = Stage.IDLE,
    val documentsUi: Boolean = false,
    val weekly: Boolean = true,
    val lastAutoAtMs: Long? = null,
    val lastAutoLocation: String? = null,
    val durable: Boolean = false,
    val passphraseStored: Boolean = false,
    val passphraseLostToKeyReset: Boolean = false,
    val includeLogins: Boolean = true,
    val pass1Set: Boolean = false,
    val pass2Set: Boolean = false,
    /** Inline problem of the current step (short passphrase, mismatch, a failure with its next step). */
    val error: String? = null,
    val status: String? = null,
    /** RESTORE_LIST: the `.thebox` files found in Downloads. */
    val downloads: List<String> = emptyList(),
    val downloadsUnreadable: Boolean = false,
    /** Show "Keep a copy on your phone" (last phone/file backup older than BACKUP_NUDGE_DAYS). */
    val nudge: Boolean = false,
    /** Where BACKUP_LOGINS leads ("file" or "downloads"). */
    val target: Target = Target.DOWNLOADS,
    val formatTime: (Long) -> String = { it.toString() },
    /** C-B2 / D-B1 (code review 2026-10-16; additive): a PIN is set, so Restore asks for it (§3.9.2). */
    val pinSet: Boolean = false,
) {
    enum class Stage { IDLE, CHOOSE_TARGET, LOGINS, RUNNING, CHOOSE_SOURCE, RESTORE_LIST }
    enum class Target { FILE, DOWNLOADS }
}

/** Pure builder of the Backup page (§3.3.9) and the restore preview (§3.10.4), table-tested in `BackupPagesTest`. */
object BackupPages {
    fun backup(s: BackupPageState, t: BackupTexts, advanced: Boolean, version: Long, route: SettingsRoute = SettingsRoute.Backup): PageModel {
        val rows = ArrayList<SettingsRow>()
        rows += SettingsRow(BackupRows.NOW, t.get(BackupText.BACKUP_NOW), description = t.get(BackupText.BACKUP_NOW_DESC), kind = RowKind.Action(), effect = EffectTiming.NONE)
        when (s.stage) {
            BackupPageState.Stage.CHOOSE_TARGET -> {
                rows += SettingsRow(BackupRows.TO_PHONE, t.get(BackupText.BACKUP_TO_PHONE), description = t.get(BackupText.PHONE_ROUTE_DESC),
                    kind = RowKind.Link(SettingsRoute.PhoneSetup(purpose = PhonePurpose.BACKUP_DOWNLOAD)), effect = EffectTiming.NONE)
                if (s.documentsUi) rows += SettingsRow(BackupRows.TO_FILE, t.get(BackupText.BACKUP_TO_FILE), kind = RowKind.Action(), effect = EffectTiming.NONE)
                rows += SettingsRow(BackupRows.TO_DOWNLOADS, t.get(BackupText.BACKUP_TO_DOWNLOADS),
                    description = t.get(if (s.durable) BackupText.WEEKLY_DURABLE else BackupText.WEEKLY_APP_ONLY), kind = RowKind.Action(), effect = EffectTiming.NONE)
                rows += cancel(t)
            }
            BackupPageState.Stage.LOGINS, BackupPageState.Stage.RUNNING -> {
                rows += SettingsRow(BackupRows.INCLUDE_LOGINS, t.get(BackupText.BACKUP_INCLUDE_LOGINS), description = t.get(BackupText.BACKUP_INCLUDE_LOGINS_DESC),
                    kind = RowKind.Toggle(s.includeLogins), effect = EffectTiming.NONE)
                if (s.includeLogins) {
                    rows += SettingsRow(BackupRows.PASS1, t.get(BackupText.BACKUP_PASS1), value = mask(s.pass1Set),
                        kind = RowKind.Text("", TextHint.PASSPHRASE, masked = true), effect = EffectTiming.NONE)
                    rows += SettingsRow(BackupRows.PASS2, t.get(BackupText.BACKUP_PASS2), value = mask(s.pass2Set),
                        kind = RowKind.Text("", TextHint.PASSPHRASE, masked = true), effect = EffectTiming.NONE)
                }
                val running = s.stage == BackupPageState.Stage.RUNNING
                rows += SettingsRow(BackupRows.GO, t.get(BackupText.BACKUP_GO), kind = RowKind.Action(), enabled = !running,
                    disabledReason = if (running) t.get(BackupText.BACKUP_RUNNING) else null, effect = EffectTiming.NONE)
                if (!running) rows += cancel(t)
            }
            BackupPageState.Stage.CHOOSE_SOURCE, BackupPageState.Stage.RESTORE_LIST, BackupPageState.Stage.IDLE -> Unit
        }
        s.error?.let { rows += SettingsRow(BackupRows.STATUS, it, kind = RowKind.Info(emptyList(), Tone.BAD), effect = EffectTiming.NONE) }
            ?: s.status?.let { rows += SettingsRow(BackupRows.STATUS, it, kind = RowKind.Info(emptyList(), Tone.GOOD), effect = EffectTiming.NONE) }

        // C-B2 / D-B1 (§3.9.2): Restore asks for the PIN while one is set (a restore can replace every lock).
        rows += SettingsRow(BackupRows.RESTORE, t.get(BackupText.RESTORE), description = t.get(BackupText.RESTORE_DESC), kind = RowKind.Action(needsPin = s.pinSet))
        when (s.stage) {
            BackupPageState.Stage.CHOOSE_SOURCE -> {
                rows += SettingsRow(BackupRows.FROM_PHONE, t.get(BackupText.RESTORE_FROM_PHONE), description = t.get(BackupText.PHONE_ROUTE_DESC),
                    kind = RowKind.Link(SettingsRoute.PhoneSetup(purpose = PhonePurpose.RESTORE)), effect = EffectTiming.NONE)
                if (s.documentsUi) rows += SettingsRow(BackupRows.FROM_FILE, t.get(BackupText.RESTORE_FROM_FILE), kind = RowKind.Action(), effect = EffectTiming.NONE)
                rows += SettingsRow(BackupRows.FROM_DOWNLOADS, t.get(BackupText.RESTORE_FROM_DOWNLOADS), kind = RowKind.Action(), effect = EffectTiming.NONE)
                rows += cancel(t)
            }
            BackupPageState.Stage.RESTORE_LIST -> {
                if (s.downloads.isEmpty()) {
                    val why = if (s.downloadsUnreadable) BackupText.RESTORE_DOWNLOADS_UNREADABLE else BackupText.RESTORE_NONE_FOUND
                    rows += SettingsRow(RowId(BackupRows.DOWNLOAD_FILE_PREFIX), t.get(why), kind = RowKind.Info(listOf(t.get(BackupText.RESTORE_NOT_LISTED)), Tone.WARN), effect = EffectTiming.NONE)
                } else {
                    for (name in s.downloads) rows += SettingsRow(RowId(BackupRows.DOWNLOAD_FILE_PREFIX + name), name, kind = RowKind.Item(primary = "restore"), effect = EffectTiming.NONE)
                    rows += SettingsRow(RowId(BackupRows.DOWNLOAD_FILE_PREFIX + "?"), t.get(BackupText.RESTORE_NOT_LISTED), kind = RowKind.Link(SettingsRoute.PhoneSetup(purpose = PhonePurpose.RESTORE)), effect = EffectTiming.NONE)
                }
                rows += cancel(t)
            }
            else -> Unit
        }
        if (s.nudge) {
            rows += SettingsRow(BackupRows.NUDGE, t.get(BackupText.NUDGE), description = t.get(BackupText.NUDGE_DESC),
                kind = RowKind.Link(SettingsRoute.PhoneSetup(purpose = PhonePurpose.BACKUP_DOWNLOAD)), effect = EffectTiming.NONE)
        }
        if (advanced) {
            val last = s.lastAutoAtMs?.let { t.get(BackupText.WEEKLY_VALUE, s.formatTime(it), s.lastAutoLocation ?: "") } ?: t.get(BackupText.WEEKLY_NEVER)
            val where = t.get(if (s.durable) BackupText.WEEKLY_DURABLE else BackupText.WEEKLY_APP_ONLY)
            val desc = if (s.passphraseLostToKeyReset) t.get(BackupText.WEEKLY_NO_LOGINS_AFTER_RESET) else where
            rows += SettingsRow(BackupRows.WEEKLY, t.get(BackupText.WEEKLY), value = last, description = desc, kind = RowKind.Toggle(s.weekly), visibility = Visibility.ADVANCED)
            rows += SettingsRow(BackupRows.PASSPHRASE, t.get(BackupText.PASSPHRASE_AUTO),
                value = t.get(if (s.passphraseStored) BackupText.PASSPHRASE_AUTO_SET else BackupText.PASSPHRASE_AUTO_NOT_SET),
                description = t.get(BackupText.PASSPHRASE_AUTO_DESC), kind = RowKind.Text("", TextHint.PASSPHRASE, masked = true), visibility = Visibility.ADVANCED)
            // C-S7: the Text row sets and changes; removing is its own destructive row (an empty edit is refused).
            if (s.passphraseStored) {
                rows += SettingsRow(BackupRows.PASSPHRASE_REMOVE, t.get(BackupText.PASSPHRASE_REMOVE), description = t.get(BackupText.PASSPHRASE_REMOVE_DESC),
                    kind = RowKind.Action(destructive = true, confirm = ConfirmSpec(t.get(BackupText.PASSPHRASE_REMOVE), t.get(BackupText.PASSPHRASE_REMOVE_CONFIRM_BODY), t.get(BackupText.REMOVE), t.get(BackupText.CANCEL))),
                    visibility = Visibility.ADVANCED, effect = EffectTiming.LIVE)
            }
        }
        return PageModel(route, t.get(BackupText.BACKUP_TITLE), rows = rows, version = version)
    }

    private fun cancel(t: BackupTexts) = SettingsRow(BackupRows.CANCEL, t.get(BackupText.CANCEL), kind = RowKind.Action(), effect = EffectTiming.NONE)

    private fun mask(set: Boolean) = if (set) "••••••••" else null

    /** Restore preview state (§3.10.4). [result] is the final summary or failure text once the restore ran. */
    data class PreviewState(
        val preview: RestorePreview?,
        val passphraseSet: Boolean = false,
        val mode: app.thebox.core.backup.RestoreMode = app.thebox.core.backup.RestoreMode.MERGE,
        val running: Boolean = false,
        val result: String? = null,
        val resultTone: Tone = Tone.GOOD,
        val error: String? = null,
        /** C-B2 / D-B1 (additive): a PIN is set, so Restore now asks for it. */
        val pinSet: Boolean = false,
        /** C-S8 (additive): the restore succeeded ([result] is its summary): offer Start watching. */
        val restored: Boolean = false,
        /** C-S8 (additive): non-null = offer a PIN for the locks the restore carried ([lockedGroups] groups; 0 = channels only). */
        val pinOfferGroups: Int? = null,
    )

    fun preview(route: SettingsRoute.RestorePreview, s: PreviewState, t: BackupTexts, version: Long): PageModel {
        val p = s.preview ?: return PageModel(route, t.get(BackupText.PREVIEW_TITLE), rows = emptyList(), version = version, placeholder = t.get(BackupText.PREVIEW_GONE))
        val rows = ArrayList<SettingsRow>()
        val c = p.header.counts
        fun n(k: String) = c[k] ?: 0
        rows += SettingsRow(
            BackupRows.PREVIEW_SUMMARY,
            t.get(BackupText.PREVIEW_SUMMARY, n("fav_lists"), n("favourites"), n("reminders"), n("channel_prefs"), n("settings")),
            value = p.header.appVersionName, kind = RowKind.Info(emptyList()), effect = EffectTiming.NONE,
        )
        for (m in p.playlists) {
            val what = t.get(if (m.target == MatchTarget.NEW) BackupText.PREVIEW_WILL_ADD else BackupText.PREVIEW_WILL_UPDATE)
            rows += SettingsRow(RowId(BackupRows.PREVIEW_PLAYLIST_PREFIX + m.uid), m.name, value = what, kind = RowKind.Info(emptyList()), effect = EffectTiming.NONE)
        }
        if (p.problems.isNotEmpty()) rows += SettingsRow(BackupRows.PREVIEW_PROBLEMS, t.get(BackupText.PREVIEW_PROBLEMS), kind = RowKind.Info(p.problems, Tone.WARN), effect = EffectTiming.NONE)
        if (s.result == null) {
            if (p.needsPassphrase) {
                rows += SettingsRow(BackupRows.PREVIEW_PASSPHRASE, t.get(BackupText.PREVIEW_PASSPHRASE), value = mask(s.passphraseSet),
                    description = t.get(BackupText.PREVIEW_PASSPHRASE_DESC), kind = RowKind.Text("", TextHint.PASSPHRASE, masked = true), effect = EffectTiming.NONE)
            }
            val options = listOf(
                ChoiceOption("MERGE", t.get(BackupText.PREVIEW_MERGE), t.get(BackupText.PREVIEW_MERGE_DETAIL)),
                ChoiceOption("REPLACE", t.get(BackupText.PREVIEW_REPLACE), t.get(BackupText.PREVIEW_REPLACE_DETAIL)),
            )
            rows += SettingsRow(BackupRows.PREVIEW_MODE, t.get(BackupText.PREVIEW_MODE), value = options[s.mode.ordinal].label,
                kind = RowKind.Choice(options, s.mode.ordinal), effect = EffectTiming.NONE)
            val replace = s.mode == app.thebox.core.backup.RestoreMode.REPLACE
            // C-B2 / D-B1 (§3.9.2): every restore path ends here, so Restore now asks for the PIN while one is set.
            rows += SettingsRow(BackupRows.PREVIEW_GO, t.get(BackupText.PREVIEW_GO),
                kind = RowKind.Action(destructive = replace, confirm = if (replace) replaceConfirm(t) else null, needsPin = s.pinSet), enabled = !s.running,
                disabledReason = if (s.running) t.get(BackupText.PREVIEW_RUNNING) else null, effect = EffectTiming.LIVE)
            s.error?.let { rows += SettingsRow(BackupRows.STATUS, it, kind = RowKind.Info(emptyList(), Tone.BAD), effect = EffectTiming.NONE) }
        } else {
            rows += SettingsRow(BackupRows.PREVIEW_RESULT, s.result, kind = RowKind.Info(emptyList(), s.resultTone), effect = EffectTiming.NONE)
            if (s.restored) {
                // C-S8 (§3.4.6, B3): the locks came back without a PIN to protect them.
                s.pinOfferGroups?.let { n ->
                    val offer = if (n > 0) t.get(BackupText.RESTORE_PIN_OFFER, n) else t.get(BackupText.RESTORE_PIN_OFFER_CHANNELS)
                    rows += SettingsRow(BackupRows.PREVIEW_PIN_OFFER, offer, kind = RowKind.Info(listOf(t.get(BackupText.RESTORE_PIN_OFFER_BODY)), Tone.WARN), effect = EffectTiming.NONE)
                    rows += SettingsRow(BackupRows.PREVIEW_PIN_SET, t.get(BackupText.RESTORE_PIN_SET), kind = RowKind.Action(), effect = EffectTiming.NONE)
                    rows += SettingsRow(BackupRows.PREVIEW_PIN_LATER, t.get(BackupText.RESTORE_PIN_LATER), kind = RowKind.Action(), effect = EffectTiming.NONE)
                }
                // C-S8 (§3.4.1 "Restore preview → GUIDE"): a first-run restore must not end on a dead end.
                rows += SettingsRow(BackupRows.PREVIEW_START, t.get(BackupText.RESTORE_START_WATCHING), kind = RowKind.Action(), effect = EffectTiming.NONE)
            }
        }
        // C-S8: the cursor lands on Start watching (or the PIN offer) when the result arrives.
        val focus = when {
            !s.restored -> null
            s.pinOfferGroups != null -> BackupRows.PREVIEW_PIN_SET
            else -> BackupRows.PREVIEW_START
        }
        return PageModel(route, t.get(BackupText.PREVIEW_TITLE), subtitle = p.header.appVersionName, rows = rows, version = version, focus = focus)
    }

    /** Reason and next step for a restore failure (no bare "failed"). */
    fun failureText(f: RestoreFailure, t: BackupTexts): String = t.get(
        when (f) {
            RestoreFailure.NOT_A_BACKUP -> BackupText.FAIL_NOT_A_BACKUP
            RestoreFailure.NEWER_SCHEMA -> BackupText.FAIL_NEWER_SCHEMA
            RestoreFailure.TRUNCATED -> BackupText.FAIL_TRUNCATED
            RestoreFailure.WRONG_PASSPHRASE -> BackupText.FAIL_WRONG_PASSPHRASE
            RestoreFailure.CORRUPT_SECRETS -> BackupText.FAIL_CORRUPT_SECRETS
            RestoreFailure.TOO_LARGE -> BackupText.FAIL_TOO_LARGE
            RestoreFailure.IO -> BackupText.FAIL_IO
        },
    )

    /** Confirm card for Replace (destructive). */
    fun replaceConfirm(t: BackupTexts) = ConfirmSpec(t.get(BackupText.PREVIEW_REPLACE), t.get(BackupText.PREVIEW_REPLACE_DETAIL), t.get(BackupText.CONFIRM), t.get(BackupText.CANCEL))
}
