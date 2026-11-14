package app.thebox.core.sports

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * (CHL §7.4, S8; WP-3 fix pass S3) [ChlLiveGate] on the fake panel's recordings of its featured game (one per league,
 * `core/src/test/resources/sports/chl/live-gate/`, written in `record_live.py`'s format by `tools/fakepanel/test_sports_chl.py`),
 * two doctored recordings it must fail, and, when `CHL_LIVE_RECORDINGS` names a folder, every real recording in it.
 */
class ChlLiveGateTest {
    private fun fixture(name: String): File =
        File(requireNotNull(javaClass.classLoader.getResource("sports/chl/live-gate/$name")) { "missing $name" }.toURI())

    @Test fun theFakePanelGamesPassTheGate() {
        val games = listOf("fake-ohl", "fake-whl", "fake-lhjmq").map { ChlLiveGate.game(fixture(it)) }
        val v = ChlLiveGate.verdict(games)
        println(v.text)
        assertTrue(v.pass, v.text)
        for (g in games) {
            assertTrue(g.modelPpS > 100, "${g.league}: the scenario plays power plays")
            assertTrue(g.matchedS > 0, "${g.league}: bars were shown")
        }
    }

    /** A derivation that hides everything must fail on coverage (review S3 (a)). */
    @Test fun alwaysUnknownFails() {
        val dir = doctored("fake-ohl") { i, last, line ->
            if (last) line else line.replace("\"penalties\": 200", "\"penalties\": 503")
        }
        val v = ChlLiveGate.verdict(listOf(ChlLiveGate.game(dir)))
        assertFalse(v.pass, v.text)
        assertTrue(v.text.contains("hidden + missing"), v.text)
    }

    /** Penalties recorded on the wrong side while live (fixed only in the final data) must fail on wrong bars. */
    @Test fun aWrongSideFails() {
        val dir = doctored("fake-ohl") { _, last, line ->
            if (last) line else line.replace("\"Home\": 0", "\"Home\": 2").replace("\"Home\": 1", "\"Home\": 0").replace("\"Home\": 2", "\"Home\": 1")
        }
        val v = ChlLiveGate.verdict(listOf(ChlLiveGate.game(dir)))
        assertFalse(v.pass, v.text)
        assertTrue(v.text.contains("FAIL: wrong"), v.text)
    }

    /** Real recordings: `CHL_LIVE_RECORDINGS=<folder> ./gradlew :core:test --tests '*ChlLiveGateTest*' -i` (tools/chl/README.md). */
    @Test fun recordedGamesPassTheGate() {
        val root = System.getenv("CHL_LIVE_RECORDINGS")
        assumeTrue(root != null, "set CHL_LIVE_RECORDINGS to a folder of record_live.py recordings")
        val dirs = ChlLiveGate.recordings(File(root!!))
        assertTrue(dirs.isNotEmpty(), "no recordings under $root")
        val v = ChlLiveGate.verdict(dirs.map(ChlLiveGate::game))
        println(v.text)
        assertTrue(v.pass, v.text)
    }

    /** A copy of a fixture recording with every poll line passed through [edit] (the last one flagged). */
    private fun doctored(name: String, edit: (Int, Boolean, String) -> String): File {
        val src = fixture(name)
        val dst = Files.createTempDirectory("chl-gate").toFile().also { it.deleteOnExit() }
        for (f in src.listFiles().orEmpty()) f.copyTo(File(dst, f.name))
        val lines = File(src, "polls.jsonl").readLines().filter { it.isNotBlank() }
        // The final data stays the original: one more poll at the end carries the untouched last penalties and goals.
        val json = kotlinx.serialization.json.Json
        val latest = HashMap<String, kotlinx.serialization.json.JsonElement>()
        var unix = 0.0
        for (l in lines) {
            val o = json.parseToJsonElement(l) as kotlinx.serialization.json.JsonObject
            (o["nodes"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) -> latest[k] = v }
            unix = (o["unix"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        }
        val tail = kotlinx.serialization.json.buildJsonObject {
            put("unix", kotlinx.serialization.json.JsonPrimitive(unix + 5))
            put("status", kotlinx.serialization.json.JsonObject(emptyMap()))
            put("nodes", kotlinx.serialization.json.JsonObject(mapOf("penalties" to latest.getValue("penalties"), "goals" to latest.getValue("goals"))))
        }
        val edited = lines.mapIndexed { i, l -> edit(i, false, l) } + tail.toString()
        File(dst, "polls.jsonl").writeText(edited.joinToString("\n"))
        return dst
    }
}
