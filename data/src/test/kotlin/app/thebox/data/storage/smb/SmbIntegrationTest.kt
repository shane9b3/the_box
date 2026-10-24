package app.thebox.data.storage.smb

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.storage.ManualAddressParser
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.SmbRingStorage
import com.hierynomus.smbj.auth.AuthenticationContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/**
 * Opt-in real-server tests (§16.2): skipped unless `THEBOX_SMB_URL` is set, e.g.
 * `THEBOX_SMB_URL=smb://127.0.0.1:4445/test/box THEBOX_SMB_USER=test THEBOX_SMB_PASSWORD=… ./gradlew :data:testDebugUnitTest --tests '*SmbIntegrationTest*'`
 * (`tools/smbtest/`). The password comes from the environment and is never printed.
 */
class SmbIntegrationTest {
    companion object {
        /** The server under test, or null (the test is skipped). */
        fun target(): Pair<SmbAddress, CharArray?>? {
            val url = System.getenv("THEBOX_SMB_URL")?.takeIf { it.isNotBlank() } ?: return null
            val p = ManualAddressParser.parse(url) as? ManualAddressParser.Result.Parsed ?: error("THEBOX_SMB_URL does not parse")
            val user = System.getenv("THEBOX_SMB_USER")?.takeIf { it.isNotBlank() }
            val pw = System.getenv("THEBOX_SMB_PASSWORD")?.toCharArray()
            return SmbAddress(p.host, p.port, p.share ?: error("THEBOX_SMB_URL needs a share"), p.path, user = user, guest = user == null) to pw
        }

        val CLOCK = MonoClock { System.nanoTime() / 1_000_000L }

        fun link(address: SmbAddress, pw: CharArray?): Pair<SmbjRemoteLink, SmbExecutor> {
            val exec = SmbExecutor("it-box-smb", CLOCK)
            val l = SmbjRemoteLink(SmbjShareOpener(address, StorageConstants.SMB_IO_TIMEOUT_MS), exec, CLOCK, StorageConstants.SMB_IO_TIMEOUT_MS,
                password = { PasswordRead.Ok(pw?.copyOf()) })
            val err = exec.submit { l.connectBlocking() }.get()
            if (err != null) throw AssertionError("connect failed: ${SmbFaults.faultName(err)}")
            return l to exec
        }

        fun dir(a: SmbAddress) = if (a.path.isEmpty()) "TheBox-it" else "${a.path}/TheBox-it"
    }

    @Test fun listSharesWriteReadRemoveAndServerTime() {
        val (address, pw) = target().also { assumeTrue("THEBOX_SMB_URL not set", it != null) }!!
        // Shares through dcerpc srvsvc (U-15), on the browse client.
        val client = SmbjClients.browseClient()
        val conn = client.connect(address.host, address.port)
        try {
            val ctx = if (address.user != null) AuthenticationContext(address.user, pw ?: CharArray(0), null) else AuthenticationContext.guest()
            val session = conn.authenticate(ctx)
            SmbjClients.zero(ctx)
            val listing = SmbShareLister().list(session, signedInAsUser = address.user != null)
            assertNull("listing error ${listing.error}", listing.error)
            assertTrue(listing.shares.any { it.equals(address.share, ignoreCase = true) })
        } finally { SmbjClients.forceClose(conn) }

        val (l, _) = link(address, pw)
        val share = l.currentShare()!!
        val d = dir(address)
        share.mkdirs(d)
        val f = share.open("$d/it.bin", OpenMode.CREATE_OVERWRITE)
        val data = ByteArray(RING_BLOCK_BYTES) { (it * 13).toByte() }
        f.write(data, 0, data.size, 0L)
        val back = ByteArray(data.size)
        var got = 0
        while (got < back.size) { val n = f.read(back, got, back.size - got, got.toLong()); if (n < 0) break; got += n }
        assertArrayEquals(data, back)
        f.closeAsync()
        val t = share.lastWriteMs("$d/it.bin")
        assertTrue("server time is plausible", t > 1_600_000_000_000L)
        assertTrue(share.list(d, 100).any { it.name == "it.bin" && !it.directory })
        assertTrue(share.freeBytes() > 0)
        share.delete("$d/it.bin")
        assertTrue(share.list(d, 100).none { it.name == "it.bin" })
        l.disconnect()
    }

