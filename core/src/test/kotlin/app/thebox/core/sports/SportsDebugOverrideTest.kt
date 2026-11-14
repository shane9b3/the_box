package app.thebox.core.sports

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** (CHL integration review L4) The one debug check and read shared by `:data` and `:ui`. */
class SportsDebugOverrideTest {
    @Test
    fun onlyADebuggableAppReadsTheFile() {
        val dir = Files.createTempDirectory("filesdir").toFile()
        try {
            assertNull(SportsDebugOverride.text(dir, debuggable = true), "no file")
            java.io.File(dir, "debug").mkdirs()
            java.io.File(dir, SportsDebugOverride.FILE).writeText("http://10.0.2.2:8080\n")
            assertEquals("http://10.0.2.2:8080\n", SportsDebugOverride.text(dir, debuggable = true))
            assertNull(SportsDebugOverride.text(dir, debuggable = false), "a release build never reads it, even with the file")
            assertEquals(SportsConstants.DEBUG_OVERRIDE_FILE, SportsDebugOverride.FILE)
            assertEquals("debug/sports_base", SportsDebugOverride.FILE)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theFlagIsTheApplicationInfoBit() {
        assertTrue(SportsDebugOverride.isDebuggable(0x2))
        assertTrue(SportsDebugOverride.isDebuggable(0x2 or 0x40 or 0x8000))
        assertFalse(SportsDebugOverride.isDebuggable(0))
        assertFalse(SportsDebugOverride.isDebuggable(0x1 or 0x4 or 0x8000))
    }
}
