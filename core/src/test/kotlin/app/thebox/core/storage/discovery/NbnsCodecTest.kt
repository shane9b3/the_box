package app.thebox.core.storage.discovery

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Golden packets for RFC 1002 node status (§4.2.17/§4.2.18). The hex was built by an independent Python `struct` script,
 * not by [NbnsCodec]; the "FRED" vector is RFC 1001 §14.1's own example.
 */
class NbnsCodecTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val goldenRequest = hex("12340000000100000000000020434b4141414141414141414141414141414141414141414141414141414141410000210001")

    /** Windows-like: DESKTOP-7F3K2LQ<00> unique, WORKGROUP<00> group, DESKTOP-7F3K2LQ<20>, WORKGROUP<1E>, MAC a0:b1:…. */
    private val windowsResponse = hex(
        "beef8400000000010000000020434b4141414141414141414141414141414141414141414141414141414141410000210001000000000077" +
            "044445534b544f502d3746334b324c51000400574f524b47524f55502020202020200084004445534b544f502d3746334b324c5120" +
            "0400574f524b47524f55502020202020201e8400a0b1c2d3e4f5" + "00".repeat(40),
    )

    /** Samba-like: WORKGROUP<00> group first, __MSBROWSE__<01> (control bytes), then NAS<00> unique, NAS<20>. */
    private val sambaResponse = hex(
        "00018400000000010000000020434b414141414141414141414141414141414141414141414141414141414141000021000100000000007704" +
            "574f524b47524f555020202020202000840001025f5f4d5342524f5753455f5f020184004e41532020202020202020202020200004004e41" +
            "53202020202020202020202020200400" + "00".repeat(46),
    )

    @Test fun requestIsTheGolden50ByteNbstatQuery() {
        val req = NbnsCodec.nodeStatusRequest(0x1234)
        assertEquals(50, req.size)
        assertContentEquals(goldenRequest, req)
    }

    @Test fun requestFieldByField() {
        val r = NbnsCodec.nodeStatusRequest(0xABCD)
        assertEquals(0xAB.toByte(), r[0]); assertEquals(0xCD.toByte(), r[1])
        assertEquals(0, r[2].toInt()); assertEquals(0, r[3].toInt())           // flags: QUERY, no NM_FLAGS
        assertEquals(1, r[5].toInt())                                           // QDCOUNT
        for (i in 6..11) assertEquals(0, r[i].toInt())
        assertEquals(32, r[12].toInt())
        assertEquals("CK" + "A".repeat(30), String(r, 13, 32, Charsets.US_ASCII))
        assertEquals(0, r[45].toInt())
        assertEquals(0x21, r[47].toInt()); assertEquals(1, r[49].toInt())
    }

    @Test fun transactionIdUsesLow16Bits() {
        val r = NbnsCodec.nodeStatusRequest(0x1_0042)
        assertEquals(0, r[0].toInt()); assertEquals(0x42, r[1].toInt())
    }

    @Test fun firstLevelEncodingMatchesRfc1001Example() {
        assertEquals("EGFCEFEECACACACACACACACACACACACA", String(NbnsCodec.encodeName(NbnsCodec.rawName("FRED", 0x20)), Charsets.US_ASCII))
    }

    @Test fun wildcardIsStarAndNuls() {
        val w = NbnsCodec.wildcardName()
        assertEquals('*'.code.toByte(), w[0])
        for (i in 1..15) assertEquals(0, w[i].toInt())
    }

    @Test fun rawNameRejectsLongNames() {
        assertFailsWith<IllegalArgumentException> { NbnsCodec.rawName("A".repeat(16), 0) }
    }

    @Test fun parsesWindowsNameTable() {
        val s = NbnsCodec.parseNodeStatus(windowsResponse, expectedTransactionId = 0xBEEF)!!
        assertEquals(0xBEEF, s.transactionId)
        assertEquals(4, s.names.size)
        assertEquals(NbnsCodec.NodeName("DESKTOP-7F3K2LQ", 0x00, 0x0400), s.names[0])
        assertEquals(NbnsCodec.NodeName("WORKGROUP", 0x00, 0x8400), s.names[1])
        assertTrue(s.names[1].group)
        assertEquals(0x20, s.names[2].suffix)
        assertEquals(0x1E, s.names[3].suffix)
        assertContentEquals(hex("a0b1c2d3e4f5"), s.unitId)
        assertEquals("DESKTOP-7F3K2LQ", s.computerName())
    }

    @Test fun computerNameSkipsGroupsAndControlNames() {
        val s = NbnsCodec.parseNodeStatus(sambaResponse, expectedTransactionId = 1)!!
        assertEquals(3, s.names.size)                                           // __MSBROWSE__ dropped
        assertEquals("NAS", s.computerName())
    }

    @Test fun roundTripThroughOwnRequestIsRejected() {
        assertNull(NbnsCodec.parseNodeStatus(goldenRequest))                    // R bit clear
    }

    @Test fun wrongTransactionIdIsRejected() {
        assertNull(NbnsCodec.parseNodeStatus(windowsResponse, expectedTransactionId = 0xBEEE))
    }

    @Test fun negativeResponseIsRejected() {
        val p = windowsResponse.copyOf(); p[3] = (p[3].toInt() or 0x03).toByte()   // RCODE NAM_ERR
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun nonQueryOpcodeIsRejected() {
        val p = windowsResponse.copyOf(); p[2] = (0x80 or (5 shl 3)).toByte()        // OPCODE 5 = registration
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun wrongTypeIsRejected() {
        val p = windowsResponse.copyOf(); p[47] = 0x20                              // NB, not NBSTAT
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun zeroAnswersIsRejected() {
        val p = windowsResponse.copyOf(); p[7] = 0
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun everyTruncationIsNullOrConsistentAndNeverThrows() {
        for (n in 0 until windowsResponse.size) {
            val s = NbnsCodec.parseNodeStatus(windowsResponse, length = n)
            if (s != null) assertEquals("DESKTOP-7F3K2LQ", s.computerName())
        }
        // The whole name array needs 12 + 34 + 10 + 1 + 4 × 18 = 129 bytes.
        assertNull(NbnsCodec.parseNodeStatus(windowsResponse, length = 128))
        val noStats = NbnsCodec.parseNodeStatus(windowsResponse, length = 129)!!
        assertNull(noStats.unitId)
        assertEquals(4, noStats.names.size)
    }

    @Test fun nameCountPastTheEndIsRejected() {
        val p = windowsResponse.copyOf(); p[56] = 200.toByte()
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun rdLengthShorterThanTheNamesIsRejected() {
        val p = windowsResponse.copyOf(); p[54] = 0; p[55] = 20
        assertNull(NbnsCodec.parseNodeStatus(p))
    }

    @Test fun compressedRrNameIsAccepted() {
        // Header + a 2-byte pointer in place of the 34-byte RR_NAME.
        val tail = windowsResponse.copyOfRange(46, windowsResponse.size)
        val p = windowsResponse.copyOfRange(0, 12) + hex("c00c") + tail
        assertEquals("DESKTOP-7F3K2LQ", NbnsCodec.parseNodeStatus(p)!!.computerName())
    }

    @Test fun badLabelBitsAndLoopsAreRejected() {
        val p = windowsResponse.copyOf(); p[12] = 0x40                              // reserved label type
        assertNull(NbnsCodec.parseNodeStatus(p))
        val loop = windowsResponse.copyOfRange(0, 12) + ByteArray(200) { 1.toByte() }        // 1-byte labels, never terminated
        assertNull(NbnsCodec.parseNodeStatus(loop))
    }

    @Test fun conflictOrDeregisteringNamesAreNotTheComputerName() {
        val p = windowsResponse.copyOf()
        p[57 + 16] = 0x0C                                                           // first entry: ACT | CNF
        val s = NbnsCodec.parseNodeStatus(p)!!
        assertFalse(s.names[0].usable)
        assertNull(s.computerName())                                                // the other <00> is a group
    }

    @Test fun garbageNeverThrows() {
        val rnd = java.util.Random(7)
        repeat(2_000) {
            val b = ByteArray(rnd.nextInt(200)).also(rnd::nextBytes)
            if (b.size > 2) b[2] = (b[2].toInt() or 0x80).toByte()
            NbnsCodec.parseNodeStatus(b)
        }
        assertNull(NbnsCodec.parseNodeStatus(ByteArray(0)))
        assertNull(NbnsCodec.parseNodeStatus(windowsResponse, length = -5))
    }

    @Test fun lengthLargerThanBufferIsClamped() {
        assertEquals("DESKTOP-7F3K2LQ", NbnsCodec.parseNodeStatus(windowsResponse, length = 10_000)!!.computerName())
    }
}
