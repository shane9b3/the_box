package app.thebox.data.di

/** Injected wall clock (§6.6: no `System.currentTimeMillis()` inside logic classes). */
fun interface WallClock {
    fun now(): Long

    companion object {
        val SYSTEM = WallClock { System.currentTimeMillis() }
    }
}
