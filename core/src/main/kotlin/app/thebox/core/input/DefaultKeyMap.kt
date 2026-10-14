package app.thebox.core.input

/** WP-D stub (docs/design/m3-guide.md §1.5, §3.18.2): the default bindings; M5's mapping screen edits them. */
class DefaultKeyMap : KeyMap {
    override fun short(context: KeyContext, keyCode: Int): BoxAction? = TODO("WP-D")
    override fun long(context: KeyContext, keyCode: Int): BoxAction? = TODO("WP-D")
    override fun digit(keyCode: Int): Int = TODO("WP-D")
    override fun bindings(context: KeyContext): List<KeyBinding> = TODO("WP-D")
}
