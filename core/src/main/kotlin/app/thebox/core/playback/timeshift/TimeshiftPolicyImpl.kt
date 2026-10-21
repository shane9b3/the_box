// WP-A (docs/design/m6-timeshift.md §7.3, §8.2, §8.4, §11.5; lead rulings 2 and 3).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.ASSUMED_BYTES_PER_SEC
import app.thebox.core.playback.timeshift.TimeshiftConstants.AUTO_LENGTH_LARGE_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.AUTO_LENGTH_SMALL_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.CAPACITY_HEADROOM
import app.thebox.core.playback.timeshift.TimeshiftConstants.DAILY_LIMIT_SMALL
import app.thebox.core.playback.timeshift.TimeshiftConstants.FREE_SPACE_RESERVE_FRACTION
import app.thebox.core.playback.timeshift.TimeshiftConstants.FREE_SPACE_RESERVE_MIN
import app.thebox.core.playback.timeshift.TimeshiftConstants.MAX_RING_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.MIN_RING_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.SMALL_STORAGE_BYTES
import app.thebox.core.settings.StorageProtection
import app.thebox.core.settings.TimeshiftHold
import app.thebox.core.settings.TimeshiftLength

/**
 * Pure sizing and policy (§8).
 *
 * - **Length:** 15/30/60/120 min; Auto = 30 min on ≤ 16 GB total storage, 60 min above (lead ruling 2). "Pause only"
 *   sizes like Auto (the file still holds what a pause saves). Off → null.
 * - **Capacity:** `floorToBlocks(clamp(lengthSec × rate × 1.15, 64 MiB, min(8 GiB, available + currentFile − reserve)))`
 *   with `reserve = max(1 GiB, total × 0.10)`. 0 (= `NO_SPACE`) when the upper bound is below 64 MiB.
 * - **Hold:** Auto and "Until full" = no time limit (lead ruling 3); otherwise 10 min / 30 min / 1 h.
 * - **Daily cap:** "Protect built-in storage" Auto = 12 GB/day on ≤ 16 GB storage, none above; Off = none (§8.4).
 * - **Mode:** "Pause only" always; over the cap, PAUSE_ONLY only when nothing needs persisting (never while paused or
 *   behind live: a running time-shift is never cut, §8.4).
 */
class TimeshiftPolicyImpl : TimeshiftPolicy {
    override fun lengthMs(setting: TimeshiftLength, totalStorageBytes: Long): Long? = when (setting) {
        TimeshiftLength.OFF -> null
        TimeshiftLength.MIN_15 -> 15 * MINUTE
        TimeshiftLength.MIN_30 -> 30 * MINUTE
        TimeshiftLength.H_1 -> 60 * MINUTE
        TimeshiftLength.H_2 -> 120 * MINUTE
        TimeshiftLength.AUTO, TimeshiftLength.PAUSE_ONLY -> autoLength(totalStorageBytes)
    }

    override fun capacityBytes(lengthMs: Long, bytesPerSec: Long, availableBytes: Long, totalBytes: Long, currentFileBytes: Long): Long {
        val rate = if (bytesPerSec > 0) bytesPerSec else ASSUMED_BYTES_PER_SEC
        val reserve = maxOf(FREE_SPACE_RESERVE_MIN, (totalBytes * FREE_SPACE_RESERVE_FRACTION).toLong())
        val upper = minOf(MAX_RING_BYTES, availableBytes + currentFileBytes - reserve)
        if (upper < MIN_RING_BYTES) return 0L
        val wanted = (lengthMs / 1000.0 * rate * CAPACITY_HEADROOM).toLong()
        val bytes = wanted.coerceIn(MIN_RING_BYTES, upper)
        return bytes / RING_BLOCK_BYTES * RING_BLOCK_BYTES
    }

    override fun holdLimitMs(setting: TimeshiftHold): Long? = when (setting) {
        TimeshiftHold.AUTO, TimeshiftHold.UNTIL_FULL -> null
        TimeshiftHold.MIN_10 -> 10 * MINUTE
        TimeshiftHold.MIN_30 -> 30 * MINUTE
        TimeshiftHold.H_1 -> 60 * MINUTE
    }

    override fun dailyLimitBytes(setting: StorageProtection, totalStorageBytes: Long): Long? = when (setting) {
        StorageProtection.OFF -> null
        StorageProtection.AUTO -> if (totalStorageBytes <= SMALL_STORAGE_BYTES) DAILY_LIMIT_SMALL else null
    }

    override fun mode(setting: TimeshiftLength, writtenTodayBytes: Long, dailyLimitBytes: Long?, needsPersist: Boolean): BufferMode = when {
        setting == TimeshiftLength.PAUSE_ONLY -> BufferMode.PAUSE_ONLY
        needsPersist -> BufferMode.PERSIST
        dailyLimitBytes != null && writtenTodayBytes >= dailyLimitBytes -> BufferMode.PAUSE_ONLY
        else -> BufferMode.PERSIST
    }

    override fun pauseRemainingMs(holdRemainingMs: Long?, freeCapacityBytes: Long, bytesPerSec: Long): Long {
        val rate = if (bytesPerSec > 0) bytesPerSec else ASSUMED_BYTES_PER_SEC
        val byFull = maxOf(0L, freeCapacityBytes) * 1000 / rate
        val r = if (holdRemainingMs == null) byFull else minOf(byFull, holdRemainingMs)
        return maxOf(0L, r)
    }

    /** The effective window when space caps the ring: `min(lengthMs, capacity / rate)` (§8.2, "storage is limited"). */
    fun effectiveWindowMs(lengthMs: Long, capacityBytes: Long, bytesPerSec: Long): Long {
        val rate = if (bytesPerSec > 0) bytesPerSec else ASSUMED_BYTES_PER_SEC
        return minOf(lengthMs, capacityBytes * 1000 / rate)
    }

    private fun autoLength(total: Long): Long = if (total <= SMALL_STORAGE_BYTES) AUTO_LENGTH_SMALL_MS else AUTO_LENGTH_LARGE_MS

    private companion object { const val MINUTE = 60_000L }
}
