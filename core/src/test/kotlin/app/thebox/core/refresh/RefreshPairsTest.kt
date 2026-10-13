package app.thebox.core.refresh

import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** End-to-end parser → keys → differ → fake library on the refresh fixture pairs (docs/design/m1-data-layer.md §5.2). */
class RefreshPairsTest {
    private val day = 24L * 3600 * 1000

    @Test fun `basic - counters, samples and favourites`() {
        val lib = FakeLibrary()
        val r1 = lib.refresh("refresh/basic_v1.m3u")
        assertEquals(20, r1.added)
        assertEquals(10, r1.addedSample.size, "samples are capped at 10")
        assertEquals(mapOf("TVG" to 20), r1.keyRules)
        lib.favourites += listOf("tvg:ch03.uk|channel 03", "tvg:ch19.uk|channel 19", "tvg:ch05.uk|channel 05")

        val r2 = lib.refresh("refresh/basic_v2.m3u", nowMs = 2_000_000)
        assertEquals(19, r2.entries)
        assertEquals(2, r2.added)
        assertEquals(listOf("Channel 21 HD", "Channel 22 HD"), r2.addedSample)
        assertEquals(3, r2.newlyMissing)
        assertEquals(listOf("Channel 18 HD", "Channel 19 HD", "Channel 20 HD"), r2.removedSample)
        assertEquals(4, r2.updated) // 2 token rotations, 1 rename, 1 logo
        assertEquals(2, r2.urlChanged)
        assertEquals(1, r2.renamed)
        assertEquals(4, r2.moved) // 5 reversed, the middle one keeps its place
        assertEquals(9, r2.unchanged)
        assertEquals(2, r2.favouritesKept, "HD -> FHD rename keeps the key, so its favourite survives")
        assertEquals(1, r2.favouritesOrphaned)
        assertEquals("Channel 03 FHD", lib.row("tvg:ch03.uk|channel 03").channel.channel.name)
        assertFalse(r2.massRemapSuggested)

        // Identical third refresh: nothing written.
        val r3 = lib.refresh("refresh/basic_v2.m3u", nowMs = 3_000_000)
        assertEquals(19, r3.unchanged)
        assertEquals(0, lib.rowsWritten)
        assertTrue(lib.lastFinish!!.newlyMissingIds.isEmpty())
        assertEquals(3, lib.lastFinish!!.stillMissingIds.size)

        // Back to v1: the three come back (restored), 21/22 go missing.
        val r4 = lib.refresh("refresh/basic_v1.m3u", nowMs = 4_000_000)
        assertEquals(3, r4.restored)
        assertEquals(2, r4.newlyMissing)
        assertEquals(3, r4.favouritesKept)
    }

    @Test fun `basic - purge after 7 days and 2 refreshes, user data stays`() {
        val lib = FakeLibrary()
        lib.refresh("refresh/basic_v1.m3u", nowMs = 0)
        lib.favourites += "tvg:ch20.uk|channel 20"
        lib.refresh("refresh/basic_v2.m3u", nowMs = 1 * day)
        val six = lib.refresh("refresh/basic_v2.m3u", nowMs = 7 * day) // 6 days missing
        assertEquals(0, six.purged)
        val seven = lib.refresh("refresh/basic_v2.m3u", nowMs = 8 * day) // 7 days, 3rd refresh
        assertEquals(3, seven.purged)
        assertEquals(3, lib.lastFinish!!.purgedKeys.size)
        assertTrue("tvg:ch20.uk|channel 20" in lib.lastFinish!!.purgedKeys)
        assertEquals(1, seven.favouritesOrphaned)
        assertTrue("tvg:ch20.uk|channel 20" in lib.favourites, "purge deletes the row, never the favourite")
        // It reappears: a new row with the same key, the favourite reattaches.
        val back = lib.refresh("refresh/basic_v1.m3u", nowMs = 9 * day)
        assertEquals(1, back.favouritesKept)
    }

    @Test fun `host_rotation - URL-rule keys are identical across hosts`() {
        val lib = FakeLibrary()
        val r1 = lib.refresh("refresh/host_rotation_v1.m3u")
        assertEquals(mapOf("URL" to 15), r1.keyRules)
        val keys1 = lib.activeKeys()
        lib.favourites += keys1.take(5)
        val r2 = lib.refresh("refresh/host_rotation_v2.m3u")
        assertEquals(keys1, lib.activeKeys())
        assertEquals(0, r2.added)
        assertEquals(0, r2.newlyMissing)
        assertEquals(15, r2.urlChanged)
        assertEquals(5, r2.favouritesKept)
        assertTrue(lib.rows.values.all { it.channel.urlTemplate.contains("lb7") })
    }

