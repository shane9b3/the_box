package app.thebox.data.backup

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupService
import app.thebox.core.backup.RestoreFailure
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.thebox` backup and restore (docs/design/m5-setup-settings.md §2.8, §3.10). **Commit-0 stub (WP-D replaces the
 * body):** export and the weekly backup answer Unsupported; preview and restore refuse the file as NOT_A_BACKUP.
 */
@Singleton
class BackupServiceImpl @Inject constructor() : BackupService {
    private fun <T> notYet(): Outcome<T> = Outcome.Err(SourceError.Unsupported("backup arrives with WP-D"))

    override suspend fun export(sink: OutputStream, passphrase: CharArray?): Outcome<BackupHeader> {
        passphrase?.fill('\u0000')
        return notYet()
    }
    override suspend fun preview(file: File): Outcome<RestorePreview> = notYet()
    override suspend fun restore(file: File, passphrase: CharArray?, mode: RestoreMode): RestoreResult {
        passphrase?.fill('\u0000')
        return RestoreResult.Failed(RestoreFailure.NOT_A_BACKUP)
    }
    override suspend fun autoBackup(nowMs: Long): Outcome<String> = notYet()
}
