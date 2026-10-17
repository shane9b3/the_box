package app.thebox.data.backup

import android.content.Context
import app.thebox.core.backup.AutoBackupRetention
import app.thebox.core.backup.BACKUP_KDF_ITERATIONS
import app.thebox.core.backup.BackupCrypto
import app.thebox.core.backup.BackupDomain
import app.thebox.core.backup.BackupFormatException
import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupNames
import app.thebox.core.backup.BackupService
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.Redact
import app.thebox.core.common.SourceError
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.work.ImportMutex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** What a restore triggers after its commit (§3.10.4 step 5); `:data`'s default refreshes and recomputes. */
interface RestoreFollowUp {
    suspend fun afterRestore(newOrRelogin: List<Long>, affected: List<Long>)
}

/**
 * `.thebox` backup and restore (docs/design/m5-setup-settings.md §2.8, §3.10): export streams through
 * [BackupExporter]; preview/restore run [BackupRestorer]'s passes; the weekly backup writes through [BackupStorage]
 * and keeps the newest [app.thebox.core.backup.AUTO_BACKUP_KEEP] files **it wrote** (an index in app storage), never
 * other files. Logins in weekly backups only with the stored passphrase (secure blob `backup-passphrase`); after a
 * Keystore reset that blob is gone and weekly backups continue without logins ([passphraseLost] says so).
 */
