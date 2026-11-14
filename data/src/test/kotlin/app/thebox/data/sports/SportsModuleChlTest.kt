package app.thebox.data.sports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** (CHL §1.6 #41) One `@LeagueKey` provider per league, and each CHL provider builds a `HockeyTechScoreSource` of its league. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SportsModuleChlTest {
    @Test fun everyLeagueHasExactlyOneProviderOfItsOwnLeague() {
        val providers = SportsModule.Companion::class.java.declaredMethods
            .mapNotNull { m -> m.getAnnotation(LeagueKey::class.java)?.let { it.value to m } }
        assertEquals(League.entries.toSet(), providers.map { it.first }.toSet())
        assertEquals(League.entries.size, providers.size)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        for ((league, m) in providers) {
            val s = m.invoke(SportsModule.Companion, client, context) as ScoreSource
            assertEquals(league, s.league)
            when (league) {
                League.OHL, League.WHL, League.QMJHL -> assertTrue("$league", s is HockeyTechScoreSource)
                League.NHL -> assertTrue(s is NhlScoreSource)
                League.NFL -> assertTrue(s is EspnScoreSource)
            }
        }
    }
}
