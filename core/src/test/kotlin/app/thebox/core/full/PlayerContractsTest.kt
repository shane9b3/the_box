package app.thebox.core.full

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.archive.ArchiveFrames
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.ArchiveVisibility
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.rawEndMin
import app.thebox.core.input.BoxAction
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.xtream.XtreamStreamRef
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Commit-0 checks on the M4 contracts and the shared fakes (docs/design/m4-player-ux.md §1.7, §1.8, §2): fixtures
 * cover every error code, the B1 visibility rule and the B2 live edge behave as written, ids stay append-only, and
 * the redacted `toString`s carry no credentials.
 */
class PlayerContractsTest {
    @Test
    fun everyPlaybackErrorCodeHasAFixture() {
        for (code in PlaybackErrorCode.entries) {
            val e = PlayerFixtures.error(code)
            assertEquals(code, e.code)
            assertEquals(code.defaultSteps, e.actions, "$code: the ladder's default actions")
            assertEquals(code == PlaybackErrorCode.NO_DATA_TIMEOUT, e.retry != null, "$code: retry only on the switch-timeout card (unlimited retries)")
        }
        assertEquals(PlaybackErrorCode.entries.size, PlayerFixtures.allErrors().map { it.code }.toSet().size)
    }

    @Test
    fun boxActionIdsAreUniqueAndM4AppendsAfterDigit() {
        val ids = BoxAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids are persisted: unique")
        val m4 = listOf(BoxAction.PLAY, BoxAction.PAUSE, BoxAction.TOGGLE_FAVOURITE, BoxAction.VIDEO_DECODER, BoxAction.PREV_PROGRAMME, BoxAction.NEXT_PROGRAMME, BoxAction.TIME_SHIFT)
        assertEquals(m4, BoxAction.entries.drop(BoxAction.DIGIT.ordinal + 1).take(m4.size), "append-only, after DIGIT (M5 appends after these)")
        assertEquals("favourite_toggle", BoxAction.TOGGLE_FAVOURITE.id)
    }

    @Test
    fun liveEdgeMinusCorrectionIsThePanelNowForEverySign() {
        // B2/F1: liveEdge = deviceNow + skew + correction.
        for (corrMs in listOf(-5_400_000L, 0L, 7_200_000L)) {
            val f = ArchiveFrames(correctionMs = corrMs, skewMs = 45_000, displayShiftMs = 0)
            assertEquals(1_000_045_000L, f.archiveOf(f.liveEdge(1_000_000_000L)))
            assertEquals(123L, f.archiveOf(f.programmeOf(123L)))
        }
    }

    @Test
    fun rawEndMinKeepsTheDuration() {
        val p = GuideFixtures.info(1_000, 1_030, shiftMin = 60)
        assertEquals(940, p.rawStartMin)
        assertEquals(970, p.rawEndMin)
    }

    private val ch = GuideFixtures.channel(3, archiveDays = 7)
    private fun tuned(phase: MiniPhase, tuneId: Long = 10, archive: Boolean = true) = TunedState(
        identity = ch.identity, rowId = ch.rowId, identityHash = ch.identityHash, phase = phase, isArchive = archive,
        error = if (phase == MiniPhase.ERROR) PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE else null,
        errorDetail = if (phase == MiniPhase.ERROR) PlayerFixtures.error(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE) else null,
        tuneId = tuneId,
    )
    private fun status(phase: ArchivePhase, owned: Long = 10) = ArchiveStatus(phase = phase, channel = ch, identityHash = ch.identityHash, ownedTuneId = owned)

