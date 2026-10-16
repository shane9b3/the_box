package app.thebox.core.setup.pages

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.net.DnsChoice
import app.thebox.core.net.NetworkSettings
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.Domains
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.Tone
import app.thebox.core.setup.InputProblem
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.NormalizedInput
import app.thebox.core.setup.SetupAction
import app.thebox.core.setup.SetupCard
import app.thebox.core.setup.SetupResultMapper
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.XtreamForm
import java.net.URI

/** The `network` domain as WP-B writes it for "Use secure DNS" (§3.4.4): WP-A's object (M5 integration). */
internal val SetupNetworkDomain: SettingsDomain<NetworkSettings> get() = app.thebox.core.settings.state.NetworkDomain

/** The result card as rows: an Info block with the tone, then one or two action rows (§3.4.4). */
internal fun SetupPage.cardRows(card: SetupCard?, labelOf: (SetupAction) -> String): List<SettingsRow> {
    if (card == null) return emptyList()
    val out = ArrayList<SettingsRow>(3)
    out += row(ROW_CARD, card.headline, app.thebox.core.settings.RowKind.Info(card.lines, card.tone))
    out += row(ROW_CARD_PRIMARY, labelOf(card.primary), app.thebox.core.settings.RowKind.Action())
    card.secondary?.let { out += row(ROW_CARD_SECONDARY, labelOf(it), app.thebox.core.settings.RowKind.Action()) }
    return out
}

internal const val ROW_CARD = "setup.card"
internal const val ROW_CARD_PRIMARY = "setup.card.primary"
internal const val ROW_CARD_SECONDARY = "setup.card.secondary"

/** Host and explicit port of a normalised URL, for the texts that name them ("Can't find the server …"). */
internal fun contextOf(kind: SourceKind, url: String?, editing: Boolean = false): SetupResultMapper.Context {
    val u = url?.let { runCatching { URI(it) }.getOrNull() }
    return SetupResultMapper.Context(kind, u?.host, u?.port?.takeIf { it > 0 }, editing)
}

