package app.thebox.ui.guide

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only (docs/design/m3-guide.md §5.5 I-12, §2.13 item 20): composition counts of the guide's hot
 * composables, bumped from a `SideEffect`, read by the instrumented suite. Release builds count nothing.
 */
object GuideDebugCounters {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun bump(name: String) {
        counts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
    }

    fun count(name: String): Int = counts[name]?.get() ?: 0

    const val ENABLED = true
}
