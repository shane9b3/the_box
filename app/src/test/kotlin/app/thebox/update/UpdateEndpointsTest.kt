package app.thebox.update

import app.thebox.core.update.UpdateEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-build-type endpoints (docs/design/m5-setup-settings.md §3.12.1; reviewed 2026-10-16, S7; §8 Q7/Q8 resolved 2026-11-08). */
class UpdateEndpointsTest {
    // DebugUpdateEndpoint is in the debug source set, which this unit test also sees; repeated so the check reads plainly.
    private val DEBUG_KEY_HEX = "0cbdb295f6fd7d0464c7e9ffdbbf2d6269cc407ec81ac77a68eb2066109380d1"

    @Test
    fun releaseBuildReadsTheOwnersRepoWithTheOwnersKeys() {
        val e = UpdateEndpoints(emptySet()).current()
        assertEquals("release", e.label)
        assertTrue("Q7/Q8 resolved: the release updater is on", e.enabled)
        assertFalse(e.anyHost)
        assertEquals("https://github.com/shane9b3/the_box/releases/latest/download/update.json", e.manifestUrl)
        assertEquals("https://github.com/shane9b3/the_box/releases/latest/download/update.json.sig", e.signatureUrl)
        assertEquals("current, then next (rotation)", 2, e.publicKeys.size)
        assertTrue(e.publicKeys.all { it.size == 32 })
        assertFalse("release builds never trust the debug key",
            ReleaseUpdateConfig.UPDATE_PUBLIC_KEYS_HEX.any { it.equals(DEBUG_KEY_HEX, ignoreCase = true) })
    }

    @Test
    fun anOverrideReplacesTheReleaseEndpointEntirely() {
        val debug = UpdateEndpoint("http://10.0.2.2:18080/update/update.json", listOf(ByteArray(32) { 7 }), anyHost = true, label = "debug")
        val e = UpdateEndpoints(setOf(UpdateEndpointOverride { debug })).current()
        assertSame(debug, e)
        assertTrue(e.enabled)
        assertEquals("http://10.0.2.2:18080/update/update.json.sig", e.signatureUrl)
    }

    @Test
    fun releaseUrlShapeAndHex() {
        assertEquals("https://github.com/o/r/releases/latest/download/update.json", ReleaseUpdateConfig.manifestUrl("o/r"))
        assertArrayEquals(byteArrayOf(0x0c, 0xbd.toByte(), 0x01), ReleaseUpdateConfig.hexToBytes("0cbd01"))
    }
}