@Singleton
class BackupServiceImpl internal constructor(
    private val db: LibraryDatabase,
    private val creds: CredentialStore,
    private val blobs: SecureBlobStore,
    private val store: SettingsStore,
    private val importMutex: ImportMutex,
    private val storage: BackupStorage,
    private val env: BackupEnvironment,
    private val followUp: RestoreFollowUp,
    private val clock: WallClock,
    private val io: CoroutineDispatcher,
    private val stateDir: File,
    private val kdfIterations: Int,
) : BackupService {
    @Inject constructor(
        db: LibraryDatabase,
        creds: CredentialStore,
        blobs: SecureBlobStore,
        store: SettingsStore,
        importMutex: ImportMutex,
        storage: BackupStorage,
        env: BackupEnvironment,
        followUp: RestoreFollowUp,
        clock: WallClock,
        @IoDispatcher io: CoroutineDispatcher,
        @ApplicationContext context: Context,
    ) : this(db, creds, blobs, store, importMutex, storage, env, followUp, clock, io, File(context.filesDir, "backups"), BACKUP_KDF_ITERATIONS)

    private val exporter get() = BackupExporter(db, creds, store, env, clock::now, kdfIterations)
    private val restorer get() = BackupRestorer(db, creds, store, importMutex, clock::now)
    private val index = AutoIndex(File(stateDir, "auto-index.txt"))
    private val lostFlag = File(stateDir, "passphrase-lost")

    /** C-S8: what the last successful [restore] carried in locks (the page offers a PIN when none exists). */
    class RestoreLocks(val uids: Set<String>, val groups: Int)

    @Volatile var lastRestoreLocks: RestoreLocks? = null
        private set

    /** The stored passphrase was wiped by a Keystore reset (the Backup page says weekly backups lost their logins). */
    val passphraseLost: Boolean get() = lostFlag.exists()

    override suspend fun export(sink: OutputStream, passphrase: CharArray?): Outcome<BackupHeader> = withContext(io) {
        try {
            exporter.export(sink, passphrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // D-L3: SQLite and crypto failures too (callers run on appScope, which has no handler).
            BoxLog.w(TAG, "export failed: ${e.javaClass.simpleName}")
            Outcome.Err(SourceError.Internal("write failed"))
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    override suspend fun preview(file: File): Outcome<RestorePreview> = withContext(io) {
        try {
            Outcome.Ok(restorer.preview(file))
        } catch (e: BackupFormatException) {
            Outcome.Err(SourceError.Parse(e.failure.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // D-L3: an IO, SQLite or other failure is an IO failure with its reason and next step.
            BoxLog.w(TAG, "preview failed: ${e.javaClass.simpleName}")
            Outcome.Err(SourceError.Parse(RestoreFailure.IO.name))
        }
    }

    override suspend fun restore(file: File, passphrase: CharArray?, mode: RestoreMode): RestoreResult = withContext(io) {
        try {
            val (result, applied) = restorer.restore(file, passphrase, mode)
            lastRestoreLocks = applied?.let { RestoreLocks(it.lockedUids, it.lockedGroups) }
            if (applied != null) {
                store.update(SetupDomain) { it.copy(completed = true) }
                try {
                    followUp.afterRestore((applied.newPlaylists + applied.reloginPlaylists).distinct(), applied.affected)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.e(TAG, "restore follow-up failed (the data is restored)", e)
                }
            }
            result
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    /** The failure a preview error stands for (the page shows its reason and next step). */
    fun previewFailure(err: SourceError): RestoreFailure =
        (err as? SourceError.Parse)?.let { p -> RestoreFailure.entries.firstOrNull { it.name == p.detail } } ?: RestoreFailure.IO

    override suspend fun autoBackup(nowMs: Long): Outcome<String> = withContext(io) {
        val prefs = store.flow(BackupDomain).value
        val pass = if (prefs.passphraseStored) storedPassphrase() else null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val name = BackupNames.auto(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        try {
            val stored = importMutex.withLock {
                storage.write(name) { out ->
                    when (val r = exporter.export(out, pass)) {
                        is Outcome.Ok -> Unit
                        is Outcome.Err -> throw IOException(r.error.code)
                    }
                }
            }
            index.add(AutoBackupRetention.Written(stored.location, nowMs))
            for (old in AutoBackupRetention.toDelete(index.read().filter { it.location != stored.location } + AutoBackupRetention.Written(stored.location, nowMs))) {
                storage.delete(old.location)
                index.remove(old.location)
            }
            store.update(BackupDomain) { it.copy(lastAutoAtMs = nowMs, lastAutoFile = stored.name) }
            BoxLog.i(TAG) { "weekly backup written (${stored.where}), logins=${pass != null}" }
            Outcome.Ok(stored.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BoxLog.w(TAG, "weekly backup failed: ${e.javaClass.simpleName}")
            Outcome.Err(SourceError.Internal("weekly backup failed"))
        } finally {
            pass?.fill('\u0000')
        }
    }

    /** Stores the weekly backups' passphrase (≥ BACKUP_PASSPHRASE_MIN, checked by the page); empty removes it. */
    suspend fun setStoredPassphrase(passphrase: CharArray): Outcome<Unit> = withContext(io) {
        try {
            if (passphrase.isEmpty()) {
                blobs.deleteBlob(SecureBlobStore.REF_BACKUP_PASSPHRASE)
                store.update(BackupDomain) { it.copy(passphraseStored = false) }
                return@withContext Outcome.Ok(Unit)
            }
            val bytes = BackupCrypto.utf8(passphrase)
            try {
                when (val r = blobs.putBlob(SecureBlobStore.REF_BACKUP_PASSPHRASE, bytes)) {
                    is Outcome.Ok -> {
                        store.update(BackupDomain) { it.copy(passphraseStored = true) }
                        lostFlag.delete()
                        Outcome.Ok(Unit)
                    }
                    is Outcome.Err -> r
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun storedPassphrase(): CharArray? = when (val r = blobs.getBlob(SecureBlobStore.REF_BACKUP_PASSPHRASE)) {
        is Outcome.Ok -> {
            val cb = Charsets.UTF_8.decode(ByteBuffer.wrap(r.value))
            val chars = CharArray(cb.remaining()).also { cb.get(it) }
            r.value.fill(0)
            if (cb.hasArray()) cb.array().fill('\u0000')
            Redact.registerTransient(String(chars))
            chars
        }
        is Outcome.Err -> {
            // Gone (Keystore reset wiped every secret, or the row is missing): continue without logins and say so.
            BoxLog.w(TAG, "stored backup passphrase unavailable (${r.error.code}); weekly backup without logins")
            if (r.error != SourceError.Credentials(app.thebox.core.common.CredentialFailure.UNAVAILABLE)) {
                store.update(BackupDomain) { it.copy(passphraseStored = false) }
                stateDir.mkdirs()
                lostFlag.createNewFile()
            }
            null
        }
    }

    /** Locations of the automatic backups this app wrote, one `createdAtMs<TAB>location` per line. */
    private class AutoIndex(private val file: File) {
        @Synchronized fun read(): List<AutoBackupRetention.Written> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
            val tab = l.indexOf('\t')
            if (tab <= 0) null else l.substring(0, tab).toLongOrNull()?.let { AutoBackupRetention.Written(l.substring(tab + 1), it) }
        }
        @Synchronized fun add(w: AutoBackupRetention.Written) {
            file.parentFile?.mkdirs()
            val rest = read().filter { it.location != w.location }
            file.writeText((rest + w).joinToString("") { "${it.createdAtMs}\t${it.location}\n" })
        }
        @Synchronized fun remove(location: String) {
            file.writeText(read().filter { it.location != location }.joinToString("") { "${it.createdAtMs}\t${it.location}\n" })
        }
    }

    private companion object { const val TAG = "BackupService" }
}
