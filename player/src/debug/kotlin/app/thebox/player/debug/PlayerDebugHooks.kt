package app.thebox.player.debug

import app.thebox.player.engine.TsDebugOverrides

/**
 * Debug-build-only knobs for diagnosis runs (follow-up 2026-10-14, O-1). This source set does not exist in release, so
 * release builds cannot change [TsDebugOverrides] and always use the design flags.
 */
object PlayerDebugHooks {
    /**
     * Turns off **only** `FLAG_DETECT_ACCESS_UNITS` in the TS extractor (compat level and PMT mode unchanged), for
     * sessions prepared after the change. Reset it in the test's teardown.
     */
    var detectAccessUnitsOff: Boolean
        get() = TsDebugOverrides.detectAccessUnitsOff
        set(value) { TsDebugOverrides.detectAccessUnitsOff = value }

    /** (diagnosis 2026-10-18) Directory for raw TS captures of every following live session; null stops capturing. */
    var captureDir: java.io.File?
        get() = TsDebugOverrides.captureDir
        set(value) { TsDebugOverrides.captureDir = value }
}
