package app.thebox.core.sports.hub

import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.TvBroadcast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m6-sports-hub.md §5.2 A, §10.2 `BroadcastAliasesTest` (A-B3, R-S2, R-S3). */
class BroadcastAliasesTest {
    private val sn = TvBroadcast("SN", Market.NATIONAL, "CA")
    private fun b(code: String, market: Market = Market.NATIONAL, country: String? = null) = TvBroadcast(code, market, country)
    private fun m(name: String, br: TvBroadcast) = BroadcastAliases.match(name, br)

    @Test fun residueStripsListedTagsAndTrailingNoise() {
        assertEquals(BroadcastAliases.Residue("ca", "CA", listOf("sn", "ontario")), BroadcastAliases.residue("CA| SN ONTARIO FHD"))
        assertEquals(listOf("sportsnet", "ontario"), BroadcastAliases.residue("CA| SPORTSNET ONTARIO HD").tokens)
        assertEquals("GB", BroadcastAliases.residue("UK: TNT SPORTS 1").country)
        assertEquals("US", BroadcastAliases.residue("USA | ESPN").country)
        assertEquals("CA", BroadcastAliases.residue("[CA] Sportsnet One").country)
        assertEquals("CA", BroadcastAliases.residue("Sportsnet One (CA)").country)
        assertEquals("CA", BroadcastAliases.residue("|CA| Sportsnet").country)
        assertEquals("EX-YU", BroadcastAliases.residue("EX-YU: Arena Sport 1").country)
        assertEquals("tnt", BroadcastAliases.residue("US: TNT HD (backup)").text)
        assertEquals("espn", BroadcastAliases.residue("US: ESPN H.265 50 FPS").text)
        assertEquals("sportsnet +1", BroadcastAliases.residue("CA: Sportsnet +1").text, "a +1 time-shift feed keeps its marker")
        assertEquals("sn+ 01", BroadcastAliases.residue("CA| SN+ 01").text, "+ stays on its token")
    }

    /** R-S2: an alias token is never a country tag, so the network survives ("SN" is also Senegal in ISO). */
    @Test fun aliasTokensAreNeverCountryTags() {
        for (t in listOf("SN", "TSN", "CBC", "ABC", "TNT", "MSG", "RDS", "CTV", "NBC", "CBS", "FOX", "TVA")) {
            assertFalse(BroadcastAliases.isCountryTag(t), t)
        }
        for (t in listOf("CA", "US", "UK", "USA", "CAN", "LAT", "EU", "ARB", "AFR", "EX-YU", "DE", "FR")) {
            assertTrue(BroadcastAliases.isCountryTag(t), t)
        }
        assertEquals(BroadcastAliases.Residue(null, null, listOf("sn", "ontario")), BroadcastAliases.residue("SN - Ontario"))
        assertEquals(BroadcastAliases.Residue(null, null, listOf("tsn", "3")), BroadcastAliases.residue("TSN: 3"))
        assertEquals(BroadcastAliases.Residue(null, null, listOf("cbc", "toronto")), BroadcastAliases.residue("CBC - Toronto"))
        assertEquals(BroadcastAliases.Residue(null, null, listOf("abc", "7")), BroadcastAliases.residue("ABC | 7"))
    }

