package app.thebox.core.catchup.archive

import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ArchiveVisibility.visible` (docs/design/m4-player-ux.md §2.3a, review B1) as a table: every `ArchivePhase` × visible
 * phase × {owned, earlier own cycle, later cycle} of the session's channel, plus live and another channel.
 * Integration 2026-10-15: a lower tuneId of the session's channel (the previous cycle, reported for one emission after
 * `ownedTuneId` rose in the same turn as `tuneArchive`) is judged as owned.
 */
class ArchiveVisibilityTest {
    private val ch = GuideFixtures.channel(3, archiveDays = 7)
    private val owned = 10L

    private fun tuned(phase: MiniPhase, tuneId: Long = owned, archive: Boolean = true, identityHash: Long = ch.identityHash) = TunedState(
        identity = ch.identity, rowId = ch.rowId, identityHash = identityHash, phase = phase, isArchive = archive,
        error = if (phase == MiniPhase.ERROR) PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE else null,
        errorDetail = if (phase == MiniPhase.ERROR) PlayerFixtures.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE) else null,
        tuneId = tuneId,
    )

    private fun status(phase: ArchivePhase) = ArchiveStatus(phase = phase, channel = ch, identityHash = ch.identityHash, ownedTuneId = owned)

    private val active = ArchivePhase.entries.filter { it != ArchivePhase.IDLE && it != ArchivePhase.FAILED }
    private val handoffPhases = setOf(ArchivePhase.SEEKING, ArchivePhase.CONTINUING, ArchivePhase.PROBING)
    private val waiting = setOf(MiniPhase.ERROR, MiniPhase.CONNECTING, MiniPhase.BUFFERING)

    @Test
    fun ownedEarlierAndLaterCyclesOfTheSessionChannelAreJudgedAlike() {
        for (ap in active) for (mp in MiniPhase.entries) for (id in listOf(owned, owned - 1, owned - 5, owned + 1)) {
            val t = tuned(mp, tuneId = id)
            val v = ArchiveVisibility.visible(t, status(ap))
            val row = "archive=$ap tuned=$mp tuneId=$id (owned $owned)"
            assertEquals(id, v.tuneId, "$row: tuneId kept")
            if (mp !in waiting) {
                assertSame(t, v, "$row: not waiting, unchanged")
                continue
            }
            val handoff = ap in handoffPhases || (ap == ArchivePhase.PLAYING && mp == MiniPhase.ERROR)
            assertEquals(if (handoff) MiniPhase.PLAYING else MiniPhase.CONNECTING, v.phase, row)
            assertEquals(handoff, v.handoff, row)
            assertNull(v.error, row)
            assertNull(v.errorDetail, row)
        }
    }

    /** The B1 hole (integration 2026-10-15): PROBING with ownedTuneId already raised, the failed variant's Error still showing. */
    @Test
    fun previousCycleErrorWhileProbingIsAHandOffNotTheCard() {
        val v = ArchiveVisibility.visible(tuned(MiniPhase.ERROR, tuneId = owned - 1), status(ArchivePhase.PROBING))
        assertEquals(MiniPhase.PLAYING, v.phase)
        assertTrue(v.handoff)
        assertNull(v.error)
    }

    @Test
    fun inactiveSessionLiveAndOtherChannelsPassThrough() {
        for (mp in MiniPhase.entries) for (id in listOf(owned - 1, owned, owned + 1)) {
            val t = tuned(mp, tuneId = id)
            assertSame(t, ArchiveVisibility.visible(t, null))
            assertSame(t, ArchiveVisibility.visible(t, status(ArchivePhase.IDLE)))
            assertSame(t, ArchiveVisibility.visible(t, status(ArchivePhase.FAILED)))
            for (ap in active) {
                val live = tuned(mp, tuneId = id, archive = false)
                assertSame(live, ArchiveVisibility.visible(live, status(ap)), "live $mp $id $ap")
                val other = tuned(mp, tuneId = id, identityHash = ch.identityHash + 1)
                assertSame(other, ArchiveVisibility.visible(other, status(ap)), "other channel $mp $id $ap")
            }
        }
    }
}
