package app.thebox.crash

import android.app.Application
import android.content.Context
import app.thebox.BuildConfig
import app.thebox.core.common.Redact
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.plugins.SimplePluginLoader
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File

/**
 * ACRA, local only (docs/design/m5-setup-settings.md §1.1 D3, §1.3a; plan §3 "no telemetry"). Before M5 `acra-core`
 * was a dependency but `ACRA.init` was never called, so crashes were not recorded at all.
 *
 * - **No network sender exists.** ACRA's only sender is [LocalFileSender], loaded through a [SimplePluginLoader] (not the
 *   ServiceLoader, so a transitive sender plugin can never be picked up). It writes each report, redacted with
 *   [Redact.text], to `filesDir/acra/<epoch-ms>.json` and keeps the newest [KEEP] files; WP-E's log export reads them.
 * - **No logcat, device id, shared preferences or dumpsys** in a report ([FIELDS]): logcat carries Media3 and system
 *   lines that never went through `BoxLog`'s redaction.
 * - ACRA hands the stored report to its sender in the `:acra` process (`JobSenderService`, declared by ACRA's manifest);
 *   `TheBoxApplication` returns early there ([isSenderProcess]) after calling [installLocalOnly].
 */
object CrashConfig {
    const val DIR = "acra"
    const val KEEP = 10

    val FIELDS: List<ReportField> = listOf(
        ReportField.REPORT_ID, ReportField.APP_VERSION_CODE, ReportField.APP_VERSION_NAME, ReportField.PACKAGE_NAME,
        ReportField.PHONE_MODEL, ReportField.BRAND, ReportField.PRODUCT, ReportField.ANDROID_VERSION, ReportField.BUILD,
        ReportField.TOTAL_MEM_SIZE, ReportField.AVAILABLE_MEM_SIZE, ReportField.IS_SILENT, ReportField.STACK_TRACE,
        ReportField.USER_APP_START_DATE, ReportField.USER_CRASH_DATE, ReportField.THREAD_DETAILS,
    )

    fun isSenderProcess(): Boolean = ACRA.isACRASenderServiceProcess()

    /** Idempotent enough for `Application.onCreate` (ACRA warns on a second init; we never call it twice). */
    fun installLocalOnly(app: Application) {
        app.initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON
            reportContent = FIELDS
            pluginLoader = SimplePluginLoader(LocalFileSenderFactory::class.java)
        }
    }

    fun reportDir(context: Context): File = File(context.filesDir, DIR)
}

/** Instantiated by ACRA's [SimplePluginLoader] (needs a public no-arg constructor). */
class LocalFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender = LocalFileSender()
}

/** Writes the report to `filesDir/acra`; never sends anything anywhere. */
class LocalFileSender : ReportSender {
    override fun send(context: Context, errorContent: CrashReportData) {
        val dir = CrashConfig.reportDir(context)
        dir.mkdirs()
        File(dir, "${System.currentTimeMillis()}.json").writeText(Redact.text(errorContent.toJSON()))
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(CrashConfig.KEEP)
            ?.forEach { it.delete() }
    }
}