    @Test fun positivesPerCode() {
        // R-S2's positives.
        assertNotNull(m("SN - Ontario", sn))
        assertEquals("TSN3", m("TSN: 3", b("TSN3", country = "CA"))?.code)
        assertEquals(MatchConfidence.LIKELY, m("CA| SN ONTARIO FHD", sn)?.confidence)
        assertEquals(MatchConfidence.LIKELY, m("CA| SPORTSNET ONTARIO HD", sn)?.confidence)
        assertEquals(MatchConfidence.LIKELY, m("CA: Sportsnet", sn)?.confidence)
        assertEquals(MatchConfidence.LIKELY, m("CA| SPORTSNET PACIFIC", sn)?.confidence)
        val cases = listOf(
            "CA| SPORTSNET ONE" to b("SN1", country = "CA"),
            "CA| SN1 HD" to b("SN1", country = "CA"),
            "CA| SPORTSNET 360" to b("SN360", country = "CA"),
            "CA: SN 360" to b("SN360", country = "CA"),
            "CA| TVA SPORTS" to b("TVAS", country = "CA"),
            "CA| TVA SPORTS 2" to b("TVAS2", country = "CA"),
            "US: TNT" to b("TNT", country = "US"),
            "US: truTV" to b("TRUTV", country = "US"),
            "US: ESPN" to b("ESPN", country = "US"),
            "US: ESPN 2" to b("ESPN2", country = "US"),
            "US: NHL Network" to b("NHLN", country = "US"),
            "US: NFL Network" to b("NFLN", country = "US"),
            "CA| TSN" to b("TSN", country = "CA"),
        )
        for ((name, br) in cases) assertEquals(MatchConfidence.LIKELY, m(name, br)?.confidence, "$name ~ ${br.network}")
        val regional = listOf(
            "US: MSG" to b("MSG", Market.HOME, "US"),
            "US: MSG Sportsnet" to b("MSGSN", Market.HOME, "US"),
            "US: MSG 2" to b("MSG2", Market.HOME, "US"),
            "US: NBC Sports California" to b("NBCSCA", Market.HOME, "US"),
            "US: FanDuel Sports Network Detroit" to b("FDSNDET", Market.HOME, "US"),
            "US: Bally Sports Detroit" to b("BSDET", Market.HOME, "US"),
            "CA| TSN 4" to b("TSN4", Market.NATIONAL, "CA"),
            "CA| RDS" to b("RDS", Market.NATIONAL, "CA"),
            "CA| RDS2" to b("RDS2", Market.NATIONAL, "CA"),
            "CA| SPORTSNET ONTARIO" to b("SNO", Market.HOME, "CA"),
        )
        for ((name, br) in regional) assertEquals(MatchConfidence.POSSIBLE, m(name, br)?.confidence, "$name ~ ${br.network}")
    }

    /** Market H or A lowers any match to POSSIBLE; an untagged channel is one level lower. */
    @Test fun marketAndUntaggedLowerTheLevel() {
        assertEquals(MatchConfidence.POSSIBLE, m("CA| SPORTSNET ONTARIO", b("SN", Market.HOME, "CA"))?.confidence)
        assertEquals(MatchConfidence.POSSIBLE, m("Sportsnet Ontario", sn)?.confidence)
        assertEquals(MatchConfidence.POSSIBLE, m("SN - Ontario", sn)?.confidence)
        assertFalse(m("Sportsnet Ontario", sn)!!.tagged)
    }

    @Test fun countryMismatchIsDropped() {
        assertNull(m("UK: TNT", b("TNT", country = "US")))
        assertNull(m("US: Sportsnet", sn))
        assertNull(m("LAT: ESPN", b("ESPN", country = "US")))
        assertNull(m("CA| TNT", b("TNT")), "the table's default country (US) applies when the feed gives none")
        assertNotNull(m("US: TNT", b("TNT")))
    }

    /** A-B3's negatives. */
    @Test fun negatives() {
        assertNull(m("CA| SPORTSNET 360", sn), "Sportsnet 360 is not SN")
        assertNull(m("CA| SPORTSNET ONE", sn))
        assertNull(m("CA| SPORTSNET WORLD", sn))
        assertNull(m("US: ESPN 2", b("ESPN", country = "US")), "ESPN 2 is not ESPN")
        assertNull(m("UK: TNT SPORTS 1", b("TNT", country = "US")))
        assertNull(m("US: TNT Sports 1", b("TNT", country = "US")))
        assertNull(m("US: MSG Sportsnet", b("MSG", Market.HOME, "US")), "MSG Sportsnet is not MSG")
        assertNull(m("US: MSG 2", b("MSG", Market.HOME, "US")))
        assertNull(m("UK: SNL Classics", sn))
        assertNull(m("UK: Snooker", sn))
        assertNull(m("CA| SN+ 01", sn))
        assertNull(m("CA: Sportsnet +1", sn), "a +1 feed is not the live network")
        assertNull(m("CA| Sportsnet Prairies", sn), "only the listed region words")
        assertNull(m("CA| Sportsnet Ontario West", sn), "one region word, not two")
    }

    /** Every Sportsnet channel against SN+ (and every other streaming-only code): nothing (rule 4). */
    @Test fun streamingCodesMatchNothing() {
        val sportsnet = listOf("CA| SPORTSNET ONTARIO HD", "CA| SN ONTARIO FHD", "SN - Ontario", "CA| SPORTSNET 360", "CA| SPORTSNET ONE",
            "CA| SPORTSNET", "CA| SN+ 01", "CA| SPORTSNET+", "Sportsnet Plus", "CA| SPORTSNET WEST")
        for (code in listOf("SN+", "Prime Video", "HBO MAX", "Max", "ESPN+", "Peacock", "Paramount+", "Mammoth+", "Wild+", "FOXLA+", "Victory+")) {
            assertNull(BroadcastAliases.ftsMatch(code), code)
            assertEquals(BroadcastAliases.CodeKind.STREAMING, BroadcastAliases.kindOf(code), code)
            for (name in sportsnet + listOf("US: Prime Video", "US: HBO Max", "US: ESPN+", "US: Peacock")) {
                assertNull(m(name, b(code, country = "CA")), "$name ~ $code")
                assertNull(m(name, b(code, country = "US")), "$name ~ $code")
            }
        }
    }

