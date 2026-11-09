package app.thebox.core.settings.state

import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.GuidePrefs

/**
 * (reviewed 2026-10-16, S12) The one place `GuidePrefs` (stored) maps onto `GuideSettings` (read by the guide), used by
 * `PersistedGuideSettings` and the Interface page's values: clock SYSTEM → the system's 24-hour setting; automatic
 * mini player and ambient (`null`) → Still / Off on a low-end device (the device class, or Performance = Low-end).
 * `debugUnlocked` is never stored (decision §0.4).
 */
object GuidePrefsMapping {
    /** Low-end mode as `GuideViewModel.lowEndFor` resolves it: Performance Low, or Auto on a low-end device. */
    fun lowEnd(performance: PerformanceMode, deviceLowEnd: Boolean): Boolean = when (performance) {
        PerformanceMode.LOW -> true
        PerformanceMode.HIGH -> false
        PerformanceMode.AUTO -> deviceLowEnd
    }

    fun clock24(clock: ClockMode, system24h: Boolean): Boolean = when (clock) {
        ClockMode.SYSTEM -> system24h
        ClockMode.H12 -> false
        ClockMode.H24 -> true
    }

    fun miniStill(p: GuidePrefs, deviceLowEnd: Boolean): Boolean = p.miniStill ?: lowEnd(p.performance, deviceLowEnd)

    fun ambient(p: GuidePrefs, deviceLowEnd: Boolean): Boolean = p.ambient ?: !lowEnd(p.performance, deviceLowEnd)

    fun toSettings(p: GuidePrefs, system24h: Boolean, deviceLowEnd: Boolean, debugUnlocked: Boolean = false): GuideSettings = GuideSettings(
        clock24 = clock24(p.clock, system24h),
        showLogos = p.showLogos,
        showNumbers = p.showNumbers,
        performance = p.performance,
        miniStill = miniStill(p, deviceLowEnd),
        ambient = ambient(p, deviceLowEnd),
        exitConfirmation = p.exitConfirmation,
        openGuideOn = p.openGuideOn,
        reminderAutoSwitch = p.reminderAutoSwitch,
        reminderLeadSec = p.reminderLeadSec,
        scrollDescriptions = p.scrollDescriptions,
        debugUnlocked = debugUnlocked,
    )

    /**
     * The stored form after a `GuideSettingsSource.update` changed [before] into [after] (the debug receiver and M3/M4
     * callers write whole `GuideSettings`). Only fields that changed between [before] and [after] are applied, so an
     * untouched automatic value (clock SYSTEM, mini player, ambient) stays automatic and a transform queued before the
     * store's read (S1) never overwrites stored fields it did not change.
     */
    fun fromSettings(old: GuidePrefs, before: GuideSettings, after: GuideSettings): GuidePrefs = old.copy(
        clock = if (after.clock24 == before.clock24) old.clock else if (after.clock24) ClockMode.H24 else ClockMode.H12,
        showLogos = pick(old.showLogos, before.showLogos, after.showLogos),
        showNumbers = pick(old.showNumbers, before.showNumbers, after.showNumbers),
        performance = pick(old.performance, before.performance, after.performance),
        miniStill = if (after.miniStill == before.miniStill) old.miniStill else after.miniStill,
        ambient = if (after.ambient == before.ambient) old.ambient else after.ambient,
        exitConfirmation = pick(old.exitConfirmation, before.exitConfirmation, after.exitConfirmation),
        openGuideOn = pick(old.openGuideOn, before.openGuideOn, after.openGuideOn),
        reminderAutoSwitch = pick(old.reminderAutoSwitch, before.reminderAutoSwitch, after.reminderAutoSwitch),
        reminderLeadSec = pick(old.reminderLeadSec, before.reminderLeadSec, after.reminderLeadSec),
        scrollDescriptions = pick(old.scrollDescriptions, before.scrollDescriptions, after.scrollDescriptions),
    )

    /** [after] when the caller changed the field, else the stored [old]. */
    fun <T> pick(old: T, before: T, after: T): T = if (after == before) old else after
}
