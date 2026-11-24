package app.thebox.crash

import android.app.Application
import android.content.Context
import app.thebox.BuildConfig
import app.thebox.core.common.Redact
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.ReportingAdministrator
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.plugins.Plugin
import org.acra.plugins.PluginLoader
import org.acra.plugins.ServicePluginLoader
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File

/**
 * ACRA, local only (docs/design/m5-setup-settings.md §1.1 D3, §1.3a; plan §3 "no telemetry"). Before M5 `acra-core`
 * was a dependency but `ACRA.init` was never called, so crashes were not recorded at all.
 *
 * - **No network sender exists.** ACRA's only sender is [LocalFileSender]: [LocalOnlyPluginLoader] answers every request
 *   for a sender with it alone, so a transitive sender plugin can never be picked up. ACRA's own collectors and start-up
 *   processors still come from its ServiceLoader list: ACRA asks the same loader for those, and a loader that knew only
 *   the sender left every report empty (`{}`; review 2026-11-24). [RedactingAdministrator] redacts the stack trace
 *   in the crashing process, where the registered secrets are. The sender writes each report, redacted once more with
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
            pluginLoader = LocalOnlyPluginLoader()
        }
    }

    fun reportDir(context: Context): File = File(context.filesDir, DIR)
}

/**
 * Senders: [LocalFileSenderFactory] and nothing else. Everything else ACRA loads through its plugin loader (the
 * collectors that fill a report, the start-up processors): ACRA's own list. Stateless and serializable, because ACRA
 * passes its configuration to the `:acra` process.
 */
class LocalOnlyPluginLoader : PluginLoader {
    override fun <T : Plugin> load(clazz: Class<T>): List<T> = own(clazz) { ServicePluginLoader().load(clazz) }

    override fun <T : Plugin> loadEnabled(config: CoreConfiguration, clazz: Class<T>): List<T> =
        own(clazz) { ServicePluginLoader().loadEnabled(config, clazz) }

    private fun <T : Plugin> own(clazz: Class<T>, acra: () -> List<T>): List<T> = when {
        clazz.isAssignableFrom(LocalFileSenderFactory::class.java) -> listOf(clazz.cast(LocalFileSenderFactory())!!)
        clazz.isAssignableFrom(RedactingAdministrator::class.java) -> acra() + clazz.cast(RedactingAdministrator())!!
        else -> acra()
    }

    private companion object { private const val serialVersionUID = 1L }
}

/**
 * (Review 2026-11-24) Redacts the stack trace in the crashing process, before ACRA stores the report. The sender runs
 * in the `:acra` process, where no credential was ever registered with [Redact], so its own pass only catches what the
 * patterns catch (query strings, user-info); an exception message that quotes a password or a token path segment was
 * written as it stood. Here the registered secrets of the app's process still exist.
 */
class RedactingAdministrator : ReportingAdministrator {
    override fun shouldSendReport(context: Context, config: CoreConfiguration, crashReportData: CrashReportData): Boolean {
        try {
            crashReportData.getString(ReportField.STACK_TRACE)?.let { crashReportData.put(ReportField.STACK_TRACE, redactTrace(it)) }
        } catch (_: Exception) {
            // The report is still written; the sender's pattern pass runs on it.
        }
        return true
    }

    companion object {
        fun redactTrace(trace: String): String = Redact.text(trace)
    }
}

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
