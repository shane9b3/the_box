package app.thebox.core.sports.hub

import app.thebox.core.events.EventNameParser
import app.thebox.core.events.EventParseContext
import app.thebox.core.events.EventZoneSource
import app.thebox.core.events.ParsedName
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.TeamLine
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** docs/design/m6-event-channels.md §8.4, §11.1 (`EventGameRuleTest`). */
class EventGameRuleTest {
    private val c = MatchConfidence.CONFIRMED
    private val l = MatchConfidence.LIKELY
    private val newYork = ZoneId.of("America/New_York")
    private val nov24 = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun game(league: League, away: String, home: String, startIso: String, id: String = "$away@$home") = GameSnapshot(
        id = GameId(league, id), dayKey = startIso.take(10), startUtcMs = ms(startIso), phase = GamePhase.SCHEDULED,
        away = TeamLine(away, away, null, null), home = TeamLine(home, home, null, null),
        period = null, periodKind = null, otNumber = null, clock = null, finalKind = null,
    )

    /** Golden Knights at Oilers, 8:00 PM ET on Nov 24. */
    private val vgkEdm = game(League.NHL, "VGK", "EDM", "2026-11-25T01:00:00Z")

    private fun facts(name: String, group: String?, now: Long = nov24, assumed: ZoneId = newYork): EventFacts {
        val e = EventNameParser.parse(name, EventParseContext(now, assumed)) as? ParsedName.Event ?: fail("not an event: $name")
        return EventFacts(name, e.title, group, e.startUtcMs, e.zone)
    }

    private fun grade(name: String, group: String?, g: GameSnapshot, board: List<GameSnapshot> = listOf(g), now: Long = nov24) =
        EventGameRule.grade(facts(name, group, now), g, board)

    private fun grade(conf: MatchConfidence, citiesOnly: Boolean = false, zoneAssumed: Boolean = false) =
        EventGameRule.Grade(conf, citiesOnly, zoneAssumed)

    // ------------------------------------------------------------------ the worked cases (§8.4)