    @Test fun theRingStorageAndTheTestAgainstTheServer() {
        val (address, pw) = target().also { assumeTrue("THEBOX_SMB_URL not set", it != null) }!!
        val (l, _) = link(address, pw)
        val st = SmbRingStorage(l, "${dir(address)}/ring-it-1-1.bin", 32L * RING_BLOCK_BYTES, StorageLimits.SMB, CLOCK)
        st.writeHeader(1, st.capacityBytes)
        assertTrue(st.create(CLOCK.nowMs() + 6_000))
        val b = ByteBuffer.wrap(ByteArray(RING_BLOCK_BYTES) { it.toByte() })
        st.pwrite(b, RING_HEADER_BYTES.toLong())
        val r = ByteBuffer.allocate(RING_BLOCK_BYTES)
        assertEquals(RING_BLOCK_BYTES, st.pread(r, RING_HEADER_BYTES.toLong()))
        assertArrayEquals(b.array(), r.array())
        assertTrue(st.availableBytes() >= 0)
        st.delete()
        val res = LocationTester(CLOCK, "0000aaaa1111", 1, { SmbFaults.classify(it, Thread.currentThread().isInterrupted, true) }, wifi = false)
            .run(l.currentShare()!!, address.path) {}
        assertNotEquals("test failed: ${res.error}", TestVerdict.FAILED, res.verdict)
        l.disconnect()
    }
}

/**
 * Opt-in (§16.2 `SmbConcurrencyTest`, U-1): one writer and two reader threads on one handle, 60 s at 2 MB/s, with CRC.
 * `THEBOX_SMB_SOAK_S` shortens it.
 */
class SmbConcurrencyTest {
    @Test fun oneWriterTwoReadersOnOneHandle() {
        val (address, pw) = SmbIntegrationTest.target().also { assumeTrue("THEBOX_SMB_URL not set", it != null) }!!
        val seconds = System.getenv("THEBOX_SMB_SOAK_S")?.toLongOrNull() ?: 60L
        val (l, _) = SmbIntegrationTest.link(address, pw)
        val share = l.currentShare()!!
        val d = SmbIntegrationTest.dir(address)
        share.mkdirs(d)
        val f = share.open("$d/concurrency.bin", OpenMode.CREATE_OVERWRITE, deleteOnClose = true)
        val block = RING_BLOCK_BYTES
        val written = AtomicLong(0)
        val crcs = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val failure = AtomicReference<Throwable?>()
        val end = System.nanoTime() + seconds * 1_000_000_000L
        val writer = Thread {
            try {
                val buf = ByteArray(block); var n = 0L; val t0 = System.nanoTime()
                while (System.nanoTime() < end) {
                    for (i in buf.indices) buf[i] = ((n * 31 + i) and 0xFF).toByte()
                    f.write(buf, 0, block, n * block)
                    crcs[n] = CRC32().also { it.update(buf) }.value
                    n++; written.set(n)
                    val due = t0 + n * block * 1_000_000_000L / 2_000_000L
                    val wait = (due - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val readers = (1..2).map { k ->
            Thread {
                try {
                    val buf = ByteArray(block); val rnd = java.util.Random(k.toLong())
                    while (System.nanoTime() < end) {
                        val have = written.get()
                        if (have < 2) { Thread.sleep(5); continue }
                        val b = rnd.nextInt((have - 1).toInt()).toLong()
                        var got = 0
                        while (got < block) { val n = f.read(buf, got, block - got, b * block + got); if (n < 0) break; got += n }
                        val expect = crcs[b] ?: continue
                        if (CRC32().also { it.update(buf, 0, got) }.value != expect) throw AssertionError("CRC mismatch at block $b")
                    }
                } catch (e: Throwable) { failure.compareAndSet(null, e) }
            }
        }
        writer.start(); readers.forEach { it.start() }
        writer.join(); readers.forEach { it.join() }
        f.closeAsync()
        l.disconnect()
        failure.get()?.let { throw AssertionError("failed: ${SmbFaults.faultName(it)}", it) }
        assertTrue(written.get() > 0)
    }
}
