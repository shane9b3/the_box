package app.thebox.data.sports

import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.FakeClipResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (NFL hub P2, docs/design/m6-nfl-hub.md §4.1, §4.2, §8.2 `ClipResolversTest`) The router: NHL refs to the NHL resolver,
 * NFL refs to ESPN's with `fresh` passed through, every other league `NotFound` without a call, and the
 * `ESPN_VIDEO_ENABLED` kill switch answering `NotFound` for NFL refs without touching ESPN (NHL unaffected).
 */
class ClipResolversTest {
    private val nhl = FakeClipResolver()
    private val espn = FakeClipResolver()
    private val nhlRef = ClipRef(6408795671112L, ClipKind.RECAP, GameId(League.NHL, "2026020385"))
    private val nflRef = ClipRef(50578630L, ClipKind.PLAY, GameId(League.NFL, "401873078"))
    private val ohlRef = ClipRef(1L, ClipKind.GOAL, GameId(League.OHL, "29187"))

    init {
        nhl.streams[nhlRef.videoId] = FakeClipResolver.okStream(nhlRef)
        nhl.infos[nhlRef.videoId] = ClipResult.Ok((FakeClipResolver.okStream(nhlRef) as ClipResult.Ok).value.first)
        espn.streams[nflRef.videoId] = FakeClipResolver.okStream(nflRef)
        espn.infos[nflRef.videoId] = ClipResult.Ok((FakeClipResolver.okStream(nflRef) as ClipResult.Ok).value.first)
    }

    @Test fun routesByLeague() {
        val r = ClipResolvers(nhl, espn, espnVideo = true)
        assertTrue(runBlocking { r.stream(nhlRef) } is ClipResult.Ok)
        assertTrue(runBlocking { r.stream(nflRef, fresh = true) } is ClipResult.Ok)
        assertTrue(runBlocking { r.info(nflRef) } is ClipResult.Ok)
        assertTrue(runBlocking { r.info(nhlRef) } is ClipResult.Ok)
        assertEquals(listOf(nhlRef), nhl.streamCalls)
        assertEquals(listOf(nflRef), espn.streamCalls)
        assertEquals("fresh is passed through", listOf(true), espn.streamFresh)
        assertEquals(listOf(nhlRef), nhl.infoCalls)
        assertEquals(listOf(nflRef), espn.infoCalls)
    }

    @Test fun otherLeaguesAreNotFoundWithoutACall() {
        val r = ClipResolvers(nhl, espn, espnVideo = true)
        assertEquals(ClipResult.NotFound, runBlocking { r.stream(ohlRef) })
        assertEquals(ClipResult.NotFound, runBlocking { r.info(ohlRef) })
        assertTrue(nhl.streamCalls.isEmpty() && espn.streamCalls.isEmpty() && nhl.infoCalls.isEmpty() && espn.infoCalls.isEmpty())
    }

    @Test fun theKillSwitchTurnsNflVideoOffOnly() {
        val r = ClipResolvers(nhl, espn, espnVideo = false)
        assertEquals(ClipResult.NotFound, runBlocking { r.stream(nflRef) })
        assertEquals(ClipResult.NotFound, runBlocking { r.stream(nflRef, fresh = true) })
        assertEquals(ClipResult.NotFound, runBlocking { r.info(nflRef) })
        assertTrue("ESPN is never asked", espn.streamCalls.isEmpty() && espn.infoCalls.isEmpty())
        assertTrue("the NHL is unaffected", runBlocking { r.stream(nhlRef) } is ClipResult.Ok)
    }
}
