package app.thebox.ui.guide

/** Release builds (§5.5 I-12): a no-op; see the debug source set. */
object GuideDebugCounters {
    @Suppress("UNUSED_PARAMETER")
    fun bump(name: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun count(name: String): Int = 0

    const val ENABLED = false
}