    /** R-S3: local affiliates are guide-only; no name alias matches them. */
    @Test fun affiliatesMatchNothingByName() {
        val names = listOf("CBC - Toronto", "CA| CBC", "US: ABC 7 NEW YORK", "US: ABC", "FOX 5 WNYW", "US: FOX", "CBS 2", "US: CBS", "US: NBC",
            "CA| CTV", "CA| CITYTV", "US: KONG", "US: KCOP 13")
        for (code in listOf("ABC", "CBS", "FOX", "NBC", "CTV", "CBC", "CITY", "KONG", "KCOP", "WGN", "KTVD2")) {
            assertNull(BroadcastAliases.ftsMatch(code), code)
            assertEquals(BroadcastAliases.CodeKind.AFFILIATE, BroadcastAliases.kindOf(code), code)
            for (n in names) assertNull(m(n, b(code, Market.HOME, "US")), "$n ~ $code")
        }
    }

    /** Rule 5: an unknown code only on residue equality with the code (≥ 3 characters), POSSIBLE. */
    @Test fun unknownCodes() {
        assertEquals(BroadcastAliases.CodeKind.UNKNOWN, BroadcastAliases.kindOf("Utah16"))
        assertEquals(MatchConfidence.POSSIBLE, m("US: UTAH16", b("Utah16", Market.AWAY, "US"))?.confidence)
        assertNull(m("US: Utah 16", b("Utah16", Market.AWAY, "US")))
        assertNull(m("US: Utah16 News", b("Utah16", Market.AWAY, "US")))
        assertEquals("\"abtv\"", BroadcastAliases.ftsMatch("ABTV "))
        assertNull(BroadcastAliases.ftsMatch("XY"), "under 3 characters")
    }

    @Test fun ftsTextIsQuotedPhrasesJoinedByOr() {
        assertEquals("\"sportsnet\" OR \"sn ontario\" OR \"sn east\" OR \"sn west\" OR \"sn pacific\"", BroadcastAliases.ftsMatch("SN"))
        assertEquals("\"espn2\" OR \"espn 2\"", BroadcastAliases.ftsMatch("ESPN2"))
        for (code in BroadcastAliases.codes) {
            val q = BroadcastAliases.ftsMatch(code)!!
            assertFalse('*' in q || '(' in q || ')' in q, "$code: $q")
            q.split(" OR ").forEach { assertTrue(it.startsWith("\"") && it.endsWith("\""), "$code: $it") }
        }
    }

    @Test fun variantsShareTheCanonicalKey() {
        val keys = listOf("CA| SPORTSNET ONTARIO HD", "CA| SN ONTARIO FHD", "SN - Ontario", "Sportsnet Ontario 4K").map { m(it, sn)!!.key }.toSet()
        assertEquals(setOf("sportsnet ontario"), keys)
        assertEquals("sportsnet ontario", m("CA| SPORTSNET ONTARIO", b("SNO", Market.HOME, "CA"))!!.key)
        assertEquals("sportsnet", m("CA| SPORTSNET", sn)!!.key)
    }

    // ---------------------------------------------------------------- the recorded slate (A-B3)

    private fun slate(path: String): List<GameSnapshot> {
        val games = Json.parseToJsonElement(SportsFixtures.text(path)).jsonObject["games"]!!.jsonArray
        return games.map { g ->
            val o = g.jsonObject
            fun team(k: String) = o[k]!!.jsonObject.let { t ->
                TeamLine(t["abbrev"]!!.jsonPrimitive.content, t["name"]?.jsonObject?.get("default")?.jsonPrimitive?.content ?: t["abbrev"]!!.jsonPrimitive.content, null, null)
            }
            val bs = o["tvBroadcasts"]?.jsonArray.orEmpty().mapNotNull { x ->
                val b = x.jsonObject
                val market = when (b["market"]?.jsonPrimitive?.content) { "N" -> Market.NATIONAL; "H" -> Market.HOME; "A" -> Market.AWAY; else -> null } ?: return@mapNotNull null
                TvBroadcast(b["network"]!!.jsonPrimitive.content.trim(), market, b["countryCode"]?.jsonPrimitive?.content)
            }
            GameSnapshot(
                GameId(League.NHL, o["id"]!!.jsonPrimitive.content), "", java.time.Instant.parse(o["startTimeUTC"]!!.jsonPrimitive.content).toEpochMilli(),
                GamePhase.FINAL, team("awayTeam"), team("homeTeam"), null, null, null, null, null, broadcasts = bs,
            )
        }
    }

