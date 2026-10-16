package app.thebox.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I-M5-22 "Migration 3 → 4 on device" (docs/design/m5-setup-settings.md §4.2, §6.3; F22's bundled-driver run): a v3
 * `library.db` fixture (the exported v3 schema, [LibraryV3Schema]) is opened by the production builder with the bundled
 * SQLite driver: data kept, `uid` unique and non-null, `rule_hidden = 0`. Runs on commit 0 + WP-E.
 */
class MigrationOnDeviceTest : SettingsTestBase() {
    @Test
    fun migrate3To4KeepsDataAndBackfillsUid() {
        val r = runBlocking { probe.migrationCheck.run(LibraryV3Schema.STATEMENTS) }
        log("migration result: $r")
        assertEquals("migrated to v4", 4, r.version)
        assertEquals("playlists kept", listOf("Alpha", "Beta"), r.playlistNames)
        assertEquals("every uid non-null", 2, r.uidsNonNull)
        assertEquals("uids unique", 2, r.uidsDistinct)
        assertEquals("channels kept", 3, r.channels)
        assertEquals("rule_hidden = 0", 0, r.ruleHiddenNonZero)
    }
}