    @Test fun `xc_templated - password change leaves templates and keys untouched`() {
        val lib = FakeLibrary()
        val r1 = lib.refresh("refresh/xc_templated_v1.m3u")
        assertTrue(r1.credentialsTemplated)
        assertEquals(DetectedCredentials("http://panel.example.tv:8080", "acct", "oldpass"), lib.lastDetected)
        assertEquals(mapOf("XTREAM" to 12), r1.keyRules)
        assertEquals(((501..511).map { "xc:$it" } + "xcm:9001").sorted(), lib.activeKeys().sorted())
        val catchupRow = lib.row("xc:511").channel
        assertEquals("http://panel.example.tv:8080/live/{{u}}/{{p}}/511.ts", catchupRow.urlTemplate)
        assertEquals("http://panel.example.tv:8080/timeshift/{{u}}/{{p}}/{duration}/{Y}-{m}-{d}:{H}-{M}/511.ts", catchupRow.channel.catchup.source)
        assertEquals(511L, catchupRow.channel.xtreamStreamId)
        assertTrue(lib.rows.values.none { CredentialTemplater.leaks(it.channel.channel, lib.lastDetected!!) || it.key.contains("oldpass") })
        lib.favourites += "xc:505"

        val r2 = lib.refresh("refresh/xc_templated_v2.m3u")
        assertEquals(DetectedCredentials("http://panel.example.tv:8080", "acct", "newpass"), lib.lastDetected)
        assertEquals(12, r2.unchanged)
        assertEquals(0, lib.rowsWritten)
        assertEquals(1, r2.favouritesKept)
    }

    @Test fun `provider_migration - mass remap suggested and planned 1 to 1`() {
        val lib = FakeLibrary()
        lib.refresh("refresh/provider_migration_v1.m3u")
        assertEquals(25, lib.liveCount)
        val missingBefore = lib.rows.values.map { RemapCandidate(it.key, it.channel.channel.name, it.channel.channel.groupTitle) }
        val r2 = lib.refresh("refresh/provider_migration_v2.m3u")
        assertTrue(r2.massRemapSuggested)
        assertEquals(25, r2.added)
        assertEquals(25, r2.newlyMissing)
        assertTrue(lib.lastFinish!!.autoRemaps.isEmpty(), "every URL changed: nothing to auto-remap")
        val added = lib.rows.values.filter { it.missingSince == null }.map { RemapCandidate(it.key, it.channel.channel.name, it.channel.channel.groupTitle) }
        val plan = RemapPlannerImpl().plan(missingBefore, added)
        assertEquals(25, plan.size)
        assertEquals(KeyRemap("xc:1000", "xc:7000", sameGroup = true), plan.first())
        assertTrue(plan.all { it.oldKey.removePrefix("xc:").toInt() + 6000 == it.newKey.removePrefix("xc:").toInt() })
    }

    @Test fun `shrink - 8 of 400 fails before any write, accept anyway applies it`() {
        val lib = FakeLibrary()
        lib.refresh("refresh/shrink_v1.m3u")
        assertEquals(400, lib.liveCount)
        val begins = lib.begins
        val e = assertFailsWith<SourceException> { lib.refresh("refresh/shrink_v2.m3u") }
        assertEquals(SourceError.SuspiciousShrink(400, 8), e.error)
        assertEquals(begins, lib.begins)
        assertEquals(400, lib.activeKeys().size)
        val forced = lib.refresh("refresh/shrink_v2.m3u", force = true)
        assertEquals(392, forced.newlyMissing)
        assertEquals(8, forced.unchanged)
        val failed = RefreshReports.failed(MergeContext(7, app.thebox.core.source.PlaylistType.M3U_URL, 3, 10, 400), 20, e.error)
        assertEquals(RefreshOutcome.FAILED, failed.outcome)
        assertEquals("shrink", failed.errorCode)
    }

    @Test fun `duplicates - same tvg-id and name three times`() {
        val lib = FakeLibrary()
        val r = lib.refresh("refresh/duplicates.m3u")
        assertEquals(listOf("tvg:dup.uk|dup channel", "tvg:dup.uk|dup channel~2", "tvg:dup.uk|dup channel~3", "tvg:other.uk|other"), lib.activeKeys())
        assertEquals(2, r.duplicateKeys)
        val again = lib.refresh("refresh/duplicates.m3u")
        assertEquals(4, again.unchanged)
    }

    @Test fun `the Xtream get_php fixture keys and templates every credential`() {
        val lib = FakeLibrary()
        val r = lib.refresh("m3u/xtream_get_php.m3u")
        assertEquals(mapOf("XTREAM" to 17), r.keyRules)
        assertTrue("xc:102" in lib.activeKeys(), "short form keyed only because the list voted Xtream")
        assertTrue("xcm:2001" in lib.activeKeys())
        assertTrue("xce:3002" in lib.activeKeys())
        assertTrue(lib.rows.values.none { CredentialTemplater.leaks(it.channel.channel, lib.lastDetected!!) })
        assertTrue(lib.rows.values.none { it.channel.urlTemplate.contains("u%40x") })
        assertNotEquals(0, lib.lastGroups.size)
    }
}
