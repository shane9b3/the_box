package app.thebox.player.datasource

import app.thebox.player.datasource.HlsPlaylistScanner.Ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** (HLS edge hand-over, stage 0) The playlist line scan: real-shaped bodies, CRLF, splits across reads. */
class HlsPlaylistScannerTest {
    /** Identity "hash" so the test can see which name was taken (the real one is salted SHA-256). */
    private fun scan(body: String, baseHost: String? = "edge1.example", chunk: Int = Int.MAX_VALUE): HlsPlaylistScanner.Summary {
        val sc = HlsPlaylistScanner(baseHost) { "h($it)" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            // Feed from an offset inside a bigger buffer, as Media3 does.
            val buf = ByteArray(n + 7)
            System.arraycopy(bytes, i, buf, 3, n)
            sc.feed(buf, 3, n)
            i += n
        }
        return sc.finish()
    }

    private val xtreamLive = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:48213
        #EXTINF:10.000000,
        /hls/0a1b2c3d4e5f/538850_48213.ts
        #EXTINF:10.000000,
        /hls/0a1b2c3d4e5f/538850_48214.ts
        #EXTINF:9.960000,
        /hls/0a1b2c3d4e5f/538850_48215.ts
    """.trimIndent() + "\n"

    @Test
    fun xtreamShapedMediaPlaylistWithoutPdt() {
        val s = scan(xtreamLive)
        assertFalse(s.master)
        assertTrue(s.isMedia)
        assertEquals(48213L, s.msn)
        assertEquals(48215L, s.lastMsn)
        assertEquals(3, s.segments)
        assertEquals(10L, s.targetDurationS)
        assertNull(s.discontinuitySequence)
        assertNull(s.pdtFirst)
        assertNull(s.pdtLast)
        assertEquals("h(538850_48215.ts)", s.lastNameHash)
        assertEquals(setOf(Ref.ABS_PATH), s.segmentRefs)
        assertEquals(29_960L, s.durationMs)
        assertEquals(listOf(48213L, 48214L, 48215L), s.entries.map { it.msn })
        assertTrue(s.entries.all { it.pdtMs == null })
        assertTrue(s.keyMethods.isEmpty())
    }

    @Test
    fun crlfAndEveryReadSplitGiveTheSameSummary() {
        val crlf = xtreamLive.replace("\n", "\r\n")
        val whole = scan(xtreamLive)
        for (chunk in listOf(1, 2, 3, 7, 64, 100_000)) {
            assertEquals("chunk $chunk", whole, scan(crlf, chunk = chunk))
            assertEquals("chunk $chunk", whole, scan(xtreamLive, chunk = chunk))
        }
    }

    @Test
    fun pdtDiscontinuitiesKeysAndUriClasses() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-DISCONTINUITY-SEQUENCE:4
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.other.example/k?token=SECRET",IV=0x0123
            #EXT-X-PROGRAM-DATE-TIME:2026-11-25T12:02:30.000Z
            #EXTINF:6.0,
            seg100.ts?sig=abc
            #EXTINF:6.0,
            https://edge1.example/live/seg101.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-PROGRAM-DATE-TIME:2026-11-25T12:02:45.500+0000
            #EXTINF:6.0,
            http://edge2.example:8080/live/seg102.ts
        """.trimIndent()
        val s = scan(body) // no final newline: the last line still counts
        assertEquals(100L, s.msn)
        assertEquals(3, s.segments)
        assertEquals(4L, s.discontinuitySequence)
        assertEquals(1, s.discontinuities)
        assertEquals("2026-11-25T12:02:30.000Z", s.pdtFirst)
        assertEquals("2026-11-25T12:02:45.500+0000", s.pdtLast)
        assertEquals(setOf(Ref.REL, Ref.ABS_SAME, Ref.ABS_OTHER), s.segmentRefs)
        assertEquals(setOf("AES-128"), s.keyMethods)
        assertEquals(setOf(Ref.ABS_OTHER), s.keyRefs)
        assertEquals("h(seg102.ts)", s.lastNameHash)
        assertEquals(listOf("h(seg100.ts)", "h(seg101.ts)", "h(seg102.ts)"), s.entries.map { it.nameHash })
        val t0 = java.time.Instant.parse("2026-11-25T12:02:30Z").toEpochMilli()
        assertEquals(listOf(t0, t0 + 6_000, t0 + 15_500), s.entries.map { it.pdtMs })
    }

    @Test
    fun masterPlaylistIsRecognisedAndCountsVariants() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="en",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="a"
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a"
            http://edge9.example/1080/index.m3u8?token=x
        """.trimIndent() + "\n"
        val s = scan(body, baseHost = "panel.example")
        assertTrue(s.master)
        assertFalse(s.isMedia)
        assertEquals(2, s.variants)
        assertEquals(1, s.renditions)
        assertEquals(0, s.segments)
        assertEquals(setOf(Ref.REL, Ref.ABS_OTHER), s.segmentRefs)
    }

    @Test
    fun fmp4MapNamesAndBomAndAnOverlongLine() {
        val long = "#EXT-X-SOMETHING:" + "x".repeat(HlsPlaylistScanner.MAX_LINE + 10)
        val body = "﻿#EXTM3U\n#EXT-X-TARGETDURATION:4\n$long\n#EXT-X-MAP:URI=\"init-v1.mp4\"\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4,\nv1-7.m4s\n#EXT-X-ENDLIST\n"
        val s = scan(body, chunk = 5)
        assertEquals(7L, s.msn)
        assertEquals(1, s.segments)
        assertEquals(setOf("h(init-v1.mp4)"), s.mapNameHashes)
        assertEquals(1, s.linesSkipped)
        assertTrue(s.endList)
    }

    @Test
    fun noMediaSequenceTagMeansUnknownNotZero() {
        val s = scan("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n")
        assertNull(s.msn)
        assertNull(s.lastMsn)
        assertEquals(1, s.segments)
        assertEquals(0L, s.entries.single().msn)
    }

    @Test
    fun attributesQuotedUnquotedAndAbsent() {
        assertEquals("AES-128", HlsPlaylistScanner.attr("#EXT-X-KEY:METHOD=AES-128,URI=\"k,1\"", "METHOD"))
        assertEquals("k,1", HlsPlaylistScanner.attr("#EXT-X-KEY:METHOD=AES-128,URI=\"k,1\"", "URI"))
        assertEquals("0x01", HlsPlaylistScanner.attr("#EXT-X-KEY:URI=\"a,b\",IV=0x01", "IV"))
        assertNull(HlsPlaylistScanner.attr("#EXT-X-KEY:METHOD=NONE", "URI"))
        assertEquals("seg1.ts", HlsPlaylistScanner.fileName("http://h/a/b/seg1.ts?x=1#f"))
    }
}
