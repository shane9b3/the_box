package app.thebox.core.backup.fakes

import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupService
import app.thebox.core.backup.RestoreMode
import app.thebox.core.backup.RestorePreview
import app.thebox.core.backup.RestoreResult
import app.thebox.core.common.Outcome
import java.io.File
import java.io.OutputStream

/** [BackupService] with scripted answers for page and machine tests (WP-D); records calls, zeroes passphrases. */
class FakeBackupService(
    var header: BackupHeader = BackupHeader(createdAtMs = 0, appVersionName = "test", appVersionCode = 1, includesLogins = false, counts = emptyMap()),
) : BackupService {
    var previewResult: Outcome<RestorePreview> = Outcome.Ok(RestorePreview(header, emptyList(), needsPassphrase = false, problems = emptyList()))
    var restoreResult: RestoreResult = RestoreResult.Done(0, 0, 0, 0, 0)
    val calls = ArrayList<String>()

    override suspend fun export(sink: OutputStream, passphrase: CharArray?): Outcome<BackupHeader> {
        calls += "export(logins=${passphrase != null})"
        passphrase?.fill('\u0000')
        sink.write("{\"format\":\"${BackupHeader.FORMAT}\"}\n".encodeToByteArray())
        return Outcome.Ok(header.copy(includesLogins = passphrase != null))
    }
    override suspend fun preview(file: File): Outcome<RestorePreview> { calls += "preview(${file.name})"; return previewResult }
    override suspend fun restore(file: File, passphrase: CharArray?, mode: RestoreMode): RestoreResult {
        calls += "restore(${file.name},$mode)"
        passphrase?.fill('\u0000')
        return restoreResult
    }
    override suspend fun autoBackup(nowMs: Long): Outcome<String> { calls += "autoBackup($nowMs)"; return Outcome.Ok("TheBox-backup-auto.thebox") }
}
