package app.thebox.core.setup.pages

import app.thebox.core.common.Outcome
import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import app.thebox.core.setup.NormalizedInput
import app.thebox.core.setup.PlaylistDetail
import app.thebox.core.setup.PlaylistSummary
import app.thebox.core.setup.PrefixPreview
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupResultMapper
import app.thebox.core.setup.SetupText
import app.thebox.core.source.PlaylistType
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Settings → Playlists (§3.3.1, §3.6.1): one Item per playlist, then Add playlist, Set up from phone, Refresh all. */
class PlaylistsSectionPage(d: SetupPageDeps) : SetupPage(SettingsRoute.Section(SectionId.PLAYLISTS), d) {
    private var playlists: List<PlaylistSummary>? = null
    private var pendingRemove: Long? = null

    override fun start() {
        publish()
        launch { d.admin.observeAll().collect { playlists = it; publish() } }
    }

    override fun build(): PageModel {
        val list = playlists
        val rows = ArrayList<SettingsRow>((list?.size ?: 0) + 3)
        list?.forEach { p ->
            val badges = buildSet { if (p.failed || p.needsReauth) add(Badge.FAILED) }
            rows += row(
                itemRow(p.id), p.name,
                RowKind.Item(
                    route = SettingsRoute.Playlist(p.id),
                    menu = listOf(
                        ChoiceOption(MENU_REFRESH, t(SetupText.PLAYLIST_MENU_REFRESH)),
                        ChoiceOption(MENU_EDIT, t(SetupText.PLAYLIST_MENU_EDIT)),
                        ChoiceOption(MENU_TEST, t(SetupText.PLAYLIST_MENU_TEST)),
                        ChoiceOption(MENU_REMOVE, t(SetupText.PLAYLIST_MENU_REMOVE)),
                    ),
                    badges = badges,
                ),
                value = summaryLine(p),
            )
        }
        rows += link(ROW_ADD, t(SetupText.PLAYLISTS_ADD), SettingsRoute.AddSource())
        rows += link(ROW_PHONE, t(SetupText.WELCOME_PHONE), SettingsRoute.PhoneSetup())
        rows += action(ROW_REFRESH_ALL, t(SetupText.PLAYLISTS_REFRESH_ALL), enabled = !list.isNullOrEmpty(), disabledReason = t(SetupText.PLAYLISTS_NONE))
        return page(t(SetupText.PLAYLISTS_TITLE), rows, placeholder = if (list == null) t(SetupText.LOADING) else null)
    }

    /** "Xtream · 2,140 channels · updated 14:02", or the refresh progress, or the failure "reason · next step". */
    internal fun summaryLine(p: PlaylistSummary): String {
        if (p.refreshing) return t(SetupText.PLAYLIST_REFRESHING, p.progressLine ?: t(SetupText.STAGE_RUNNING))
        if (p.needsReauth) return t(SetupText.PLAYLIST_NEEDS_LOGIN)
        if (p.failed) return d.mapper.refreshFailureLine(p.lastStatus!!, SetupResultMapper.Context(kindOf(p.type), host = p.displaySource.substringBefore(':')))
        val parts = ArrayList<String>(4)
        parts += t(typeLabel(p.type))
        parts += t(SetupText.PLAYLIST_CHANNELS, num(p.liveCount))
        p.lastSuccessAtMs?.let { parts += t(SetupText.PLAYLIST_UPDATED, when_(it)) }
        p.expiresAtSec?.let { exp ->
            val days = SetupResultMapper.daysUntil(exp, d.nowMs())
            if (days <= 0) parts += t(SetupText.PLAYLIST_EXPIRED) else if (days <= SetupConstants.EXPIRY_WARN_DAYS) parts += t(SetupText.PLAYLIST_EXPIRES_IN, days)
        }
        return parts.joinToString(" · ")
    }