    /** A playlist's worth of Sportsnet spellings: none matches a game whose only Canadian broadcaster is SN+. */
    @Test fun snPlusOnlyGamesMatchNoSportsnetChannel() {
        val games = slate(HubFixtures.SCORE_2026_11_23).associateBy { it.id.id }
        val names = listOf("CA| SPORTSNET ONTARIO HD", "CA| SN ONTARIO FHD", "SN - Ontario", "CA| SPORTSNET 360", "CA| SPORTSNET ONE",
            "CA| SPORTSNET", "CA| SPORTSNET EAST", "CA| SPORTSNET WEST", "CA| SPORTSNET PACIFIC", "CA| SN+ 01", "CA| SPORTSNET+", "CA| SPORTSNET WORLD")
        for (id in listOf("2026020379", "2026020380", "2026020382")) {
            val g = games.getValue(id)
            for (bc in g.broadcasts) for (n in names) assertNull(m(n, bc), "$id: $n ~ ${bc.network}")
        }
        // And the SN games of the same slate do match.
        assertNotNull(games.getValue("2026020381").broadcasts.firstNotNullOfOrNull { m("CA| SPORTSNET ONTARIO", it) })
        assertNotNull(games.getValue("2026020384").broadcasts.firstNotNullOfOrNull { m("CA| SPORTSNET 360", it) })
    }

    // ---------------------------------------------------------------- GameListing

    private val vgkEdm = GameSnapshot(GameId(League.NHL, "2026020385"), "", 0, GamePhase.SCHEDULED,
        TeamLine("VGK", "Golden Knights", null, null), TeamLine("EDM", "Oilers", null, null), null, null, null, null, null)

    @Test fun listingKinds() {
        val t = GameListing.teamsOf(vgkEdm)
        assertEquals(listOf("golden knights oilers", "vegas edmonton"), t.queries)
        assertEquals(GameListing.Kind.GAME, GameListing.classify("NHL Hockey: Golden Knights at Oilers", null, t))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("Vegas at Edmonton", null, t))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("NHL Hockey", "Golden Knights at Oilers", t))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("Hockey Night in Canada: Oilers", null, t), "one team + a sport word")
        assertEquals(GameListing.Kind.NONE, GameListing.classify("Oilers Classics", null, t), "one team alone is not the game")
        assertEquals(GameListing.Kind.SPORT, GameListing.classify("Hockey LNH", null, t))
        assertEquals(GameListing.Kind.SPORT, GameListing.classify("NHL Tonight", null, t))
        assertEquals(GameListing.Kind.OTHER_GAME, GameListing.classify("NHL Hockey: Kings at Ducks", null, t))
        assertEquals(GameListing.Kind.OTHER_GAME, GameListing.classify("NHL Hockey", "Kings vs. Ducks", t))
        assertEquals(GameListing.Kind.NONE, GameListing.classify("Escape at Dannemora", null, t))
        // Nicknames collide across leagues: "Jets" alone (NFL) is not the NHL game.
        val wpg = GameListing.teamsOf(vgkEdm.copy(away = TeamLine("UTA", "Mammoth", null, null), home = TeamLine("WPG", "Jets", null, null)))
        assertEquals(GameListing.Kind.NONE, GameListing.classify("NFL Football: Jets at Bills", null, wpg))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("NHL: Utah at Winnipeg", null, wpg))
    }

    @Test fun landingFullNamesAndPlacesWithDiacritics() {
        val t = GameListing.teamsOf(vgkEdm.copy(away = TeamLine("MTL", "Montréal Canadiens", null, null), home = TeamLine("STL", "St. Louis Blues", null, null)))
        assertEquals(listOf("canadiens blues", "montreal st louis"), t.queries)
        assertEquals(GameListing.Kind.GAME, GameListing.classify("LNH: Canadiens c. Blues", null, t))
        assertEquals(GameListing.Kind.GAME, GameListing.classify("Montreal at St. Louis", null, t))
    }
}
