package app.thebox.update

import app.thebox.core.update.UpdateEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-build-type endpoints (docs/design/m5-setup-settings.md §3.12.1; reviewed 2026-10-16, S7; §8 Q7/Q8 open). */
class UpdateEndpointsTest {
    @Test
    fun releaseBuildIsDisabledUntilTheOwnerNamesTheRepoAndTheKey() {
        val e = UpdateEndpoints(emptySet()).current()
        assertEquals("release", e.label)
        assertFalse("no request leaves the device before Q7/Q8 are answered", e.enabled)
        assertFalse(e.anyHost)
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