    private fun when_(ms: Long): String {
        val z = d.zone()
        val at = Instant.ofEpochMilli(ms).atZone(z)
        val today = LocalDate.now(java.time.Clock.fixed(Instant.ofEpochMilli(d.nowMs()), z))
        return if (at.toLocalDate() == today) DateTimeFormatter.ofPattern("HH:mm", d.locale()).format(at)
        else DateTimeFormatter.ofPattern("d MMM", d.locale()).format(at)
    }

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Run -> when (intent.row.key) {
                ROW_REFRESH_ALL -> { d.admin.refreshAll(); act(PageAction.Notice(t(SetupText.NOTICE_REFRESHING))) }
                ROW_REMOVE_CONFIRMED -> pendingRemove?.let { id -> pendingRemove = null; launch { d.admin.remove(id) } }
            }
            is RowIntent.Menu -> {
                val id = idOf(intent.row.key) ?: return
                when (intent.optionId) {
                    MENU_REFRESH -> { d.admin.refreshNow(id); act(PageAction.Notice(t(SetupText.NOTICE_REFRESHING))) }
                    MENU_EDIT -> act(PageAction.Push(SettingsRoute.PlaylistSource(id)))
                    MENU_TEST -> act(PageAction.Push(SettingsRoute.NetworkTest(id)))
                    MENU_REMOVE -> launch {
                        val p = playlists?.firstOrNull { it.id == id } ?: return@launch
                        val c = d.admin.removalCounts(id)
                        pendingRemove = id
                        act(PageAction.Confirm(removeSpec(this@PlaylistsSectionPage, p.name, c), RowIntent.Run(RowId(ROW_REMOVE_CONFIRMED))))
                    }
                }
            }
            else -> return
        }
        publish()
    }

    companion object {
        const val ROW_ITEM_PREFIX = "playlists.p."
        const val ROW_ADD = "playlists.add"
        const val ROW_PHONE = "playlists.phone"
        const val ROW_REFRESH_ALL = "playlists.refresh_all"
        const val ROW_REMOVE_CONFIRMED = "playlists.remove_confirmed"
        const val MENU_REFRESH = "refresh"
        const val MENU_EDIT = "edit"
        const val MENU_TEST = "test"
        const val MENU_REMOVE = "remove"

        fun itemRow(id: Long) = "$ROW_ITEM_PREFIX$id"
        fun idOf(key: String): Long? = if (key.startsWith(ROW_ITEM_PREFIX)) key.removePrefix(ROW_ITEM_PREFIX).toLongOrNull() else null
    }
}

internal fun kindOf(type: PlaylistType) = if (type == PlaylistType.XTREAM) app.thebox.core.settings.SourceKind.XTREAM else app.thebox.core.settings.SourceKind.M3U

internal fun typeLabel(type: PlaylistType) = when (type) {
    PlaylistType.XTREAM -> SetupText.TYPE_XTREAM
    PlaylistType.M3U_URL -> SetupText.TYPE_M3U
    PlaylistType.M3U_FILE -> SetupText.TYPE_M3U_FILE
}

internal fun removeSpec(p: SetupPage, name: String, c: app.thebox.core.setup.RemovalCounts) = ConfirmSpec(
    title = p.t(SetupText.REMOVE_TITLE, name),
    body = p.t(SetupText.REMOVE_BODY, p.num(c.channels), p.num(c.favourites), p.num(c.reminders)),
    confirmLabel = p.t(SetupText.REMOVE_CONFIRM),
    cancelLabel = p.t(SetupText.ACTION_CANCEL),
)

/** Playlist(id) (§3.3.1): the account card, then every per-playlist row of the table. */
class PlaylistPage(private val r: SettingsRoute.Playlist, d: SetupPageDeps) : SetupPage(r, d) {
    private var detail: PlaylistDetail? = null
    private var gone = false
    private var preview: PrefixPreview? = null
    /** Sub-rows opened by a choice (Start at N, custom prefix, custom UA, other zone). */
    private var customUa = false
    private var otherZone = false

    override fun start() {
        publish()
        launch {
            d.admin.observe(r.playlistId).collect { p ->
                if (p == null && detail != null) { gone = true; act(PageAction.PopTo(SettingsRoute.Section(SectionId.PLAYLISTS))) }
                detail = p
                if (p != null && p.namePrefixMode == "CUSTOM") preview = d.admin.previewNamePrefix(r.playlistId, p.namePrefixMode, p.namePrefixCustom)
                publish()
            }
        }
    }

