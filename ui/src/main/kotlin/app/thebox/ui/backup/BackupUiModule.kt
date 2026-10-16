package app.thebox.ui.backup

import android.app.Activity
import android.content.Intent
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.DocumentRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** WP-D's `:ui` bindings: the strings of WP-D's pages (`strings_backup.xml`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupUiModule {
    @Binds abstract fun backupTexts(impl: BackupTextsImpl): BackupTexts
}

/**
 * The SAF intents for a [DocumentRequest] (§3.10.5; offered only when DocumentsUI was detected, F17). `:ui` has no
 * activity-result dependency, so the activity (MainActivity) launches [intentFor] with `startActivityForResult` or an
 * `ActivityResultLauncher` and hands [resultUri] to `DocumentPicker.picked(request.id, uri)`.
 */
object BackupDocuments {
    const val MIME = "application/octet-stream"

    fun intentFor(request: DocumentRequest): Intent =
        if (request.create) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(MIME)
                .apply { request.suggestedName?.let { putExtra(Intent.EXTRA_TITLE, it) } }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        }

    fun resultUri(resultCode: Int, data: Intent?): String? = if (resultCode == Activity.RESULT_OK) data?.data?.toString() else null
}
