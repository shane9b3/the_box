package app.thebox.ui.editor

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.editor.EditorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.IllegalFormatException

/** docs/design/m6-channel-editor.md §6.3: every `EditorText` maps to a string or a plural, and the plurals agree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourceEditorTextsTest {
    private val texts = ResourceEditorTexts(ApplicationProvider.getApplicationContext())

    /** Argument shapes the keys use: numbers, names, or a number then a name. */
    private val shapes: List<Array<Any>> = listOf(arrayOf(3, 2, 1), arrayOf("A", "B", "C"), arrayOf(3, "B", "C"))

    private fun render(key: EditorText, n: Int): String {
        for (args in shapes) {
            try {
                return if (key in EditorText.PLURAL) texts.count(key, n, *args.drop(1).toTypedArray()) else texts.get(key, *args)
            } catch (e: IllegalFormatException) {
                continue
            }
        }
        fail("no argument shape formats $key")
        error("unreachable")
    }

    @Test
    fun everyKeyMapsAndFormatsWithoutPlaceholdersLeft() {
        for (key in EditorText.entries) {
            val s = render(key, 3)
            assertFalse("$key is empty", s.isBlank())
            assertFalse("$key left a placeholder: $s", s.contains('%'))
        }
    }

    @Test
    fun pluralsAgreeWithTheirCount() {
        assertEquals("1 channel selected", texts.count(EditorText.MENU_TITLE_CHANNELS, 1))
        assertEquals("3 channels selected", texts.count(EditorText.MENU_TITLE_CHANNELS, 3))
        assertEquals("1 channel · 0 hidden", texts.count(EditorText.CHANNELS_COUNT, 1, 0))
        assertEquals("120 channels · 8 hidden", texts.count(EditorText.CHANNELS_COUNT, 120, 8))
        assertEquals("Move 1 channel to", texts.count(EditorText.PICKER_TITLE, 1))
        assertEquals("Moved 3 channels to UK | Sport", texts.count(EditorText.N_MOVED, 3, "UK | Sport"))
        assertEquals("1 stays hidden by a rule or by “Hide channels with no event”. See Hide rules or playlist settings.", texts.count(EditorText.N_SKIPPED_RULE, 1))
        // A plural key through get() takes its count from the first argument; the rest follow.
        assertEquals("Moved 2 channels to News", texts.get(EditorText.N_MOVED, 2, "News"))
        assertEquals("2 groups selected", texts.get(EditorText.MENU_TITLE_GROUPS, 2))
    }

    /**
     * Code review 2026-11-25, UI open item 1: the menu's disabled reasons wrap to at most two lines in the card at Default
     * and Largest (22 design px, the app's regular face), so the next step is never cut.
     */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun disabledReasonsFitTwoLinesAtLargest() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val face = app.thebox.ui.sports.sansTypeface(ctx, app.thebox.ui.R.font.instrument_sans_regular)
        for (f in listOf(1.0f, 1.2f)) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 22 * f }
            for (key in listOf(EditorText.D_RULE, EditorText.D_NO_EVENT, EditorText.D_RULE_ANY)) {
                val text = texts.get(key)
                var lines = 1
                var cur = ""
                for (w in text.split(' ')) {
                    val next = if (cur.isEmpty()) w else "$cur $w"
                    if (paint.measureText(next) <= EditorUi.MENU_REASON_W) cur = next else { lines++; cur = w }
                }
                assertTrue("$key at $f: $text", lines <= 2)
            }
        }
    }
}
