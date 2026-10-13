package app.thebox.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CharsetDecoderChainTest {
    private fun decode(chain: CharsetDecoderChain, s: ByteArray, onFb: (String) -> Unit = {}) =
        chain.decode(s, s.size) { onFb(it.name()) }

    @Test fun `ASCII and valid UTF-8 decode as UTF-8`() {
        val c = CharsetDecoderChain(null, null)
        assertEquals("plain", decode(c, "plain".toByteArray()))
        assertEquals("Télé ✓", decode(c, "Télé ✓".toByteArray()))
        assertNull(c.fallback)
        assertEquals("UTF-8", c.reportedName)
    }

    @Test fun `first malformed line picks windows-1252 for Latin text and fires once`() {
        val c = CharsetDecoderChain(null, null)
        val fired = ArrayList<String>()
        assertEquals("Zürich", decode(c, "Zürich".toByteArray(CharsetDecoderChain.CP1252)) { fired += it })
        assertEquals("Ñandú", decode(c, "Ñandú".toByteArray(CharsetDecoderChain.CP1252)) { fired += it })
        assertEquals(listOf("windows-1252"), fired)
        assertEquals("windows-1252", c.reportedName)
    }

    @Test fun `Cyrillic high bytes pick windows-1251`() {
        val c = CharsetDecoderChain(null, null)
        assertEquals("Первый канал", decode(c, "Первый канал".toByteArray(CharsetDecoderChain.CP1251)))
        assertEquals("windows-1251", c.fallback!!.name())
    }

    @Test fun `S15 - one bad line in a UTF-8 file only decodes that line with the fallback`() {
        val c = CharsetDecoderChain(null, null)
        assertEquals("Café", decode(c, "Café".toByteArray()))
        assertEquals("Café 1252", decode(c, "Café 1252".toByteArray(CharsetDecoderChain.CP1252)))
        // Later UTF-8 lines are still UTF-8, not garbled as 1252 ("CafÃ©").
        assertEquals("Café again", decode(c, "Café again".toByteArray()))
        assertEquals("日本", decode(c, "日本".toByteArray()))
        assertEquals(1, c.fallbackLines)
    }

    @Test fun `fallback rule thresholds - 3 Cyrillic bytes and 70 percent`() {
        fun pick(vararg b: Int) = CharsetDecoderChain.chooseFallback(ByteArray(b.size) { b[it].toByte() }, b.size).name()
        assertEquals("windows-1252", pick(0xC0, 0xC1)) // only 2
        assertEquals("windows-1251", pick(0xC0, 0xC1, 0xC2))
        assertEquals("windows-1251", pick(0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0x80, 0x81, 0x82)) // exactly 70 %
        assertEquals("windows-1252", pick(0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0x80, 0x81, 0x82)) // 66 %
    }

    @Test fun `isolated Latin accents stay windows-1252 even with many E0-FF bytes`() {
        fun pick(s: String, cs: java.nio.charset.Charset) = s.toByteArray(cs).let { CharsetDecoderChain.chooseFallback(it, it.size).name() }
        assertEquals("windows-1252", pick("#EXTINF:-1 group-title=\"Français\",Télé Québec", CharsetDecoderChain.CP1252))
        assertEquals("windows-1252", pick("Señor Müller à la télé", CharsetDecoderChain.CP1252))
        assertEquals("windows-1251", pick("#EXTINF:-1,НТВ", CharsetDecoderChain.CP1251))
        assertEquals("windows-1251", pick("Первый канал HD", CharsetDecoderChain.CP1251))
    }

    @Test fun `forced charset wins over everything`() {
        val c = CharsetDecoderChain(CharsetDecoderChain.CP1251, null)
        // 0xD0 is "Р" in 1251 (it would be "Ð" in 1252), and valid UTF-8 is not tried first.
        assertEquals("Р", decode(c, byteArrayOf(0xD0.toByte())))
        assertEquals("Г©", decode(c, "é".toByteArray())) // valid UTF-8 C3 A9, but forced 1251 wins
        assertEquals("windows-1251", c.reportedName)
    }

    @Test fun `a non-UTF-8 hint is used for every line, a UTF-8 hint is ignored`() {
        val latin = CharsetDecoderChain(null, CharsetDecoderChain.parseCharset("text/plain; charset=ISO-8859-1"))
        assertEquals("Ã©", decode(latin, "é".toByteArray()))
        val utf = CharsetDecoderChain(null, CharsetDecoderChain.parseCharset("audio/x-mpegurl; charset=utf-8"))
        assertEquals("é", decode(utf, "é".toByteArray()))
    }

    @Test fun `parseCharset accepts names and content types and rejects junk`() {
        assertEquals("windows-1251", CharsetDecoderChain.parseCharset("windows-1251")!!.name())
        assertEquals("UTF-8", CharsetDecoderChain.parseCharset("application/x-mpegURL;charset=\"UTF-8\"")!!.name())
        assertNull(CharsetDecoderChain.parseCharset("text/plain"))
        assertNull(CharsetDecoderChain.parseCharset("no-such-charset"))
        assertNull(CharsetDecoderChain.parseCharset(null))
    }
}