    override fun build(): PageModel {
        val p = detail ?: return page(t(SetupText.PLAYLISTS_TITLE), emptyList(), placeholder = t(if (gone) SetupText.PLAYLIST_GONE else SetupText.LOADING))
        val s = p.summary
        val xtream = s.type == PlaylistType.XTREAM
        val rows = ArrayList<SettingsRow>(32)
        rows += accountCard(p)
        // Simple rows (§3.3.13: seven).
        rows += text(ROW_NAME, t(SetupText.ROW_NAME), s.name, TextHint.NAME)
        rows += link(ROW_SOURCE, t(SetupText.ROW_SOURCE), SettingsRoute.PlaylistSource(s.id), value = s.displaySource, description = t(SetupText.ROW_SOURCE_DESC), needsPin = lockSettings())
        rows += action(ROW_REFRESH, t(SetupText.ROW_REFRESH), value = refreshValue(p), enabled = !s.refreshing, disabledReason = t(SetupText.PLAYLIST_REFRESHING, s.progressLine ?: ""))
        rows += toggle(ROW_UPDATE_ON_START, t(SetupText.ROW_UPDATE_ON_START), p.updateOnStart, description = t(SetupText.EFFECT_NEXT_LAUNCH))
        val numberingId = if (p.numberingMode.startsWith("START_AT")) "START_AT" else p.numberingMode
        rows += choice(
            ROW_NUMBERING, t(SetupText.ROW_NUMBERING),
            listOf(ChoiceOption("PROVIDER", t(SetupText.NUMBERING_PROVIDER)), ChoiceOption("PLAYLIST_ORDER", t(SetupText.NUMBERING_PLAYLIST)), ChoiceOption("START_AT", t(SetupText.NUMBERING_START_AT))),
            numberingId, description = t(SetupText.EFFECT_NEXT_REFRESH),
        )
        if (numberingId == "START_AT") {
            val n = p.numberingMode.substringAfter(':', "1").toIntOrNull() ?: 1
            rows += row(ROW_NUMBERING_START, t(SetupText.ROW_NUMBERING_START), RowKind.Number(n, 1, 99_999), value = n.toString())
        }
        rows += choice(
            ROW_NAME_PREFIX, t(SetupText.ROW_NAME_PREFIX),
            listOf(ChoiceOption("AUTO", t(SetupText.PREFIX_AUTO)), ChoiceOption("OFF", t(SetupText.PREFIX_OFF)), ChoiceOption("CUSTOM", t(SetupText.PREFIX_CUSTOM))),
            p.namePrefixMode,
            description = preview?.let { t(SetupText.PREFIX_PREVIEW, num(it.changed), num(it.total)) },
        )
        if (p.namePrefixMode == "CUSTOM") {
            rows += text(ROW_NAME_PREFIX_CUSTOM, t(SetupText.ROW_NAME_PREFIX_CUSTOM), PrefixRuleText.toText(p.namePrefixCustom), TextHint.PATTERN, description = t(SetupText.PREFIX_CUSTOM_HINT))
        }
        rows += action(ROW_REMOVE, t(SetupText.ROW_REMOVE), destructive = true, needsPin = lockSettings())
        // Advanced rows (§3.3.1).
        rows += toggle(ROW_ENABLED, t(SetupText.ROW_ENABLED), s.enabled)
        rows += choice(
            ROW_INTERVAL, t(SetupText.ROW_INTERVAL),
            listOf(6, 12, 24, 0).map { ChoiceOption(it.toString(), if (it == 0) t(SetupText.INTERVAL_MANUAL) else t(SetupText.INTERVAL_HOURS, it)) },
            p.refreshIntervalH.toString(),
        )
        rows += choice(
            ROW_ORDER, t(SetupText.ROW_ORDER),
            listOf(ChoiceOption("PROVIDER", t(SetupText.ORDER_PROVIDER)), ChoiceOption("NUMBER", t(SetupText.ORDER_NUMBER)), ChoiceOption("NAME", t(SetupText.ORDER_NAME))),
            p.orderMode, description = t(SetupText.EFFECT_NEXT_REFRESH),
        )
        rows += toggle(ROW_PLAYLIST_EPG, t(SetupText.ROW_PLAYLIST_EPG), p.usePlaylistEpg, description = t(SetupText.EFFECT_NEXT_EPG))
        val uaId = when (p.userAgent) {
            null, "" -> UA_THE_BOX
            SetupConstants.VLC_USER_AGENT -> UA_VLC
            SetupConstants.BROWSER_USER_AGENT -> UA_BROWSER
            else -> UA_CUSTOM
        }
        rows += choice(
            ROW_USER_AGENT, t(SetupText.ROW_USER_AGENT),
            listOf(ChoiceOption(UA_THE_BOX, t(SetupText.UA_THE_BOX)), ChoiceOption(UA_VLC, t(SetupText.UA_VLC)), ChoiceOption(UA_BROWSER, t(SetupText.UA_BROWSER)), ChoiceOption(UA_CUSTOM, t(SetupText.UA_CUSTOM))),
            if (customUa) UA_CUSTOM else uaId,
            description = if (uaId == UA_VLC) t(SetupText.UA_VLC_NOTE) else t(SetupText.EFFECT_NEXT_TUNE),
        )
        if (customUa || uaId == UA_CUSTOM) rows += text(ROW_USER_AGENT_CUSTOM, t(SetupText.ROW_USER_AGENT_CUSTOM), p.userAgent.orEmpty(), TextHint.HEADER)
        p.headers.entries.sortedBy { it.key.lowercase() }.forEach { (k, v) ->
            rows += row(headerRow(k), k, RowKind.Item(menu = listOf(ChoiceOption(MENU_REMOVE_HEADER, t(SetupText.HEADER_REMOVE)))), value = v)
        }
        if (p.secretHeaderCount > 0) rows += info(ROW_HEADERS_SECRET, t(SetupText.HEADERS_SECRET, p.secretHeaderCount), emptyList())
        rows += text(ROW_HEADERS, t(SetupText.ROW_HEADERS_ADD), "", TextHint.HEADER, description = t(SetupText.HEADERS_HINT))
        if (xtream) {
            rows += choice(
                ROW_STREAM_FORMAT, t(SetupText.ROW_STREAM_FORMAT),
                listOf(ChoiceOption("AUTO", t(SetupText.FORMAT_AUTO)), ChoiceOption("TS", t(SetupText.FORMAT_TS)), ChoiceOption("HLS", t(SetupText.FORMAT_HLS))),
                p.xtreamOutput,
                description = if (p.xtreamOutput == "AUTO" && p.xtreamOutputLearned == "HLS") t(SetupText.FORMAT_LEARNED_HLS) else t(SetupText.EFFECT_NEXT_TUNE),
            )
            p.backupHosts.forEachIndexed { i, h ->
                rows += row(backupRow(i), t(SetupText.BACKUP_SERVER_N, i + 2), RowKind.Item(menu = listOf(ChoiceOption(MENU_REMOVE_BACKUP, t(SetupText.BACKUP_REMOVE)))), value = h)
            }
            rows += text(
                ROW_BACKUP_SERVERS, t(SetupText.ROW_BACKUP_ADD), "", TextHint.URL, chips = SetupPage.SERVER_CHIPS,
                description = if (p.backupHosts.isNotEmpty()) t(SetupText.BACKUP_ACTIVE, p.activeBaseIndex + 1, p.backupHosts.size + 1) else t(SetupText.BACKUP_HINT),
            )
        }
        rows += choice(
            ROW_CATCHUP_MODE, t(SetupText.ROW_CATCHUP_MODE),
            listOf("AUTO", "XC", "DEFAULT", "APPEND", "SHIFT", "FLUSSONIC", "OFF").map { ChoiceOption(it, t(catchupLabel(it))) },
            p.catchupModeSetting.uppercase(),
        )
        rows += row(ROW_CATCHUP_DAYS, t(SetupText.ROW_CATCHUP_DAYS), RowKind.Number(p.catchupDaysOverride ?: 0, 0, 30), value = (p.catchupDaysOverride ?: 0).let { if (it == 0) t(SetupText.CATCHUP_DAYS_PROVIDER) else t(SetupText.DAYS_N, it) }, description = t(SetupText.CATCHUP_DAYS_HINT))
        val zoneId = when (p.tokenZoneSetting.uppercase()) { "AUTO", "UTC", "DEVICE", "SERVER" -> p.tokenZoneSetting.uppercase(); else -> ZONE_OTHER }
        rows += choice(
            ROW_CATCHUP_ZONE, t(SetupText.ROW_CATCHUP_ZONE),
            listOf(ChoiceOption("AUTO", t(SetupText.ZONE_AUTO)), ChoiceOption("UTC", t(SetupText.ZONE_UTC)), ChoiceOption("DEVICE", t(SetupText.ZONE_DEVICE)), ChoiceOption("SERVER", t(SetupText.ZONE_SERVER)), ChoiceOption(ZONE_OTHER, t(SetupText.ZONE_OTHER))),
            if (otherZone) ZONE_OTHER else zoneId,
        )
        if (otherZone || zoneId == ZONE_OTHER) rows += text(ROW_CATCHUP_ZONE_OTHER, t(SetupText.ROW_CATCHUP_ZONE_OTHER), if (zoneId == ZONE_OTHER) p.tokenZoneSetting else "", TextHint.NAME, description = t(SetupText.ZONE_OTHER_HINT))
        rows += choice(ROW_PADDING_BEFORE, t(SetupText.ROW_PADDING_BEFORE), listOf(0, 1, 2, 5).map { ChoiceOption((it * 60).toString(), t(SetupText.MINUTES_N, it)) }, p.paddingBeforeSec.toString())
        rows += choice(ROW_PADDING_AFTER, t(SetupText.ROW_PADDING_AFTER), listOf(0, 2, 5, 10).map { ChoiceOption((it * 60).toString(), t(SetupText.MINUTES_N, it)) }, p.paddingAfterSec.toString())
        rows += if (xtream) {
            info(ROW_MAX_CONNECTIONS, t(SetupText.ROW_MAX_CONNECTIONS), listOf(p.account?.maxConnections?.let { t(SetupText.MAX_CONN_ACCOUNT, it) } ?: t(SetupText.MAX_CONN_UNKNOWN)))
        } else {
            val m = p.maxConnectionsOverride ?: 1
            row(ROW_MAX_CONNECTIONS, t(SetupText.ROW_MAX_CONNECTIONS), RowKind.Number(m, 1, 10), value = m.toString())
        }
        rows += row(
            ROW_IGNORE_TLS, t(SetupText.ROW_IGNORE_TLS), RowKind.Toggle(p.ignoreTlsErrors),
            description = t(SetupText.IGNORE_TLS_DESC),
        )
        rows += link(ROW_NETWORK_TEST, t(SetupText.ROW_NETWORK_TEST), SettingsRoute.NetworkTest(s.id))
        return page(s.name, rows, subtitle = s.displaySource)
    }

