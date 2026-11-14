package app.thebox.data.sports

import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportInputs
import app.thebox.core.sports.League
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * (CHL §2.2, §7.2, L6) The diagnostics export never carries a HockeyTech key: the real keys (read from [HockeyTechKeys],
 * never written out here), registered by loading the source class, in a log line, a full `lscluster` URL, an echoed body
 * excerpt and a crash stack trace. Nothing is passed as an extra secret: the registration alone must do it.
 */
class HockeyTechExportRedactionTest {
    @Test fun noKeyReachesTheExport() {
        // Constructing a source loads the class; its companion registers the keys.
        HockeyTechScoreSource(League.OHL, okhttp3.OkHttpClient(), app.thebox.core.playback.MonoClock { 0L })
        val keys = listOf(HockeyTechKeys.OHL, HockeyTechKeys.WHL, HockeyTechKeys.QMJHL)
        val logs = keys.flatMap { k ->
            listOf(
                "ChlScores OHL board → ok ($k)",
                "${HockeyTechEndpoints.FEED_URL}?feed=modulekit&view=seasons&key=$k&client_code=whl&fmt=json&lang=en",
                """JsonDecodingException: Unexpected JSON token at offset 77: {"SiteKit":{"Parameters":{"key":"$k"""",
            )
        }
        val crash = keys.map { k ->
            """{"STACK_TRACE":"java.lang.IllegalStateException: body $k\n\tat app.thebox.data.sports.HockeyTechScoreSource.read(HockeyTechScoreSource.kt:1)\nCaused by: java.io.IOException: ${HockeyTechEndpoints.FEED_URL}?key=$k"}"""
        }
        val inputs = ExportInputs(
            header = listOf("version" to "test"),
            deviceFacts = null,
            settings = emptyMap(),
            playlists = emptyList(),
            syncStatus = emptyList(),
            refreshReports = emptyList(),
            events = emptyList(),
            zaps = emptyList(),
            logs = listOf(logs),
            crashReports = crash,
            anrNotes = emptyList(),
            extraSecrets = emptyList(),
        )
        val out = ByteArrayOutputStream()
        ExportBundleBuilder(1_000_000).write(inputs, out)
        val text = out.toString(Charsets.UTF_8)
        for (k in keys) assertFalse("a key survived:\n$text", text.contains(k))
    }
}
