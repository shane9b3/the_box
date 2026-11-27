package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.diag.ExportBundleBuilder
import app.thebox.core.diag.ExportInputs
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.hub.ChlHubFixtures
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * (CHL hub P2, docs/design/m6-chl-hub.md §4.1, D6) Every request the hub's [ChlDetailSource] makes (`gc` preview and
     * gamesummary, `topgoalies`, both `statviewfeed` tables, per league), sent with the **real** keys to a local scripted
     * server that echoes the key in `Parameters` and refuses some reads: neither the source's log lines nor an export that
     * also carries each full request URL (as a log line and in a stack trace) and each answer's `toString` holds a key.
     */
    @Test fun noKeyReachesTheExportFromAnyHubRequest() = runBlocking {
        val server = MockWebServer()
        val urls = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.url
                urls += u.toString()
                val key = u.queryParameter("key").orEmpty()
                val echo = "\"Parameters\":{\"key\":\"$key\",\"client_code\":\"${u.queryParameter("client_code")}\"}"
                val body = when (u.queryParameter("feed")) {
                    "gc" -> if (u.queryParameter("tab") == "preview") {
                        """{"GC":{$echo,"Preview":{"visitor_team":{"team_id":"20"},"home_team":{"team_id":"14"},"current_season":{"id":"88","playoff":"0"}}}}"""
                    } else {
                        """{"GC":{$echo,"Gamesummary":{}}}"""
                    }
                    "modulekit" -> if (u.queryParameter("team_id") == "20") "Invalid key." else """{"SiteKit":{$echo,"Statviewtype":[]}}"""
                    else -> if (u.queryParameter("special") == "true") "<html>$key</html>" else "([{\"sections\":[]}])"
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val lines = CopyOnWriteArrayList<String>()
        val old = BoxLog.sink
        BoxLog.sink = BoxLog.Sink { _, tag, message, _ -> lines += "$tag $message" }
        val answers = ArrayList<String>()
        try {
            val endpoints = HockeyTechEndpoints(server.url("/feed/index.php").toString(), "unused", "https://assets.leaguestat.com/", HockeyTechKeys::of)
            val source = ChlDetailSource(
                okhttp3.OkHttpClient(), app.thebox.core.playback.MonoClock { 0L }, { endpoints },
                mapPreview = { id, _, _, _, _, _, _ -> ChlHubFixtures.preOhl29246().copy(id = id) },
                mapSummary = { _, _, _ -> null },
                mapRanks = { _, _ -> null },
            )
            for (l in listOf(League.OHL, League.WHL, League.QMJHL)) {
                answers += source.detailAnswer(GameId(l, "29246"), GamePhase.PRE).toString()
                answers += source.detailAnswer(GameId(l, "29239"), GamePhase.FINAL).toString()
            }
        } finally {
            BoxLog.sink = old
            server.close()
        }
        val keys = listOf(HockeyTechKeys.OHL, HockeyTechKeys.WHL, HockeyTechKeys.QMJHL)
        assertEquals("5 pre-game + 1 final per league", 18, urls.size)
        assertTrue("the real keys were sent", keys.all { k -> urls.any { it.contains(k) } })
        for (l in lines) for (k in keys) assertFalse("a log line carries a key", l.contains(k))
        val inputs = ExportInputs(
            header = listOf("version" to "test"),
            deviceFacts = null,
            settings = emptyMap(),
            playlists = emptyList(),
            syncStatus = emptyList(),
            refreshReports = emptyList(),
            events = emptyList(),
            zaps = emptyList(),
            logs = listOf(lines + urls + answers),
            crashReports = urls.map { """{"STACK_TRACE":"java.io.IOException: unexpected end of stream on $it\n\tat app.thebox.data.sports.HockeyTechHttp.call(HockeyTechHttp.kt:1)"}""" },
            anrNotes = emptyList(),
            extraSecrets = emptyList(),
        )
        val out = ByteArrayOutputStream()
        ExportBundleBuilder(4_000_000).write(inputs, out)
        val text = out.toString(Charsets.UTF_8)
        for (k in keys) assertFalse("a key survived", text.contains(k))
    }
}