    @Test
    fun goldenKnightsAtOilersFromEveryRealStyle() {
        assertEquals(grade(c), grade("NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", "Sports | NHL", vgkEdm))
        assertEquals(grade(c), grade("NHL 01: NHL on TNT: Golden Knights at Oilers @ 24 Nov 07:30 PM ET", "Sports | NHL (2)", vgkEdm))
        assertEquals(grade(c), grade("MAX 002: NHL on TNT: Golden Knights at Oilers (11.24 07:30 PM ET)", "Sports | HBO Max US", vgkEdm))
        assertEquals(grade(c, citiesOnly = true), grade("Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertEquals(
            grade(c),
            grade("(UK) (Dazn 030) | NHL.TV | Golden Knights @ Oilers (2026-11-25 01:00:00)", "Sports | DAZN UK", vgkEdm),
        )
        // Nicknames only, no hint: neither is a cross-league nickname.
        assertEquals(grade(c), grade("DAZN CA 20: Golden Knights @ Oilers @ 24 Nov 08:00 PM ET", "Sports | DAZN CA", vgkEdm))
        assertEquals(grade(c), grade("DAZN CA 21: Vegas Golden Knights @ Edmonton Oilers @ 24 Nov 08:00 PM ET", "Sports | DAZN CA", vgkEdm))
    }

    @Test
    fun nhlTvKingsAtCanadiens() {
        val lakMtl = game(League.NHL, "LAK", "MTL", "2026-11-24T00:00:00Z")
        // " | " split; the nhl.tv hint lifts the cross-league "Kings".
        assertEquals(grade(c), grade("DAZN UK 30: NHL.TV | Kings @ Canadiens @ 23 Nov 07:00 PM ET", "Sports | DAZN UK (2)", lakMtl))
        // Without a hint, "Kings" could be Sacramento.
        assertEquals(grade(l), grade("DAZN UK 30: Kings @ Canadiens @ 23 Nov 07:00 PM ET", "Sports | DAZN UK (2)", lakMtl))
    }

    @Test
    fun nbaOnSportsnetPlusNeverConfirms() {
        // Detroit @ Orlando: no Orlando club in either table.
        val detAtFla = game(League.NHL, "DET", "FLA", "2026-11-25T00:00:00Z")
        assertNull(grade("Sportsnet+ 08: Detroit @ Orlando @ 24 Nov 07:00 PM ET", "Sports | Sportsnet+", detAtFla))
        val nflDet = game(League.NFL, "DET", "MIA", "2026-11-25T00:00:00Z")
        assertNull(grade("Sportsnet+ 08: Detroit @ Orlando @ 24 Nov 07:00 PM ET", "Sports | Sportsnet+", nflDet))
        // Dallas @ Denver against an NFL DAL at DEN at the same minute: cities-only CONFIRMED is NHL only (E6).
        val dalDen = game(League.NFL, "DAL", "DEN", "2026-11-25T03:00:00Z")
        assertEquals(grade(l, citiesOnly = true), grade("Sportsnet+ 10: Dallas @ Denver @ 24 Nov 10:00 PM ET", "Sports | Sportsnet+", dalDen))
        // No NHL club is "Denver".
        val dalCol = game(League.NHL, "DAL", "COL", "2026-11-25T03:00:00Z")
        assertNull(grade("Sportsnet+ 10: Dallas @ Denver @ 24 Nov 10:00 PM ET", "Sports | Sportsnet+", dalCol))
    }

    @Test
    fun basketballNamesAgainstHockeyAndFootball() {
        val bosWsh = game(League.NHL, "BOS", "WSH", "2026-11-25T04:55:00Z")
        assertNull(grade("Sky Sports + 19: Basketball NBA Boston Celtics @ Washington Wizards @ 25 Nov 04:55 AM London", "UK | Sky Sports+", bosWsh))
        val dalDen = game(League.NFL, "DAL", "DEN", "2026-11-25T02:55:00Z")
        assertNull(grade("Sky Sports + 20: Basketball NBA Dallas Mavericks @ Denver Nuggets @ 25 Nov 02:55 AM London", "UK | Sky Sports+", dalDen))
        // The sides alone already fail, with no hint anywhere.
        val bare = EventFacts("X 01: Dallas Mavericks @ Denver Nuggets", "Dallas Mavericks @ Denver Nuggets", null, ms("2026-11-25T02:55:00Z"), EventZoneSource.EXPLICIT)
        assertNull(EventGameRule.grade(bare, dalDen, listOf(dalDen)))
        val bare2 = EventFacts("X 01: Basketball Dallas Mavericks @ Denver Nuggets", "Basketball NBA Dallas Mavericks @ Denver Nuggets", null, ms("2026-11-25T02:55:00Z"), EventZoneSource.EXPLICIT)
        assertNull(EventGameRule.grade(bare2, dalDen, listOf(dalDen)))
    }

    // ------------------------------------------------------------------ time window

    @Test
    fun windowEdges() {
        fun at(time: String) = grade("(UK) (Dazn 030) | NHL.TV | Golden Knights @ Oilers (2026-11-$time:00)", "Sports | DAZN UK", vgkEdm)
        assertEquals(grade(c), at("24 23:30")) // −90
        assertNull(at("24 23:29")) // −91
        assertEquals(grade(c), at("25 01:45")) // +45
        assertNull(at("25 01:46")) // +46
    }

    @Test
    fun theOwnersOmName() {
        val mtlCol = game(League.NHL, "MTL", "COL", "2026-11-26T02:00:00Z") // 9:00 PM ET
        val now = ms("2026-11-25T02:55:55Z")
        // Δ +30: in the window, but past the cities-only ±15.
        assertEquals(
            grade(l, citiesOnly = true),
            grade("Sportsnet+ 23: Montreal @ Colorado @ 25 Nov 09:30 OM ET", "Sports | Sportsnet+", mtlCol, now = now),
        )
        val atNine = game(League.NHL, "MTL", "COL", "2026-11-26T02:30:00Z")
        assertEquals(
            grade(c, citiesOnly = true),
            grade("Sportsnet+ 23: Montreal @ Colorado @ 25 Nov 09:30 OM ET", "Sports | Sportsnet+", atNine, now = now),
        )
    }

    // ------------------------------------------------------------------ order

    @Test
    fun orderedSeparatorsPutTheAwaySideFirst() {
        assertNull(grade("Sportsnet+ 09: Edmonton @ Vegas @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("NHL 01: Oilers at Golden Knights (11.24 8:00 PM ET)", "Sports | NHL", vgkEdm))
        // Unordered separators take either order.
        val lakMtl = game(League.NHL, "LAK", "MTL", "2026-11-25T00:00:00Z")
        val mtlLak = game(League.NHL, "MTL", "LAK", "2026-11-25T00:00:00Z")
        for (name in listOf(
            "NHL 02: Kings vs. Canadiens (11.24 7:00 PM ET)", "NHL 02: Kings vs Canadiens (11.24 7:00 PM ET)",
            "NHL 02: Kings v Canadiens (11.24 7:00 PM ET)", "NHL 02: Kings v. Canadiens (11.24 7:00 PM ET)",
            "NHL 02: Canadiens - Kings (11.24 7:00 PM ET)",
        )) {
            assertEquals(grade(c), grade(name, "Sports | NHL", lakMtl), name)
            assertEquals(grade(c), grade(name, "Sports | NHL", mtlLak), name)
        }
        assertEquals(grade(c), grade("NHL 02: Kings at Canadiens (11.24 7:00 PM ET)", "Sports | NHL", lakMtl))
        assertNull(grade("NHL 02: Kings at Canadiens (11.24 7:00 PM ET)", "Sports | NHL", mtlLak))
        assertNull(grade("NHL 02: Kings @ Canadiens (11.24 7:00 PM ET)", "Sports | NHL", mtlLak))
    }

    // ------------------------------------------------------------------ the §8.4 level table

    @Test
    fun bostonAtToronto() {
        val bosTor = game(League.NHL, "BOS", "TOR", "2026-11-25T00:00:00Z")
        val torBos = game(League.NHL, "TOR", "BOS", "2026-11-25T00:00:00Z")
        val name = "Sportsnet+ 14: Boston @ Toronto @ 24 Nov 07:00 PM ET"
        // Two leagues cannot play one city pair at once: NHL cities-only CONFIRMED with a clean board.
        assertEquals(grade(c, citiesOnly = true), grade(name, "Sports | Sportsnet+", bosTor))
        assertNull(grade(name, "Sports | Sportsnet+", torBos))
        // Unordered, no hint: no grade; with a hint: CONFIRMED.
        assertNull(grade("Sportsnet+ 14: Boston vs. Toronto @ 24 Nov 07:00 PM ET", "Sports | Sportsnet+", bosTor))
        assertEquals(grade(c, citiesOnly = true), grade("NHL 04: Boston vs. Toronto @ 24 Nov 07:00 PM ET", "Sports | NHL", bosTor))
        assertEquals(grade(c, citiesOnly = true), grade("NHL 04: Toronto vs. Boston @ 24 Nov 07:00 PM ET", "Sports | NHL", bosTor))
        // Assumed zone: tight is LIKELY, loose is no grade.
        assertEquals(grade(l, citiesOnly = true, zoneAssumed = true), grade("MonoMAX 02: Boston @ Toronto @ 24 Nov 07:00 PM", "Sports | MonoMAX", bosTor))
        assertNull(grade("MonoMAX 02: Boston @ Toronto @ 24 Nov 07:30 PM", "Sports | MonoMAX", bosTor))
        // A positive hint with an assumed zone: LIKELY.
        assertEquals(grade(l, citiesOnly = true, zoneAssumed = true), grade("MonoMAX 02: NHL Boston vs. Toronto @ 24 Nov 07:00 PM", "Sports | MonoMAX", bosTor))
        // Women's hockey on TSN+: a negative hint.
        assertNull(grade("TSN+ 09: Women's Hockey: Boston @ Toronto @ 24 Nov 07:00 PM ET", "Sports | TSN+", bosTor))
        assertNull(grade("TSN+ 09: PWHL: Boston @ Toronto @ 24 Nov 07:00 PM ET", "Sports | TSN+ Women", bosTor))
    }

    @Test
    fun citiesOnlyUpgradeNeedsTightTimeAndACleanBoard() {
        fun at(time: String, board: List<GameSnapshot>) =
            grade("(UK) (Dazn 031) | Vegas @ Edmonton (2026-11-25 $time:00)", "Sports | DAZN UK", vgkEdm, board)
        val clean = listOf(vgkEdm, game(League.NHL, "BOS", "TOR", "2026-11-25T00:00:00Z"))
        assertEquals(grade(c, citiesOnly = true), at("01:15", clean)) // |Δ| = 15
        assertEquals(grade(c, citiesOnly = true), at("00:45", clean)) // −15
        assertEquals(grade(l, citiesOnly = true), at("01:16", clean)) // 16
        assertEquals(grade(l, citiesOnly = true), at("00:44", clean))
        // Another game on the board involving Vegas or Edmonton within ±3 h.
        val clash = clean + game(League.NHL, "SEA", "VGK", "2026-11-25T03:30:00Z")
        assertEquals(grade(l, citiesOnly = true), at("01:00", clash))
        val clash2 = clean + game(League.NHL, "EDM", "CGY", "2026-11-24T22:00:00Z")
        assertEquals(grade(l, citiesOnly = true), at("01:00", clash2))
        // Outside ±3 h it does not count.
        val far = clean + game(League.NHL, "SEA", "VGK", "2026-11-25T04:01:00Z")
        assertEquals(grade(c, citiesOnly = true), at("01:00", far))
        // An empty board is clean.
        assertEquals(grade(c, citiesOnly = true), at("01:00", emptyList()))
    }

    @Test
    fun onePlaceSide() {
        assertEquals(grade(l), grade("Sportsnet+ 15: Vegas @ Edmonton Oilers @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertEquals(grade(l), grade("Sportsnet+ 15: Golden Knights @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertEquals(grade(c), grade("NHL 15: Vegas @ Edmonton Oilers @ 24 Nov 08:00 PM ET", "Sports | NHL", vgkEdm))
        assertEquals(
            grade(l, zoneAssumed = true),
            grade("NHL 15: Vegas @ Edmonton Oilers @ 24 Nov 08:00 PM", "Sports | NHL", vgkEdm),
        )
    }

    @Test
    fun newYorkAloneIsUnusable() {
        val nyrBos = game(League.NHL, "NYR", "BOS", "2026-11-25T00:00:00Z")
        assertNull(grade("MSG 01: New York @ Boston @ 24 Nov 07:00 PM ET", "Sports | MSG", nyrBos))
        assertNull(grade("NHL 05: New York @ Boston @ 24 Nov 07:00 PM ET", "Sports | NHL", nyrBos))
        assertEquals(grade(l), grade("MSG 01: Rangers @ Boston @ 24 Nov 07:00 PM ET", "Sports | MSG", nyrBos))
        assertEquals(grade(c), grade("NHL 05: Rangers @ Boston @ 24 Nov 07:00 PM ET", "Sports | NHL", nyrBos))
        assertEquals(grade(c), grade("MSG 01: New York Rangers @ Boston Bruins @ 24 Nov 07:00 PM ET", "Sports | MSG", nyrBos))
        val nyiNyr = game(League.NHL, "NYI", "NYR", "2026-11-25T00:00:00Z")
        assertNull(grade("NHL 06: New York @ New York @ 24 Nov 07:00 PM ET", "Sports | NHL", nyiNyr))
        assertEquals(grade(c), grade("NHL 06: Islanders @ Rangers @ 24 Nov 07:00 PM ET", "Sports | NHL", nyiNyr))
    }

    @Test
    fun sharedNicknames() {
        val wpgFla = game(League.NHL, "WPG", "FLA", "2026-11-25T00:00:00Z")
        assertEquals(grade(l), grade("ESPN+ 60: Jets @ Panthers @ Nov 24 7:00PM ET", "Sports | ESPN+", wpgFla))
        assertEquals(grade(c), grade("NHL 07: Jets @ Panthers @ 24 Nov 07:00 PM ET", "Sports | NHL", wpgFla))
        assertEquals(grade(c), grade("ESPN+ 60: Winnipeg Jets @ Florida Panthers @ Nov 24 7:00PM ET", "Sports | ESPN+", wpgFla))
        val nyjCar = game(League.NFL, "NYJ", "CAR", "2026-11-25T00:00:00Z")
        assertEquals(grade(l), grade("ESPN+ 61: Jets @ Panthers @ Nov 24 7:00PM ET", "Sports | ESPN+", nyjCar))
        assertEquals(grade(c), grade("NFL 07: Jets @ Panthers @ 24 Nov 07:00 PM ET", "Sports | NFL", nyjCar))
        val nygAri = game(League.NFL, "NYG", "ARI", "2026-11-25T00:00:00Z")
        assertEquals(grade(l), grade("ESPN+ 62: Giants @ Cardinals @ Nov 24 7:00PM ET", "Sports | ESPN+", nygAri))
        assertEquals(grade(c), grade("ESPN+ 62: New York Giants @ Arizona Cardinals @ Nov 24 7:00PM ET", "Sports | ESPN+", nygAri))
        val lakNyr = game(League.NHL, "LAK", "NYR", "2026-11-25T00:00:00Z")
        assertEquals(grade(l), grade("ESPN+ 63: Kings @ Rangers @ Nov 24 7:00PM ET", "Sports | ESPN+", lakNyr))
        // Kings against an NHL hockey game in an NBA group: the group's negative hint wins.
        assertNull(grade("NBA 05: Kings @ Rangers @ 24 Nov 07:00 PM ET", "Sports | NBA", lakNyr))
    }

    @Test
    fun negativeHints() {
        val name = "Sportsnet+ 09: Vegas @ Edmonton @ 24 Nov 08:00 PM ET"
        // From the group only.
        assertNull(grade(name, "Sports | NBA", vgkEdm))
        assertNull(grade(name, "Sports | NCAA Basketball (W)", vgkEdm))
        // From the slot only.
        assertNull(grade("NBA 03: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("OHL 03: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("NFL 03: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        // Inside the title or a side.
        assertNull(grade("Sportsnet+ 09: U18 Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("Sportsnet+ 09: Vegas @ Edmonton (W) @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("Sportsnet+ 09: Vegas @ Edmonton (M) @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        assertNull(grade("Sportsnet+ 09: College Hockey: Vegas @ Edmonton @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        // Southern Utah and Boston College are neither equal nor clean.
        val utaEdm = game(League.NHL, "UTA", "EDM", "2026-11-25T01:00:00Z")
        assertNull(grade("ESPN+ 50: Southern Utah @ Edmonton @ Nov 24 8:00PM ET", "Sports | ESPN+", utaEdm))
        assertEquals(grade(c, citiesOnly = true), grade("ESPN+ 50: Utah @ Edmonton @ Nov 24 8:00PM ET", "Sports | ESPN+", utaEdm))
        val bosBuf = game(League.NHL, "BOS", "BUF", "2026-11-25T01:00:00Z")
        assertNull(grade("ESPN+ 51: Boston College @ Buffalo @ Nov 24 8:00PM ET", "Sports | ESPN+", bosBuf))
        val bareBc = EventFacts("X 01: Boston College @ Buffalo", "Boston College @ Buffalo", null, ms("2026-11-25T01:00:00Z"), EventZoneSource.EXPLICIT)
        assertNull(EventGameRule.grade(bareBc, bosBuf, listOf(bosBuf)))
        // A football hint against an NHL game; a hockey hint against an NFL game.
        val nflBufMia = game(League.NFL, "BUF", "MIA", "2026-11-25T01:00:00Z")
        assertNull(grade("TSN+ 10: Hockey: Buffalo @ Miami @ 24 Nov 08:00 PM ET", "Sports | TSN+", nflBufMia))
        assertEquals(grade(c, citiesOnly = false), grade("NFL 10: Bills @ Dolphins @ 24 Nov 08:00 PM ET", "Sports | NFL", nflBufMia))
    }

    @Test
    fun nflNames() {
        val cinWsh = game(League.NFL, "CIN", "WSH", "2026-11-24T10:00:00Z")
        assertEquals(
            grade(c),
            grade("ESPN UNLTD 001: NFL: NFL Football: 2026 Week 11 Cincinnati Bengals vs. Washington Commanders (11.24 5:00 AM ET)", "Sports | ESPN Unlimited", cinWsh),
        )
        val cinWshLate = game(League.NFL, "CIN", "WSH", "2026-11-24T01:15:00Z")
        assertEquals(
            grade(c),
            grade("(UK) (Dazn 028) | NFL Game Pass | Bengals @ Commanders (2026-11-24 01:15:00)", "Sports | DAZN UK", cinWshLate),
        )
        // The untagged NFL GP name is ASSUMED (and 5 h off): out of the window.
        assertNull(grade("NFL GP 01 | Bengals @ Commanders (2026-11-24 01:15:00)", "Sports | NFL Game Pass", cinWshLate))
    }

    @Test
    fun notThisGame() {
        // Another pair of teams.
        val bosTor = game(League.NHL, "BOS", "TOR", "2026-11-25T01:00:00Z")
        assertNull(grade("NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", "Sports | NHL", bosTor))
        // One team right, the other wrong.
        val vgkCgy = game(League.NHL, "VGK", "CGY", "2026-11-25T01:00:00Z")
        assertNull(grade("NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", "Sports | NHL", vgkCgy))
        // No matchup.
        assertNull(grade("Sportsnet+ 01: Sportsnet Central: Nov. 24 @ 24 Nov 08:00 PM ET", "Sports | Sportsnet+", vgkEdm))
        // Several matchups.
        assertNull(grade("NHL 01: Golden Knights vs. Oilers • Flames vs. Kraken (11.24 8:00 PM ET)", "Sports | NHL", vgkEdm))
        // A league without a table, an unknown abbreviation, a game against itself.
        val ohl = game(League.OHL, "LDN", "KIT", "2026-11-25T01:00:00Z")
        assertNull(grade("OHL 01: London @ Kitchener (11.24 8:00 PM ET)", "Sports | OHL", ohl))
        val unknown = game(League.NHL, "XYZ", "EDM", "2026-11-25T01:00:00Z")
        assertNull(grade("NHL 01: Golden Knights vs. Oilers (11.24 8:00 PM ET)", "Sports | NHL", unknown))
        val self = game(League.NHL, "EDM", "EDM", "2026-11-25T01:00:00Z")
        assertNull(grade("NHL 01: Oilers vs. Oilers (11.24 8:00 PM ET)", "Sports | NHL", self))
    }

    @Test
    fun assumedZonesAreNeverConfirmed() {
        assertEquals(
            grade(l, zoneAssumed = true),
            grade("MonoMAX 05: NHL Golden Knights @ Oilers @ 24 Nov 08:00 PM", "Sports | MonoMAX", vgkEdm),
        )
        assertEquals(
            grade(l, zoneAssumed = true),
            grade("MonoMAX 05: Golden Knights @ Oilers @ 24 Nov 08:00 PM", "Sports | MonoMAX", vgkEdm),
        )
        // Ordered cities-only with an assumed zone outside ±15: no grade.
        assertNull(grade("MonoMAX 05: Vegas @ Edmonton @ 24 Nov 08:30 PM", "Sports | MonoMAX", vgkEdm))
    }

    @Test
    fun neverThrows() {
        val weird = EventFacts("", "", null, Long.MIN_VALUE, EventZoneSource.ASSUMED)
        assertNull(EventGameRule.grade(weird, vgkEdm, emptyList()))
        val big = EventFacts("x".repeat(5_000), " @ ".repeat(500), "|".repeat(100), vgkEdm.startUtcMs, EventZoneSource.EXPLICIT)
        assertNull(EventGameRule.grade(big, vgkEdm, listOf(vgkEdm)))
    }
}
