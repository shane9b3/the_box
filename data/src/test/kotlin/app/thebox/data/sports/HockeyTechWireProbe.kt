package app.thebox.data.sports

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * (WP-3 fix pass, lead ruling S2) An **opt-in** probe of what one followed CHL poll reports to the meter against the real
 * Firebase host: two polls of the six nodes of a game (the second conditional), counted exactly as
 * `HockeyTechScoreSource` counts them (body bytes plus [HockeyTechScoreSource.headerBytes]). No key is involved (Firebase
 * has none); the client sends the app's default UA. Off unless `CHL_FIREBASE_PROBE=ohl/29191` (client/game) is set:
 *
 *     CHL_FIREBASE_PROBE=ohl/29191 ./gradlew :data:testDebugUnitTest --tests '*HockeyTechWireProbe*' -i | grep 'probe'
 */
class HockeyTechWireProbe {
    @Test fun bytesPerPollAsReportedToTheMeter() {
        val spec = System.getenv("CHL_FIREBASE_PROBE")
        assumeTrue("set CHL_FIREBASE_PROBE=client/gameId to run", spec != null)
        val (client, game) = spec!!.split('/')
        val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        val nodes = listOf("runningclock/games", "goalssummary/1/games", "publishedclock/1/games", "shotssummary/1/games",
            "penalties/1/games", "goals/1/games")
        val etags = HashMap<String, String>()
        for (poll in 1..2) {
            var total = 0L
            val parts = ArrayList<String>()
            for (n in nodes) {
                val b = Request.Builder().url("${HockeyTechEndpoints.FIREBASE_BASE}$client/$n/$game.json")
                    .header("Accept", "application/json").header("User-Agent", "TheBox/1.0 (Android TV)")
                    .header("X-Firebase-ETag", "true")
                etags[n]?.let { b.header("If-None-Match", it) }
                http.newCall(b.build()).execute().use { res ->
                    val body = res.body.bytes().size.toLong()
                    res.header("ETag")?.let { etags[n] = it }
                    val headers = HockeyTechScoreSource.headerBytes(res)
                    total += body + headers
                    parts += "${n.substringBefore('/')} ${res.code} body $body + headers $headers"
                }
            }
            println("probe poll $poll: $total B (${parts.joinToString("; ")})")
        }
    }
}
