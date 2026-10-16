package app.thebox.core.diag

import app.thebox.core.common.Redact
import app.thebox.core.playback.PlaybackEvent
import app.thebox.core.playback.PlaybackEventType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m5-setup-settings.md §3.11.4: a bundle built from logs that contain every secret form must contain none
 * of them; the audit counts what the second pass caught; the settings snapshot prints no string; the size cap drops
 * the oldest log lines first.
 */
class ExportRedactionTest {
    private val user = "zxuser41"
    private val pass = "pw+Q9!x7"
    private val passphrase = "correct horse battery"
    private val pairing = "f00dfacecafe1234"
    private val transient = "tok-9f8e7d6c"

    private fun inputs(logs: List<String>, extra: Collection<String> = listOf(user, pass), crash: List<String> = emptyList(), settings: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = ExportInputs(
        header = listOf("version" to "1.0.3 (3) debug", "installer" to "unknown"),
        deviceFacts = null,
        settings = settings,
        playlists = listOf(ExportPlaylist(1, "XTREAM", "panel.example.com:8080", 2140, "ok", false, true)),
        syncStatus = listOf("idle"),
        refreshReports = (1..15).map { "report $it" },
        events = listOf(PlaybackEvent(1, 2, 3, PlaybackEventType.OPEN, "open", "GET http://h/live/$user/$pass/1.ts")),
        zaps = emptyList(),
        logs = listOf(logs),
        crashReports = crash,
        anrNotes = emptyList(),
        extraSecrets = extra,
    )

    private fun build(i: ExportInputs, max: Int = 1_000_000): Pair<String, ExportSummary> {
        val out = ByteArrayOutputStream()
        val s = ExportBundleBuilder(max).write(i, out)
        return out.toString(Charsets.UTF_8) to s
    }

    @Test
    fun everySecretFormIsRemoved() {
        Redact.registerTransient(passphrase)
        Redact.registerTransient(pairing)
        Redact.registerTransient(transient)
        val logs = listOf(
            "GET http://panel.example.com:8080/player_api.php?username=$user&password=$pass&action=get_live_streams",
            "GET http://panel.example.com:8080/live/$user/$pass/102.ts",
            "GET http://panel.example.com:8080/timeshift/$user/$pass/60/2026-10-16:14-00/102.ts",
            "M3U http://$user:$pass@lists.example.com/list.m3u",
            "form body username=$user&password=$pass",
            "header Authorization: Bearer-abc123def",
            "header Cookie: sid=deadbeef99",
            "phone pairing token $pairing code 123456",
            "passphrase set: $passphrase",
            "segment token=$transient",
            "plain mention of the password $pass in a sentence",
            "user=$user pass=$pass",
            "java.io.IOException: unexpected end of stream on http://panel.example.com:8080/live/$user/$pass/1.ts",
        )
        val crash = listOf("""{"STACK_TRACE":"java.net.SocketTimeoutException at http://h/get.php?username=$user&password=$pass"}""")
        val (text, summary) = build(inputs(logs, crash = crash))
        for (secret in listOf(user, pass, passphrase, pairing, transient, "Bearer-abc123def", "deadbeef99")) {
            assertFalse(text.contains(secret), "\"$secret\" survived:\n$text")
        }
        assertFalse(Regex("""(?i)username=(?!\*)""").containsMatchIn(text), text)
        assertFalse(Regex("""(?i)password=(?!\*)""").containsMatchIn(text), text)
        assertFalse(Regex("""/live/(?!\*\*\*/)""").containsMatchIn(text.replace("/live/****/****/", "/live/***/***/")), text)
        assertTrue(summary.auditHits > 0, "the second pass should have caught the unregistered login")
        assertTrue(text.contains("== Redaction audit: ${summary.auditHits} value(s) replaced by ****"))
    }

    @Test
    fun cleanLogsNeedNoAuditHit() {
        val (text, summary) = build(inputs(listOf("guide opened in 120 ms", "zap 3 → 4 ready 800 ms"), extra = emptyList()))
        assertEquals(0, summary.auditHits, text)
        assertTrue(text.contains("guide opened in 120 ms"))
    }

    @Test
    fun settingsSnapshotPrintsEnumsBooleansAndNumbersOnly() {
        val settings = mapOf(
            "network" to buildJsonObject { put("dns", "CUSTOM"); put("customDohUrl", "https://doh.example.com/dns-query?key=secret"); put("connectTimeoutSec", 10) },
            "playback" to buildJsonObject { put("tunneling", true); put("externalPlayer", "org.videolan.vlc"); put("subtitles", JsonPrimitive("")) },
        )
        val (text, _) = build(inputs(emptyList(), extra = emptyList(), settings = settings))
        assertTrue(text.contains("network.dns = CUSTOM"))
        assertTrue(text.contains("network.customDohUrl = <set>"))
        assertTrue(text.contains("network.connectTimeoutSec = 10"))
        assertTrue(text.contains("playback.tunneling = true"))
        assertTrue(text.contains("playback.externalPlayer = <set>"))
        assertTrue(text.contains("playback.subtitles = <empty>"))
        assertFalse(text.contains("doh.example.com"))
        assertFalse(text.contains("videolan"))
    }

    @Test
    fun sizeCapDropsTheOldestLogLinesFirst() {
        val logs = (1..5_000).map { "line %05d %s".format(it, "x".repeat(80)) }
        val (text, summary) = build(inputs(logs, extra = emptyList()), max = 64 * 1024)
        assertTrue(text.toByteArray().size <= 64 * 1024, "bundle ${text.toByteArray().size} bytes")
        assertTrue(summary.droppedLogLines > 0)
        assertTrue(text.contains("line 05000"))
        assertFalse(text.contains("line 00001 "))
        assertTrue(text.contains("oldest lines dropped"))
    }

    @Test
    fun sectionsAreBoundedAsSpecified() {
        val (text, _) = build(inputs(emptyList(), extra = emptyList(), crash = (1..8).map { "crash $it" }))
        assertTrue(text.contains("report 15"))
        assertFalse(text.lines().contains("report 5"), "only the last 10 refresh reports")
        assertTrue(text.lines().contains("report 6"))
        assertTrue(text.contains("crash 5"))
        assertFalse(text.contains("crash 6"))
        assertTrue(text.contains("#1 XTREAM panel.example.com:8080 channels=2140"))
    }
}
