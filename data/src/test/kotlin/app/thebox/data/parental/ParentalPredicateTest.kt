package app.thebox.data.parental

import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.exec
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m5-setup-settings.md §4.3 (B3, S5, S10): the SQL truth table over the four access values × channel and
 * group adult flags × explicit group and channel locks (none / unlocked / locked) × hidden × rule_hidden, through the
 * guide's lineup query, the Groups panel query and the TuneGuard's index (the same predicate in three places).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalPredicateTest {
    private lateinit var db: LibraryDatabase
    private lateinit var gg: GuideGraph
    private var p = 0L

    private data class Case(
        val key: String, val chAdult: Boolean, val gAdult: Boolean, val gpLocked: Boolean?, val cpLocked: Boolean?,
        val hidden: Boolean, val ruleHidden: Boolean,
    )

    private val cases = ArrayList<Case>()

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        gg = GuideGraph(TestGraph(db))
        p = db.newPlaylist()
        var i = 0
        val tri = listOf<Boolean?>(null, false, true)
        for (chAdult in listOf(false, true)) for (gAdult in listOf(false, true)) for (gp in tri) for (cp in tri) for (hidden in listOf(false, true)) for (rule in listOf(false, true)) {
            val k = "c${i++}"
            val c = Case(k, chAdult, gAdult, gp, cp, hidden, rule)
            cases += c
            val g = db.newGroup(p, "g:$k", "G $k", adult = gAdult)
            db.newChannel(p, k, "Ch $k", g, sortIndex = i, number = 100 + i, adult = chAdult)
            if (gp != null) db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (?, 0, ?, 0, ?, 1)", p, "g:$k", gp)
            if (cp != null || hidden) db.exec("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, watch_count, updated_at) VALUES (?, ?, ?, ?, 0, 1)", p, k, hidden, cp)
            if (rule) db.exec("UPDATE channel SET rule_hidden = 1 WHERE playlist_id = ? AND stable_key = ?", p, k)
        }
    }

    @After fun tearDown() { db.close() }

    /** The §4.3 predicate written out independently of the SQL. */
    private fun visible(c: Case, access: ParentalAccess): Boolean {
        if (c.hidden || c.ruleHidden) return false
        if (access == ParentalAccess.OPEN) return true
        val auto = access == ParentalAccess.LOCKED_AUTO || access == ParentalAccess.FILTERED_NO_PIN
        val channelLock = c.cpLocked ?: (auto && c.gpLocked == null && c.chAdult)
        val groupLock = c.gpLocked ?: (auto && c.gAdult)
        return !channelLock && !groupLock
    }

    private fun groupLocked(c: Case, access: ParentalAccess): Boolean =
        access != ParentalAccess.OPEN && (c.gpLocked ?: (access in setOf(ParentalAccess.LOCKED_AUTO, ParentalAccess.FILTERED_NO_PIN) && c.gAdult))

    @Test fun lineupTruthTable() = runBlocking {
        val keyOf = HashMap<Long, String>()
        db.channelDao().allForPlaylist(p).forEach { keyOf[it.id] = it.stableKey }
        for (access in ParentalAccess.entries) {
            val ids = gg.queries.lineup(GuideSource.All(p), access).ids
            val got = ids.map { keyOf.getValue(it) }.toSet()
            val want = cases.filter { visible(it, access) }.map { it.key }.toSet()
            assertEquals("access $access", want, got)
        }
    }

    /** S5: an explicit group unlock wins over per-channel detection; an explicit channel lock still wins over everything. */
    @Test fun explicitGroupDecisionOverridesChannelDetection() = runBlocking {
        val keyOf = db.channelDao().allForPlaylist(p).associate { it.id to it.stableKey }
        val got = gg.queries.lineup(GuideSource.All(p), ParentalAccess.LOCKED_AUTO).ids.map { keyOf.getValue(it) }.toSet()
        val unlockedAdult = cases.first { it.chAdult && it.gAdult && it.gpLocked == false && it.cpLocked == null && !it.hidden && !it.ruleHidden }
        assertTrue("group is_adult, gp.locked = 0, channel is_adult → visible under LOCKED_AUTO", unlockedAdult.key in got)
        val lockedChannel = cases.first { !it.chAdult && !it.gAdult && it.gpLocked == false && it.cpLocked == true && !it.hidden && !it.ruleHidden }
        assertFalse("explicit channel lock wins", lockedChannel.key in got)
        // FILTERED_NO_PIN filters exactly as LOCKED_AUTO.
        assertEquals(got, gg.queries.lineup(GuideSource.All(p), ParentalAccess.FILTERED_NO_PIN).ids.map { keyOf.getValue(it) }.toSet())
    }

    @Test fun groupsPanelListsLockedGroupsWithTheirFlag() = runBlocking {
        for (access in ParentalAccess.entries) {
            val rows = db.groupDao().panelGroups(p, 0, access.ordinal).first().associateBy { it.group.groupKey }
            for (c in cases) {
                val r = rows["g:${c.key}"]
                assertTrue("every non-hidden group is listed ($access ${c.key})", r != null)
                assertEquals("locked flag $access $c", groupLocked(c, access), r!!.locked)
            }
        }
        // A hidden or rule-hidden group is never listed.
        db.exec("UPDATE channel_group SET rule_hidden = 1 WHERE group_key = ?", "g:c0")
        db.exec("INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, updated_at) VALUES (?, 0, ?, 1, 1) ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET hidden = 1", p, "g:c1")
        val keys = db.groupDao().panelGroups(p, 0, 0).first().map { it.group.groupKey }
        assertFalse("g:c0" in keys); assertFalse("g:c1" in keys)
    }

    /** The TuneGuard's index evaluates the same lock clauses for one identity (hidden does not matter to a tune). */
    @Test fun tuneGuardIndexAgreesWithTheLineup() = runBlocking {
        val index = LockedChannelIndex(db, gg.g.scope, kotlinx.coroutines.Dispatchers.IO)
        index.rebuild()
        for (access in ParentalAccess.entries) for (c in cases) {
            val shown = visible(c.copy(hidden = false, ruleHidden = false), access)
            assertEquals("index $access $c", !shown, index.locked(ChannelIdentity(p, StableKey(c.key)), access))
        }
    }

    /** The legacy Boolean signatures keep working (OPEN / LOCKED_AUTO) with the new clauses. */
    @Test fun legacyBooleanQueriesMapOntoOpenAndLockedAuto() = runBlocking {
        val open = db.channelDao().browseAll(p, unlocked = true).first().map { it.channel.stableKey }.toSet()
        val locked = db.channelDao().browseAll(p, unlocked = false).first().map { it.channel.stableKey }.toSet()
        assertEquals(cases.filter { visible(it, ParentalAccess.OPEN) }.map { it.key }.toSet(), open)
        assertEquals(cases.filter { visible(it, ParentalAccess.LOCKED_AUTO) }.map { it.key }.toSet(), locked)
        val numbered = cases.filter { visible(it, ParentalAccess.LOCKED_MANUAL_ONLY) }.map { it.key }.toSet()
        for ((i, c) in cases.withIndex()) {
            val row = db.channelDao().byNumberAccess(p, 101 + i, ParentalAccess.LOCKED_MANUAL_ONLY.ordinal)
            assertEquals("byNumberAccess ${c.key}", c.key in numbered, row != null)
        }
    }
}
