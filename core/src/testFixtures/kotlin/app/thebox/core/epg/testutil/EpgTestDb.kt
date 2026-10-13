package app.thebox.core.epg.testutil

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.thebox.core.epg.FreeSpace
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * Helpers that build XMLTV inputs and inspect epg.db generations in tests (docs/design/m1-data-layer.md §1.4).
 * Shared with `:data` tests through `testFixtures(project(":core"))`, read-only for WP-D.
 *
 * It deliberately does not construct `EpgStoreImpl` (its constructor takes an `XmlPullParser` factory,
 * which is compile-only in `:core`): tests pass `::KXmlParser` (JVM) or `Xml::newPullParser` themselves.
 */
object EpgTestDb {
    data class ProgrammeRow(
        val sourceId: Long,
        val xmltvId: String,
        val startMin: Int,
        val endMin: Int,
        val title: String,
        val flags: Int,
        val catchupId: String?,
    )

    data class ChannelRow(
        val id: Long,
        val sourceId: Long,
        val xmltvId: String,
        val displayName: String,
        val imported: Boolean,
        val programmeCount: Int,
        val maxLenMin: Int,
    )

    /** A [FreeSpace] that always reports [bytes]. */
    fun freeSpace(bytes: Long): FreeSpace = FreeSpace { bytes }

    val PLENTY_OF_SPACE: FreeSpace = freeSpace(Long.MAX_VALUE / 4)

    /** Epoch minutes of an ISO instant ("2026-10-13T19:00:00Z"). */
    fun minutes(iso: String): Int = (Instant.parse(iso).toEpochMilli() / 60_000L).toInt()

    /** XMLTV time for epoch minutes, rendered in [offsetMin] ("20261013190000 +0100"). */
    fun xmltvTime(epochMin: Int, offsetMin: Int = 0): String {
        val local = java.time.LocalDateTime.ofEpochSecond((epochMin + offsetMin) * 60L, 0, java.time.ZoneOffset.UTC)
        val sign = if (offsetMin < 0) '-' else '+'
        val a = kotlin.math.abs(offsetMin)
        return "%04d%02d%02d%02d%02d00 %c%02d%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, sign, a / 60, a % 60)
    }

    /** The generation named by `epg-current`, or null. */
    fun currentGeneration(dir: File): Long? = File(dir, "epg-current").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    fun generationFile(dir: File, gen: Long): File = File(dir, "epg-$gen.db")

    fun currentFile(dir: File): File? = currentGeneration(dir)?.let { generationFile(dir, it) }

    /** Sorted names of the files in the epg directory. */
    fun fileNames(dir: File): List<String> = (dir.listFiles() ?: emptyArray()).map { it.name }.sorted()

    /** Opens [file] read-only (bundled driver) for the duration of [block]. */
    fun <T> read(file: File, driver: SQLiteDriver = BundledSQLiteDriver(), block: (SQLiteConnection) -> T): T {
        val c = if (driver is BundledSQLiteDriver) driver.open(file.path, SQLITE_OPEN_READONLY) else driver.open(file.path)
        try {
            return block(c)
        } finally {
            c.close()
        }
    }

    fun scalar(file: File, sql: String): Long? = read(file) { c -> c.prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getLong(0) else null } }

    fun meta(file: File): Map<String, String> = read(file) { c ->
        c.prepare("SELECT key, value FROM meta").use { st -> buildMap { while (st.step()) put(st.getText(0), st.getText(1)) } }
    }

    /** Programme rows in PK order, optionally for one channel. */
    fun programmes(file: File, sourceId: Long? = null, xmltvId: String? = null): List<ProgrammeRow> = read(file) { c ->
        val where = buildList {
            if (sourceId != null) add("c.source_id = $sourceId")
            if (xmltvId != null) add("c.xmltv_id = ?")
        }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
        c.prepare(
            "SELECT c.source_id, c.xmltv_id, p.start_min, p.end_min, t.title, p.flags, p.catchup_id FROM programme p " +
                "JOIN epg_channel c ON c.id = p.epg_channel_id JOIN epg_text t ON t.id = p.text_id $where " +
                "ORDER BY c.source_id, c.xmltv_id, p.start_min",
        ).use { st ->
            if (xmltvId != null) st.bindText(1, xmltvId)
            buildList { while (st.step()) add(ProgrammeRow(st.getLong(0), st.getText(1), st.getLong(2).toInt(), st.getLong(3).toInt(), st.getText(4), st.getLong(5).toInt(), st.textOrNull(6))) }
        }
    }

    fun channels(file: File, sourceId: Long? = null): List<ChannelRow> = read(file) { c ->
        c.prepare(
            "SELECT id, source_id, xmltv_id, display_name, imported, programme_count, max_len_min FROM epg_channel " +
                (if (sourceId != null) "WHERE source_id = $sourceId " else "") + "ORDER BY id",
        ).use { st ->
            buildList {
                while (st.step()) {
                    add(ChannelRow(st.getLong(0), st.getLong(1), st.getText(2), st.getText(3), st.getLong(4) != 0L, st.getLong(5).toInt(), st.getLong(6).toInt()))
                }
            }
        }
    }

    private fun SQLiteStatement.textOrNull(i: Int): String? = if (isNull(i)) null else getText(i)

    /** Gzip bytes (for compression variants built in tests). */
    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    /**
     * Minimal XMLTV document builder for tests:
     * `xmltv { channel("bbc1.uk", "BBC One"); programme("bbc1.uk", start, stop, "News") }`.
     */
    class XmltvBuilder {
        private val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")

        fun channel(id: String, vararg displayNames: String, icon: String? = null) {
            sb.append("  <channel id=\"").append(esc(id)).append("\">\n")
            for (n in displayNames) sb.append("    <display-name>").append(esc(n)).append("</display-name>\n")
            if (icon != null) sb.append("    <icon src=\"").append(esc(icon)).append("\"/>\n")
            sb.append("  </channel>\n")
        }

        /** [stopMin] null = no stop attribute. [extra] is raw XML placed after the title. */
        fun programme(channel: String, startMin: Int, stopMin: Int?, title: String, subtitle: String? = null, extra: String = "", catchupId: String? = null) {
            sb.append("  <programme start=\"").append(xmltvTime(startMin)).append('"')
            if (stopMin != null) sb.append(" stop=\"").append(xmltvTime(stopMin)).append('"')
            sb.append(" channel=\"").append(esc(channel)).append('"')
            if (catchupId != null) sb.append(" catchup-id=\"").append(esc(catchupId)).append('"')
            sb.append(">\n    <title>").append(esc(title)).append("</title>\n")
            if (subtitle != null) sb.append("    <sub-title>").append(esc(subtitle)).append("</sub-title>\n")
            sb.append(extra)
            sb.append("  </programme>\n")
        }

        fun build(): String = sb.toString() + "</tv>\n"

        fun writeTo(file: File, gzip: Boolean = false): File {
            file.parentFile?.mkdirs()
            val bytes = build().toByteArray(Charsets.UTF_8)
            file.writeBytes(if (gzip) gzip(bytes) else bytes)
            return file
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    fun xmltv(block: XmltvBuilder.() -> Unit): XmltvBuilder = XmltvBuilder().apply(block)
}
