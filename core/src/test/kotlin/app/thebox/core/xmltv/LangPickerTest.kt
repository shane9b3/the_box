package app.thebox.core.xmltv

import kotlin.test.Test
import kotlin.test.assertEquals

class LangPickerTest {
    @Test
    fun ranksExactThenPrimarySubtag() {
        val p = LangPicker(listOf("en-GB", "fr"))
        assertEquals(0, p.rank("en-GB"))
        assertEquals(0, p.rank("EN-gb"))
        assertEquals(0, p.rank("en"))
        assertEquals(0, p.rank("en_US"))
        assertEquals(1, p.rank("fr"))
        assertEquals(LangPicker.UNRANKED, p.rank("de"))
        assertEquals(LangPicker.UNRANKED, p.rank(null))
    }

    @Test
    fun emptyPreferenceKeepsDocumentOrder() {
        assertEquals(LangPicker.UNRANKED, LangPicker.NONE.rank("en"))
        assertEquals(listOf("b", "a"), LangPicker.NONE.order(listOf("b", "a")) { it })
    }

    @Test
    fun orderIsStable() {
        val p = LangPicker(listOf("en"))
        val items = listOf("x" to "fr", "y" to "en", "z" to null, "w" to "en")
        assertEquals(listOf("y", "w", "x", "z"), p.order(items) { it.second }.map { it.first })
    }
}
