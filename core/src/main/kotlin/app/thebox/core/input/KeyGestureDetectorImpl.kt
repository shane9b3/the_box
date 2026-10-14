package app.thebox.core.input

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.18.1). */
class KeyGestureDetectorImpl : KeyGestureDetector {
    override fun onKey(input: KeyInput, hasLong: Boolean, out: MutableList<KeyGesture>): Unit = TODO("WP-D")
    override fun onTimer(keyCode: Int, nowMs: Long, out: MutableList<KeyGesture>): Unit = TODO("WP-D")
    override fun reset(): Unit = TODO("WP-D")
}
