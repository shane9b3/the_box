// M6 time-shift, WP-B (docs/design/m6-timeshift.md §17.4: I-TS-4, -5, -12, -20).
package app.thebox.player.timeshift

/**
 * Debug-build overrides for the device suite (set by the debug receiver; null = the real value). Read by
 * [TimeshiftSessionFactoryImpl.prepare] at each tune, so a change applies from the next tune.
 *
 * - [ringBytes]: the ring capacity (I-TS-5 "1-min ring", I-TS-15, I-TS-21), still floored to whole blocks and to
 *   `MIN_RING_BYTES`.
 * - [holdMs]: the "Keep recording while paused" limit (I-TS-4, 30 s).
 * - [dailyLimitBytes]: the daily write cap (I-TS-20, 50 MB); 0 = no cap.
 * - [freeSpaceBytes]: the free space the sizing rule sees (I-TS-12 low space).
 *
 * Never set in release builds (nothing there writes it).
 */
object TimeshiftDebugOverrides {
    @Volatile var ringBytes: Long? = null
    @Volatile var holdMs: Long? = null
    @Volatile var dailyLimitBytes: Long? = null
    @Volatile var freeSpaceBytes: Long? = null

    fun clear() {
        ringBytes = null
        holdMs = null
        dailyLimitBytes = null
        freeSpaceBytes = null
    }
}
