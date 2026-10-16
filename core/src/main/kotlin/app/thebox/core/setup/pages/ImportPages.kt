package app.thebox.core.setup.pages

import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.Tone
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.setup.ImportStage
import app.thebox.core.setup.ImportSummary
import app.thebox.core.setup.SetupAction
import app.thebox.core.setup.SetupCard
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupProgress
import app.thebox.core.setup.SetupText
import app.thebox.core.source.PlaylistType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.EnumMap

/**
 * One playlist's import as the pages see it (§3.4.5): folded from [app.thebox.core.setup.SourceSetup.progress], kept
 * by [SetupPages] across the ImportProgress → GroupsReview → matching report pages (the guide import continues in
 * the background after Start watching). Main thread.
 */
class ImportTracker(
    val playlistId: Long,
    private val scope: CoroutineScope,
    private val source: (Long) -> kotlinx.coroutines.flow.Flow<SetupProgress>,
    /** Called once per tracker when the import first reaches stage 3 (ReadyToWatch). */
    private val onReady: (Long) -> Unit = {},
) {
    private var readyReported = false
    class StageState(val done: Boolean, val count: String?)

    val stages: MutableMap<ImportStage, StageState> = EnumMap(ImportStage::class.java)
    var ready = false
        private set
    var failed: SetupCard? = null
        private set
    var summary: ImportSummary? = null
        private set
    private var job: Job? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    val running: Boolean get() = job?.isActive == true
    val guideDownloaded: Boolean get() = stages[ImportStage.DOWNLOADING_GUIDE]?.done == true
    val finished: Boolean get() = summary != null

    fun listen(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }

    /** (Re)starts collecting; a retry after a failure starts from scratch. */
    fun start() {
        job?.cancel()
        stages.clear(); ready = false; failed = null; summary = null
        job = scope.launch {
            source(playlistId).collect { p ->
                when (p) {
                    is SetupProgress.Stage -> stages[p.stage] = StageState(p.done, p.count ?: stages[p.stage]?.count)
                    is SetupProgress.ReadyToWatch -> ready = true
                    is SetupProgress.Failed -> failed = p.card
                    is SetupProgress.Finished -> { summary = p.report; ready = true }
                }
                if (ready && !readyReported) { readyReported = true; onReady(playlistId) }
                for (l in listeners.toList()) l()
            }
        }
    }

    fun ensureStarted() { if (job == null) start() }

    fun stop() { job?.cancel(); job = null }
}

