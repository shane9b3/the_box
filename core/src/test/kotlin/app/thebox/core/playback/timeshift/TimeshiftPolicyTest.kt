package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.MAX_RING_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.MIN_RING_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.settings.StorageProtection
import app.thebox.core.settings.TimeshiftHold
import app.thebox.core.settings.TimeshiftLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §17.1 `TimeshiftPolicyTest` (§8.2, §8.4, §7.3, §11.5). */
class TimeshiftPolicyTest {
    private val p = TimeshiftPolicyImpl()
    private val gb = 1_000_000_000L
    private val gib = 1L shl 30

    @Test
    fun lengths() {
        assertEquals(1_800_000L, p.lengthMs(TimeshiftLength.AUTO, 8 * gb), "Auto on ≤ 16 GB")
        assertEquals(1_800_000L, p.lengthMs(TimeshiftLength.AUTO, 16 * gb))
        assertEquals(3_600_000L, p.lengthMs(TimeshiftLength.AUTO, 32 * gb), "Auto above 16 GB")
        assertEquals(900_000L, p.lengthMs(TimeshiftLength.MIN_15, 8 * gb))
        assertEquals(1_800_000L, p.lengthMs(TimeshiftLength.MIN_30, 64 * gb))
        assertEquals(3_600_000L, p.lengthMs(TimeshiftLength.H_1, 8 * gb))
        assertEquals(7_200_000L, p.lengthMs(TimeshiftLength.H_2, 8 * gb))
        assertEquals(1_800_000L, p.lengthMs(TimeshiftLength.PAUSE_ONLY, 8 * gb))
        assertNull(p.lengthMs(TimeshiftLength.OFF, 64 * gb))
    }

    @Test
    fun fireTvExampleStorageIsLimited() {
        // Fire TV Stick 4K: 8 GB, 3 GB free, Auto (30 min) at the assumed 1 MB/s.
        val len = p.lengthMs(TimeshiftLength.AUTO, 8 * gb)!!
        val cap = p.capacityBytes(len, 1_000_000, availableBytes = 3 * gb, totalBytes = 8 * gb, currentFileBytes = 0)
        // reserve = max(1 GiB, 0.8 GB) = 1 GiB; upper = 3 GB − 1 GiB = 1.926 GB < 30 min × 1 MB/s × 1.15 = 2.07 GB.
        val upper = 3 * gb - gib
        assertEquals(upper / RING_BLOCK_BYTES * RING_BLOCK_BYTES, cap)
        assertEquals(0L, cap % RING_BLOCK_BYTES)
        // 1.926 GB holds 32 min at 1 MB/s: the 30-min length still fits (the 15 % headroom is what space took).
        assertEquals(len, p.effectiveWindowMs(len, cap, 1_000_000))
        // The same box with only 2 GB free: storage limits the window (the row reads "storage is limited").
        val cap2 = p.capacityBytes(len, 1_000_000, 2 * gb, 8 * gb, 0)
        val w2 = p.effectiveWindowMs(len, cap2, 1_000_000)
        assertTrue(w2 in 920_000L..930_000L, "≈ 15 min: $w2")
    }

    @Test
    fun capacityRules() {
        // Plenty of space: length × rate × 1.15, whole blocks.
        val c = p.capacityBytes(3_600_000, 1_000_000, 500 * gb, 1_000 * gb, 0)
        assertEquals(4_140_000_000L / RING_BLOCK_BYTES * RING_BLOCK_BYTES, c)
        // Never above 8 GiB.
        assertEquals(MAX_RING_BYTES / RING_BLOCK_BYTES * RING_BLOCK_BYTES, p.capacityBytes(7_200_000, 2_000_000, 500 * gb, 1_000 * gb, 0))
        // Never below 64 MiB while space allows it.
        assertEquals(MIN_RING_BYTES / RING_BLOCK_BYTES * RING_BLOCK_BYTES, p.capacityBytes(10_000, 1_000_000, 50 * gb, 64 * gb, 0))
        // The current file counts as available (re-sizing an existing ring).
        val withFile = p.capacityBytes(3_600_000, 1_000_000, gib + 100_000_000, 8 * gb, 2 * gb)
        assertEquals((gib + 100_000_000 + 2 * gb - gib) / RING_BLOCK_BYTES * RING_BLOCK_BYTES, withFile)
        // Below 64 MiB of room: off (NO_SPACE).
        assertEquals(0L, p.capacityBytes(1_800_000, 1_000_000, gib + 10_000_000, 8 * gb, 0))
        // The reserve is 10 % of large storage.
        assertEquals(0L, p.capacityBytes(1_800_000, 1_000_000, 10 * gb, 100 * gb, 0))
        // An unknown rate uses ASSUMED_BYTES_PER_SEC.
        assertEquals(p.capacityBytes(600_000, 1_000_000, 50 * gb, 64 * gb, 0), p.capacityBytes(600_000, 0, 50 * gb, 64 * gb, 0))
    }

    @Test
    fun holdAutoIsUntilFull() {
        assertNull(p.holdLimitMs(TimeshiftHold.AUTO))
        assertNull(p.holdLimitMs(TimeshiftHold.UNTIL_FULL))
        assertEquals(600_000L, p.holdLimitMs(TimeshiftHold.MIN_10))
        assertEquals(1_800_000L, p.holdLimitMs(TimeshiftHold.MIN_30))
        assertEquals(3_600_000L, p.holdLimitMs(TimeshiftHold.H_1))
    }

    @Test
    fun dailyCapOnlyOnSmallStorage() {
        assertEquals(12 * gb, p.dailyLimitBytes(StorageProtection.AUTO, 8 * gb))
        assertEquals(12 * gb, p.dailyLimitBytes(StorageProtection.AUTO, 16 * gb))
        assertNull(p.dailyLimitBytes(StorageProtection.AUTO, 32 * gb))
        assertNull(p.dailyLimitBytes(StorageProtection.OFF, 8 * gb))
    }

    @Test
    fun modeSwitchesNeverWhileBehind() {
        val cap = 12 * gb
        assertEquals(BufferMode.PERSIST, p.mode(TimeshiftLength.AUTO, 11 * gb, cap, needsPersist = false))
        assertEquals(BufferMode.PAUSE_ONLY, p.mode(TimeshiftLength.AUTO, 12 * gb, cap, needsPersist = false))
        assertEquals(BufferMode.PERSIST, p.mode(TimeshiftLength.AUTO, 13 * gb, cap, needsPersist = true), "a running time-shift is never cut")
        assertEquals(BufferMode.PERSIST, p.mode(TimeshiftLength.H_2, 100 * gb, null, needsPersist = false), "no cap")
        assertEquals(BufferMode.PAUSE_ONLY, p.mode(TimeshiftLength.PAUSE_ONLY, 0, null, needsPersist = true))
    }

    @Test
    fun pauseRemaining() {
        // 600 MB free at 1 MB/s = 10 min; a 3-min hold left wins.
        assertEquals(600_000L, p.pauseRemainingMs(null, 600_000_000, 1_000_000))
        assertEquals(180_000L, p.pauseRemainingMs(180_000, 600_000_000, 1_000_000))
        assertEquals(600_000L, p.pauseRemainingMs(3_600_000, 600_000_000, 1_000_000))
        assertEquals(0L, p.pauseRemainingMs(null, -5, 1_000_000))
        assertEquals(600_000L, p.pauseRemainingMs(null, 600_000_000, 0), "unknown rate → assumed")
    }
}
