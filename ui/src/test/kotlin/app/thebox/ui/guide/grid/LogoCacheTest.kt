package app.thebox.ui.guide.grid

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.RecordingGuideCanvas
import app.thebox.core.guide.fakes.FakeTextMeasurer
import app.thebox.core.guide.layout.GridConstants
import app.thebox.core.guide.render.GuideReferenceFrame
import app.thebox.core.guide.render.GuideRendererImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m3-guide.md §3.7, §4.4, §4.7 (S8, N3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogoCacheTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val requests = ArrayList<Triple<String, Int, Int>>()
    private val pending = HashMap<String, CompletableDeferred<Bitmap?>>()

    private fun cache() = LogoCache({ url, w, h ->
        requests += Triple(url, w, h)
        pending.getOrPut(url) { CompletableDeferred() }.await()
    }, scope)

    private fun bmp(w: Int = 130, h: Int = 64) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun missStartsOneLoadPerUrlAndHitReturnsTheBitmap() {
        val c = cache()
        var loaded = 0
        c.onLoaded = { loaded++ }
        repeat(60) { assertNull(c.get("http://x/a.png")) }      // 60 frames while loading
        assertEquals(1, requests.size)
        assertEquals(Triple("http://x/a.png", 150, 74), requests[0])  // GridConstants.LOGO_W × LOGO_H (2026-11-09)
        pending["http://x/a.png"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertEquals(1, loaded)
        val img = c.get("http://x/a.png")
        assertNotNull(img)
        assertEquals(130, img!!.widthPx)
        assertEquals(1, requests.size)
    }

    @Test
    fun failuresAreRememberedForTheSession() {
        val c = cache()
        c.get("bad")
        pending["bad"]!!.complete(null)
        scope.advanceUntilIdle()
        assertTrue(c.failed("bad"))
        assertNull(c.get("bad"))
        assertEquals(1, requests.size)
        val c2 = LogoCache({ _, _, _ -> throw IllegalStateException("404") }, scope)
        c2.get("boom")
        scope.advanceUntilIdle()
        assertTrue(c2.failed("boom"))
    }

    @Test
    fun slotSizeFollowsScale() {
        val c = cache()
        c.scale = 2f
        c.get("u")
        assertEquals(Triple("u", 300, 148), requests.last())
    }

    @Test
    fun lruCapsEntriesAndBytesLeastRecentlyDrawnFirst() {
        // Small bitmaps so the entry cap binds before the byte cap (160 full 130×64 slots are 5.3 MB > 4 MB).
        val c = LogoCache({ _, _, _ -> bmp(96, 40) }, scope)
        for (i in 0 until GridConstants.LOGO_CACHE + 10) c.get("u$i")
        scope.advanceUntilIdle()
        assertEquals(GridConstants.LOGO_CACHE, c.size)
        assertNull("oldest evicted", c.get("u0"))
        // Low-end: 48 entries.
        c.lowEnd = true
        assertEquals(GridConstants.LOGO_CACHE_LOW_END, c.size)
        // Recently drawn survives a trim.
        val keep = "u${GridConstants.LOGO_CACHE + 9}"
        assertNotNull(c.get(keep))
        for (i in 1000 until 1000 + GridConstants.LOGO_CACHE_LOW_END - 1) c.get("u$i")
        scope.advanceUntilIdle()
        assertNotNull(c.get(keep))
        // Byte cap: 4 MB / 400 KB bitmaps → at most 10.
        val big = LogoCache({ _, _, _ -> bmp(320, 320) }, scope)
        for (i in 0 until 20) big.get("b$i")
        scope.advanceUntilIdle()
        assertTrue(big.byteCount <= GridConstants.LOGO_CACHE_BYTES)
        assertEquals(10, big.size)
    }

    @Test
    fun clearDropsBitmapsAndInFlightResults() {
        val c = cache()
        c.get("a")
        c.clear()
        pending["a"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertEquals(0, c.size)
        assertFalse(c.failed("a"))
        assertNull(c.get("a"))
        assertEquals(2, requests.size)
    }

    private val cancelled = ArrayList<String>()

    /** A cache whose fetches record their cancellation. */
    private fun cancellableCache() = LogoCache({ url, w, h ->
        requests += Triple(url, w, h)
        try {
            pending.getOrPut(url) { CompletableDeferred() }.await()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            cancelled += url
            pending.remove(url)
            throw e
        }
    }, scope)

    private fun LogoCache.ticks(n: Int) = repeat(n) { tick() }

    @Test
    fun offScreenLoadsAreCancelledAfterTheStaleWindowAndStillRequestedOnesAreNot() {
        val c = cancellableCache()
        c.get("gone"); c.get("kept")
        // "kept" keeps being asked for each draw; "gone" is not.
        repeat(LogoCache.STALE_FRAMES + 16) { c.tick(); c.get("kept") }
        assertEquals(listOf("gone"), cancelled)
        assertFalse("a cancelled load is not a failure", c.failed("gone"))
        assertEquals(0, c.size)
        pending["kept"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertNotNull(c.get("kept"))
    }

    @Test
    fun aBriefScrollPastAndBackDoesNotCancel() {
        val c = cancellableCache()
        c.get("a")
        c.ticks(LogoCache.STALE_FRAMES - 10)
        c.get("a")                                  // back in view
        c.ticks(LogoCache.STALE_FRAMES - 10)
        assertTrue(cancelled.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun aCancelledUrlLoadsAgainWhenItNextBecomesVisible() {
        val c = cancellableCache()
        c.get("a")
        c.ticks(LogoCache.STALE_FRAMES + 16)
        assertEquals(listOf("a"), cancelled)
        assertNull(c.get("a"))
        assertEquals(2, requests.size)
        pending["a"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertNotNull(c.get("a"))
        assertTrue("cancelled loads did not add a failure", !c.failed("a"))
    }

    // ---- review 2026-11-10 S3: rows replayed from a recording touch their logo instead of getting it

    @Test
    fun cancellingDoesNotBumpTheVersion() {
        val c = cancellableCache()
        c.get("a")
        val v = c.version
        c.ticks(LogoCache.STALE_FRAMES + 16)
        assertEquals(listOf("a"), cancelled)
        assertEquals("only off-screen loads are cancelled: no row needs re-recording", v, c.version)
    }

    @Test
    fun aTouchedPendingLoadSurvivesPastTheStaleWindow() {
        val c = cancellableCache()
        c.get("visible"); c.get("gone")
        repeat(LogoCache.STALE_FRAMES * 3) { c.tick(); c.touch("visible") }
        assertEquals("the off-screen one is still cancelled", listOf("gone"), cancelled)
        assertEquals("touch did not start a second load", 2, requests.size)
        pending["visible"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertNotNull(c.get("visible"))
    }

    @Test
    fun touchRestartsALoadCancelledWhileItsRowWasOffScreen() {
        // The row's recording outlived the cancel: replaying it must bring the load back, as get would.
        val c = cancellableCache()
        c.get("a")
        c.ticks(LogoCache.STALE_FRAMES + 16)
        assertEquals(listOf("a"), cancelled)
        c.touch("a")
        assertEquals(2, requests.size)
        c.touch("a")
        assertEquals("deduplicated like get", 2, requests.size)
        pending["a"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertNotNull(c.get("a"))
    }

    @Test
    fun touchKeepsACachedLogoRecentlyUsedAndLeavesFailuresAlone() {
        val c = LogoCache({ url, _, _ -> if (url == "bad") null else bmp(96, 40) }, scope)
        c.get("keep"); c.get("bad")
        scope.advanceUntilIdle()
        c.lowEnd = true
        for (i in 0 until GridConstants.LOGO_CACHE_LOW_END - 1) c.get("u$i")
        scope.advanceUntilIdle()
        c.touch("keep")                                  // replayed: still on screen
        c.get("one-more"); scope.advanceUntilIdle()      // evicts the least recently used
        assertNotNull("touched entry survives", c.get("keep"))
        c.touch("bad")
        assertTrue(c.failed("bad"))
    }

    @Test
    fun aReplayedVisibleRowKeepsItsLoadAliveThroughTheRenderer() {
        // End to end with O-2 replays: after the first draw every row replays, so only touch reaches the cache.
        val c = cancellableCache()
        val frame = GuideReferenceFrame.build(FakeTextMeasurer(), images = c)
        val renderer = GuideRendererImpl(FakeTextMeasurer())
        val canvas = ReplayingCanvas()
        repeat(LogoCache.STALE_FRAMES * 3) {
            c.tick()
            renderer.draw(canvas, frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        }
        assertTrue("rows were replayed", canvas.replayed > 0)
        assertTrue("visible logos were requested", requests.isNotEmpty())
        assertEquals("no visible row's load was cancelled", emptyList<String>(), cancelled)
        assertEquals("one load per visible logo", requests.map { it.first }.toSet().size, requests.size)
        val v = c.version
        // Scrolled far away: the old rows' loads go stale and are cancelled, without a version bump.
        repeat(LogoCache.STALE_FRAMES + 16) {
            c.tick()
            renderer.draw(canvas, frame, frame.windowStartMin, 0f, 30f)
        }
        assertTrue(cancelled.containsAll(listOf("logo://1", "logo://2")))
        assertEquals(v, c.version)
    }

    /** Records each key once and replays it after, like the API 29+ RenderNode adapter. */
    private class ReplayingCanvas(private val inner: RecordingGuideCanvas = RecordingGuideCanvas()) : GuideCanvas by inner {
        private val known = HashSet<Long>()
        var replayed = 0
        override fun beginRow(key: Long, top: Float): Boolean {
            inner.ops.clear()
            if (key in known) { replayed++; return true }
            known += key
            return false
        }
        override fun endRow() {}
    }

    @Test
    fun anArrivalBumpsTheVersionExactlyOnce() {
        val c = cancellableCache()
        c.get("a")
        val v = c.version
        pending["a"]!!.complete(bmp())
        scope.advanceUntilIdle()
        assertEquals(v + 1, c.version)
    }

    @Test
    fun aFailedLoadKeepsItsNegativeCacheAcrossTicks() {
        val c = cancellableCache()
        c.get("bad")
        pending["bad"]!!.complete(null)
        scope.advanceUntilIdle()
        c.ticks(LogoCache.STALE_FRAMES * 3)
        assertTrue(c.failed("bad"))
        assertNull(c.get("bad"))
        assertEquals(1, requests.size)
    }

    @Test
    fun clearCancelsInFlightLoads() {
        val c = cancellableCache()
        c.get("a")
        c.clear()
        assertEquals(listOf("a"), cancelled)
    }

    @Test
    fun trimMemoryClearsLogosAndTextCaches() {
        val c = LogoCache({ _, _, _ -> bmp() }, scope)
        c.get("a"); scope.advanceUntilIdle()
        assertEquals(1, c.size)
        val renderer = GuideRendererImpl(FakeTextMeasurer())
        val trim = GuideTrim(c, renderer)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(1, c.size)
        assertEquals(0, trim.trims)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(0, c.size)
        c.get("a"); scope.advanceUntilIdle()
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(0, c.size)
        assertEquals(2, trim.trims)
        GuideTrim(null, FakeGuideRenderer()).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** §2.13 item 6: registered owners (the guide VM) get the level; closing the handle unregisters. */
    @Test
    fun trimFansOutToRegisteredTargetsUntilClosed() {
        val trim = GuideTrim(null, null)
        val seen = ArrayList<Pair<Int, Boolean>>()
        val handle = trim.register { level, hidden -> seen += level to hidden }
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        handle.close()
        trim.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW to false, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN to true), seen)
        assertEquals(3, trim.trims)
    }
}
