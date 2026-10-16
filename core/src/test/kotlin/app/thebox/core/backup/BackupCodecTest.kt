package app.thebox.core.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.10.1, §6.1 WP-D `BackupCodecTest`. */
class BackupCodecTest {
    private val header = BackupHeader(createdAtMs = 1, appVersionName = "1.0", appVersionCode = 3, includesLogins = false, counts = mapOf("playlists" to 1))
    private val records = listOf(
        BackupRecord.Settings("guide", JsonObject(mapOf("clock" to JsonPrimitive("H24")))),
        BackupRecord.Playlist("uid1", "Provider A", "XTREAM", "panel:8080", 0, true, mapOf("user_agent" to JsonPrimitive("VLC")), null),
        BackupRecord.ChannelPref("uid1", "k1", mapOf("hidden" to JsonPrimitive(true))),
        BackupRecord.HideRule("uid1", 0, "Sports", isRegex = false, enabled = true),
    )

    private fun file(h: BackupHeader = header, rs: List<BackupRecord> = records): ByteArray {
        val out = ByteArrayOutputStream()
        val w = BackupWriter(out)
        w.writeHeader(h)
        rs.forEach(w::write)
        w.finish()
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray, maxBytes: Long = BACKUP_MAX_BYTES, maxLine: Int = BACKUP_MAX_LINE_BYTES): Pair<BackupReader, List<BackupRecord>> {
        val r = BackupReader(ByteArrayInputStream(bytes), maxBytes, maxLine)
        r.readHeader()
        return r to generateSequence { r.next() }.toList()
    }

    private fun failure(bytes: ByteArray, maxBytes: Long = BACKUP_MAX_BYTES, maxLine: Int = BACKUP_MAX_LINE_BYTES): RestoreFailure =
        assertFailsWith<BackupFormatException> { readAll(bytes, maxBytes, maxLine) }.failure

    @Test
    fun roundTrip() {
        val bytes = file()
        val (r, got) = readAll(bytes)
        assertEquals(header, r.header)
        assertEquals(records, got)
        assertNull(r.next())
        // One LF-terminated line per record plus header and End; the discriminator is `t`.
        val lines = bytes.decodeToString().split('\n')
        assertEquals(records.size + 3, lines.size) // trailing empty after the last LF
        assertTrue(lines[1].startsWith("""{"t":"settings""""))
        assertEquals("""{"t":"end","records":4}""", lines[records.size + 1])
    }

    @Test
    fun headerBytesAreTheExactFirstLine() {
        val bytes = file()
        val (r, _) = readAll(bytes)
        assertContentEquals(bytes.decodeToString().substringBefore('\n').encodeToByteArray(), r.headerBytes)
    }

    @Test
    fun truncatedFilesAreDetected() {
        val text = file().decodeToString()
        assertEquals(RestoreFailure.TRUNCATED, failure(text.substringBeforeLast("{\"t\":\"end\"").encodeToByteArray()))
        val wrongEnd = text.replace("\"records\":4", "\"records\":7")
        assertEquals(RestoreFailure.TRUNCATED, failure(wrongEnd.encodeToByteArray()))
        val cut = text.substring(0, text.length / 2)
        assertEquals(RestoreFailure.TRUNCATED, failure(cut.encodeToByteArray()))
    }

    @Test
    fun unknownRecordTypesAreSkippedButCounted() {
        val text = file().decodeToString().lines().toMutableList()
        text.add(2, """{"t":"future_thing","x":1}""")
        val fixed = text.joinToString("\n").replace("\"records\":4", "\"records\":5")
        val (r, got) = readAll(fixed.encodeToByteArray())
        assertEquals(records, got)
        assertEquals(1, r.skipped)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = file().decodeToString().replace("""{"t":"hide_rule",""", """{"t":"hide_rule","colour":"red",""")
        assertEquals(records, readAll(text.encodeToByteArray()).second)
    }

    @Test
    fun newerSchemaIsRefused() {
        assertEquals(RestoreFailure.NEWER_SCHEMA, failure(file(header.copy(schema = BackupHeader.SCHEMA + 1))))
        // A newer minor is fine.
        assertEquals(records, readAll(file(header.copy(minor = 9))).second)
    }

    @Test
    fun notABackup() {
        assertEquals(RestoreFailure.NOT_A_BACKUP, failure(ByteArray(0)))
        assertEquals(RestoreFailure.NOT_A_BACKUP, failure("#EXTM3U\n#EXTINF:-1,x\n".encodeToByteArray()))
        assertEquals(RestoreFailure.NOT_A_BACKUP, failure("""{"format":"other","schema":1}""".encodeToByteArray()))
        assertEquals(RestoreFailure.NOT_A_BACKUP, failure(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, '\n'.code.toByte())))
        assertEquals(RestoreFailure.NOT_A_BACKUP, failure(ByteArray(5000) { 'a'.code.toByte() }, maxLine = 1000))
    }

    @Test
    fun lineAndFileLimits() {
        val long = records + BackupRecord.Playlist("u2", "x".repeat(5_000), "M3U_URL", "h", 1, true, emptyMap(), null)
        assertEquals(RestoreFailure.TOO_LARGE, failure(file(rs = long), maxLine = 2_000))
        val bytes = file()
        assertEquals(RestoreFailure.TOO_LARGE, failure(bytes, maxBytes = bytes.size - 10L))
    }

    @Test
    fun secretsRecordRoundTrips() {
        val s = BackupRecord.Secrets(BACKUP_KDF_NAME, 50_000, "c2FsdA==", "bm9uY2U=", "Y3Q=")
        assertEquals(listOf<BackupRecord>(s), readAll(file(rs = listOf(s))).second)
    }
}
