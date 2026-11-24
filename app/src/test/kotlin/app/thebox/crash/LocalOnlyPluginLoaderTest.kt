package app.thebox.crash

import org.acra.collector.Collector
import org.acra.collector.StacktraceCollector
import org.acra.plugins.Plugin
import org.acra.sender.ReportSenderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

/** Review 2026-11-24: a loader that knew only the sender gave ACRA no collectors, and every crash report was `{}`. */
class LocalOnlyPluginLoaderTest {
    private val loader = LocalOnlyPluginLoader()

    @Test
    fun theOnlySenderIsTheLocalFile() {
        assertEquals(listOf(LocalFileSenderFactory::class.java), loader.load(ReportSenderFactory::class.java).map { it.javaClass })
    }

    @Test
    fun acraStillGetsItsCollectors() {
        val collectors = loader.load(Collector::class.java)
        assertTrue(collectors.any { it is StacktraceCollector })
        assertTrue(collectors.none { it is ReportSenderFactory })
    }

    @Test
    fun aRequestForEveryPluginHoldsNoOtherSender() {
        assertTrue(loader.load(Plugin::class.java).filterIsInstance<ReportSenderFactory>().all { it is LocalFileSenderFactory })
    }

    @Test
    fun itTravelsToTheSenderProcess() {
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(loader) }
    }

    @Test
    fun theStackTraceIsRedactedBeforeTheReportIsStored() {
        val admins = loader.load(org.acra.config.ReportingAdministrator::class.java)
        assertTrue(admins.any { it is RedactingAdministrator })
        app.thebox.core.common.Redact.registerSecret("hunter2secret")
        val stored = RedactingAdministrator.redactTrace("java.io.IOException: 403 for /live/someone/hunter2secret/12.ts")
        assertTrue(stored, "hunter2secret" !in stored)
        assertTrue(stored, stored.startsWith("java.io.IOException"))
    }
}
