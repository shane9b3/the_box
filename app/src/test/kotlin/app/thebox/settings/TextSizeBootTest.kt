package app.thebox.settings

import app.thebox.core.settings.TextSize
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSizeBootTest {
    @Test
    fun anUnknownOrMissingMirrorValueIsTheDefaultSize() {
        assertEquals(TextSize.DEFAULT, TextSizeBoot.parse(null))
        assertEquals(TextSize.DEFAULT, TextSizeBoot.parse("HUGE"))
        assertEquals(TextSize.LARGEST, TextSizeBoot.parse("LARGEST"))
        assertEquals(TextSize.SMALL, TextSizeBoot.parse("SMALL"))
    }
}