    private fun lockSettings(): Boolean = d.parental.value.let { it.pinSet && it.policy.lockSettings }

    private fun refreshValue(p: PlaylistDetail): String? {
        val s = p.summary
        if (s.refreshing) return t(SetupText.PLAYLIST_REFRESHING, s.progressLine ?: t(SetupText.STAGE_RUNNING))
        if (s.failed) return d.mapper.refreshFailureLine(s.lastStatus!!, SetupResultMapper.Context(kindOf(s.type)))
        val rep = p.lastReport ?: return null
        val parts = ArrayList<String>(5)
        parts += t(SetupText.REPORT_ADDED, num(rep.added))
        parts += t(SetupText.REPORT_REMOVED, num(rep.newlyMissing))
        if (rep.urlChanged > 0) parts += t(SetupText.REPORT_URLS_CHANGED, num(rep.urlChanged))
        if (rep.favouritesOrphaned == 0) parts += t(SetupText.REPORT_FAVOURITES_KEPT)
        return parts.joinToString(" · ")
    }

    private fun accountCard(p: PlaylistDetail): SettingsRow {
        val a = p.account
        val lines = ArrayList<String>(4)
        var tone = Tone.NORMAL
        if (a != null) {
            val exp = a.expiresAtSec
            lines += when {
                exp == null -> t(SetupText.ACCOUNT_NEVER_EXPIRES, t(statusLabel(a.status)))
                else -> {
                    val days = SetupResultMapper.daysUntil(exp, d.nowMs())
                    tone = if (days <= 0) Tone.BAD else if (days < SetupConstants.EXPIRY_WARN_DAYS) Tone.WARN else Tone.NORMAL
                    if (days <= 0) t(SetupText.ACCOUNT_EXPIRED, t(statusLabel(a.status))) else t(SetupText.ACCOUNT_EXPIRES_IN, t(statusLabel(a.status)), days)
                }
            }
            // (M5 integration, I-M5-3) A panel that reports Expired / Banned / Disabled is red whatever the date says.
            if (a.status == app.thebox.core.xtream.AccountStatus.EXPIRED || a.status == app.thebox.core.xtream.AccountStatus.BANNED ||
                a.status == app.thebox.core.xtream.AccountStatus.DISABLED) tone = Tone.BAD
            if (a.maxConnections != null) lines += t(SetupText.ACCOUNT_CONNECTIONS, a.activeConnections ?: 0, a.maxConnections)
            if (a.allowedOutputFormats.isNotEmpty()) lines += t(SetupText.CARD_FORMATS, a.allowedOutputFormats.map(SetupResultMapper::formatName).distinct().joinToString(", "))
        }
        p.serverZone?.let { z ->
            val skewMin = p.clockSkewSec / 60
            lines += if (abs(skewMin) < 1) t(SetupText.ACCOUNT_ZONE_IN_SYNC, z) else t(SetupText.ACCOUNT_ZONE_SKEW, z, skewMin)
        }
        if (p.userAgent == SetupConstants.VLC_USER_AGENT) lines += t(SetupText.UA_FALLBACK_NOTE)
        if (p.summary.needsReauth) { lines += t(SetupText.PLAYLIST_NEEDS_LOGIN); tone = Tone.BAD }
        if (lines.isEmpty()) lines += t(typeLabel(p.summary.type))
        return info(ROW_ACCOUNT, t(SetupText.ACCOUNT_TITLE), lines, tone)
    }

