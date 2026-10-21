package app.thebox.core.guide.render

import app.thebox.core.common.Hash64
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.fakes.FakeTextMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/design/m3-guide.md §3.6.4, §5.1 (B4, S6). Fake widths: chars × size × 0.5 (CELL_TITLE 30 px: 15 px per char). */
class TextFitTest {
    private val st = GuideTextStyle.CELL_TITLE
    private val ell = 15f

    @Test
    fun fitsWholeOrCutsBeforeTheEllipsis() {
        val fit = TextFit(FakeTextMeasurer(), 512)
        val t = "Six O'Clock News"                    // 16 chars = 240 px
        assertEquals(16, fit.fit(t, Hash64.of(t), st, 250f, ell))
        assertEquals(240f, fit.lastWidth)
        assertEquals(5, fit.fit(t, Hash64.of(t), st, 100f, ell))    // (100 − 15) / 15 = 5
        assertEquals(75f, fit.lastWidth)
        assertEquals(0, fit.fit(t, Hash64.of(t), st, 5f, ell))
        assertFalse(fit.lastRtl)
    }

    @Test
    fun cachedByContentHashWidthAndStyleWithLengthCheck() {
        val fit = TextFit(FakeTextMeasurer(), 512)
        val long = "A very long programme title"
        fit.fit(long, 42L, st, 100f, ell)
        fit.fit(long, 42L, st, 100f, ell)
        assertEquals(1, fit.hits)
        // Same key (a stale or colliding hash), shorter string: the length check rejects the entry (B4).
        val short = "Short"
        val n = fit.fit(short, 42L, st, 100f, ell)
        assertEquals(5, n)
        assertEquals(2, fit.misses)
        // Another style at the same key and width is a different entry.
        fit.fit(long, 42L, GuideTextStyle.CH_NAME, 100f, ell)
        assertEquals(3, fit.misses)
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val fit = TextFit(FakeTextMeasurer(), 512)
        // "News 😀 tonight": the emoji is chars 5..6. A cut of 6 would split it.
        val t = "News 😀 tonight"
        val n = fit.fit(t, Hash64.of(t), st, 6 * 13f + ell, ell)
        assertEquals(5, n)
        assertFalse(Character.isHighSurrogate(t[n - 1]))
    }

    @Test
    fun neverSplitsCombiningOrZwjSequences() {
        assertEquals(3, TextFit.backOff("Café au lait", 4, complex = false))          // e + combining acute
        assertEquals(5, TextFit.backOff("Café au lait", 5, complex = false))
        val family = "A 👨‍👩‍👧 B"
        for (k in 3..9) assertEquals(2, TextFit.backOff(family, k, complex = false), "cut $k inside the ZWJ family")
        assertEquals(2, TextFit.backOff("A ❤️ B", 3, complex = false))             // variation selector
        assertEquals(2, TextFit.backOff("A 👍🏽 B", 4, complex = false)) // skin tone modifier
        assertEquals(2, TextFit.backOff("A 🇬🇧 B", 4, complex = false)) // regional-indicator flag
        assertEquals(3, TextFit.backOff("Café au lait", 4, complex = true))
        assertEquals(5, TextFit.backOff("Café au lait", 5, complex = true))
    }

    @Test
    fun detectsRightToLeft() {
        val fit = TextFit(FakeTextMeasurer(), 512)
        val arabic = "الأخبار المسائية"
        fit.fit(arabic, Hash64.of(arabic), st, 60f, ell)
        assertTrue(fit.lastRtl)
        assertTrue(TextFit.isRtl("חדשות 12"))
        assertFalse(TextFit.isRtl("12 News"))
        assertFalse(TextFit.isRtl("123"))
    }

    @Test
    fun clearDropsEverything() {
        val fit = TextFit(FakeTextMeasurer(), 8)
        fit.fit("Hello world", 1L, st, 50f, ell)
        fit.clear()
        fit.fit("Hello world", 1L, st, 50f, ell)
        assertEquals(0, fit.hits)
        assertEquals(2, fit.misses)
    }
}
