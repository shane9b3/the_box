package app.thebox.core.epg.testutil

import java.io.File

/**
 * Deterministic XMLTV generator for tests and perf runs (docs/design/m1-data-layer.md §5.5).
 * Commit-0 stub (test fixture, visible to :data tests); WP-B owns this file.
 */
@Suppress("unused")
class XmltvGenerator(
    val channels: Int = 50_000,
    val whitelisted: Int = 20_000,
    val days: Int = 7,
    val perDay: Int = 7,
    val gzip: Boolean = true,
    val seed: Long = 1L,
) {
    fun writeTo(file: File): File = TODO("WP-B")
}