    override fun onIntent(intent: RowIntent) {
        val id = r.playlistId
        val p = detail ?: return
        when (intent) {
            is RowIntent.Edit -> when (val k = intent.row.key) {
                ROW_NAME -> launch { d.admin.rename(id, d.normalizer.field(intent.text).ifEmpty { p.summary.displaySource }) }
                ROW_NAME_PREFIX_CUSTOM -> launch {
                    // (M5 integration, G-L19) A pattern that could backtrack exponentially is refused, as for hide rules.
                    if (PrefixRuleText.risky(intent.text)) { act(PageAction.Notice(t(SetupText.PREFIX_TOO_SLOW))); return@launch }
                    val json = PrefixRuleText.toJson(intent.text)
                    d.admin.setNamePrefix(id, "CUSTOM", json)
                    preview = d.admin.previewNamePrefix(id, "CUSTOM", json)
                    publish()
                }
                ROW_USER_AGENT_CUSTOM -> launch { customUa = false; d.admin.setUserAgent(id, d.normalizer.field(intent.text).ifEmpty { null }) }
                ROW_HEADERS -> {
                    val (name, value) = HeaderText.parse(intent.text) ?: run { act(PageAction.Notice(t(SetupText.HEADERS_INVALID))); return }
                    launch { d.admin.setHeader(id, name, value) }
                }
                ROW_BACKUP_SERVERS -> {
                    val n = d.normalizer.server(intent.text) as? NormalizedInput.Xtream ?: run { act(PageAction.Notice(t(SetupText.BACKUP_INVALID))); return }
                    launch {
                        val cur = d.admin.editableSource(id) as? app.thebox.core.setup.EditableSource.Xtream ?: return@launch
                        val r = d.admin.setBackupServers(id, (cur.form.backups + n.server).distinct())
                        if (r is Outcome.Err) act(PageAction.Notice(d.mapper.error(r.error).headline))
                    }
                }
                ROW_CATCHUP_ZONE_OTHER -> {
                    val z = d.normalizer.field(intent.text)
                    if (runCatching { java.time.ZoneId.of(z) }.isFailure) { act(PageAction.Notice(t(SetupText.ZONE_INVALID))); return }
                    otherZone = false
                    launch { d.admin.setCatchupZone(id, z) }
                }
                else -> return
            }
            is RowIntent.Toggle -> when (intent.row.key) {
                ROW_UPDATE_ON_START -> launch { d.admin.setUpdateOnStart(id, intent.on) }
                ROW_ENABLED -> launch { d.admin.setEnabled(id, intent.on) }
                ROW_PLAYLIST_EPG -> launch { d.admin.setUsePlaylistEpg(id, intent.on) }
                ROW_IGNORE_TLS -> if (intent.on) {
                    act(PageAction.Confirm(app.thebox.core.settings.ConfirmSpec(t(SetupText.IGNORE_TLS_TITLE), t(SetupText.IGNORE_TLS_BODY), t(SetupText.IGNORE_TLS_CONFIRM), t(SetupText.ACTION_CANCEL)), RowIntent.Run(RowId(ROW_IGNORE_TLS_CONFIRMED))))
                } else launch { d.admin.setIgnoreTls(id, false) }
                else -> return
            }
            is RowIntent.Choose -> when (intent.row.key) {
                ROW_NUMBERING -> launch { d.admin.setNumbering(id, if (intent.optionId == "START_AT") "START_AT:1" else intent.optionId) }
                ROW_NAME_PREFIX -> launch {
                    val custom = if (intent.optionId == "CUSTOM") p.namePrefixCustom else null
                    d.admin.setNamePrefix(id, intent.optionId, custom)
                    preview = if (intent.optionId == "CUSTOM") d.admin.previewNamePrefix(id, intent.optionId, custom) else null
                    publish()
                }
                ROW_INTERVAL -> launch { d.admin.setRefreshInterval(id, intent.optionId.toInt()) }
                ROW_ORDER -> launch { d.admin.setOrder(id, intent.optionId) }
                ROW_USER_AGENT -> when (intent.optionId) {
                    UA_CUSTOM -> customUa = true
                    else -> { customUa = false; launch { d.admin.setUserAgent(id, uaValue(intent.optionId)) } }
                }
                ROW_STREAM_FORMAT -> launch { d.admin.setStreamFormat(id, intent.optionId) }
                ROW_CATCHUP_MODE -> launch { d.admin.setCatchupMode(id, intent.optionId) }
                ROW_CATCHUP_ZONE -> if (intent.optionId == ZONE_OTHER) otherZone = true else { otherZone = false; launch { d.admin.setCatchupZone(id, intent.optionId) } }
                ROW_PADDING_BEFORE -> launch { d.admin.setPadding(id, intent.optionId.toInt(), null) }
                ROW_PADDING_AFTER -> launch { d.admin.setPadding(id, null, intent.optionId.toInt()) }
                else -> return
            }
            is RowIntent.SetNumber -> when (intent.row.key) {
                ROW_NUMBERING_START -> launch { d.admin.setNumbering(id, "START_AT:${intent.value}") }
                ROW_CATCHUP_DAYS -> launch { d.admin.setCatchupDays(id, intent.value.takeIf { it > 0 }) }
                ROW_MAX_CONNECTIONS -> launch { d.admin.setMaxConnections(id, intent.value) }
                else -> return
            }
            is RowIntent.Run -> when (intent.row.key) {
                ROW_REFRESH -> { d.admin.refreshNow(id); act(PageAction.Notice(t(SetupText.NOTICE_REFRESHING))) }
                ROW_REMOVE -> launch {
                    val c = d.admin.removalCounts(id)
                    act(PageAction.Confirm(removeSpec(this@PlaylistPage, p.summary.name, c), RowIntent.Run(RowId(ROW_REMOVE_CONFIRMED))))
                }
                ROW_REMOVE_CONFIRMED -> launch { d.admin.remove(id); act(PageAction.PopTo(SettingsRoute.Section(SectionId.PLAYLISTS))) }
                ROW_IGNORE_TLS_CONFIRMED -> launch { d.admin.setIgnoreTls(id, true) }
                else -> return
            }
            is RowIntent.Menu -> {
                val k = intent.row.key
                when {
                    k.startsWith(ROW_HEADER_PREFIX) && intent.optionId == MENU_REMOVE_HEADER -> launch { d.admin.setHeader(id, k.removePrefix(ROW_HEADER_PREFIX), null) }
                    k.startsWith(ROW_BACKUP_PREFIX) && intent.optionId == MENU_REMOVE_BACKUP -> {
                        val i = k.removePrefix(ROW_BACKUP_PREFIX).toIntOrNull() ?: return
                        launch {
                            val cur = d.admin.editableSource(id) as? app.thebox.core.setup.EditableSource.Xtream ?: return@launch
                            d.admin.setBackupServers(id, cur.form.backups.filterIndexed { j, _ -> j != i })
                        }
                    }
                    else -> return
                }
            }
            else -> return
        }
        publish()
    }

