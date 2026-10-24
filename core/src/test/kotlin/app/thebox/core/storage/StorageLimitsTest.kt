package app.thebox.core.storage

import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.playback.timeshift.TimeshiftPolicyImpl
import app.thebox.core.settings.TimeshiftLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `StorageLimitsTest` (docs/design/m6-storage-locations.md §16.1, commit 0 / WP-S2): caps, reserves, the `availableBytes`
 * offset, pool sizes by `memoryClass`, Auto length per kind, and the policy overloads (§3.4, §12.3).
 */
class StorageLimitsTest {
    private val gib = 1L shl 30
    private val mib = 1L shl 20
    private val policy = TimeshiftPolicyImpl()

    @Test
    fun capsAndReservesPerKind() {
        val small = StorageLimits.internal(8_000_000_000L)
        assertEquals(TimeshiftConstants.MAX_RING_BYTES, small.maxRingBytes)
        assertEquals(gib, small.reserveBytes, "max(1 GiB, total × 0.10)")
        assertEquals(6_400_000_000L, StorageLimits.internal(64_000_000_000L).reserveBytes)
        assertTrue(small.countsWear && small.preallocate)

        assertEquals(4_294_954_752L, StorageLimits.VOLUME.maxRingBytes, "65,648 whole blocks")
        assertEquals(0L, StorageLimits.VOLUME.maxRingBytes % TimeshiftConstants.RING_BLOCK_BYTES)
        assertTrue(StorageLimits.VOLUME.maxRingBytes + TimeshiftConstants.RING_HEADER_BYTES <= StorageConstants.FAT32_MAX_FILE_BYTES)
        assertTrue(StorageLimits.VOLUME.maxRingBytes + TimeshiftConstants.RING_BLOCK_BYTES + TimeshiftConstants.RING_HEADER_BYTES > StorageConstants.FAT32_MAX_FILE_BYTES, "the largest such size")
        assertEquals(512 * mib, StorageLimits.VOLUME.reserveBytes)
        assertEquals(32 * gib, StorageLimits.SMB.maxRingBytes)
        assertEquals(2 * gib, StorageLimits.SMB.reserveBytes)
        for (l in listOf(StorageLimits.VOLUME, StorageLimits.SMB)) {
            assertFalse(l.countsWear, "the wear cap is internal only")
            assertFalse(l.preallocate, "no preallocation (FAT zero-fill, no SET_INFO)")
        }
        assertEquals(StorageLimits.SMB, StorageLimits.of(StorageKind.SMB, 0L))
        assertEquals(StorageLimits.VOLUME, StorageLimits.of(StorageKind.VOLUME, 0L))
        assertEquals(StorageLimits.internal(5L * gib), StorageLimits.of(StorageKind.INTERNAL, 5L * gib))
    }

    @Test
    fun poolSizesByMemoryClass() {
        // Internal: today's rule (isLowRamDevice), memoryClass ignored.
        val internal = StorageLimits.internal(8_000_000_000L)
        assertEquals(64, internal.ramBlocks(memoryClassMb = 96, lowRamDevice = false))
        assertEquals(32, internal.ramBlocks(memoryClassMb = 512, lowRamDevice = true))
        // Remote kinds: 160 at memoryClass ≥ 192 MiB, else 96 (lead ruling 5); isLowRamDevice ignored.
        for (l in listOf(StorageLimits.VOLUME, StorageLimits.SMB)) {
            assertEquals(160, l.ramBlocks(192, lowRamDevice = true))
            assertEquals(160, l.ramBlocks(256, lowRamDevice = false))
            assertEquals(96, l.ramBlocks(191, lowRamDevice = false))
        }
        // 160 blocks are ≈ 10.5 s at the assumed 1 MB/s, and the I/O timeout sits inside it (A-B4).
        val poolMs = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL * TimeshiftConstants.RING_BLOCK_BYTES * 1000L / TimeshiftConstants.ASSUMED_BYTES_PER_SEC
        assertTrue(StorageConstants.SMB_IO_TIMEOUT_MS < poolMs && StorageConstants.SMB_OPEN_DEADLINE_MS < poolMs)
        val smallMs = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL_SMALL * TimeshiftConstants.RING_BLOCK_BYTES * 1000L / TimeshiftConstants.ASSUMED_BYTES_PER_SEC
        assertTrue(StorageConstants.SMB_IO_TIMEOUT_SMALL_POOL_MS < smallMs && StorageConstants.SMB_OPEN_DEADLINE_SMALL_POOL_MS < smallMs)
    }

