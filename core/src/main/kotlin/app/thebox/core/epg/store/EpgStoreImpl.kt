package app.thebox.core.epg.store

import androidx.sqlite.SQLiteDriver
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.FreeSpace
import kotlinx.coroutines.flow.StateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Generation-named epg.db engine (docs/design/m1-data-layer.md §2.3–§2.5). Commit-0 stub; WP-B owns this file.
 * When `driver is BundledSQLiteDriver`, readers open with `SQLITE_OPEN_READONLY`; with any other driver
 * (Robolectric's `AndroidSQLiteDriver`) they rely on `PRAGMA query_only=1`. [forceFts4] is a test hook.
 */
@Suppress("unused")
class EpgStoreImpl(
    private val dir: File,
    private val driver: SQLiteDriver,
    private val freeSpace: FreeSpace,
    private val xmlParser: () -> XmlPullParser,
    private val clock: () -> Long,
    private val forceFts4: Boolean = false,
) : EpgStore {
    override val info: StateFlow<EpgGenerationInfo?> get() = TODO("WP-B")

    override fun openReader(): EpgReader? = TODO("WP-B")

    override fun build(
        request: EpgBuildRequest,
        whitelist: EpgWhitelistResolver,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: (EpgProgress) -> Unit,
    ): EpgImportReport = TODO("WP-B")

    override fun patch(
        request: EpgPatchRequest,
        gate: ImportGate,
        cancel: CancelCheck,
        progress: (EpgProgress) -> Unit,
    ): EpgImportReport = TODO("WP-B")

    override fun recover(): Unit = TODO("WP-B")

    override fun clear(): Unit = TODO("WP-B")
}
