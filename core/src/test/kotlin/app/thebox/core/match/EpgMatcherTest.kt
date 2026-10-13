package app.thebox.core.match

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchFixtures.epg
import app.thebox.core.match.MatchFixtures.subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgMatcherTest {
    private fun match(
        subjects: List<MatchSubject>,
        vararg channels: app.thebox.core.epg.EpgChannelInfo,
        settings: MatchSettings = MatchSettings(),
        overrides: Map<app.thebox.core.refresh.ChannelIdentity, EpgChannelRef?> = emptyMap(),
    ): List<MatchResult> {
        val m = EpgMatcher(settings)
        return m.matchSource(m.prepare(subjects), 1, channels.asSequence(), overrides)
    }

    private fun one(s: MatchSubject, vararg channels: app.thebox.core.epg.EpgChannelInfo, settings: MatchSettings = MatchSettings()) =
        match(listOf(s), *channels, settings = settings).single()

    @Test
    fun exactIdWins() {
        val r = one(subject("BBC One", tvgId = "BBCOne.uk"), epg("bbcone.uk", "BBC One"), epg("BBCOne.uk", "Something else"))
        assertEquals(MatchStatus.AUTO, r.status)
        assertEquals(MatchMethod.ID_EXACT, r.best!!.method)
        assertEquals("BBCOne.uk", r.best!!.ref.xmltvId)
        assertEquals(1f, r.best!!.score)
    }

    @Test
    fun caseInsensitiveId() {
        val r = one(subject("x", tvgId = "BBCOne.uk"), epg("bbcone.UK", "BBC One"))
        assertEquals(MatchMethod.ID_CASE_INSENSITIVE, r.best!!.method)
        assertEquals(MatchStatus.AUTO, r.status)
        val off = one(subject("x", tvgId = "BBCOne.uk"), epg("bbcone.UK", "Y"), settings = MatchSettings(caseInsensitiveIds = false))
        assertNull(off.status)
    }

    @Test
    fun iptvOrgBaseIdPrefersFeedWithData() {
        val r = one(
            subject("BBC 1", tvgId = "BBCOne.uk"),
            epg("BBCOne.uk@SD", "BBC One SD", programmes = 0),
            epg("BBCOne.uk@HD", "BBC One HD", programmes = 40),
        )
        assertEquals(MatchMethod.ID_BASE, r.best!!.method)
        assertEquals(0.99f, r.best!!.score)
        assertEquals("BBCOne.uk@HD", r.best!!.ref.xmltvId)
        // The subject side may carry the feed too.
        val r2 = one(subject("BBC 1", tvgId = "bbcone.uk@HD"), epg("BBCOne.uk", "BBC One"))
        assertEquals(MatchMethod.ID_BASE, r2.best!!.method)
        // No '@' on either side: plain different ids never base-match.
        val r3 = one(subject("Q", tvgId = "Foo.uk"), epg("Foo.us", "Z"))
        assertNull(r3.best)
    }

    @Test
    fun idBeatsNamesWithinOneSource() {
        val r = one(subject("CNN", tvgId = "cnn.us"), epg("cnn-other", "CNN"), epg("cnn.us", "CNN International"))
        assertEquals(MatchMethod.ID_EXACT, r.best!!.method)
    }

    @Test
    fun tvgNameStage() {
        val r = one(subject("Channel A", tvgName = "Das_Erste"), epg("daserste.de", "Das Erste"))
        assertEquals(MatchMethod.TVG_NAME, r.best!!.method)
        assertEquals(MatchStatus.AUTO, r.status)
        assertEquals(0.98f, r.best!!.score)
    }

    @Test
    fun displayNameStageWithDiacriticsAndQualitySuffixes() {
        assertEquals(MatchMethod.DISPLAY_NAME, one(subject("Télé-Québec"), epg("tq", "Tele Quebec")).best!!.method)
        for (n in listOf("BBC One HD", "BBC One FHD", "BBC One 4K", "BBC One UHD", "BBC One SD", "UK: BBC One")) {
            val r = one(subject(n), epg("bbcone", "BBC One"))
            assertEquals(MatchStatus.AUTO, r.status, n)
            assertEquals(MatchMethod.DISPLAY_NAME, r.best!!.method, n)
        }
    }

    @Test
    fun digitGuardSkySports() {
        val r = one(subject("Sky Sports 1"), epg("ss2", "Sky Sports 2"), epg("ss3", "Sky Sports 3"))
        assertNull(r.best, "Sky Sports 1 must never pick 2 or 3")
        val ok = one(subject("Sky Sports 1 HD"), epg("ss2", "Sky Sports 2"), epg("ss1", "Sky Sports 1"))
        assertEquals("ss1", ok.best!!.ref.xmltvId)
        assertEquals(MatchStatus.AUTO, ok.status)
        val plus1 = one(subject("Channel 4"), epg("c4p1", "Channel 4 +1"))
        assertNull(plus1.best)
    }

    @Test
    fun hboEastWestAmbiguityOnlySuggests() {
        val r = one(subject("HBO"), epg("hbo.east", "HBO (East)"), epg("hbo.west", "HBO (West)"))
        assertEquals(MatchStatus.SUGGESTED, r.status)
        assertEquals(setOf("hbo.east", "hbo.west"), (listOf(r.best!!) + r.suggestions).map { it.ref.xmltvId }.toSet())
        // The bracket content is part of forKey: "HBO (East)" is unambiguous.
        val east = one(subject("HBO (East)"), epg("hbo.east", "HBO (East)"), epg("hbo.west", "HBO (West)"))
        assertEquals(MatchStatus.AUTO, east.status)
        assertEquals("hbo.east", east.best!!.ref.xmltvId)
    }

    @Test
    fun countryConflictsAreDroppedFromNameMatches() {
        val r = one(subject("UK: BBC One"), epg("BBCOne.uk", "BBC One"), epg("BBCOne.ie", "BBC One"))
        assertEquals(MatchStatus.AUTO, r.status)
        assertEquals("BBCOne.uk", r.best!!.ref.xmltvId)
        // Without a hint on the subject both remain: ambiguous → suggestions only.
        val amb = one(subject("BBC One"), epg("BBCOne.uk", "BBC One"), epg("BBCOne.ie", "BBC One"))
        assertEquals(MatchStatus.SUGGESTED, amb.status)
    }

    @Test
    fun sameBaseFeedsAreNotAmbiguous() {
        val r = one(subject("Arte"), epg("Arte.fr@SD", "Arte", programmes = 0), epg("Arte.fr@HD", "Arte", programmes = 5))
        assertEquals(MatchStatus.AUTO, r.status)
        assertEquals("Arte.fr@HD", r.best!!.ref.xmltvId)
    }

    @Test
    fun fuzzySuggestsByDefaultAndAutoOnlyWhenOptedIn() {
        val s = subject("Discovery Channel")
        val def = one(s, epg("disc", "Discovery Chanel"))
        assertEquals(MatchStatus.SUGGESTED, def.status)
        assertEquals(MatchMethod.FUZZY, def.best!!.method)
        assertTrue(def.best!!.score >= 0.92f)
        val auto = one(s, epg("disc", "Discovery Chanel"), settings = MatchSettings(level = MatchLevel.AUTO_FUZZY))
        assertEquals(MatchStatus.AUTO, auto.status)
    }

    @Test
    fun fuzzyAutoNeedsAMargin() {
        val s = subject("Discovery Channel")
        val r = one(
            s, epg("a", "Discovery Chanel"), epg("b", "Discovery Channl"),
            settings = MatchSettings(level = MatchLevel.AUTO_FUZZY),
        )
        assertEquals(MatchStatus.SUGGESTED, r.status, "two near-identical candidates: no auto")
        assertEquals(1, r.suggestions.size)
    }

    @Test
    fun fuzzyBelowSuggestThresholdIsNoMatch() {
        val r = one(subject("Eurosport"), epg("x", "Totally Different"))
        assertNull(r.best)
        assertNull(r.status)
    }

    @Test
    fun levelsGateStages() {
        val s = subject("BBC One")
        val exactOnly = MatchSettings(level = MatchLevel.EXACT_ONLY)
        assertNull(one(s, epg("bbc", "BBC One"), settings = exactOnly).best)
        val names = MatchSettings(level = MatchLevel.EXACT_AND_NAMES)
        assertEquals(MatchMethod.DISPLAY_NAME, one(s, epg("bbc", "BBC One"), settings = names).best!!.method)
        assertNull(one(subject("Discovery Channel"), epg("d", "Discovery Chanel"), settings = names).best)
    }

    @Test
    fun overridesWinAndNoneSuppresses() {
        val a = subject("BBC One", tvgId = "BBCOne.uk", key = "a")
        val b = subject("ITV", key = "b")
        val c = subject("Channel 5", key = "c")
        val results = match(
            listOf(a, b, c),
            epg("BBCOne.uk", "BBC One"), epg("itv1", "ITV 1"), epg("five", "Channel 5"),
            overrides = mapOf(
                a.identity to EpgChannelRef(1, "five"),
                b.identity to null,
                c.identity to EpgChannelRef(2, "elsewhere"),
            ),
        )
        assertEquals(MatchMethod.OVERRIDE, results[0].best!!.method)
        assertEquals("five", results[0].best!!.ref.xmltvId)
        assertEquals("Channel 5", results[0].best!!.displayName)
        assertNull(results[1].best)
        assertNull(results[2].best, "override in another source: no match in this one")
    }

    @Test
    fun maxThreeDisplayNamesAndSuggestionsCap() {
        val r = one(subject("Fox"), epg("f", "A", "B", "C", "Fox"))
        assertNull(r.best, "the 4th display-name is ignored")
        val many = one(
            subject("Discovery Channel"),
            epg("1", "Discovery Chanel"), epg("2", "Discovery Channl"), epg("3", "Discovry Channel"),
            epg("4", "Discovery Chann"), epg("5", "Discover Channel"),
        )
        assertEquals(3, many.suggestions.size)
        val all = listOf(many.best!!) + many.suggestions
        assertEquals(all.sortedByDescending { it.score }, all)
    }

    @Test
    fun suggestPicker() {
        val m = EpgMatcher(MatchSettings())
        val list = m.suggest(
            subject("Sky Sports 1 HD", tvgId = "skysports1.uk"),
            sequenceOf(epg("ss2", "Sky Sports 2"), epg("skysports1.uk", "Sky Sports One"), epg("ss1", "Sky Sports 1"), epg("n", "News")),
        )
        assertEquals(listOf("skysports1.uk", "ss1"), list.map { it.ref.xmltvId })
        assertEquals(MatchMethod.ID_EXACT, list[0].method)
    }

    @Test
    fun resultsKeepSubjectOrder() {
        val subs = (0 until 50).map { subject("Name $it", key = "k$it") }
        val r = match(subs, epg("x", "Name 7"))
        assertEquals(subs.map { it.identity }, r.map { it.subject })
        assertEquals("x", r[7].best!!.ref.xmltvId)
    }
}
