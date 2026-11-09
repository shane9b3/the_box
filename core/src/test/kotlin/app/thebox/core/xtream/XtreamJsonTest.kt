package app.thebox.core.xtream

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class XtreamJsonTest {
    private fun bytes(s: String) = ByteArrayInputStream(s.toByteArray())

    @Test
    fun sniffSkipsBomAndWhitespace() {
        val s = XtreamJson.sniff(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + " \n\t[1]".toByteArray()))
        assertEquals(XtreamPayloadKind.ARRAY, s.kind)
        assertEquals('['.code, s.stream.read())
        assertEquals(XtreamPayloadKind.OBJECT, XtreamJson.sniff(bytes("  {}")).kind)
        assertEquals(XtreamPayloadKind.HTML, XtreamJson.sniff(bytes("\n<html>")).kind)
        assertEquals(XtreamPayloadKind.EMPTY, XtreamJson.sniff(bytes(" \r\n")).kind)
        assertEquals(XtreamPayloadKind.OTHER, XtreamJson.sniff(bytes("Access denied")).kind)
        assertEquals(XtreamPayloadKind.ARRAY, XtreamJson.sniffKind("﻿ [".toByteArray()))
        assertEquals(XtreamPayloadKind.EMPTY, XtreamJson.sniffKind(ByteArray(0)))
    }

    @Test
    fun parseErrorDetailNeverQuotesTheBody() {
        // Code review M1 S-5: kotlinx appends "JSON input: …" (the login body holds user_info.password).
        val body = """{"user_info":{"username":"alice","password":"SECRETPW","auth":1},"server_info":{"url":"h" """
        val e = assertFailsWith<SourceException> { XtreamJson.tree(bytes(body)) }
        val detail = (e.error as SourceError.Parse).detail
        kotlin.test.assertFalse(detail.contains("SECRETPW"), detail)
        kotlin.test.assertFalse(generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("SECRETPW") })
    }

    @Test
    fun itemsArrayAndObjectFormAgree() {
        val a = Fixtures.use("xtream/live_streams.json") { XtreamJson.items(it).toList() }
        val o = Fixtures.use("xtream/live_streams_object.json") { XtreamJson.items(it).toList() }
        assertEquals(20, a.size)
        assertEquals(a, o)
    }

    @Test
    fun objectFormTransformHandlesTrickyStrings() {
        val src = """ { "0" : {"name":"a}b,\"c\": {","x":[1,{"y":"]"}]}, "k\"ey,1":{"name":"second"} ,"2": 5, "3":"s,t" } """
        val items = XtreamJson.items(bytes(src)).toList()
        assertEquals(4, items.size)
        assertEquals("a}b,\"c\": {", (items[0] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals("second", (items[1] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(5), items[2])
        assertEquals("s,t", items[3].jsonPrimitive.content)
        assertEquals(0, XtreamJson.items(bytes("{}")).count())
    }

    @Test
    fun itemsAreLazy() {
        // The stream fails after ~120 KB (well past the decoder's read-ahead): the first items must already be available.
        val head = ("[" + (1..5000).joinToString(",") { """{"stream_id":$it}""" } + ",").toByteArray()
        val failing = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < head.size) head[i++].toInt() and 0xFF else throw java.io.IOException("boom")
        }
        val it = XtreamJson.items(failing).iterator()
        assertEquals(1L, LenientJson.long(it.next() as JsonObject, "stream_id"))
        assertEquals(2L, LenientJson.long(it.next() as JsonObject, "stream_id"))
        val e = assertFailsWith<SourceException> { while (it.hasNext()) it.next() }
        assertIs<SourceError.Parse>(e.error)
    }

    @Test
    fun itemsRejectNonJson() {
        assertEquals(SourceError.NotASource(NotASourceKind.HTML_PAGE), assertFailsWith<SourceException> { XtreamJson.items(bytes("<html>")) }.error)
        assertEquals(SourceError.NotASource(NotASourceKind.EMPTY), assertFailsWith<SourceException> { XtreamJson.items(bytes("")) }.error)
        assertEquals(SourceError.NotASource(NotASourceKind.WRONG_FORMAT), assertFailsWith<SourceException> { XtreamJson.items(bytes("nope")) }.error)
        assertIs<SourceError.Parse>(assertFailsWith<SourceException> { XtreamJson.items(bytes("[{\"a\":1},{\"b\":")).toList() }.error)
    }

    @Test
    fun bomFixture() {
        val ids = Fixtures.use("xtream/live_streams_bom.json") { XtreamDecoders.liveStreams(it).map { s -> s.streamId }.toList() }
        assertEquals(listOf(101L, 102L, 103L), ids)
    }

    @Test
    fun oneOverLongItemFailsAsUnsupportedInBothForms() {
        val huge = "a".repeat(XtreamJson.MAX_ITEM_BYTES + 10)
        val arr = """[{"name":"ok"},{"name":"$huge"}]"""
        val e = assertFailsWith<SourceException> { XtreamJson.items(bytes(arr)).toList() }
        assertIs<SourceError.Unsupported>(e.error)
        val obj = """{"0":{"name":"ok"},"1":{"name":"$huge"}}"""
        val o = assertFailsWith<SourceException> { XtreamJson.items(bytes(obj)).toList() }
        assertIs<SourceError.Unsupported>(o.error)
        // Many items whose total is far over the cap are fine: the count restarts at each top-level comma,
        // and commas, brackets and escaped quotes inside strings do not end an item.
        val item = "a".repeat(64 * 1024)
        val many = (1..40).joinToString(",", "[", "]") { """{"name":"$item","x":"a,]\\\"[","y":[1,2]}""" }
        assertEquals(40, XtreamJson.items(bytes(many)).toList().size)
    }
}