/** S5: import progress (§3.4.5), with the PIN offer outside first run (B3) and the failure card. */
class ImportProgressPage(
    private val r: SettingsRoute.ImportProgress,
    d: SetupPageDeps,
    private val tracker: ImportTracker,
    /** Where Edit on a failure card goes (the form the user came from, or the playlist's source page). */
    private val editRoute: (PlaylistType?) -> SettingsRoute,
) : SetupPage(r, d) {
    private var unlisten: (() -> Unit)? = null
    private var type: PlaylistType? = null
    private var uid: String? = null
    private var pinOfferVisible = false

    override fun start() {
        tracker.ensureStarted()
        unlisten = tracker.listen { onProgress(); publish() }
        launch {
            d.admin.summary(r.playlistId)?.let { type = it.type; uid = it.uid }
            onProgress()
            publish()
        }
        publish()
    }

    override fun build(): PageModel {
        val rows = ArrayList<SettingsRow>(10)
        tracker.failed?.let { card ->
            rows += cardRows(card) { a -> if (a == SetupAction.SAVE_ANYWAY && isShrink(card)) t(SetupText.ACTION_ACCEPT_ANYWAY) else d.mapper.actionLabel(a) }
        }
        for (s in ImportStage.entries) {
            val st = tracker.stages[s]
            val state = when {
                st?.done == true -> t(SetupText.STAGE_DONE)
                st != null -> t(SetupText.STAGE_RUNNING)
                else -> t(SetupText.STAGE_WAITING)
            }
            rows += info(stageRow(s), t(stageLabel(s)), listOfNotNull(st?.count ?: state), if (st?.done == true) Tone.GOOD else Tone.NORMAL)
                .copy(value = st?.count ?: state)
        }
        tracker.summary?.uaNote?.let { rows += info(ROW_UA_NOTE, it, emptyList(), Tone.WARN) }
        if (pinOfferVisible) rows += pinOfferRows(adultGroups())
        rows += action(
            ROW_START, t(SetupText.IMPORT_START_WATCHING),
            description = if (!tracker.ready) t(SetupText.IMPORT_START_HINT) else if (!tracker.finished) t(SetupText.IMPORT_GUIDE_CONTINUES) else null,
            enabled = tracker.ready, disabledReason = t(SetupText.NOTICE_STILL_READING),
        )
        return page(t(SetupText.IMPORT_TITLE), rows, subtitle = t(SetupText.IMPORT_SUBTITLE))
    }

    private fun adultGroups(): Int = tracker.summary?.adultGroups ?: 0

    private fun onProgress() {
        // B3: outside first run, an import that found adult groups offers a PIN once per playlist.
        val s = tracker.summary
        val key = uid
        if (!r.firstRun && !pinOfferVisible && s != null && s.adultGroups > 0 && key != null && !d.parental.value.pinSet) {
            val offered = d.store.flow(SetupDomain).value.pinOfferedFor
            if (key !in offered) {
                pinOfferVisible = true
                d.store.update(SetupDomain) { it.copy(pinOfferedFor = it.pinOfferedFor + key) }
            }
        }
    }

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Run -> when (intent.row.key) {
                ROW_START -> startWatching()
                ROW_PIN_SET -> { pinOfferVisible = false; act(PageAction.CreatePin) }
                ROW_PIN_LATER -> pinOfferVisible = false
                ROW_STOP -> stop()
                ROW_CARD_PRIMARY -> tracker.failed?.let { cardAction(it.primary, it) }
                ROW_CARD_SECONDARY -> tracker.failed?.let { c -> c.secondary?.let { cardAction(it, c) } }
            }
            else -> return
        }
        publish()
    }

    /** BACK (§3.4.5): ask first while the import still runs; after Stop (N12) first run → the form, else Playlists. */
    override fun onBack(): Boolean {
        if (!tracker.running || tracker.failed != null) {
            if (tracker.ready) return false
            act(PageAction.Replace(editRoute(type)))
            return true
        }
        act(
            PageAction.Confirm(
                ConfirmSpec(t(SetupText.IMPORT_STOP_TITLE), t(SetupText.IMPORT_STOP_BODY), t(SetupText.IMPORT_STOP_CONFIRM), t(SetupText.IMPORT_STOP_CANCEL)),
                RowIntent.Run(RowId(ROW_STOP)),
            ),
        )
        return true
    }

    private fun stop() {
        launch { d.setup.cancelImport(r.playlistId) }
        tracker.stop()
        act(PageAction.Replace(if (r.firstRun) editRoute(type) else SettingsRoute.Section(SectionId.PLAYLISTS)))
    }

    private fun startWatching() {
        if (!tracker.ready) {
            act(PageAction.Notice(t(SetupText.NOTICE_STILL_READING)))
            return
        }
        if (!r.firstRun) { act(PageAction.LeaveToGuide); return }
        launch {
            val groups = d.groups.groups(r.playlistId)
            val review = groups.size > SetupConstants.GROUPS_REVIEW_MIN_GROUPS || groups.any { it.adult }
            act(if (review) PageAction.Replace(SettingsRoute.GroupsReview(r.playlistId, firstRun = true)) else PageAction.LeaveToGuide)
        }
    }

    private fun cardAction(a: SetupAction, card: SetupCard) {
        when (a) {
            SetupAction.SAVE_ANYWAY -> if (isShrink(card)) { d.admin.refreshNow(r.playlistId, forceAcceptShrink = true); tracker.start() } else startWatchingAnyway()
            SetupAction.CONTINUE -> startWatchingAnyway()
            SetupAction.RETRY -> { d.admin.refreshNow(r.playlistId); tracker.start() }
            SetupAction.EDIT, SetupAction.PHONE_SETUP -> act(PageAction.Replace(if (a == SetupAction.EDIT) editRoute(type) else SettingsRoute.PhoneSetup(r.firstRun)))
            SetupAction.USE_SECURE_DNS -> {
                d.store.update(SetupNetworkDomain) { it.copy(dns = app.thebox.core.net.DnsChoice.CLOUDFLARE) }
                act(PageAction.Notice(t(SetupText.NOTICE_SECURE_DNS_ON)))
                d.admin.refreshNow(r.playlistId); tracker.start()
            }
            SetupAction.CANCEL, SetupAction.USE_XTREAM_INSTEAD, SetupAction.ADD_AS_SINGLE_CHANNEL -> act(PageAction.Replace(editRoute(type)))
        }
    }

    private fun startWatchingAnyway() = act(if (r.firstRun) PageAction.LeaveToGuide else PageAction.Pop)

    fun dispose() { unlisten?.invoke(); unlisten = null }

    companion object {
        const val ROW_START = "setup.import.start"
        const val ROW_STOP = "setup.import.stop"
        const val ROW_UA_NOTE = "setup.import.ua_note"

        fun stageRow(s: ImportStage) = "setup.import.stage." + s.name.lowercase()

        fun stageLabel(s: ImportStage) = when (s) {
            ImportStage.DOWNLOADING_PLAYLIST -> SetupText.STAGE_DOWNLOADING_PLAYLIST
            ImportStage.READING_CHANNELS -> SetupText.STAGE_READING_CHANNELS
            ImportStage.GROUPS -> SetupText.STAGE_GROUPS
            ImportStage.DOWNLOADING_GUIDE -> SetupText.STAGE_DOWNLOADING_GUIDE
            ImportStage.MATCHING_GUIDE -> SetupText.STAGE_MATCHING_GUIDE
        }

        /** The shrink guard's card (§3.4.4): its SAVE_ANYWAY is "Accept anyway" (`forceAcceptShrink`). */
        fun isShrink(card: SetupCard): Boolean = card.primary == SetupAction.SAVE_ANYWAY && card.secondary == SetupAction.RETRY
    }
}

