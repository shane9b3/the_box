package app.thebox.core.diag

/**
 * Process-wide switches of the debug harness (docs/design/m5-setup-settings.md §6.5), set only by the debug build's
 * `GuideDebugReceiver` (`thebox-debug://guide?clock_offset_ms=…&phone_port=…`); in release builds nothing writes them,
 * so they stay at their neutral values. Readers: the PIN lockout clock and the LAN server's lifetime and isolation hint
 * (WP-C, WP-D) add [clockOffsetMs] to their wall clock; the LAN server prefers [phonePort] when it is set.
 */
object DebugOverrides {
    @Volatile var clockOffsetMs: Long = 0
    @Volatile var phonePort: Int? = null

    /** Wall clock with the debug offset (the offset is 0 outside tests). */
    fun nowMs(): Long = System.currentTimeMillis() + clockOffsetMs

    fun reset() {
        clockOffsetMs = 0
        phonePort = null
    }
}