/** S3: the Xtream login form (also the PlaylistSource page of an Xtream playlist, with [existingId]). */
class XtreamFormPage(
    route: SettingsRoute,
    d: SetupPageDeps,
    private val firstRun: Boolean,
    /** Edit in place (PlaylistSource): keeps playlist_id and every (playlist_id, stable_key) row. */
    private val existingId: Long? = null,
    private val onSaved: (Long) -> Unit = {},
) : SetupPage(route, d) {
    private var draft
        get() = if (existingId == null) d.drafts.xtream else editDraft
        set(v) { if (existingId == null) d.drafts.xtream = v else editDraft = v }
    private var editDraft = app.thebox.core.setup.XtreamDraft()
    private var card: SetupCard? = null
    private var cardError: SourceError? = null
    private var busy = false

    fun prefill(form: XtreamForm) {
        editDraft = app.thebox.core.setup.XtreamDraft(
            name = form.name.orEmpty(), server = form.server, username = form.username, password = form.password,
            output = form.output, userAgent = form.userAgent.orEmpty(), backups = form.backups.joinToString(", "),
        )
    }

    override fun start() = publish()

    override fun build(): PageModel {
        val dr = draft
        val rows = ArrayList<SettingsRow>(12)
        rows += cardRows(card) { d.mapper.actionLabel(it, cardError) }
        if (existingId == null) {
            rows += text(ROW_NAME, t(SetupText.FORM_NAME), dr.name, TextHint.NAME, shown = dr.name.ifEmpty { null }, description = t(SetupText.FORM_NAME_HINT))
        }
        rows += text(ROW_SERVER, t(SetupText.FORM_SERVER), dr.server, TextHint.URL, chips = SetupPage.SERVER_CHIPS, description = t(SetupText.FORM_SERVER_HINT))
        rows += text(ROW_USERNAME, t(SetupText.FORM_USERNAME), dr.username, TextHint.USERNAME)
        rows += text(ROW_PASSWORD, t(SetupText.FORM_PASSWORD), dr.password, TextHint.PASSWORD, masked = true)
        if (existingId == null) {
            if (!dr.moreOptions) {
                rows += action(ROW_MORE, t(SetupText.FORM_MORE_OPTIONS), description = t(SetupText.FORM_MORE_OPTIONS_HINT))
            } else {
                rows += text(ROW_UA, t(SetupText.FORM_USER_AGENT), dr.userAgent, TextHint.NAME, shown = dr.userAgent.ifEmpty { t(SetupText.UA_THE_BOX) })
                rows += choice(ROW_OUTPUT, t(SetupText.FORM_STREAM_FORMAT), outputOptions(), dr.output)
                rows += text(ROW_BACKUPS, t(SetupText.FORM_BACKUPS), dr.backups, TextHint.URL, chips = SetupPage.SERVER_CHIPS, description = t(SetupText.FORM_BACKUPS_HINT))
            }
        }
        rows += action(
            ROW_CONNECT, t(if (existingId == null) SetupText.FORM_CONNECT else SetupText.FORM_TEST_AND_SAVE),
            value = if (busy) t(SetupText.FORM_CONNECTING) else null, enabled = !busy, disabledReason = t(SetupText.FORM_CONNECTING),
        )
        return page(
            title = t(if (existingId == null) SetupText.XTREAM_TITLE else SetupText.SOURCE_TITLE),
            subtitle = t(if (existingId == null) SetupText.XTREAM_SUBTITLE else SetupText.SOURCE_SUBTITLE),
            rows = rows,
            // (M5 integration) A new result card takes the focus on its primary button (§3.4.4, I-M5-4).
            focus = if (card != null) ROW_CARD_PRIMARY else null,
        )
    }

    private fun outputOptions() = listOf(
        ChoiceOption("AUTO", t(SetupText.FORMAT_AUTO)),
        ChoiceOption("TS", t(SetupText.FORMAT_TS)),
        ChoiceOption("HLS", t(SetupText.FORMAT_HLS)),
    )

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Edit -> edit(intent.row.key, intent.text)
            is RowIntent.Choose -> if (intent.row.key == ROW_OUTPUT) draft = draft.copy(output = intent.optionId)
            is RowIntent.Run -> when (intent.row.key) {
                ROW_MORE -> draft = draft.copy(moreOptions = true)
                ROW_CONNECT -> connect()
                ROW_CARD_PRIMARY -> card?.let { cardAction(it.primary) }
                ROW_CARD_SECONDARY -> card?.secondary?.let(::cardAction)
            }
            else -> return
        }
        publish()
    }

    private fun edit(row: String, raw: String) {
        when (row) {
            ROW_NAME -> draft = draft.copy(name = d.normalizer.field(raw))
            ROW_SERVER -> when (val n = d.normalizer.server(raw)) {
                is NormalizedInput.Xtream -> {
                    draft = if (n.filledFromLink) {
                        act(PageAction.Notice(t(SetupText.NOTICE_FILLED_FROM_LINK)))
                        draft.copy(server = n.server, username = n.username.orEmpty(), password = n.password.orEmpty(), output = n.output ?: draft.output)
                    } else {
                        draft.copy(server = n.server)
                    }
                    if (card != null && cardError == null && card?.primary == SetupAction.EDIT) clearCard()
                }
                is NormalizedInput.Invalid -> { draft = draft.copy(server = d.normalizer.field(raw)); showCard(d.mapper.input(n.reason), null) }
                is NormalizedInput.M3u -> draft = draft.copy(server = n.url)
            }
            ROW_USERNAME -> draft = draft.copy(username = d.normalizer.field(raw))
            // Passwords lose only invisible characters at the ends (§3.4.3); a "+" or inner space stays.
            ROW_PASSWORD -> draft = draft.copy(password = d.normalizer.field(raw))
            ROW_UA -> draft = draft.copy(userAgent = d.normalizer.field(raw))
            ROW_BACKUPS -> draft = draft.copy(backups = d.normalizer.field(raw))
        }
    }

    /** The form, or the card that explains why there is none. */
    private fun form(): XtreamForm? {
        val dr = draft
        val server = when (val n = d.normalizer.server(dr.server)) {
            is NormalizedInput.Xtream -> n.server
            is NormalizedInput.Invalid -> { showCard(d.mapper.input(n.reason), null); return null }
            is NormalizedInput.M3u -> n.url
        }
        if (dr.username.isEmpty() || dr.password.isEmpty()) {
            showCard(SetupCard(Tone.WARN, t(SetupText.CARD_LOGIN_EMPTY), listOf(t(SetupText.CARD_LOGIN_EMPTY_NEXT)), SetupAction.EDIT, SetupAction.PHONE_SETUP), null)
            return null
        }
        val backups = dr.backups.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { (d.normalizer.server(it) as? NormalizedInput.Xtream)?.server }
            .filter { it != server }.distinct()
        return XtreamForm(
            name = dr.name.ifBlank { null }, server = server, username = dr.username, password = dr.password,
            output = dr.output, userAgent = dr.userAgent.ifBlank { null }, backups = backups,
        )
    }

    private fun connect() {
        if (busy) return
        val form = form() ?: return
        clearCard()
        busy = true
        launch {
            val r = d.setup.testXtream(form)
            busy = false
            when (r) {
                is Outcome.Ok -> showCard(d.mapper.login(r.value, d.nowMs()), null)
                is Outcome.Err -> showCard(d.mapper.error(r.error, contextOf(SourceKind.XTREAM, form.server, existingId != null)), r.error)
            }
            publish()
        }
    }

    private fun save() {
        if (busy) return
        val form = form() ?: return
        busy = true
        launch {
            val r = d.setup.saveXtream(form, existingId ?: draft.createdId)
            busy = false
            when (r) {
                is Outcome.Ok -> {
                    clearCard()
                    if (existingId == null) draft = draft.copy(createdId = r.value)
                    onSaved(r.value)
                    act(PageAction.Replace(SettingsRoute.ImportProgress(r.value, firstRun)))
                }
                is Outcome.Err -> showCard(d.mapper.error(r.error, contextOf(SourceKind.XTREAM, form.server, existingId != null)), r.error)
            }
            publish()
        }
    }

    private fun cardAction(a: SetupAction) {
        when (a) {
            SetupAction.CONTINUE, SetupAction.SAVE_ANYWAY -> save()
            SetupAction.EDIT, SetupAction.CANCEL -> clearCard()
            SetupAction.RETRY -> connect()
            SetupAction.USE_SECURE_DNS -> {
                d.store.update(SetupNetworkDomain) { it.copy(dns = DnsChoice.CLOUDFLARE) }
                act(PageAction.Notice(t(SetupText.NOTICE_SECURE_DNS_ON)))
                connect()
            }
            SetupAction.PHONE_SETUP -> act(PageAction.Push(SettingsRoute.PhoneSetup(firstRun)))
            SetupAction.USE_XTREAM_INSTEAD, SetupAction.ADD_AS_SINGLE_CHANNEL -> clearCard()
        }
    }

    private fun showCard(c: SetupCard, e: SourceError?) { card = c; cardError = e }
    private fun clearCard() { card = null; cardError = null }

    companion object {
        const val ROW_NAME = "setup.xtream.name"
        const val ROW_SERVER = "setup.xtream.server"
        const val ROW_USERNAME = "setup.xtream.username"
        const val ROW_PASSWORD = "setup.xtream.password"
        const val ROW_MORE = "setup.xtream.more"
        const val ROW_UA = "setup.xtream.user_agent"
        const val ROW_OUTPUT = "setup.xtream.output"
        const val ROW_BACKUPS = "setup.xtream.backups"
        const val ROW_CONNECT = "setup.xtream.connect"
    }
}

