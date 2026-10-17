package app.thebox.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** D-L5 (M5 code review 2026-10-16): a restore copy stops at BACKUP_MAX_BYTES and leaves nothing behind. */
class CopyCappedTest {
    @Test
    fun aCopyOverTheCapFailsAndIsDeleted() {
        val dir = Files.createTempDirectory("restore").toFile()
        try {
            val ok = File(dir, "ok.thebox")
            copyCapped(ByteArrayInputStream(ByteArray(100)), ok, max = 100)
            assertEquals(100L, ok.length())
            val big = File(dir, "big.thebox")
            val failed = try { copyCapped(ByteArrayInputStream(ByteArray(101)), big, max = 100); false } catch (e: IOException) { true }
            assertTrue("over the cap throws", failed)
            assertFalse("nothing left behind", big.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
