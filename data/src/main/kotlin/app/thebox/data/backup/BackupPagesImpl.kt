package app.thebox.data.backup

import android.content.Context
import android.net.Uri
import app.thebox.core.backup.BACKUP_NUDGE_DAYS
import app.thebox.core.backup.BACKUP_PASSPHRASE_MIN
import app.thebox.core.backup.BackupDomain
import app.thebox.core.backup.BackupNames
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.Tone
import app.thebox.core.settings.tree.BackupPageState
import app.thebox.core.settings.tree.BackupPages
import app.thebox.core.settings.tree.BackupRows
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.DocumentPicker
import app.thebox.core.settings.tree.DocumentRequest
import app.thebox.core.settings.tree.PageAction
import app.thebox.core.settings.tree.PhoneBackupFlow
import app.thebox.core.settings.tree.SectionPages
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.core.setup.lan.PhoneSubmission
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Backup page, the restore preview, the document picker requests and the backup side of the phone page
 * (docs/design/m5-setup-settings.md §3.3.9, §3.10). Holds the flow state and the passphrases typed on the TV (zeroed
 * after use or cancel); long work (export, restore) runs on [scope] and reloads the page through [invalidations].
 */
@Singleton
class BackupPagesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: BackupServiceImpl,
    private val storage: BackupStorage,
    private val documents: DocumentsUiDetector,
    private val scheduler: AutoBackupScheduler,
    private val store: SettingsStore,
    private val phone: PhoneLink,
    private val navigator: SettingsNavigator,
    private val texts: BackupTexts,
    private val clock: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SectionPages, DocumentPicker, PhoneBackupFlow {
    private val version = AtomicLong()
    private val _invalidations = MutableSharedFlow<SettingsRoute>(extraBufferCapacity = 8)
    override val invalidations: Flow<SettingsRoute> = _invalidations.asSharedFlow()
    private val _requests = MutableStateFlow<DocumentRequest?>(null)
    override val requests: StateFlow<DocumentRequest?> = _requests.asStateFlow()

    private val lock = Any()
    private var state = BackupPageState()
    private var pass1: CharArray? = null
    private var pass2: CharArray? = null
    private var listed: List<StoredBackup> = emptyList()
    private var docAction: DocAction? = null
    private val previews = HashMap<Long, PreviewEntry>()
    private val tokens = AtomicLong()
    private var phoneJob: Job? = null

    private val restoreDir get() = File(context.cacheDir, "restore").apply { mkdirs() }
    private val outDir get() = File(context.cacheDir, "backup-out").apply { mkdirs() }
    private val lastManual get() = File(File(context.filesDir, "backups").apply { mkdirs() }, "last-manual")

    private enum class DocAction { EXPORT, IMPORT }

    private class PreviewEntry(val file: File, val preview: RestorePreview?) {
        var passphrase: CharArray? = null
        var mode = RestoreMode.MERGE
        var running = false
        var result: String? = null
        var tone = Tone.GOOD
        var error: String? = null
    }

    override fun handles(route: SettingsRoute): Boolean =
        route == SettingsRoute.Backup || route is SettingsRoute.RestorePreview || (route is SettingsRoute.Section && route.id == SectionId.BACKUP)

    override suspend fun page(route: SettingsRoute, advanced: Boolean): PageModel = when (route) {
        is SettingsRoute.RestorePreview -> {
            val e = synchronized(lock) { previews[route.token] }
            val s = if (e == null) BackupPages.PreviewState(null) else BackupPages.PreviewState(e.preview, e.passphrase != null, e.mode, e.running, e.result, e.tone, e.error)
            BackupPages.preview(route, s, texts, version.incrementAndGet())
        }
        else -> BackupPages.backup(snapshot(), texts, advanced, version.incrementAndGet(), route)
    }

    override suspend fun items(route: SettingsRoute, from: Int, to: Int): List<SettingsRow> = emptyList()

    private fun snapshot(): BackupPageState {
        val prefs = store.flow(BackupDomain).value
        val docs = documents.available()
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val last = lastManual.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        val nudge = last == null || clock.now() - last > BACKUP_NUDGE_DAYS * 86_400_000L
        return synchronized(lock) {
            state.copy(
                documentsUi = docs,
                weekly = prefs.weekly,
                lastAutoAtMs = prefs.lastAutoAtMs,
                lastAutoLocation = prefs.lastAutoFile,
                durable = storage.downloadsWritable() && (android.os.Build.VERSION.SDK_INT < 30 || docs),
                passphraseStored = prefs.passphraseStored,
                passphraseLostToKeyReset = service.passphraseLost,
                pass1Set = pass1 != null,
                pass2Set = pass2 != null,
                downloads = listed.map { it.name },
                nudge = nudge,
                formatTime = { df.format(Date(it)) },
            )
        }
    }

    private fun set(transform: (BackupPageState) -> BackupPageState) = synchronized(lock) { state = transform(state) }

    private fun clearPasses() = synchronized(lock) {
        pass1?.fill('\u0000'); pass2?.fill('\u0000'); pass1 = null; pass2 = null
    }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent): PageAction {
        if (route is SettingsRoute.RestorePreview) return previewIntent(route, intent)
        val row = intent.row
        return when {
            row == BackupRows.NOW && intent is RowIntent.Run -> { clearPasses(); set { it.copy(stage = BackupPageState.Stage.CHOOSE_TARGET, error = null, status = null) }; PageAction() }
            row == BackupRows.RESTORE && intent is RowIntent.Run -> { clearPasses(); set { it.copy(stage = BackupPageState.Stage.CHOOSE_SOURCE, error = null, status = null) }; PageAction() }
            row == BackupRows.CANCEL -> { clearPasses(); set { BackupPageState() }; PageAction() }
            row == BackupRows.TO_FILE -> { set { it.copy(stage = BackupPageState.Stage.LOGINS, target = BackupPageState.Target.FILE, includeLogins = true) }; PageAction() }
            row == BackupRows.TO_DOWNLOADS -> { set { it.copy(stage = BackupPageState.Stage.LOGINS, target = BackupPageState.Target.DOWNLOADS, includeLogins = true) }; PageAction() }
            row == BackupRows.INCLUDE_LOGINS && intent is RowIntent.Toggle -> { set { it.copy(includeLogins = intent.on, error = null) }; PageAction() }
            row == BackupRows.PASS1 && intent is RowIntent.Edit -> { synchronized(lock) { pass1?.fill('\u0000'); pass1 = secret(intent.text) }; set { it.copy(error = null) }; PageAction() }
            row == BackupRows.PASS2 && intent is RowIntent.Edit -> { synchronized(lock) { pass2?.fill('\u0000'); pass2 = secret(intent.text) }; set { it.copy(error = null) }; PageAction() }
            row == BackupRows.GO && intent is RowIntent.Run -> startBackup()
            row == BackupRows.FROM_FILE -> { request(DocAction.IMPORT, create = false, name = null); PageAction(reload = false) }
            row == BackupRows.FROM_DOWNLOADS -> {
                val l = withContext(io) { storage.list() }
                synchronized(lock) { listed = l.files }
                set { it.copy(stage = BackupPageState.Stage.RESTORE_LIST, downloadsUnreadable = l.unreadable) }
                PageAction()
            }
            row.key.startsWith(BackupRows.DOWNLOAD_FILE_PREFIX) && (intent is RowIntent.Run || intent is RowIntent.Menu) -> {
                val name = row.key.removePrefix(BackupRows.DOWNLOAD_FILE_PREFIX)
                val f = synchronized(lock) { listed.firstOrNull { it.name == name } } ?: return PageAction.NONE
                val dest = File(restoreDir, "downloads-${tokens.incrementAndGet()}.thebox")
                if (!withContext(io) { storage.copyTo(f.location, dest) }) return PageAction(notice = texts.get(BackupText.FAIL_IO), reload = false)
                openPreview(dest)?.let { PageAction(open = SettingsRoute.RestorePreview(it)) } ?: PageAction(notice = lastPreviewError, reload = false)
            }
            row == BackupRows.WEEKLY && intent is RowIntent.Toggle -> {
                store.update(BackupDomain) { it.copy(weekly = intent.on) }
                scheduler.sync(intent.on)
                PageAction()
            }
            row == BackupRows.PASSPHRASE && intent is RowIntent.Edit -> {
                val chars = intent.text.toCharArray()
                if (chars.isNotEmpty() && chars.size < BACKUP_PASSPHRASE_MIN) return PageAction(notice = texts.get(BackupText.BACKUP_PASS_SHORT, BACKUP_PASSPHRASE_MIN), reload = false)
                val empty = chars.isEmpty()
                when (service.setStoredPassphrase(chars)) {
                    is Outcome.Ok -> PageAction(notice = texts.get(if (empty) BackupText.PASSPHRASE_REMOVED else BackupText.PASSPHRASE_SAVED))
                    is Outcome.Err -> PageAction(notice = texts.get(BackupText.BACKUP_FAILED), reload = false)
                }
            }
            else -> PageAction.NONE
        }
    }

    private fun secret(text: String): CharArray? {
        if (text.isEmpty()) return null
        Redact.registerTransient(text)
        return text.toCharArray()
    }

    /** Validates the passphrase step; null = OK. */
    private fun passProblem(p1: CharArray?, p2: CharArray?): String? = when {
        p1 == null || p1.size < BACKUP_PASSPHRASE_MIN -> texts.get(BackupText.BACKUP_PASS_SHORT, BACKUP_PASSPHRASE_MIN)
        p2 == null || !p1.contentEquals(p2) -> texts.get(BackupText.BACKUP_PASS_MISMATCH)
        else -> null
    }

    private fun startBackup(): PageAction {
        val s = synchronized(lock) { state }
        val pass: CharArray? = if (s.includeLogins) {
            passProblem(pass1, pass2)?.let { msg -> set { it.copy(error = msg) }; return PageAction() }
            pass1!!.copyOf()
        } else {
            null
        }
        clearPasses()
        set { it.copy(stage = BackupPageState.Stage.RUNNING, error = null, status = null) }
        when (s.target) {
            BackupPageState.Target.DOWNLOADS -> scope.launch(io) {
                val name = manualName()
                val result = try {
                    var err: Outcome.Err? = null
                    val stored = storage.write(name) { out -> (service.export(out, pass) as? Outcome.Err)?.let { err = it; throw IOException("export failed") } }
                    if (err == null) stored else null
                } catch (e: IOException) {
                    null
                } finally {
                    pass?.fill('\u0000')
                }
                finishBackup(result?.let { texts.get(BackupText.BACKUP_DONE, (if (it.where == StoredBackup.Where.DOWNLOADS) "Download/TheBox/" else "") + it.name) })
            }
            BackupPageState.Target.FILE -> {
                pendingExportPass?.fill('\u0000')
                pendingExportPass = pass
                request(DocAction.EXPORT, create = true, name = manualName())
            }
        }
        return PageAction()
    }

    private var pendingExportPass: CharArray? = null
    private var lastPreviewError: String? = null

    private fun finishBackup(doneText: String?) {
        if (doneText != null) lastManual.writeText(clock.now().toString())
        set { it.copy(stage = BackupPageState.Stage.IDLE, status = doneText, error = if (doneText == null) texts.get(BackupText.BACKUP_FAILED) else null) }
        _invalidations.tryEmit(SettingsRoute.Backup)
        _invalidations.tryEmit(SettingsRoute.Section(SectionId.BACKUP))
    }

    private fun manualName(): String {
        val c = Calendar.getInstance().apply { timeInMillis = clock.now() }
        return BackupNames.manual(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ---- Document picker (SAF) --------------------------------------------------------------------------------

    private fun request(action: DocAction, create: Boolean, name: String?) {
        synchronized(lock) { docAction = action }
        _requests.value = DocumentRequest(tokens.incrementAndGet(), create, name)
    }

    override fun picked(requestId: Long, uri: String?) {
        if (_requests.value?.id != requestId) return
        _requests.value = null
        val action = synchronized(lock) { docAction.also { docAction = null } }
        if (uri == null) {
            pendingExportPass?.fill('\u0000'); pendingExportPass = null
            set { BackupPageState() }
            _invalidations.tryEmit(SettingsRoute.Backup)
            return
        }
        scope.launch(io) {
            when (action) {
                DocAction.EXPORT -> {
                    val pass = pendingExportPass.also { pendingExportPass = null }
                    val ok = try {
                        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { service.export(it, pass) is Outcome.Ok } ?: false
                    } catch (e: IOException) {
                        false
                    } catch (e: SecurityException) {
                        false
                    } finally {
                        pass?.fill('\u0000')
                    }
                    finishBackup(if (ok) texts.get(BackupText.BACKUP_DONE, Uri.parse(uri).lastPathSegment ?: "") else null)
                }
                DocAction.IMPORT -> {
                    val dest = File(restoreDir, "file-${tokens.incrementAndGet()}.thebox")
                    val copied = try {
                        context.contentResolver.openInputStream(Uri.parse(uri))?.use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } } != null
                    } catch (e: IOException) {
                        false
                    } catch (e: SecurityException) {
                        false
                    }
                    val token = if (copied) openPreview(dest) else null
                    if (token != null) navigate(SettingsRoute.RestorePreview(token))
                    else { set { it.copy(error = lastPreviewError ?: texts.get(BackupText.FAIL_IO)) }; _invalidations.tryEmit(SettingsRoute.Backup) }
                }
                null -> Unit
            }
        }
    }

    private suspend fun navigate(route: SettingsRoute) = withContext(Dispatchers.Main) { navigator.openSettings(route, fromFull = false, detached = false) }

    // ---- Restore preview ----------------------------------------------------------------------------------------

    /** Previews [file]; returns the token of a new RestorePreview route, or null with [lastPreviewError] set. */
    private suspend fun openPreview(file: File, passphrase: CharArray? = null): Long? = when (val p = service.preview(file)) {
        is Outcome.Ok -> {
            val token = tokens.incrementAndGet()
            synchronized(lock) { previews[token] = PreviewEntry(file, p.value).also { it.passphrase = passphrase } }
            token
        }
        is Outcome.Err -> {
            lastPreviewError = BackupPages.failureText(service.previewFailure(p.error), texts)
            file.delete()
            passphrase?.fill('\u0000')
            null
        }
    }

    private fun previewIntent(route: SettingsRoute.RestorePreview, intent: RowIntent): PageAction {
        val e = synchronized(lock) { previews[route.token] } ?: return PageAction()
        return when {
            intent.row == BackupRows.PREVIEW_PASSPHRASE && intent is RowIntent.Edit -> {
                synchronized(lock) { e.passphrase?.fill('\u0000'); e.passphrase = secret(intent.text); e.error = null }
                PageAction()
            }
            intent.row == BackupRows.PREVIEW_MODE && intent is RowIntent.Choose -> {
                synchronized(lock) { e.mode = RestoreMode.entries.firstOrNull { it.name == intent.optionId } ?: RestoreMode.MERGE }
                PageAction()
            }
            intent.row == BackupRows.PREVIEW_GO && intent is RowIntent.Run -> {
                if (e.running) return PageAction.NONE
                synchronized(lock) { e.running = true; e.error = null }
                val pass = e.passphrase?.copyOf()
                scope.launch(io) {
                    val r = service.restore(e.file, pass, e.mode)
                    synchronized(lock) {
                        e.running = false
                        when (r) {
                            is RestoreResult.Done -> {
                                e.result = texts.get(BackupText.RESTORE_DONE, r.playlistsAdded + r.playlistsUpdated, r.rows) +
                                    if (r.needsLogin > 0) " " + texts.get(BackupText.RESTORE_NEEDS_LOGIN, r.needsLogin) else ""
                                e.tone = if (r.needsLogin > 0) Tone.WARN else Tone.GOOD
                                e.passphrase?.fill('\u0000'); e.passphrase = null
                                e.file.delete()
                            }
                            is RestoreResult.Failed -> {
                                val text = BackupPages.failureText(r.reason, texts)
                                // A wrong passphrase can be retyped; other failures end the preview.
                                if (r.reason == RestoreFailure.WRONG_PASSPHRASE) e.error = text else { e.result = text; e.tone = Tone.BAD }
                            }
                        }
                    }
                    _invalidations.tryEmit(route)
                }
                PageAction()
            }
            else -> PageAction.NONE
        }
    }

    // ---- Phone page (RESTORE, BACKUP_DOWNLOAD, SETUP's Restore tab) ---------------------------------------------

    private var pendingPhoneFile: File? = null

    override fun attach(purpose: PhonePurpose) {
        detach()
        if (purpose == PhonePurpose.LOGS_DOWNLOAD) return
        phoneJob = scope.launch(io) {
            phone.submissions.collect { s ->
                when (s) {
                    is PhoneSubmission.BackupUploaded -> onPhoneUpload(File(s.path))
                    is PhoneSubmission.Passphrase -> onPhonePassphrase(purpose, s.value)
                    else -> Unit // setup submissions are WP-B's
                }
            }
        }
    }

    override fun detach() {
        phoneJob?.cancel()
        phoneJob = null
    }

    private suspend fun onPhoneUpload(spooled: File) {
        // Out of cacheDir/phone first: the server deletes its spool when it stops.
        val dest = File(restoreDir, "phone-${tokens.incrementAndGet()}.thebox")
        if (!spooled.renameTo(dest)) { phone.reportStatus(texts.get(BackupText.FAIL_IO), false); return }
        when (val p = service.preview(dest)) {
            is Outcome.Ok -> {
                synchronized(lock) { pendingPhoneFile?.delete(); pendingPhoneFile = dest }
                val c = p.value.header.counts
                val summary = texts.get(BackupText.PREVIEW_SUMMARY, c["fav_lists"] ?: 0, c["favourites"] ?: 0, c["reminders"] ?: 0, c["channel_prefs"] ?: 0, c["settings"] ?: 0)
                if (p.value.needsPassphrase) {
                    phone.reportStatus(summary + " " + texts.get(BackupText.PREVIEW_PASSPHRASE_DESC), false)
                } else {
                    finishPhoneRestore(null)
                }
            }
            is Outcome.Err -> {
                dest.delete()
                phone.reportStatus(BackupPages.failureText(service.previewFailure(p.error), texts), false)
            }
        }
    }

    private suspend fun onPhonePassphrase(purpose: PhonePurpose, value: CharArray) {
        val pending = synchronized(lock) { pendingPhoneFile }
        if (pending != null) { finishPhoneRestore(value.takeIf { it.isNotEmpty() }); return }
        if (purpose != PhonePurpose.BACKUP_DOWNLOAD) { value.fill('\u0000'); return }
        if (value.isNotEmpty() && value.size < BACKUP_PASSPHRASE_MIN) {
            value.fill('\u0000')
            phone.reportStatus(texts.get(BackupText.BACKUP_PASS_SHORT, BACKUP_PASSPHRASE_MIN), false)
            return
        }
        val name = manualName()
        val out = File(outDir, name)
        val ok = try {
            out.outputStream().use { service.export(it, value.takeIf { v -> v.isNotEmpty() }) is Outcome.Ok }
        } catch (e: IOException) {
            false
        } finally {
            value.fill('\u0000')
        }
        if (!ok) { out.delete(); phone.reportStatus(texts.get(BackupText.BACKUP_FAILED), false); return }
        phone.offerDownload(name, out.path, AndroidBackupStorage.MIME)
        lastManual.writeText(clock.now().toString())
        phone.reportStatus(texts.get(BackupText.BACKUP_DONE, name), false)
        _invalidations.tryEmit(SettingsRoute.Backup)
    }

    private suspend fun finishPhoneRestore(passphrase: CharArray?) {
        val file = synchronized(lock) { pendingPhoneFile.also { pendingPhoneFile = null } } ?: return
        val token = openPreview(file, passphrase) ?: run { phone.reportStatus(lastPreviewError ?: texts.get(BackupText.FAIL_IO), false); return }
        phone.reportStatus(texts.get(BackupText.PHONE_RECEIVED), true)
        navigate(SettingsRoute.RestorePreview(token))
        BoxLog.i(TAG) { "phone restore preview ready" }
    }

    private companion object { const val TAG = "BackupPages" }
}
