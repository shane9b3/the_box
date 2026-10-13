package app.thebox.core.xtream

import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.RawCatchup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XtreamCatalogDecoderImplTest {
    private val decoder = XtreamCatalogDecoderImpl()

    private fun channels(path: String = "xtream/live_streams.json"): List<ParsedChannel> {
        val cats = Fixtures.use("xtream/live_categories.json") { decoder.categories(it) }
        return Fixtures.use(path) { decoder.liveChannels(it, cats).toList() }
    }

    @Test
    fun directSourceIsNotStored() {
        // Code review M1 S-12: an upstream URL with credentials must not reach channel extras.
        val json = """[{"num":1,"name":"A","stream_type":"live","stream_id":7,"category_id":"1","direct_source":"http://up.example/live/res/SECRETPW/7.ts"}]"""
        val c = decoder.liveChannels(json.byteInputStream(), emptyList()).single()
        assertTrue(c.attributes.values.none { "SECRETPW" in it }, c.attributes.toString())
    }

    @Test
    fun fieldByField() {
        val c = channels()
        assertEquals(19, c.size)
        assertEquals((0 until 19).toList(), c.map { it.position })

        assertEquals(
            ParsedChannel(
                kind = EntryKind.LIVE,
                name = "BBC One",
                url = "{{base}}/live/{{u}}/{{p}}/101.{{ext}}",
                position = 0,
                groupTitle = "News",
                extraGroups = emptyList(),
                xtreamCategoryId = "1",
                tvgId = "bbc1.uk",
                logoUrl = "http://logo.example/1.png",
                number = 1,
                catchup = RawCatchup(xtreamTvArchive = true, xtreamArchiveDays = 7),
                xtreamStreamId = 101,
                adult = false,
                attributes = mapOf("added" to "1700000000"),
            ),
            c[0],
        )
        val byId = c.associateBy { it.xtreamStreamId }
        assertEquals(RawCatchup(xtreamTvArchive = false, xtreamArchiveDays = 0), byId[104]!!.catchup)
        assertEquals(RawCatchup(xtreamTvArchive = true, xtreamArchiveDays = null), byId[103]!!.catchup)
        assertEquals(null, byId[104]!!.tvgId)
        assertEquals(null, byId[105]!!.tvgId)
        assertEquals(null, byId[112]!!.tvgId, "M15 junk filter: \"none\"")
        assertEquals("trim.id", byId[119]!!.tvgId)

        assertEquals("Uncategorised", byId[106]!!.groupTitle)
        assertEquals("none", byId[106]!!.xtreamCategoryId)
        assertEquals("Uncategorised", byId[114]!!.groupTitle)
        assertEquals("99", byId[114]!!.xtreamCategoryId)
        assertEquals("Sports", byId[115]!!.groupTitle)
        assertEquals(listOf("UK Sports"), byId[107]!!.extraGroups)

        assertEquals(EntryKind.RADIO, byId[108]!!.kind)
        assertTrue(byId[109]!!.adult)
        assertEquals(
            mapOf("custom_sid" to "SID9", "added" to "1700000000", "thumbnail" to "http://t/x.jpg"), // S-12: no direct_source
            byId[110]!!.attributes,
        )
        assertEquals(42, byId[111]!!.number)
        assertEquals("Stream 113", byId[113]!!.name)
        assertEquals("{{base}}/live/{{u}}/{{p}}/116.{{ext}}", byId[116]!!.url)
        assertEquals("Ünïcode Chännel ✓", byId[120]!!.name)
    }

    @Test
    fun objectFormMapsIdentically() {
        assertEquals(channels(), channels("xtream/live_streams_object.json"))
    }
}