    private fun uaValue(id: String): String? = when (id) {
        UA_VLC -> SetupConstants.VLC_USER_AGENT
        UA_BROWSER -> SetupConstants.BROWSER_USER_AGENT
        else -> null
    }

    companion object {
        const val ROW_ACCOUNT = "playlist.account"
        const val ROW_NAME = "playlist.name"
        const val ROW_SOURCE = "playlist.source"
        const val ROW_REFRESH = "playlist.refresh"
        const val ROW_UPDATE_ON_START = "playlist.update_on_start"
        const val ROW_NUMBERING = "playlist.numbering"
        const val ROW_NUMBERING_START = "playlist.numbering.start"
        const val ROW_NAME_PREFIX = "playlist.name_prefix"
        const val ROW_NAME_PREFIX_CUSTOM = "playlist.name_prefix.custom"
        const val ROW_REMOVE = "playlist.remove"
        const val ROW_REMOVE_CONFIRMED = "playlist.remove.confirmed"
        const val ROW_ENABLED = "playlist.enabled"
        const val ROW_INTERVAL = "playlist.interval"
        const val ROW_ORDER = "playlist.order"
        const val ROW_PLAYLIST_EPG = "playlist.playlist_epg"
        const val ROW_USER_AGENT = "playlist.user_agent"
        const val ROW_USER_AGENT_CUSTOM = "playlist.user_agent.custom"
        const val ROW_HEADERS = "playlist.headers"
        const val ROW_HEADER_PREFIX = "playlist.headers.h."
        const val ROW_HEADERS_SECRET = "playlist.headers.secret"
        const val ROW_STREAM_FORMAT = "playlist.stream_format"
        const val ROW_BACKUP_SERVERS = "playlist.backup_servers"
        const val ROW_BACKUP_PREFIX = "playlist.backup_servers.s."
        const val ROW_CATCHUP_MODE = "playlist.catchup_mode"
        const val ROW_CATCHUP_DAYS = "playlist.catchup_days"
        const val ROW_CATCHUP_ZONE = "playlist.catchup_zone"
        const val ROW_CATCHUP_ZONE_OTHER = "playlist.catchup_zone.other"
        const val ROW_PADDING_BEFORE = "playlist.padding_before"
        const val ROW_PADDING_AFTER = "playlist.padding_after"
        const val ROW_MAX_CONNECTIONS = "playlist.max_connections"
        const val ROW_IGNORE_TLS = "playlist.ignore_tls"
        const val ROW_IGNORE_TLS_CONFIRMED = "playlist.ignore_tls.confirmed"
        const val ROW_NETWORK_TEST = "playlist.network_test"
        const val UA_THE_BOX = "THE_BOX"
        const val UA_VLC = "VLC"
        const val UA_BROWSER = "BROWSER"
        const val UA_CUSTOM = "CUSTOM"
        const val ZONE_OTHER = "OTHER"
        const val MENU_REMOVE_HEADER = "remove_header"
        const val MENU_REMOVE_BACKUP = "remove_backup"

        fun headerRow(name: String) = ROW_HEADER_PREFIX + name
        fun backupRow(i: Int) = "$ROW_BACKUP_PREFIX$i"

        fun statusLabel(s: app.thebox.core.xtream.AccountStatus) = when (s) {
            app.thebox.core.xtream.AccountStatus.ACTIVE -> SetupText.STATUS_ACTIVE
            app.thebox.core.xtream.AccountStatus.EXPIRED -> SetupText.STATUS_EXPIRED
            app.thebox.core.xtream.AccountStatus.BANNED, app.thebox.core.xtream.AccountStatus.DISABLED -> SetupText.STATUS_DISABLED
            app.thebox.core.xtream.AccountStatus.UNKNOWN -> SetupText.STATUS_UNKNOWN
        }

        fun catchupLabel(mode: String) = when (mode) {
            "AUTO" -> SetupText.CATCHUP_AUTO
            "XC" -> SetupText.CATCHUP_XTREAM
            "DEFAULT" -> SetupText.CATCHUP_DEFAULT
            "APPEND" -> SetupText.CATCHUP_APPEND
            "SHIFT" -> SetupText.CATCHUP_SHIFT
            "FLUSSONIC" -> SetupText.CATCHUP_FLUSSONIC
            else -> SetupText.CATCHUP_OFF
        }
    }
}

