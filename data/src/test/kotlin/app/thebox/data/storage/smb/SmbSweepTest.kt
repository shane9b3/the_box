package app.thebox.data.storage.smb

import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.FakeStatus
import app.thebox.core.storage.smb.FakeStatusException
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteEntry
import app.thebox.core.storage.smb.RemoteFile
import app.thebox.core.storage.smb.RemoteShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §16.2 `SmbSweepTest` (WP-S1): own other-pid files; foreign files by server time against our `.wake`; failures ignored. */
class SmbSweepTest {
    private val far = Long.MAX_VALUE / 2
    private val dir = "Rewind/TheBox-rewind"
    private val me = "abcdef012345"
    private val other = "0123456789ab"
    private val day = 86_400_000L

    private fun server(): Pair<FakeRemoteLink, RemoteShare> {
        val link = FakeRemoteLink()
        val share = link.share(far)
        share.mkdirs(dir)
        return link to share
    }

    @Test fun ownFilesOfOtherProcessesGoAndThisProcessesStay() {
        val (link, share) = server()
        val now = link.serverNowMs()
        link.putFile("$dir/.wake-$me", ByteArray(4), now)
        link.putFile("$dir/ring-$me-100-1.bin", lastWriteMs = now)        // this process
        link.putFile("$dir/ring-$me-100-2.bin", lastWriteMs = now)        // this process, an older n
        link.putFile("$dir/ring-$me-55-1.bin", lastWriteMs = now)         // a dead process of this box
        link.putFile("$dir/test-$me-55-123.bin", lastWriteMs = now)       // a dead Test of this box
        link.putFile("$dir/notes.txt", lastWriteMs = now - 30 * day)      // never ours to touch
        val r = SmbSweeper().sweep(share, dir, me, 100)
        assertEquals(setOf("ring-$me-55-1.bin", "test-$me-55-123.bin"), r.deleted.toSet())
        assertTrue(link.exists("$dir/ring-$me-100-1.bin") && link.exists("$dir/ring-$me-100-2.bin"))
        assertTrue(link.exists("$dir/notes.txt") && link.exists("$dir/.wake-$me"))
    }

    @Test fun foreignFilesGoOnlyAfterFortyEightHoursOfServerTime() {
        val (link, share) = server()
        val wake = 10_000 * day // the server's clock; the box's own clock never enters
        link.putFile("$dir/.wake-$me", ByteArray(4), wake)
        link.putFile("$dir/ring-$other-7-1.bin", lastWriteMs = wake - StorageConstants.FOREIGN_STALE_MS - 1)
        link.putFile("$dir/ring-$other-8-1.bin", lastWriteMs = wake - StorageConstants.FOREIGN_STALE_MS + 60_000) // live, 47 h 59 min
        link.putFile("$dir/test-$other-8-9.bin", lastWriteMs = wake - 3 * day)
        link.putFile("$dir/.wake-$other", ByteArray(4), wake - day)          // younger than 48 h: kept
        link.putFile("$dir/.wake-0000aaaabbbb", ByteArray(4), wake - 5 * day) // a box gone for days
        val r = SmbSweeper().sweep(share, dir, me, 100)
        assertEquals(setOf("ring-$other-7-1.bin", "test-$other-8-9.bin", ".wake-0000aaaabbbb"), r.deleted.toSet())
        assertTrue(link.exists("$dir/ring-$other-8-1.bin"))
        assertTrue(link.exists("$dir/.wake-$other"))
    }

    @Test fun withoutOurWakeFileForeignFilesAreNeverTouched() {
        val (link, share) = server()
        link.putFile("$dir/ring-$other-7-1.bin", lastWriteMs = 0L)
        val r = SmbSweeper().sweep(share, dir, me, 100)
        assertTrue(r.deleted.isEmpty())
        assertTrue(link.exists("$dir/ring-$other-7-1.bin"))
    }

    @Test fun failuresAreIgnored() {
        val (link, share) = server()
        link.putFile("$dir/.wake-$me", ByteArray(4), link.serverNowMs())
        link.putFile("$dir/ring-$me-1-1.bin")
        link.putFile("$dir/ring-$me-2-1.bin")
        val flaky = object : RemoteShare by share {
            override fun delete(path: String) {
                if (path.endsWith("-1-1.bin")) throw FakeStatusException(FakeStatus.ACCESS_DENIED)
                share.delete(path)
            }
        }
        val r = SmbSweeper().sweep(flaky, dir, me, 100)
        assertEquals(listOf("ring-$me-2-1.bin"), r.deleted)
        assertEquals(1, r.failures)
        assertTrue(link.exists("$dir/ring-$me-1-1.bin"))
        // A failed listing is a no-op.
        val dead = object : RemoteShare by share {
            override fun list(path: String, max: Int): List<RemoteEntry> = throw FakeStatusException(FakeStatus.NETWORK_NAME_DELETED)
            override fun open(path: String, mode: OpenMode, deleteOnClose: Boolean): RemoteFile = share.open(path, mode, deleteOnClose)
        }
        assertFalse(SmbSweeper().sweep(dead, dir, me, 100).deleted.isNotEmpty())
    }
}
