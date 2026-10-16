package app.thebox.core.backup

import app.thebox.core.backup.MergePlanner.BackupPlaylist
import app.thebox.core.backup.MergePlanner.LocalPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure parts of docs/design/m5-setup-settings.md §3.10.4 (`MergePlannerTest`; the DB side is `RestoreMergeTest` in `:data`). */
class MergePlannerTest {
    private fun b(uid: String, type: String = "XTREAM", ds: String = "panel.example.com:8080", urls: List<String>? = null, user: String? = null) =
        BackupPlaylist(uid, "N$uid", type, ds, urls, user)

    private fun l(id: Long, uid: String?, type: String = "XTREAM", ds: String = "panel.example.com:8080", urls: List<String>? = null, user: String? = null) =
        LocalPlaylist(id, uid, type, ds, urls, user)

    @Test
    fun uidFirstThenSourceThenNew() {
        val got = MergePlanner.match(
            listOf(b("a"), b("b", ds = "other:80"), b("c", ds = "nowhere:1")),
            listOf(l(1, "zzz", ds = "other:80"), l(2, "a")),
        )
        assertEquals(listOf(MatchTarget.SAME_UID, MatchTarget.SAME_SOURCE, MatchTarget.NEW), got.map { it.target })
        assertEquals(listOf(2L, 1L, null), got.map { it.existingId })
    }

    @Test
    fun aLocalPlaylistIsClaimedOnce() {
        val got = MergePlanner.match(listOf(b("x"), b("y")), listOf(l(1, "q")))
        assertEquals(listOf(MatchTarget.SAME_SOURCE, MatchTarget.NEW), got.map { it.target })
    }

    @Test
    fun uidWinsOverAnEarlierSourceMatch() {
        // Backup "x" matches local 1 by source, but local 1 carries backup "y"'s uid: y gets it, x becomes new.
        val got = MergePlanner.match(listOf(b("x"), b("y")), listOf(l(1, "y")))
        assertEquals(listOf(MatchTarget.NEW, MatchTarget.SAME_UID), got.map { it.target })
    }

    @Test
    fun xtreamSourceComparesHostPortAndUsernameWhenLoginsAreKnown() {
        val bk = b("x", urls = listOf("HTTP://Panel.Example.com/"), user = "ann")
        assertTrue(MergePlanner.sameSource(bk, l(1, null, ds = "different", urls = listOf("http://panel.example.com:80/player_api.php"), user = "ann")))
        assertFalse(MergePlanner.sameSource(bk, l(1, null, urls = listOf("http://panel.example.com"), user = "bob")))
        assertFalse(MergePlanner.sameSource(bk, l(1, null, urls = listOf("http://panel.example.com:8080"), user = "ann")))
        assertFalse(MergePlanner.sameSource(bk, l(1, null, type = "M3U_URL", urls = listOf("http://panel.example.com"), user = "ann")))
    }

    @Test
    fun m3uSourceIgnoresTheQuery() {
        val bk = b("x", type = "M3U_URL", urls = listOf("http://Host/list.m3u?token=1"))
        assertTrue(MergePlanner.sameSource(bk, l(1, null, type = "M3U_URL", urls = listOf("http://host/list.m3u?token=2#x"))))
        assertFalse(MergePlanner.sameSource(bk, l(1, null, type = "M3U_URL", urls = listOf("http://host/other.m3u"))))
    }

    @Test
    fun withoutLoginsTheDisplaySourceDecides() {
        assertTrue(MergePlanner.sameSource(b("x", ds = "Panel:8080"), l(1, null, ds = "panel:8080", urls = listOf("http://a"))))
        assertFalse(MergePlanner.sameSource(b("x", ds = ""), l(1, null, ds = "")))
    }

    @Test
    fun hostPortDefaults() {
        assertEquals("a.b:80", MergePlanner.hostPort("http://A.b"))
        assertEquals("a.b:443", MergePlanner.hostPort("https://a.b/x"))
        assertEquals("a.b:8080", MergePlanner.hostPort("http://u:p@a.b:8080/x?y"))
    }

    @Test
    fun remindersTravelOnlyWhenPendingAndInTheFuture() {
        val now = 100L * 60_000
        assertTrue(MergePlanner.reminderTravels(0, 101, now))
        assertFalse(MergePlanner.reminderTravels(0, 100, now))
        assertFalse(MergePlanner.reminderTravels(1, 200, now))
        assertFalse(MergePlanner.reminderTravels(3, 200, now))
    }

    @Test
    fun columnWhitelistsNeverIncludeLearnedColumns() {
        for (c in listOf("playback_flags", "fps_milli")) assertFalse(c in MergePlanner.CHANNEL_PREF_COLUMNS)
        for (c in listOf("etag", "catchup_correction_h", "server_zone", "active_base_index", "xtream_output_learned", "catchup_probe_winner", "credential_ref")) {
            assertFalse(c in MergePlanner.PLAYLIST_COLUMNS, c)
        }
    }

    @Test
    fun sourceKeysRoundTrip() {
        val k = MergePlanner.sourceKey("XTREAM", "uid1", 0, -42L)
        assertEquals(MergePlanner.SourceKey("XTREAM", "uid1", 0, -42L), MergePlanner.parseSourceKey(k))
        assertEquals(MergePlanner.SourceKey("USER", null, 0, 7L), MergePlanner.parseSourceKey(MergePlanner.sourceKey("USER", null, 0, 7L)))
        assertEquals(null, MergePlanner.parseSourceKey("bad"))
    }

    @Test
    fun autoBackupRetentionKeepsFourAndOnlyTouchesWhatItWrote() {
        val written = (1..6).map { AutoBackupRetention.Written("f$it", it * 1000L) }
        assertEquals(listOf("f2", "f1"), AutoBackupRetention.toDelete(written).map { it.location })
        assertEquals(emptyList(), AutoBackupRetention.toDelete(written.take(4)))
    }

    @Test
    fun backupNames() {
        assertEquals("TheBox-backup-2026-10-16-1402.thebox", BackupNames.manual(2026, 10, 16, 14, 2))
        assertEquals("TheBox-auto-2026-10-16.thebox", BackupNames.auto(2026, 10, 16))
        assertTrue(BackupNames.isAuto("TheBox-auto-2026-10-16.thebox"))
        assertFalse(BackupNames.isAuto("TheBox-backup-2026-10-16-1402.thebox"))
    }
}
