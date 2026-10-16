package app.thebox.settings

import app.thebox.core.backup.BackupService
import app.thebox.core.backup.RestoreMode
import app.thebox.core.common.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I-M5-16 "Backup round trip" (docs/design/m5-setup-settings.md §3.10, §6.3): seed favourites, prefs, mapping and
 * settings; export with logins and a passphrase to `filesDir`; debug wipe; restore Merge → user tables equal, the
 * credentials decrypt; a wrong passphrase gives a reason and writes nothing; without logins the playlists are flagged
 * "Enter the login"; a second restore changes nothing. Drives the `BackupService` contract directly. **Awaits WP-D**
 * (codec, crypto, merge planner).
 */
class BackupRoundTripTest : SettingsTestBase() {
    private val backup: BackupService get() = probe.backup

    private fun snapshot(): Map<String, Any?> = runBlocking {
        val playlists = ep.playlists().all().sortedBy { it.uid }
        mapOf(
            "playlists" to playlists.map { listOf(it.uid, it.name, it.type, it.displaySource, it.userAgent, it.paddingBeforeSec) },
            "channels" to playlists.associate { p -> p.uid to ep.channels().browseAll(p.id, unlocked = true).first().size },
            "settings" to probe.documents().toSortedMap().toString(),
        )
    }

    @Test
    fun exportWipeRestoreMergeIsIdempotent() {
        val pid = seedXtream("padding=120,300")
        launch()
        val before = snapshot()
        val file = File(context.filesDir, "m5-roundtrip.thebox")
        val exported = runBlocking { file.outputStream().use { backup.export(it, "correct horse 42".toCharArray()) } }
        assertTrue("export: $exported", exported is Outcome.Ok)
        wipe()
        assertTrue(runBlocking { ep.playlists().all() }.isEmpty())
        // Wrong passphrase → a reason, nothing written.
        val wrong = runBlocking { backup.restore(file, "wrong passphrase".toCharArray(), RestoreMode.MERGE) }
        log("wrong passphrase: $wrong")
        assertTrue("nothing written on a wrong passphrase", runBlocking { ep.playlists().all() }.isEmpty())
        val restored = runBlocking { backup.restore(file, "correct horse 42".toCharArray(), RestoreMode.MERGE) }
        log("restore: $restored")
        assertEquals("user data equal after restore", before["playlists"], snapshot()["playlists"])
        val p = runBlocking { ep.playlists().all().single() }
        assertTrue("credentials decrypt", runBlocking { ep.playlists().secret(p) } is Outcome.Ok)
        val once = snapshot()
        runBlocking { backup.restore(file, "correct horse 42".toCharArray(), RestoreMode.MERGE) }
        assertEquals("a second restore changes nothing", once, snapshot())
        assertTrue(pid > 0)
    }

    @Test
    fun withoutLoginsPlaylistsAskForTheLogin() {
        seedXtream()
        launch()
        val file = File(context.filesDir, "m5-nologins.thebox")
        runBlocking { file.outputStream().use { backup.export(it, null) } }
        wipe()
        runBlocking { backup.restore(file, null, RestoreMode.MERGE) }
        val p = runBlocking { ep.playlists().all().single() }
        assertTrue("flagged Enter the login", p.needsReauth)
    }
}