/** S4: the M3U form (also the PlaylistSource page of an M3U playlist, with [existingId]). */
class M3uFormPage(
    route: SettingsRoute,
    d: SetupPageDeps,
    private val firstRun: Boolean,
    private val existingId: Long? = null,
    private val onSaved: (Long) -> Unit = {},
) : SetupPage(route, d) {
    private var editDraft = app.thebox.core.setup.M3uDraft()
    private var draft
        get() = if (existingId == null) d.drafts.m3u else editDraft
        set(v) { if (existingId == null) d.drafts.m3u = v else editDraft = v }
    private var card: SetupCard? = null
    private var cardError: SourceError? = null
    /** The Xtream login found in the URL while the "Use Xtream login instead?" card is up. */
    private var xtreamOffer: NormalizedInput.Xtream? = null
    private var busy = false

    fun prefill(form: M3uForm) {
        editDraft = app.thebox.core.setup.M3uDraft(
            name = form.name.orEmpty(), url = form.url.orEmpty(), fileUri = form.fileUri, epgUrls = form.epgUrls,
            userAgent = form.userAgent.orEmpty(), keepAsM3u = true,
        )
    }

    override fun start() = publish()

    override fun build(): PageModel {
        val dr = draft
        val rows = ArrayList<SettingsRow>(12)
        rows += cardRows(card) { a ->
            if (xtreamOffer != null && a == SetupAction.CONTINUE) t(SetupText.ACTION_KEEP_M3U) else d.mapper.actionLabel(a, cardError)
        }
        if (existingId == null) rows += text(ROW_NAME, t(SetupText.FORM_NAME), dr.name, TextHint.NAME, description = t(SetupText.FORM_NAME_HINT))
        rows += text(ROW_URL, t(SetupText.FORM_PLAYLIST_URL), dr.url, TextHint.URL, chips = SetupPage.M3U_CHIPS)
        if (existingId == null || dr.fileUri != null) {
            rows += action(ROW_FILE, t(SetupText.FORM_PLAYLIST_FILE), value = dr.fileName, description = t(SetupText.FORM_PLAYLIST_FILE_HINT))
        }
        dr.epgUrls.forEachIndexed { i, u -> rows += text("$ROW_EPG_PREFIX$i", t(SetupText.FORM_EPG_URL_N, i + 1), u, TextHint.URL, chips = SetupPage.SERVER_CHIPS) }
        rows += text(ROW_EPG_NEW, t(if (dr.epgUrls.isEmpty()) SetupText.FORM_EPG_URL else SetupText.FORM_EPG_ADD), "", TextHint.URL, chips = SetupPage.SERVER_CHIPS, description = t(SetupText.FORM_EPG_HINT))
        if (existingId == null) {
            if (!dr.moreOptions) rows += action(ROW_MORE, t(SetupText.FORM_MORE_OPTIONS), description = t(SetupText.FORM_MORE_OPTIONS_HINT))
            else rows += text(ROW_UA, t(SetupText.FORM_USER_AGENT), dr.userAgent, TextHint.NAME, shown = dr.userAgent.ifEmpty { t(SetupText.UA_THE_BOX) })
        }
        rows += action(
            ROW_ADD, t(if (existingId == null) SetupText.FORM_ADD_PLAYLIST else SetupText.FORM_SAVE),
            value = if (busy) t(SetupText.FORM_SAVING) else null, enabled = !busy, disabledReason = t(SetupText.FORM_SAVING),
        )
        return page(
            title = t(if (existingId == null) SetupText.M3U_TITLE else SetupText.SOURCE_TITLE),
            subtitle = t(if (existingId == null) SetupText.M3U_SUBTITLE else SetupText.SOURCE_SUBTITLE),
            rows = rows,
            // (M5 integration) A new result card takes the focus on its primary button (§3.4.4, I-M5-4).
            focus = if (card != null) ROW_CARD_PRIMARY else null,
        )
    }

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Edit -> edit(intent.row.key, intent.text)
            is RowIntent.Run -> when (intent.row.key) {
                ROW_FILE -> act(PageAction.PickM3uFile)
                ROW_MORE -> draft = draft.copy(moreOptions = true)
                ROW_ADD -> save()
                ROW_CARD_PRIMARY -> card?.let { cardAction(it.primary) }
                ROW_CARD_SECONDARY -> card?.secondary?.let(::cardAction)
            }
            else -> return
        }
        publish()
    }

    override fun onFilePicked(uri: String?, displayName: String?) {
        if (uri == null) return
        draft = draft.copy(fileUri = uri, fileName = displayName ?: uri.substringAfterLast('/'), url = "")
        clearCard()
        publish()
    }

    private fun edit(row: String, raw: String) {
        when {
            row == ROW_NAME -> draft = draft.copy(name = d.normalizer.field(raw))
            row == ROW_UA -> draft = draft.copy(userAgent = d.normalizer.field(raw))
            row == ROW_URL -> {
                if (d.normalizer.field(raw).isEmpty()) { draft = draft.copy(url = ""); return }
                when (val n = d.normalizer.m3uUrl(raw)) {
                    is NormalizedInput.M3u -> {
                        draft = draft.copy(url = n.url, fileUri = null, fileName = null, keepAsM3u = false)
                        clearCard()
                        n.looksLikeXtream?.let(::offerXtream)
                    }
                    is NormalizedInput.Invalid -> { draft = draft.copy(url = d.normalizer.field(raw)); showCard(d.mapper.input(n.reason), null) }
                    is NormalizedInput.Xtream -> draft = draft.copy(url = n.server)
                }
            }
            row == ROW_EPG_NEW -> epgUrl(raw)?.let { draft = draft.copy(epgUrls = (draft.epgUrls + it).distinct()) }
            row.startsWith(ROW_EPG_PREFIX) -> {
                val i = row.removePrefix(ROW_EPG_PREFIX).toIntOrNull() ?: return
                val list = draft.epgUrls.toMutableList()
                if (i !in list.indices) return
                val v = epgUrl(raw)
                if (v == null) list.removeAt(i) else list[i] = v
                draft = draft.copy(epgUrls = list)
            }
        }
    }

    /** A guide URL, or null for an empty field; an invalid one shows its card. */
    private fun epgUrl(raw: String): String? {
        if (d.normalizer.field(raw).isEmpty()) return null
        return when (val n = d.normalizer.m3uUrl(raw)) {
            is NormalizedInput.M3u -> n.url
            is NormalizedInput.Invalid -> { showCard(d.mapper.input(n.reason), null); null }
            is NormalizedInput.Xtream -> n.server
        }
    }

    private fun offerXtream(x: NormalizedInput.Xtream) {
        xtreamOffer = x
        card = SetupCard(Tone.WARN, t(SetupText.CARD_XTREAM_LINK), listOf(t(SetupText.CARD_XTREAM_LINK_NEXT)), SetupAction.USE_XTREAM_INSTEAD, SetupAction.CONTINUE)
        cardError = null
    }

    private fun save() {
        if (busy) return
        val dr = draft
        if (dr.url.isEmpty() && dr.fileUri == null) {
            showCard(d.mapper.input(InputProblem.EMPTY), null)
            return
        }
        if (dr.fileUri == null) {
            when (val n = d.normalizer.m3uUrl(dr.url)) {
                is NormalizedInput.Invalid -> { showCard(d.mapper.input(n.reason), null); return }
                is NormalizedInput.M3u -> if (n.looksLikeXtream != null && !dr.keepAsM3u) { offerXtream(n.looksLikeXtream); return }
                is NormalizedInput.Xtream -> Unit
            }
        }
        val form = M3uForm(
            name = dr.name.ifBlank { null }, url = dr.url.ifEmpty { null }, fileUri = dr.fileUri,
            epgUrls = dr.epgUrls, userAgent = dr.userAgent.ifBlank { null }, fileName = dr.fileName,
        )
        busy = true
        launch {
            val r = d.setup.saveM3u(form, existingId ?: draft.createdId)
            busy = false
            when (r) {
                is Outcome.Ok -> {
                    clearCard()
                    if (existingId == null) draft = draft.copy(createdId = r.value)
                    onSaved(r.value)
                    act(PageAction.Replace(SettingsRoute.ImportProgress(r.value, firstRun)))
                }
                is Outcome.Err -> showCard(d.mapper.error(r.error, contextOf(SourceKind.M3U, form.url, existingId != null)), r.error)
            }
            publish()
        }
    }

    private fun cardAction(a: SetupAction) {
        val offer = xtreamOffer
        when (a) {
            SetupAction.USE_XTREAM_INSTEAD -> if (offer != null) {
                d.drafts.xtream = d.drafts.xtream.copy(
                    name = draft.name, server = offer.server, username = offer.username.orEmpty(), password = offer.password.orEmpty(),
                    output = offer.output ?: "AUTO",
                )
                clearCard()
                act(PageAction.Notice(t(SetupText.NOTICE_FILLED_FROM_LINK)))
                act(PageAction.Replace(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun)))
            }
            SetupAction.CONTINUE -> if (offer != null) { draft = draft.copy(keepAsM3u = true); clearCard() } else save()
            SetupAction.SAVE_ANYWAY, SetupAction.RETRY -> save()
            SetupAction.EDIT, SetupAction.CANCEL -> clearCard()
            SetupAction.ADD_AS_SINGLE_CHANNEL -> addSingle()
            SetupAction.PHONE_SETUP -> act(PageAction.Push(SettingsRoute.PhoneSetup(firstRun)))
            SetupAction.USE_SECURE_DNS -> {
                d.store.update(SetupNetworkDomain) { it.copy(dns = DnsChoice.CLOUDFLARE) }
                act(PageAction.Notice(t(SetupText.NOTICE_SECURE_DNS_ON)))
                save()
            }
        }
    }

    private fun addSingle() {
        val url = draft.url.ifEmpty { return }
        busy = true
        launch {
            val r = d.admin.addSingleStream(draft.name.ifBlank { null }, url, draft.userAgent.ifBlank { null })
            busy = false
            when (r) {
                is Outcome.Ok -> { clearCard(); act(PageAction.Replace(SettingsRoute.ImportProgress(r.value, firstRun))) }
                is Outcome.Err -> showCard(d.mapper.error(r.error, contextOf(SourceKind.M3U, url)), r.error)
            }
            publish()
        }
    }

    private fun showCard(c: SetupCard, e: SourceError?) { card = c; cardError = e; if (c.primary != SetupAction.USE_XTREAM_INSTEAD) xtreamOffer = null }
    private fun clearCard() { card = null; cardError = null; xtreamOffer = null }

    companion object {
        const val ROW_NAME = "setup.m3u.name"
        const val ROW_URL = "setup.m3u.url"
        const val ROW_FILE = "setup.m3u.file"
        const val ROW_EPG_PREFIX = "setup.m3u.epg."
        const val ROW_EPG_NEW = "setup.m3u.epg_new"
        const val ROW_MORE = "setup.m3u.more"
        const val ROW_UA = "setup.m3u.user_agent"
        const val ROW_ADD = "setup.m3u.add"
    }
}