internal const val ROW_PIN_OFFER = "setup.pin.offer"
internal const val ROW_PIN_SET = "setup.pin.set"
internal const val ROW_PIN_LATER = "setup.pin.later"

/** The PIN offer (§3.4.6, B3): "Set a 4-digit PIN to protect {n} adult groups" [Set PIN] [Not now]. */
internal fun SetupPage.pinOfferRows(lockedGroups: Int): List<SettingsRow> = listOf(
    info(ROW_PIN_OFFER, t(SetupText.PIN_OFFER, num(lockedGroups)), listOf(t(SetupText.PIN_OFFER_BODY)), Tone.WARN),
    action(ROW_PIN_SET, t(SetupText.PIN_SET)),
    action(ROW_PIN_LATER, t(SetupText.PIN_NOT_NOW)),
)

/** Where Edit goes after a failed import of [type] (first run / AddSource: the form, fields kept). */
internal fun formRoute(type: PlaylistType?, firstRun: Boolean): SettingsRoute = SettingsRoute.AddSource(
    when (type) {
        PlaylistType.XTREAM -> SourceKind.XTREAM
        PlaylistType.M3U_URL, PlaylistType.M3U_FILE -> SourceKind.M3U
        null -> null
    },
    firstRun,
)

/** Waits for the first value of the setup domain (the store's StateFlow already holds one). */
internal suspend fun SetupPageDeps.setupState() = store.flow(SetupDomain).first()
