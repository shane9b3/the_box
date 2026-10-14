package app.thebox.core.playback.ts

import app.thebox.core.playback.Sniff
import app.thebox.core.playback.SniffResult
import app.thebox.core.playback.ts.TsTestSupport.res
import kotlin.test.Test
import kotlin.test.assertEquals

/** §5.3 WP-B `StreamSniffer`: every fixture in `ts/` and `http/`, offsets, BOM/whitespace, HTML variants, bounds. */
class StreamSnifferTest {
    private val sniffer = StreamSnifferImpl()

    private fun sniff(bytes: ByteArray, eos: Boolean = false, length: Int = minOf(bytes.size, StreamSnifferImpl.SNIFF_MAX_BYTES)): Sniff =
        sniffer.sniff(bytes, length, eos)

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun tsAtOffsetZero() {
        assertEquals(Sniff(SniffResult.TS, 0), sniff(res("ts/h264_aac_2s.ts")))
        assertEquals(Sniff(SniffResult.TS, 0), sniff(res("ts/h264_mp2_2s.ts")))
        assertEquals(Sniff(SniffResult.TS, 0), sniff(res("ts/pcr_jump.bin")))
    }

    @Test
    fun tsAtOffset97AfterGarbage() {
        assertEquals(Sniff(SniffResult.TS, 97), sniff(res("ts/garbage_prefix.ts")))
    }

    @Test
    fun needMoreBelowAThreeSyncRun() {
        val ts = res("ts/h264_aac_2s.ts")
        assertEquals(SniffResult.NEED_MORE, sniff(ts, length = 376).result)
        assertEquals(Sniff(SniffResult.TS, 0), sniff(ts, length = 377))
        val garbage = res("ts/garbage_prefix.ts")
        assertEquals(SniffResult.NEED_MORE, sniff(garbage, length = 97 + 376).result)
        assertEquals(SniffResult.NEED_MORE, sniff(garbage, length = 300).result)
        assertEquals(Sniff(SniffResult.TS, 97), sniff(garbage, length = StreamSnifferImpl.TS_MIN_SNIFF_BYTES))
    }

    @Test
    fun hlsPlaylistWithAndWithoutBomAndWhitespace() {
        val m3u8 = res("http/hls_live.m3u8")
        assertEquals(SniffResult.HLS_PLAYLIST, sniff(m3u8, eos = true).result)
        assertEquals(SniffResult.HLS_PLAYLIST, sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + m3u8).result)
        assertEquals(SniffResult.HLS_PLAYLIST, sniff(text(" \r\n\t\n") + m3u8).result)
        assertEquals(SniffResult.HLS_PLAYLIST, sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text("\n\n#EXTM3U")).result)
    }

    @Test
    fun partialPlaylistHeaderNeedsMore() {
        assertEquals(SniffResult.NEED_MORE, sniff(text("#EXT")).result)
        assertEquals(SniffResult.NEED_MORE, sniff(text("   ")).result)
        assertEquals(SniffResult.TINY, sniff(text("#EXT"), eos = true).result)
    }

    @Test
    fun htmlVariants() {
        assertEquals(SniffResult.HTML, sniff(res("http/splash.html"), eos = true).result)
        assertEquals(SniffResult.HTML, sniff(text("<!DOCTYPE html><html><body>expired</body></html>")).result)
        assertEquals(SniffResult.HTML, sniff(text("<html>")).result)
        assertEquals(SniffResult.HTML, sniff(text("\n  <HTML lang=en>")).result)
        assertEquals(SniffResult.HTML, sniff(text("<Head><title>x</title>")).result)
        assertEquals(SniffResult.HTML, sniff(text("<BODY>")).result)
        assertEquals(SniffResult.HTML, sniff(text("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- c -->\n<html xmlns=\"x\">")).result)
    }

    @Test
    fun xmlWithoutHtmlIsNotHtml() {
        val rss = text("<?xml version=\"1.0\"?><rss><channel>" + " ".repeat(600) + "<html>")
        assertEquals(SniffResult.UNRECOGNIZED, sniff(rss, eos = true).result)
        assertEquals(SniffResult.TINY, sniff(text("<?xml version=\"1.0\"?><rss/>"), eos = true).result)
        // "<html" beyond the 512-byte window does not count; below it the verdict waits for more bytes.
        assertEquals(SniffResult.NEED_MORE, sniff(text("<?xml version=\"1.0\"?>")).result)
    }

    @Test
    fun htmlWithSyncLikeBytesIsStillHtml() {
        val page = ByteArray(1_000) { 'a'.code.toByte() }
        "<html>".toByteArray().copyInto(page, 0)
        for (i in 0 until 5) page[10 + i * 188] = 0x47
        assertEquals(SniffResult.HTML, sniff(page).result)
    }

    @Test
    fun tinyBody() {
        val tiny = res("http/tiny.bin")
        assertEquals(200, tiny.size)
        assertEquals(SniffResult.TINY, sniff(tiny, eos = true).result)
        assertEquals(SniffResult.NEED_MORE, sniff(tiny, eos = false).result)
        assertEquals(SniffResult.TINY, sniffer.sniff(ByteArray(16), 0, true).result)
    }

    @Test
    fun mp4IsUnrecognized() {
        val mp4 = res("http/mp4_head.bin")
        assertEquals(StreamSnifferImpl.SNIFF_MAX_BYTES, mp4.size)
        assertEquals(SniffResult.UNRECOGNIZED, sniff(mp4).result)
        assertEquals(SniffResult.NEED_MORE, sniff(mp4, length = 1_000).result)
        assertEquals(SniffResult.UNRECOGNIZED, sniff(mp4, eos = true, length = 1_000).result)
        assertEquals(SniffResult.TINY, sniff(mp4, eos = true, length = 564).result)
    }

    @Test
    fun tsBeyondOffset188IsNotTs() {
        val ts = res("ts/h264_aac_2s.ts")
        val shifted = ByteArray(200) + ts.copyOf(2_000)
        assertEquals(SniffResult.UNRECOGNIZED, sniff(shifted).result)
    }
}
