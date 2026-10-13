package app.thebox.core.xmltv

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.io.Compression
import okio.buffer
import okio.source
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {
    data class Prog(
        val channel: String, val start: Int, val stop: Int, val title: String, val subtitle: String?, val desc: String?,
        val categories: List<String>, val label: String?, val season: Int, val episode: Int, val series: String?,
        val icon: String?, val rating: String?, val star: String?, val year: Int, val credits: String?, val flags: Int, val catchupId: String?,
    )

    class Collect(private val accept: (String, Int, Int) -> Boolean = { _, _, _ -> true }) : XmltvHandler {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<Prog>()
        val bad = ArrayList<String>()
        var ended = false
        val instances = HashSet<Int>()

        override fun onChannel(c: XmltvChannel) { channels += c }
        override fun acceptProgramme(channelId: String, startMin: Int, stopMin: Int) = accept(channelId, startMin, stopMin)
        override fun onProgramme(p: XmltvProgramme) {
            instances += System.identityHashCode(p)
            programmes += Prog(
                p.channelId, p.startMin, p.stopMin, p.title, p.subtitle, p.description, p.categories.toList(), p.episodeLabel,
                p.season, p.episode, p.seriesRef, p.iconUrl, p.rating, p.starRating, p.year, p.credits, p.flags, p.catchupId,
            )
        }
        override fun onBadTime(channelId: String) { bad += channelId }
        override fun onEnd() { ended = true }
        fun byTitle(t: String) = programmes.single { it.title == t }
    }

    private fun res(name: String) = File(javaClass.getResource("/xmltv/$name")!!.toURI())
    private fun min(iso: String) = (OffsetDateTime.parse(iso).toEpochSecond() / 60).toInt()

    private fun parse(
        input: InputStream,
        options: XmltvParser.Options = XmltvParser.Options.DEFAULT,
        handler: Collect = Collect(),
        factory: () -> XmlPullParser = ::KXmlParser,
    ): Collect {
        XmltvParser(factory).parse(input, handler, CancelCheck.Never, options)
        return handler
    }

    private fun parseFile(name: String, options: XmltvParser.Options = XmltvParser.Options.DEFAULT, handler: Collect = Collect()): Collect =
        res(name).source().buffer().use { src -> parse(Compression.decompress(src).inputStream(), options, handler) }

    @Test
    fun basicEveryElement() {
        val c = parseFile("basic.xml")
        assertTrue(c.ended)
        assertEquals(3, c.channels.size)
        assertEquals(XmltvChannel("bbc1.uk", listOf(XmltvDisplayName("BBC One", "en"), XmltvDisplayName("BBC 1", null)), "http://img.example/bbc1.png"), c.channels[0])
        assertNull(c.channels[2].iconUrl)
        assertEquals(30, c.programmes.size)
        val p = c.programmes[0]
        assertEquals("bbc1.uk", p.channel)
        assertEquals(min("2026-10-13T06:00:00Z"), p.start)
        assertEquals(min("2026-10-13T07:00:00Z"), p.stop)
        assertEquals("BBC1 Show 0", p.title) // first title wins without a preference
        assertEquals("Part 0", p.subtitle)
        assertEquals("Description of show 0 on bbc1.uk.", p.desc)
        assertEquals(listOf("News", "Factual"), p.categories)
        assertEquals("S01E01", p.label)
        assertEquals(1, p.season)
        assertEquals(1, p.episode)
        assertEquals("http://img.example/bbc1.uk/0.jpg", p.icon)
        assertEquals("MPAA:PG-13", p.rating)
        assertEquals("7.5/10", p.star)
        assertEquals(2019, p.year)
        assertEquals("d:Jane Director\u001Fa:Sam Actor (Detective)\u001Fa:Alex Actor\u001Fw:Will Writer\u001Fpr:Pat Presenter", p.credits)
        assertEquals("bbc1.uk-0", p.catchupId)
        assertEquals(ProgrammeFlags.HAS_CATCHUP_ID or ProgrammeFlags.HD, p.flags)
        assertNull(c.programmes[1].catchupId)
        assertEquals(ProgrammeFlags.PREVIOUSLY_SHOWN or ProgrammeFlags.UHD, c.programmes[1].flags)
        assertEquals(ProgrammeFlags.PREMIERE or ProgrammeFlags.HAS_CATCHUP_ID, c.programmes[2].flags)
        assertEquals(ProgrammeFlags.LAST_CHANCE or ProgrammeFlags.HD, c.programmes[3].flags)
        assertEquals(ProgrammeFlags.NEW or ProgrammeFlags.UHD or ProgrammeFlags.HAS_CATCHUP_ID, c.programmes[4].flags)
        assertEquals(ProgrammeFlags.SUBTITLES, c.programmes[5].flags)
        assertEquals(c.programmes.size, c.programmes.map { it.channel to it.start }.toSet().size)
        assertEquals(1, c.instances.size, "one reusable XmltvProgramme instance")
    }

    @Test
    fun compressionVariantsAndBomGiveTheSameResult() {
        val expected = parseFile("basic.xml").programmes
        for (name in listOf("basic.xml.gz", "basic.xml.gz.gz", "basic.xml.xz", "basic.zip", "bom.xml")) {
            assertEquals(expected, parseFile(name).programmes, name)
        }
    }

    @Test
    fun offsetsFixture() {
        val utc = parseFile("offsets.xml")
        val six = min("2026-10-13T18:00:00Z")
        for (t in listOf("plus0100", "minus0500", "plus0530", "plus0545", "nospace", "zulu", "gmt", "utc", "est", "nooffset", "truncated")) {
            assertEquals(six, utc.byTitle(t).start, t)
            assertEquals(XmltvTime.MISSING, utc.byTitle(t).stop)
        }
        assertEquals(min("2026-10-13T00:00:00Z"), utc.byTitle("dateonly").start)
        assertEquals(min("2026-10-25T00:30:00Z"), utc.byTitle("dst_before").start)
        assertEquals(listOf("off"), utc.bad, "the invalid time is dropped and reported")
        assertTrue(utc.programmes.none { it.title == "invalid" })

        val london = parseFile("offsets.xml", XmltvParser.Options(assumed = XmltvTime.resolverFor(ZoneId.of("Europe/London"))))
        assertEquals(min("2026-10-13T17:00:00Z"), london.byTitle("nooffset").start)
        assertEquals(six, london.byTitle("plus0100").start, "explicit offsets ignore assumeZone")
        assertEquals(min("2026-10-24T23:30:00Z"), london.byTitle("dst_before").start)
        assertEquals(min("2026-10-25T00:30:00Z"), london.byTitle("dst_overlap").start)
        assertEquals(min("2026-10-25T02:30:00Z"), london.byTitle("dst_after").start)
    }

    @Test
    fun episodeNumFixture() {
        val c = parseFile("episode_num.xml")
        assertEquals("S3 E10 (Part 1/2)", c.byTitle("ns_full").label)
        assertEquals("E10", c.byTitle("ns_episode_only").label)
        assertEquals(1 to 1, c.byTitle("ns_zero").let { it.season to it.episode })
        assertEquals(2 to 5, c.byTitle("ns_totals").let { it.season to it.episode })
        assertEquals("S03E10", c.byTitle("onscreen").label)
        assertEquals(5, c.byTitle("onscreen_ep").episode)
        assertEquals("EP01234567", c.byTitle("dd_progid").series)
        c.byTitle("several").let {
            assertEquals(5 to 2, it.season to it.episode)
            assertEquals("S6E3", it.label)
            assertEquals("SH0001", it.series)
        }
        assertEquals(1 to 4, c.byTitle("sxxexx").let { it.season to it.episode })
    }

    @Test
    fun entitiesCdataCommentsDoctypeAndPi() {
        val c = parseFile("entities.xml")
        assertEquals("Ent & Co", c.channels.single().displayNames.single().name)
        val p = c.programmes[0]
        assertEquals("Tom & Jerry été A B", p.title, "&nbsp; becomes U+00A0 (review S4), not dropped")
        assertEquals("Fish & <Chips>", p.subtitle)
        val d = p.desc!!
        assertTrue(d.startsWith("Before ") && d.contains(" after … "), d)
        assertTrue(d.endsWith("end – — € 'q'"), d)
        assertTrue(c.programmes[1].title.startsWith("Stray"), "relaxed mode tolerates a stray &")
    }

    @Test
    fun latin1Declaration() {
        val c = parseFile("latin1.xml")
        assertEquals("Télé Latin", c.channels.single().displayNames.single().name)
        assertEquals("Télé Matin", c.programmes.single().title)
        assertEquals("Ça va très bien", c.programmes.single().desc)
    }

    @Test
    fun languagePicking() {
        val en = parseFile("lang.xml", XmltvParser.Options(preferredLanguages = listOf("en")))
        en.programmes[0].let {
            assertEquals("The News", it.title)
            assertEquals("Edition", it.subtitle, "en matches en-GB by primary subtag")
            assertEquals("The headlines", it.desc)
            assertEquals(listOf("News", "Info"), it.categories, "preferred language only, de-duplicated")
        }
        assertEquals("No Lang", en.programmes[1].title, "no match: first element")
        assertEquals(listOf("Channel", "Chaîne", "Bare"), en.channels.single().displayNames.map { it.name })

        val none = parseFile("lang.xml")
        assertEquals("Le Journal", none.programmes[0].title)
        assertEquals(listOf("Actualités", "News", "Info"), none.programmes[0].categories)
        assertEquals(listOf("Chaîne", "Channel", "Bare"), none.channels.single().displayNames.map { it.name })
    }

    @Test
    fun descriptionCapOnCodePointBoundaryAndSwitchOff() {
        val c = parseFile("long_desc.xml")
        val d = c.programmes.single().desc!!
        assertEquals(2047, d.length, "the emoji straddling 2,048 is dropped whole")
        assertTrue(d.all { it == 'a' })
        val wide = parseFile("long_desc.xml", XmltvParser.Options(maxDescriptionChars = 2049))
        assertEquals(2049, wide.programmes.single().desc!!.length)
        val off = parseFile("long_desc.xml", XmltvParser.Options(storeDescriptions = false))
        assertNull(off.programmes.single().desc)
    }

    @Test
    fun acceptHookSeesTimesAndSkipsWithoutText() {
        val seen = ArrayList<Triple<String, Int, Int>>()
        val c = Collect { ch, s, e -> seen += Triple(ch, s, e); ch == "itv1.uk" }
        parseFile("basic.xml", handler = c)
        assertEquals(30, seen.size)
        assertEquals(10, c.programmes.size)
        assertTrue(c.programmes.all { it.channel == "itv1.uk" })
    }

    /** The skip path never builds text: a spy counts getText/nextText (§5.3). */
    @Test
    fun skipPathNeverCallsGetText() {
        class Spy(private val d: XmlPullParser) : XmlPullParser by d {
            var texts = 0
            override fun getText(): String { texts++; return d.text }
            override fun nextText(): String { texts++; return d.nextText() }
        }
        val spy = Spy(KXmlParser())
        res("no_channels.xml").inputStream().use { XmltvParser { spy }.parse(it, Collect { _, _, _ -> false }) }
        assertEquals(0, spy.texts)

        val spy2 = Spy(KXmlParser())
        res("interleaved.xml").inputStream().use { XmltvParser { spy2 }.parse(it, Collect { _, _, _ -> false }) }
        assertEquals(50, spy2.texts, "only the 50 display-names are read")
    }

    @Test
    fun truncatedDocumentThrowsAfterCompleteProgrammes() {
        val bytes = res("basic.xml").readBytes()
        val cut = bytes.copyOf(bytes.size * 6 / 10)
        val c = Collect()
        assertFailsWith<EOFException> { parse(ByteArrayInputStream(cut), handler = c) }
        assertTrue(c.programmes.size in 1 until 30)
        assertTrue(c.programmes.all { it.title.matches(Regex("[A-Z0-9]+ Show \\d")) && it.rating == "MPAA:PG-13" }, "no half-read programme is emitted")
    }

    @Test
    fun notXmltvAndEmpty() {
        val html = assertFailsWith<SourceException> { parseFile("not_xmltv.html") }
        assertEquals(SourceError.NotASource(NotASourceKind.WRONG_FORMAT), html.error)
        val empty = assertFailsWith<SourceException> { parse(ByteArrayInputStream(ByteArray(0))) }
        assertIs<SourceError.NotASource>(empty.error)
        assertEquals(NotASourceKind.EMPTY, empty.error.kind)
    }

    @Test
    fun cancellationIsPolled() {
        var calls = 0
        val cancel = CancelCheck { ++calls > 0 }
        assertFailsWith<CancellationException> {
            res("interleaved.xml").inputStream().use { XmltvParser(::KXmlParser).parse(it, Collect(), cancel) }
        }
    }

    @Test
    fun parserFeaturesAreSetByTheParser() {
        // A factory that turns on namespaces and DOCDECL-like defaults must still behave the same.
        val c = Collect()
        res("entities.xml").inputStream().use {
            XmltvParser { KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) } }.parse(it, c)
        }
        assertEquals("Tom & Jerry été A B", c.programmes[0].title)
    }
}
