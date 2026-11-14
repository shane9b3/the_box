package app.thebox.data.settings

import app.thebox.core.backup.BackupHeader
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * (CHL commit 0, docs/design/m6-chl-leagues.md §1.4; fix pass L8) The backup / restore / downgrade table, row by row,
 * against the settings store's own JSON ([RoomSettingsStore.JSON]; [RoomSettingsStore.STRICT_JSON] stands in for a build
 * older than the Stacked `coerceInputValues` change), so a change to the store's config is caught here.
 */
class ChlSportsCompatTest {
    private val store = RoomSettingsStore.JSON
    private val preCoerce = RoomSettingsStore.STRICT_JSON

    /** v0.1.7's `League` and documents, field for field. */
    @Serializable private enum class OldLeague { NHL, NFL }

    @Serializable private data class OldSportsPrefs(
        val enabled: Boolean = true,
        val corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
        val delaySec: Int = 0,
        val size: ScoreBugSize = ScoreBugSize.STANDARD,
        val league: OldLeague = OldLeague.NHL,
        val nhlIntervalS: Int = 0,
        val nflIntervalS: Int = 0,
        val nhlStyle: NhlBugStyle = NhlBugStyle.PILL,
    )

    @Serializable private data class OldFollowedGame(
        val league: OldLeague,
        val gameId: String,
        val startUtcMs: Long,
        val away: String,
        val home: String,
        val finalSeenUtcMs: Long? = null,
    )

    @Serializable private data class OldFollowDoc(
        val game: OldFollowedGame? = null,
        val pollPhaseMs: Long? = null,
        val firstFollowShown: Boolean = false,
        val meters: Map<OldLeague, PollMeasure> = emptyMap(),
    )

    @Test
    fun aNewBuildReadsAnOldSportsDocumentWithTheNewFieldsAtTheirDefaults() {
        val old = """{"enabled":false,"corner":"BOTTOM_LEFT","delaySec":30,"league":"NFL","nflIntervalS":10,"nhlStyle":"STACKED"}"""
        val p = store.decodeFromString(SportsPrefs.serializer(), old)
        assertEquals(
            SportsPrefs(
                enabled = false, corner = ScoreCorner.BOTTOM_LEFT, delaySec = 30, league = League.NFL, nflIntervalS = 10,
                nhlStyle = NhlBugStyle.STACKED,
            ),
            p,
        )
        assertEquals(League.OHL, p.chlLeague)
        assertEquals(0, p.chlIntervalS)
        // The defaults are never written, so an NHL-only user's document is byte-identical to an older build's.
        assertEquals("""{"nhlStyle":"STACKED"}""", store.encodeToString(SportsPrefs.serializer(), SportsPrefs(nhlStyle = NhlBugStyle.STACKED)))
    }

    @Test
    fun aV017BuildRestoringANewBackupCoercesTheLeagueAndKeepsTheRest() {
        val newDoc = store.encodeToString(
            SportsPrefs.serializer(),
            SportsPrefs(enabled = false, delaySec = 60, league = League.WHL, chlLeague = League.WHL, chlIntervalS = 10, nhlIntervalS = 40),
        )
        val old = store.decodeFromString(OldSportsPrefs.serializer(), newDoc)
        assertEquals(OldSportsPrefs(enabled = false, delaySec = 60, league = OldLeague.NHL, nhlIntervalS = 40), old)
        assertEquals("the new build reads it whole", League.WHL, store.decodeFromString(SportsPrefs.serializer(), newDoc).league)
    }

    /**
     * (CHL integration) The same build: a backup of CHL prefs restores whole, and a followed CHL game with its meter
     * survives a restart (the `sports_follow` document read back by the store's own JSON).
     */
    @Test
    fun theSameBuildRestoresChlPrefsAndKeepsAChlFollowAcrossARestart() {
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val prefs = SportsPrefs(league = league, chlLeague = league, chlIntervalS = 15, nhlStyle = NhlBugStyle.STACKED, corner = ScoreCorner.BOTTOM_LEFT)
            assertEquals(prefs, store.decodeFromString(SportsPrefs.serializer(), store.encodeToString(SportsPrefs.serializer(), prefs)))
            val follow = SportsFollowDoc(
                game = FollowedGame(league, "92001", 1_794_612_600_000L, "NFL", "CHA"), pollPhaseMs = 1_500, firstFollowShown = true,
                meters = mapOf(league to PollMeasure(endpoint = PollMeter.CHL_LIVE, workMs = listOf(120), wireBytes = listOf(3_878))),
            )
            val back = store.decodeFromString(SportsFollowDoc.serializer(), store.encodeToString(SportsFollowDoc.serializer(), follow))
            assertEquals(follow, back)
            // L2: the team code "NFL" stays a team code; the league is the stored enum name.
            assertEquals(league, back.game?.league)
        }
        // Leaving the CHL keeps the remembered sub-league (§1.3 writing rule) and it survives the round trip.
        val nhl = SportsPrefs(league = League.NHL, chlLeague = League.QMJHL)
        assertEquals(League.QMJHL, store.decodeFromString(SportsPrefs.serializer(), store.encodeToString(SportsPrefs.serializer(), nhl)).chlShown)
    }

    @Test
    fun aBuildOlderThanTheCoerceChangeFindsTheDocumentUnreadable() {
        val newDoc = store.encodeToString(SportsPrefs.serializer(), SportsPrefs(league = League.QMJHL, chlLeague = League.QMJHL))
        assertThrows("unreadable; kept (L8)", SerializationException::class.java) {
            preCoerce.decodeFromString(OldSportsPrefs.serializer(), newDoc)
        }
    }

    @Test
    fun aDowngradeWithAChlFollowStartsWithNoFollowAndTheBackupSchemaStaysOne() {
        val doc = store.encodeToString(
            SportsFollowDoc.serializer(),
            SportsFollowDoc(game = FollowedGame(League.OHL, "29191", 1L, "LDN", "KIT"), pollPhaseMs = 3_000),
        )
        assertThrows("FollowedGame.league has no default", SerializationException::class.java) {
            store.decodeFromString(OldFollowDoc.serializer(), doc)
        }
        val meters = store.encodeToString(
            SportsFollowDoc.serializer(),
            SportsFollowDoc(meters = mapOf(League.WHL to PollMeasure(endpoint = PollMeter.CHL_LIVE))),
        )
        assertThrows("an unknown map key fails the document", SerializationException::class.java) {
            store.decodeFromString(OldFollowDoc.serializer(), meters)
        }
        assertEquals(1, BackupHeader.SCHEMA)
    }
}
