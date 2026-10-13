package app.thebox.core.xtream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LenientJsonTest {
    private fun o(s: String): JsonObject = XtreamJson.json.parseToJsonElement(s).jsonObject

    // Every JSON type, once per accessor (§5.3 "every row of §3.7.2 × every JSON type").
    private val all = o(
        """{"s":" abc ","n":12,"f":12.0,"fx":12.5,"ns":"12","nsf":"12.0","t":true,"fa":false,"nul":null,"e":"","sn":"null",
            "a":[1,"x",null],"emptyA":[],"obj":{"k":"v","n":2},"emptyO":{},"comma":"7,5","yes":"YES","off":"off","two":2,"zero":0,
            "big":9999999999,"junk":"abc","strArr":"[\"a\",\"b\"]","csv":"m3u8, ts;rtmp"}""",
    )

    @Test
    fun str() {
        assertEquals("abc", LenientJson.str(all, "s"))
        assertEquals("12", LenientJson.str(all, "n"))
        assertEquals("12.0", LenientJson.str(all, "f"))
        assertEquals("true", LenientJson.str(all, "t"))
        for (k in listOf("nul", "e", "sn", "a", "emptyA", "obj", "emptyO", "missing")) assertNull(LenientJson.str(all, k), k)
    }

    @Test
    fun longAndInt() {
        assertEquals(12L, LenientJson.long(all, "n"))
        assertEquals(12L, LenientJson.long(all, "f"))
        assertNull(LenientJson.long(all, "fx"))
        assertEquals(12L, LenientJson.long(all, "ns"))
        assertEquals(12L, LenientJson.long(all, "nsf"))
        assertEquals(1L, LenientJson.long(all, "t"))
        assertEquals(0L, LenientJson.long(all, "fa"))
        for (k in listOf("nul", "e", "junk", "a", "obj", "missing")) assertNull(LenientJson.long(all, k), k)
        assertEquals(9999999999L, LenientJson.long(all, "big"))
        assertNull(LenientJson.int(all, "big"))
        assertEquals(12, LenientJson.int(all, "ns"))
    }

    @Test
    fun bool() {
        assertEquals(true, LenientJson.bool(all, "t"))
        assertEquals(false, LenientJson.bool(all, "fa"))
        assertEquals(true, LenientJson.bool(all, "two"))
        assertEquals(false, LenientJson.bool(all, "zero"))
        assertEquals(true, LenientJson.bool(all, "yes"))
        assertEquals(false, LenientJson.bool(all, "off"))
        assertEquals(true, LenientJson.bool(o("""{"x":"1"}"""), "x"))
        assertEquals(false, LenientJson.bool(o("""{"x":"0"}"""), "x"))
        assertEquals(true, LenientJson.bool(o("""{"x":"True"}"""), "x"))
        for (k in listOf("nul", "e", "junk", "a", "obj", "missing")) assertNull(LenientJson.bool(all, k), k)
    }

    @Test
    fun double() {
        assertEquals(7.5, LenientJson.double(all, "comma"))
        assertEquals(12.5, LenientJson.double(all, "fx"))
        assertEquals(12.0, LenientJson.double(all, "ns"))
        for (k in listOf("nul", "e", "junk", "a", "obj", "missing")) assertNull(LenientJson.double(all, k), k)
    }

    @Test
    fun strList() {
        assertEquals(listOf("1", "x"), LenientJson.strList(all, "a"))
        assertEquals(listOf("a", "b"), LenientJson.strList(all, "strArr"))
        assertEquals(listOf("m3u8", "ts", "rtmp"), LenientJson.strList(all, "csv"))
        assertEquals(listOf("v", "2"), LenientJson.strList(all, "obj"))
        assertEquals(listOf("abc"), LenientJson.strList(all, "s"))
        for (k in listOf("nul", "e", "emptyA", "emptyO", "missing")) assertTrue(LenientJson.strList(all, k).isEmpty(), k)
    }

    @Test
    fun objAndValues() {
        assertEquals("v", LenientJson.str(LenientJson.obj(all, "obj"), "k"))
        assertTrue(assertNotNull(LenientJson.obj(all, "emptyA")).isEmpty())
        assertNull(LenientJson.obj(all, "a"))
        assertNull(LenientJson.obj(all, "s"))
        assertNull(LenientJson.obj(all, "nul"))
        assertEquals(2, LenientJson.objOrArrayValues(all, "obj").size)
        assertEquals(3, LenientJson.objOrArrayValues(all, "a").size)
        assertTrue(LenientJson.objOrArrayValues(all, "s").isEmpty())
    }
}