/** The custom prefix rule as one editor line: `/regex/`, or literals separated by commas (§3.3.1, M3 §3.12). */
object PrefixRuleText {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** True when [text] is a `/regex/` whose shape [app.thebox.core.text.RegexSafety] refuses. */
    fun risky(text: String): Boolean {
        val s = text.trim()
        return s.length >= 2 && s.startsWith("/") && s.endsWith("/") && app.thebox.core.text.RegexSafety.isRisky(s.substring(1, s.length - 1))
    }

    fun toJson(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        if (s.length >= 2 && s.startsWith("/") && s.endsWith("/")) {
            return kotlinx.serialization.json.buildJsonObject { put("regex", kotlinx.serialization.json.JsonPrimitive(s.substring(1, s.length - 1))) }.toString()
        }
        val literals = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return kotlinx.serialization.json.buildJsonObject {
            put("literals", kotlinx.serialization.json.JsonArray(literals.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
    }

    fun toText(jsonText: String?): String {
        if (jsonText.isNullOrBlank()) return ""
        val o = runCatching { json.parseToJsonElement(jsonText) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return ""
        (o["regex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { return "/$it/" }
        val arr = o["literals"] as? kotlinx.serialization.json.JsonArray ?: return ""
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.joinToString(", ")
    }
}

/** "Name: value" or "Name = value" → a header (RFC 7230 token name), or null. */
object HeaderText {
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun parse(text: String): Pair<String, String>? {
        val s = text.trim()
        val i = s.indexOfFirst { it == ':' || it == '=' }
        if (i <= 0) return null
        val name = s.substring(0, i).trim()
        val value = s.substring(i + 1).trim()
        if (!TOKEN.matches(name) || value.isEmpty() || value.any { it == '\r' || it == '\n' }) return null
        if (name.equals("Range", ignoreCase = true)) return null // never on live streams (CLAUDE.md)
        return name to value
    }
}