    @Test
    fun archiveVisibilityHidesOwnedHandOffsOnly() {
        // A probe error while PROBING: a hand-off, never the card.
        val probe = ArchiveVisibility.visible(tuned(MiniPhase.ERROR), status(ArchivePhase.PROBING))
        assertEquals(MiniPhase.PLAYING, probe.phase)
        assertNull(probe.error)
        assertNull(probe.errorDetail)
        assertTrue(probe.handoff)
        // The first request's Connecting while STARTING stays a (clean) CONNECTING.
        val starting = ArchiveVisibility.visible(tuned(MiniPhase.CONNECTING), status(ArchivePhase.STARTING))
        assertEquals(MiniPhase.CONNECTING, starting.phase)
        assertFalse(starting.handoff)
        // FAILED, IDLE, null status, live, another channel: unchanged.
        val err = tuned(MiniPhase.ERROR)
        assertSame(err, ArchiveVisibility.visible(err, status(ArchivePhase.FAILED)))
        assertSame(err, ArchiveVisibility.visible(err, status(ArchivePhase.IDLE)))
        assertSame(err, ArchiveVisibility.visible(err, null))
        // The session's own earlier cycle (ownedTuneId already raised) is a hand-off too (integration 2026-10-15).
        assertTrue(ArchiveVisibility.visible(err, status(ArchivePhase.PROBING, owned = 11)).handoff)
        val live = tuned(MiniPhase.ERROR, archive = false)
        assertSame(live, ArchiveVisibility.visible(live, status(ArchivePhase.PROBING)))
        val other = err.copy(identityHash = 42)
        assertSame(other, ArchiveVisibility.visible(other, status(ArchivePhase.PROBING)))
        // Playing is never touched.
        val playing = tuned(MiniPhase.PLAYING)
        assertSame(playing, ArchiveVisibility.visible(playing, status(ArchivePhase.SEEKING)))
    }

    @Test
    fun redactedToStringsCarryNoCredentials() {
        val ref = XtreamStreamRef("http://panel.example:8080", "alice", "s3cret", 101, "ts")
        val target = CatchupTarget("http://panel.example:8080/live/alice/s3cret/101.ts", mapOf("Authorization" to "Bearer tok123"), Catchup(CatchupMode.XC, 7, null, null), ref)
        val req = ArchiveRequest(
            "http://alice:s3cret@panel.example:8080/timeshift/alice/s3cret/60/2026-10-13:19-30/101.ts?token=tok123",
            mapOf("Cookie" to "tok123"), CatchupMode.XC, "xc-path", Instant.EPOCH, null, null,
        )
        for (s in listOf(ref.toString(), target.toString(), req.toString())) {
            for (secret in listOf("alice", "s3cret", "tok123", "timeshift", "101")) assertFalse(secret in s, "$secret in $s")
            assertTrue("panel.example:8080" in s, s)
        }
        assertTrue("xc-path" in req.toString())
        assertEquals(ref, ref.copy(), "equals stays generated")
    }

    @Test
    fun fakeFeedAroundIndexesTheAiringProgramme() = runTest {
        val feed = FakeProgrammeFeed()
        feed.put(ch, PlayerFixtures.programmes(ch, fromMin = 1_000, n = 40, durMin = 5))
        val slice = feed.around(ch, atMin = 1_100, fromMin = 1_000, before = 12, after = 12)
        assertEquals(25, slice.programmes.size)
        assertEquals(12, slice.nowIndex)
        assertEquals(1_100, slice.programmes[slice.nowIndex].startMin)
        val gap = feed.around(ch, atMin = 2_000, fromMin = 1_900, before = 12, after = 12)
        assertEquals(-1, gap.nowIndex)
        assertNotNull(feed.at(ch, 1_001))
    }

    @Test
    fun playerSettingsDefaults() {
        val s = PlayerSettings()
        assertEquals(5_000, s.infoBarMs)
        assertEquals(1_500, s.numberEntryWaitMs)
        assertEquals(UpDownInPlayer.QUICK_BAR_AND_INFO, s.upDown)
        assertEquals(+1, DPAD_UP_ZAP_DELTA)
        assertEquals(24, ProgrammeFeed.PROGRAMME_RANGE_LIMIT)
    }
}