    /** §3.4 "Reserve enforcement" (A-S4): the flusher halts at FREE_SPACE_RESERVE_MIN / 2 of what the storage reports. */
    @Test
    fun availableBytesOffsetKeepsTheKindsReserve() {
        val halt = TimeshiftConstants.FREE_SPACE_RESERVE_MIN / 2
        // SMB: the flusher halts when real free space falls below the 2 GiB reserve.
        assertEquals(10 * gib - (2 * gib - halt), StorageLimits.SMB.reportedAvailableBytes(10 * gib))
        assertTrue(StorageLimits.SMB.reportedAvailableBytes(2 * gib) >= halt)
        assertTrue(StorageLimits.SMB.reportedAvailableBytes(2 * gib - 1) < halt)
        assertEquals(0L, StorageLimits.SMB.reportedAvailableBytes(gib), "never below 0")
        // USB: a 512 MiB reserve is exactly the flusher's own halt, so no offset.
        assertEquals(3 * gib, StorageLimits.VOLUME.reportedAvailableBytes(3 * gib))
    }

    @Test
    fun autoLengthPerKind() {
        assertEquals(TimeshiftConstants.AUTO_LENGTH_SMALL_MS, policy.lengthMs(TimeshiftLength.AUTO, 8_000_000_000L, StorageLimits.internal(8_000_000_000L)))
        assertEquals(TimeshiftConstants.AUTO_LENGTH_LARGE_MS, policy.lengthMs(TimeshiftLength.AUTO, 64_000_000_000L, StorageLimits.internal(64_000_000_000L)))
        // USB and SMB: 1 h whatever the box's own storage.
        assertEquals(TimeshiftConstants.AUTO_LENGTH_LARGE_MS, policy.lengthMs(TimeshiftLength.AUTO, 8_000_000_000L, StorageLimits.SMB))
        assertEquals(TimeshiftConstants.AUTO_LENGTH_LARGE_MS, policy.lengthMs(TimeshiftLength.PAUSE_ONLY, 8_000_000_000L, StorageLimits.VOLUME))
        assertEquals(null, policy.lengthMs(TimeshiftLength.OFF, 8_000_000_000L, StorageLimits.SMB))
        // The old overload is the internal kind.
        for (total in listOf(8_000_000_000L, 16_000_000_000L, 16_000_000_001L, 64_000_000_000L)) {
            assertEquals(policy.lengthMs(TimeshiftLength.AUTO, total, StorageLimits.internal(total)), policy.lengthMs(TimeshiftLength.AUTO, total))
        }
    }

    @Test
    fun capacityPerKind() {
        val twoHours = 7_200_000L
        // 2 h of 4K at 3.5 MB/s × 1.15 ≈ 27.0 GiB: the network cap holds it, internal stops at 8 GiB.
        val smb = policy.capacityBytes(twoHours, 3_500_000L, 1L shl 42, StorageLimits.SMB, 0L)
        assertTrue(smb in 26 * gib..32 * gib, "$smb")
        assertEquals(0L, smb % TimeshiftConstants.RING_BLOCK_BYTES)
        assertEquals(TimeshiftConstants.MAX_RING_BYTES / TimeshiftConstants.RING_BLOCK_BYTES * TimeshiftConstants.RING_BLOCK_BYTES,
            policy.capacityBytes(twoHours, 3_500_000L, 1L shl 42, StorageLimits.internal(1L shl 42), 0L))
        assertEquals(StorageConstants.VOLUME_MAX_RING_BYTES, policy.capacityBytes(twoHours, 3_500_000L, 1L shl 42, StorageLimits.VOLUME, 0L))
        // The reserve: 3 GiB free on the NAS leaves 1 GiB, 1 GiB free leaves nothing (NO_SPACE).
        assertTrue(policy.capacityBytes(twoHours, 1_000_000L, 3 * gib, StorageLimits.SMB, 0L) <= gib)
        assertEquals(0L, policy.capacityBytes(twoHours, 1_000_000L, gib, StorageLimits.SMB, 0L))
        // The old overload is the internal kind.
        for ((avail, total) in listOf(3_000_000_000L to 8_000_000_000L, 30_000_000_000L to 64_000_000_000L, 500_000_000L to 8_000_000_000L)) {
            assertEquals(policy.capacityBytes(1_800_000L, 0L, avail, StorageLimits.internal(total), 0L), policy.capacityBytes(1_800_000L, 0L, avail, total, 0L))
        }
    }
}
