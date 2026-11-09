package app.thebox.image

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSvgTest {
    private fun source(text: String) = Buffer().writeUtf8(text)

    @Test
    fun anOrdinarySvgIsAllowedAndNotConsumed() {
        val text = """<?xml version="1.0"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "x.dtd"><svg xmlns="http://www.w3.org/2000/svg"><rect width="4" height="4"/></svg>"""
        val s = source(text)
        assertTrue(GuardedSvg.isSafe(s))
        assertEquals(text, s.readUtf8())
    }

    @Test
    fun anEntityDeclarationIsRefused() {
        assertFalse(GuardedSvg.isSafe(source("""<!DOCTYPE svg [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><svg>&b;</svg>""")))
    }

    @Test
    fun anOversizedSvgIsRefused() {
        assertFalse(GuardedSvg.isSafe(source("<svg>" + "a".repeat(GuardedSvg.MAX_SVG_BYTES.toInt()) + "</svg>")))
    }

    @Test
    fun aUtf16SvgIsRefused() {
        val s = Buffer().write("<svg/>".toByteArray(Charsets.UTF_16LE))
        assertFalse(GuardedSvg.isSafe(s))
    }
}
