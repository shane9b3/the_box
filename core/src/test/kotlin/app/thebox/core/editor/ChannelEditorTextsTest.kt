package app.thebox.core.editor

import app.thebox.core.input.BoxAction
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * docs/design/m6-channel-editor.md §5.1: every (P) key goes through `count` (and only those do); no text splices
 * another text's verb (an action label is never an argument).
 */
class ChannelEditorTextsTest {
    private class Strict : EditorTexts {
        val used = HashSet<EditorText>()
        val args = ArrayList<Pair<EditorText, List<Any>>>()
        override fun get(key: EditorText, vararg args: Any): String {
            if (key in EditorText.PLURAL) fail("$key is a quantity string: use count")
            used += key; this.args += key to args.toList()
            return EditorTexts.KEYS.get(key, *args)
        }
        override fun count(key: EditorText, n: Int, vararg args: Any): String {
            if (key !in EditorText.PLURAL) fail("$key is not a quantity string")
            used += key; this.args += key to args.toList()
            return EditorTexts.KEYS.count(key, n, *args)
        }
    }

    private fun EditorWorld.k(vararg a: BoxAction) = apply { for (x in a) { press(x); settle() } }

    @Test fun pluralKeysGoThroughCountAndNoVerbIsSpliced() {
        val t = Strict()
        // A tour over the machine's texts: notices, titles, footers, confirm, sort, move, rename, number, PIN.
        val w = EditorWorld(pinSet = true, unlocked = true, editorTexts = t).open("g:news")
        w.k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT, BoxAction.RIGHT); w.runLine(EditorAction.HIDE); w.settle()
        w.k(BoxAction.SELECT, BoxAction.BACK) // N_UNSELECTED
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.MOVE_TO_GROUP); w.settle(); w.k(BoxAction.SELECT) // N_MOVED
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.ADD_FAVORITE); w.settle()
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.LOCK); w.settle()
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.RENAME); w.settle()
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.SET_NUMBER); w.settle()
        w.k(BoxAction.RIGHT); w.runLine(EditorAction.SORT); w.settle(); w.k(BoxAction.SELECT)
        w.k(BoxAction.RIGHT, BoxAction.ZAP_DOWN, BoxAction.SELECT, BoxAction.LEFT, BoxAction.SELECT) // N_RESET
        w.k(BoxAction.UP, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.RIGHT, BoxAction.SELECT) // Find: FIND_RESULTS
        w.k(BoxAction.CONTEXT_MENU, BoxAction.BACK, BoxAction.SELECT, BoxAction.CONTEXT_MENU, BoxAction.BACK) // head menu titles
        w.k(BoxAction.LEFT, BoxAction.DOWN, BoxAction.CONTEXT_MENU); w.runLine(EditorAction.HIDE); w.settle() // N_GROUPS_HIDDEN
        w.k(BoxAction.SELECT, BoxAction.DOWN, BoxAction.SELECT, BoxAction.CONTEXT_MENU); w.runLine(EditorAction.LOCK); w.settle() // MENU_TITLE_GROUPS, N_LOCKED
        w.k(BoxAction.DOWN, BoxAction.CONTEXT_MENU); w.runLine(EditorAction.UNLOCK); w.settle() // N_UNLOCKED
        w.writeResult = { WriteResult.Failed(WriteFailure.BUSY) }
        w.k(BoxAction.RIGHT, BoxAction.FAVOURITES)
        assertTrue(t.used.size > 20, "tour too short: ${t.used}")
        val labels = EditorText.entries.filter { it.name.startsWith("A_") }.map { EditorTexts.KEYS.get(it) }.toSet()
        for ((key, a) in t.args) for (x in a) assertTrue(x.toString() !in labels, "$key splices the action label $x")
    }
}
