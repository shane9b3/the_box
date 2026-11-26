package app.thebox.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.thebox.core.playback.PlaybackEventType
import app.thebox.player.datasource.FakeOriginEdge.Companion.manifest
import app.thebox.player.datasource.FakeOriginEdge.Companion.readAll
import app.thebox.player.datasource.FakeOriginEdge.OriginMode
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * HLS playlist-URL renewal (docs/design/m6-hls-edge-handover.md §6, tests §8.1), driven with the tracker's request
 * pattern: one manifest source loads O (the multivariant loader), a second one reloads the pinned URL Media3 got from
 * `getUri()` (the media-playlist bundle), segments resolve against that URI like `HlsChunkSource` does.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsPlaylistRenewalTest {
    private lateinit var fake: FakeOriginEdge
    private val lines = CopyOnWriteArrayList<String>()
    private val debugLines = CopyOnWriteArrayList<String>()
    private val savedOut = HlsPlaylistRenewal.out
    private val savedDebug = HlsPlaylistRenewal.debug

    @Before fun setUp() {
        fake = FakeOriginEdge()
        HlsPlaylistRenewal.out = { lines += it }
        HlsPlaylistRenewal.debug = { debugLines += it }
    }

    @After fun tearDown() {
        HlsPlaylistRenewal.out = savedOut
        HlsPlaylistRenewal.debug = savedDebug
        fake.close()
    }

    private inner class Rig(
        online: OnlineCheck = OnlineCheck.ALWAYS,
        clock: app.thebox.core.playback.MonoClock = REAL_CLOCK,
        val budget: HlsRenewalBudget = HlsRenewalBudget(),
        val client: OkHttpClient = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(),
        /** O as the playlist wrote it (code review A3: maybe not OkHttp's normal form). */
        val origin: String = fake.originUrl,
        /** The online re-check waits (code review A9); none by default. */
        val pauses: MutableList<Long> = CopyOnWriteArrayList(),
        waitMs: Long = HlsPlaylistRenewal.WAIT_MS,
    ) {
        val broker = FakeBroker()
        val events = EventRecorder()
        val ctx = sessionCtx(origin, broker = broker, events = events)
        val telemetry = SessionTelemetry(REAL_CLOCK)
        val session = TransportSession(ctx, telemetry)
        val renewal = session.renewal { HlsPlaylistRenewal(ctx, budget, online, clock, telemetry, waitMs) { pauses += it } }
        val factory = RenewingHlsDataSourceFactory(ctx, client, telemetry, session, REAL_CLOCK, renewal)
        val initial: DataSource = factory.createDataSource(C.DATA_TYPE_MANIFEST)
        val bundle: DataSource = factory.createDataSource(C.DATA_TYPE_MANIFEST)
        val media: DataSource = factory.createDataSource(C.DATA_TYPE_MEDIA)
        lateinit var logical: String

        /** The tracker's first load of O; [logical] becomes what `getUri()` reported. */
        fun start(): String {
            initial.open(manifest(origin))
            logical = initial.uri.toString()
            val body = String(readAll(initial))
            initial.close()
            return body
        }

        /** One reload of the pinned URL: (body, `getUri()`, response headers). */
        fun reload(source: DataSource = bundle, url: String = logical): Triple<String, Uri?, Map<String, List<String>>> {
            source.open(manifest(url))
            try {
                val uri = source.uri
                val headers = source.responseHeaders
                return Triple(String(readAll(source)), uri, headers)
            } finally {
                source.close()
            }
        }

        /** The last segment line of [body], resolved against `getUri()` as `HlsChunkSource` does, opened and read. */
        fun lastSegment(body: String, base: String = logical): String {
            val ref = body.lines().last { it.isNotBlank() && !it.startsWith("#") }
            val url = UriUtil.resolve(base, ref)
            media.open(DataSpec.Builder().setUri(url).build())
            readAll(media)
            media.close()
            return url
        }

        fun calls(): BrokeredCallFactory = session.httpCalls(ctx, client, telemetry)

        fun fallbackEvents() = events.events.filter { it.first == PlaybackEventType.STALL && it.second.startsWith(HlsPlaylistRenewal.FALLBACK_CODE) }

        fun renewedEvents() = events.events.filter { it.first == PlaybackEventType.DECISION && it.second.startsWith(HlsPlaylistRenewal.RENEWED_CODE) }
    }

    private fun expectFailure(block: () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        fail("expected a failure")
        throw AssertionError()
    }

    /** Nothing of O, the current URL, a `Location`, a header or a body anywhere in [e]'s chain. */
    private fun assertLeaksNothing(e: Throwable, rig: Rig) {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 16) {
            val texts = mutableListOf(c.message ?: "", c.toString())
            if (c is androidx.media3.datasource.DataSourceException) Unit
            if (c is HttpDataSource.HttpDataSourceException) {
                texts += c.dataSpec.uri.toString()
                assertEquals("only Media3's own request", rig.logical, c.dataSpec.uri.toString())
                assertTrue(c.dataSpec.httpRequestHeaders.isEmpty())
            }
            if (c is HttpDataSource.InvalidResponseCodeException) {
                assertTrue(c.headerFields.isEmpty())
                assertEquals(0, c.responseBody.size)
            }
            for (t in texts) {
                for (secret in listOf("/live/u/p/7", "origin-header", "origin-error-body", fake.deadUrl, "tok2", ":${fake.origin.port}/")) {
                    assertFalse("leaked '$secret' in ${c.javaClass.simpleName}", t.contains(secret))
                }
            }
            c = c.cause
        }
    }

    // ---------------------------------------------------------------- the captured hand-over

    @Test
    fun capturedHandOverIsRenewedInPlace() {
        val rig = Rig()
        fake.renewalTd = 12 // the measured td=11 → td=12 across the renewal
        rig.start()
        assertEquals(fake.playlistUrl("tok1"), rig.logical)
        repeat(2) {
            fake.msn++
            val (body, uri, _) = rig.reload()
            assertEquals(rig.logical, uri.toString())
            assertTrue(rig.lastSegment(body).contains("/hls/tok1/"))
        }
        val liveAtOrigin = AtomicInteger(-1)
        fake.onOrigin = { liveAtOrigin.set(rig.calls().liveCalls()) }
        fake.expireAll()
        fake.msn += 3
        val edgeBefore = fake.edgeRequests.size
        val (renewed, uri, headers) = rig.reload()

        // Media3 sees the fresh body under its own URL, with no error and no headers of ours.
        assertEquals(rig.logical, uri.toString())
        assertTrue(headers.isEmpty())
        assertTrue(renewed, renewed.contains("#EXT-X-MEDIA-SEQUENCE:${fake.msn}"))
        assertTrue(renewed.contains("/hls/tok2/seg_"))
        assertEquals(2, fake.originHits.get())
        // The expired reload's 302 was not followed (no request to the dead address): the edge saw the expired reload,
        // then O's redirect to the new URL.
        assertEquals(listOf("/hls/tok1/index.m3u8", "/hls/tok2/index.m3u8"), fake.edgePaths().drop(edgeBefore))
        // The pinned call was closed before O opened: O's call was the lease's only live call.
        assertEquals(1, liveAtOrigin.get())
        // Same lease, never a second one; the renewal used the session's headers and no Range.
        assertEquals(1, rig.broker.leases.size)
        assertEquals(0, rig.broker.lease.releaseCount.get())
        val o = fake.originRequests.last()
        assertEquals(listOf("TestUA/1.0"), o.headers.values("User-Agent"))
        assertNull(o.headers["Range"])
        assertTrue(rig.fallbackEvents().isEmpty())
        assertEquals(1, rig.telemetry.playlistRenewals)
        assertEquals("the controller counts it (code review A8)", 1, rig.renewedEvents().size)
        val line = lines.single { it.startsWith("renewed") }
        assertTrue(line, line.contains("trigger=redirect:302") && line.contains(":${fake.edge.port} ") && line.contains("td=11>12"))
        assertFalse(line.contains("tok") || line.contains("/live/"))

        // Later reloads go to the new URL; segments come from the new references on the unchanged base.
        fake.msn++
        val edgeMark = fake.edgeRequests.size
        val (next, uri2, _) = rig.reload()
        assertEquals(rig.logical, uri2.toString())
        assertEquals("/hls/tok2/index.m3u8", fake.edgeRequests[edgeMark].url.encodedPath)
        assertNull(fake.edgeRequests[edgeMark].headers["Range"])
        val seg = rig.lastSegment(next)
        assertTrue(seg, seg.startsWith(fake.edge.url("/hls/tok2/seg_").toString()))
        assertEquals("/hls/tok2/seg_${fake.msn + 5}.ts", fake.edgeRequests.last().url.encodedPath)
        assertEquals(2, fake.originHits.get())
        rig.session.end()
        assertEquals(1, rig.broker.lease.releaseCount.get())
    }

    @Test
    fun aDirectoryChangeOnTheSameHostRebasesRelativeReferences() {
        // A body served from the new URL with relative references: Media3 resolves against the logical URL, so they are
        // rewritten to what they mean on the new URL.
        val body = "#EXTM3U\n#EXT-X-TARGETDURATION:2\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,été\nseg_1.ts\n/abs/seg_2.ts\nhttp://x/y.ts\n"
            .toByteArray(Charsets.ISO_8859_1)
        val out = PlaylistRebase.rebase(body, "http://e:1/hls/A/index.m3u8", "http://e:1/hls/B/index.m3u8")!!
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("URI=\"http://e:1/hls/B/key.bin\""))
        assertTrue(text.contains("\nhttp://e:1/hls/B/seg_1.ts\n"))
        assertTrue(text.contains("\n/abs/seg_2.ts\n")) // same on both bases: untouched
        assertTrue(text.contains("\nhttp://x/y.ts\n"))
        assertTrue(text.contains("#EXT-X-TARGETDURATION:2\r\n"))
        assertTrue(text.contains("été")) // a non-ASCII title is kept byte for byte
        // The measured case (absolute paths, same host): the very same array.
        val abs = "#EXTM3U\n#EXTINF:11,\n/hls/B/seg_1.ts\n".toByteArray()
        assertSame(abs, PlaylistRebase.rebase(abs, "http://e/hls/A/index.m3u8?t=1", "http://e/hls/B/index.m3u8?t=2"))
        // A reference that is not plain ASCII cannot be rewritten safely.
        assertNull(PlaylistRebase.rebase("#EXTM3U\nsé.ts\n".toByteArray(Charsets.ISO_8859_1), "http://e/a/i.m3u8", "http://e/b/i.m3u8"))
    }

    @Test
    fun continuityRuleOnTheMeasuredNumbers() {
        fun pl(
            msn: Long, td: Long = 11, n: Int = 6, prefix: String = "seg_", end: Boolean = false, dseq: Long? = null,
            pdt0: Long? = null, dur: (Long) -> String = { "11" },
        ): HlsPlaylistScanner.Summary {
            val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:$td\n#EXT-X-MEDIA-SEQUENCE:$msn\n")
            dseq?.let { sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:$it\n") }
            for (i in 0 until n) {
                // A PDT on every segment, 11 s apart from [pdt0] at MSN 0 (so the same MSN has the same PDT on both sides).
                pdt0?.let { sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(java.time.Instant.ofEpochMilli(it + (msn + i) * 11_000)).append("\n") }
                sb.append("#EXTINF:${dur(msn + i)},\n/x/$prefix${msn + i}.ts\n")
            }
            if (end) sb.append("#EXT-X-ENDLIST\n")
            return HlsPlaylistRenewal.mediaSummary(sb.toString().toByteArray())!!
        }
        val last = pl(3277)
        // Measured: msn 3277 → 3280, 16 s later, td 11 → 12.
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, td = 12), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3277), 0))
        // (Code review A6) The target duration may move by max(2 s, 25 %): 11 → 13 is the same stream, 11 → 14 is not;
        // a 2 s target may become 4 s but not 5 s.
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, td = 13), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, td = 9), 16_000))
        assertEquals("td", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, td = 14), 16_000))
        assertEquals("td", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, td = 8), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(pl(100, td = 2), 0, pl(101, td = 4), 2_000))
        assertEquals("td", HlsPlaylistRenewal.continuityViolation(pl(100, td = 2), 0, pl(101, td = 5), 2_000))
        assertTrue(HlsPlaylistRenewal.targetDurationAgrees(40, 50) && !HlsPlaylistRenewal.targetDurationAgrees(40, 51))
        assertEquals("msn_back", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3276), 16_000))
        // Bound: last + window (6) + ceil(16 s / 11 s) (2) + 2 = 3287.
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3287), 16_000))
        assertEquals("msn_ahead", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3288), 16_000))
        assertEquals("endlist", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, end = true), 16_000))
        assertEquals("dseq", HlsPlaylistRenewal.continuityViolation(pl(3277, dseq = 4), 0, pl(3280, dseq = 3), 16_000))
        assertEquals("names", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, prefix = "other_"), 16_000))
        // No overlap: names cannot be compared, the sequence bound decides.
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3284, prefix = "other_"), 16_000))
        // (Code review A7) The same MSN and name but another length is another stream; so is another PDT, when both
        // carry one (a PDT on one side only cannot be compared).
        assertEquals("dur", HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, dur = { if (it == 3281L) "6.000" else "11" }), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(last, 0, pl(3280, dur = { "11.400" }), 16_000))
        val t0 = 1_790_000_000_000L
        val withPdt = pl(3277, pdt0 = t0)
        assertNull(HlsPlaylistRenewal.continuityViolation(withPdt, 0, pl(3280, pdt0 = t0), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(withPdt, 0, pl(3280, pdt0 = t0 + 900), 16_000))
        assertEquals("pdt", HlsPlaylistRenewal.continuityViolation(withPdt, 0, pl(3280, pdt0 = t0 + 3_600_000), 16_000))
        assertNull(HlsPlaylistRenewal.continuityViolation(withPdt, 0, pl(3280), 16_000))
    }

    // ---------------------------------------------------------------- fallbacks: detected, typed, immediate

    private fun renewWith(rig: Rig, prepare: () -> Unit): IOException {
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        prepare()
        return expectFailure { rig.reload() }
    }

    private fun assertFallback(rig: Rig, e: IOException, label: String, repeatable: Boolean = true) {
        assertTrue("$e", e is HlsRenewalException)
        assertEquals("hls renewal $label", e.message)
        assertNull(e.cause)
        assertLeaksNothing(e, rig)
        assertEquals(listOf("${HlsPlaylistRenewal.FALLBACK_CODE} $label"), rig.fallbackEvents().map { it.second })
        assertTrue(lines.any { it.startsWith("$label ") })
        assertEquals(C.TIME_UNSET, policyDelay(e))
        if (!repeatable) return
        // A second reload fails the same way without network or a second event.
        val hits = fake.originHits.get()
        val again = expectFailure { rig.reload() }
        assertEquals(e.message, again.message)
        assertEquals(hits, fake.originHits.get())
        assertEquals(1, rig.fallbackEvents().size)
        // Fatal for Media3 (no retries: the ladder already has the signal).
        assertEquals(C.TIME_UNSET, policyDelay(again))
        assertEquals(0, rig.telemetry.playlistRenewals)
    }

    private fun policyDelay(e: IOException): Long {
        val policy = BoxLoadErrorHandlingPolicy(app.thebox.core.playback.LoadOrigin.HLS_MANIFEST, app.thebox.core.playback.errors.LoadRetryRulesImpl())
        val info = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            androidx.media3.exoplayer.source.LoadEventInfo(0, DataSpec(Uri.EMPTY), 0),
            androidx.media3.exoplayer.source.MediaLoadData(C.DATA_TYPE_MANIFEST), e, 1,
        )
        return policy.getRetryDelayMsFor(info)
    }

    @Test
    fun theEdgeReallyChangedIsDeclined() {
        val rig = Rig()
        val e = renewWith(rig) { fake.originMode = OriginMode.REDIRECT_EDGE2 }
        assertFallback(rig, e, "declined:host")
        assertEquals(1, fake.edge2.requestCount) // O's redirect, followed once by the renewal; never used after
    }

    @Test
    fun theMediaSequenceJumpingBackIsMisaligned() {
        val rig = Rig()
        val e = renewWith(rig) { fake.renewalMsn = { 0L } }
        assertTrue(e is HlsRenewalMisaligned)
        assertFallback(rig, e, "misaligned:msn_back")
    }

    @Test
    fun theMediaSequenceTooFarAheadIsMisaligned() {
        val rig = Rig(clock = FakeClock())
        val e = renewWith(rig) { fake.renewalMsn = { it + 40 } }
        assertFallback(rig, e, "misaligned:msn_ahead")
    }

    @Test
    fun aTargetDurationChangeIsMisaligned() {
        val rig = Rig()
        val e = renewWith(rig) { fake.renewalTd = 6 }
        assertFallback(rig, e, "misaligned:td")
    }

    @Test
    fun anEndedPlaylistIsMisaligned() {
        val rig = Rig()
        val e = renewWith(rig) { fake.renewalEndList = true }
        assertFallback(rig, e, "misaligned:endlist")
    }

    @Test
    fun aDiscontinuitySequenceGoingBackIsMisaligned() {
        fake.dseq = 5
        val rig = Rig()
        val e = renewWith(rig) { fake.renewalDseq = 4 }
        assertFallback(rig, e, "misaligned:dseq")
    }

    @Test
    fun otherSegmentNamesAtTheSameSequenceAreMisaligned() {
        val rig = Rig()
        val e = renewWith(rig) { fake.renewalNamePrefix = "other_" }
        assertFallback(rig, e, "misaligned:names")
    }

    @Test
    fun theProviderRefusingIsAFailureWithoutItsResponse() {
        val rig = Rig()
        val e = renewWith(rig) {
            fake.originMode = OriginMode.STATUS
            fake.originStatus = 403
        }
        assertFallback(rig, e, "failed:status:403")
    }

    @Test
    fun aProviderServerErrorIsAFailure() {
        val rig = Rig()
        val e = renewWith(rig) {
            fake.originMode = OriginMode.STATUS
            fake.originStatus = 503
        }
        assertFallback(rig, e, "failed:status:503")
    }

    @Test
    fun aProviderTimeoutIsAFailure() {
        val rig = Rig()
        val e = renewWith(rig) { fake.originMode = OriginMode.STALL }
        assertFallback(rig, e, "failed:timeout")
    }

    @Test
    fun theBudgetAllowsThreeRenewalsThenDisablesForATime() {
        val clock = FakeClock()
        val budget = HlsRenewalBudget()
        val rig = Rig(clock = clock, budget = budget)
        rig.start()
        fake.msn++
        rig.reload()
        repeat(3) {
            fake.expireAll()
            fake.msn++
            rig.reload()
            fake.msn++
            rig.reload() // the first reload of the adopted URL succeeds (not a redirect loop)
            clock.advance(10_000)
        }
        assertEquals(3, rig.telemetry.playlistRenewals)
        fake.expireAll()
        val e = expectFailure { rig.reload() }
        assertFallback(rig, e, "declined:budget", repeatable = false)
        // The next session of the same playlist does not renew while disabled: its reloads are today's (followed).
        val next = Rig(clock = clock, budget = budget)
        next.start()
        assertEquals(HlsPlaylistRenewal.Plan.None, next.renewal.plan(Uri.parse(next.logical)))
        clock.advance(HlsPlaylistRenewal.BUDGET_DISABLE_MS)
        assertTrue(next.renewal.plan(Uri.parse(next.logical)) is HlsPlaylistRenewal.Plan.Pinned)
    }

    @Test
    fun offlineNeverRenewsAndKeepsTodaysError() {
        val rig = Rig(online = { false })
        val e = renewWith(rig) {}
        // (Code review A9) Today's request: the redirect followed, its connect failure a transient error Media3 retries
        // (not the unfollowed 302, which the policy would treat as fatal and leave to a drained buffer).
        assertTrue("$e", e is HttpDataSource.HttpDataSourceException && e !is HlsRenewalException && e !is HttpDataSource.InvalidResponseCodeException)
        assertTrue("retried by Media3", policyDelay(e) != C.TIME_UNSET)
        assertLeaksNothing(e, rig) // no Location header, no body
        assertEquals(1, fake.originHits.get())
        assertTrue(rig.fallbackEvents().isEmpty())
        assertTrue(lines.any { it.startsWith("declined:offline") })
        assertEquals("re-checked 3 × 500 ms before declining", listOf(500L, 500L, 500L), rig.pauses.toList())
    }

    /** (Code review A9) A transient "no network" right at the expiry: the re-check sees it back and renews. */
    @Test
    fun aMomentaryOfflineReadingAtTheExpiryStillRenews() {
        val readings = java.util.concurrent.atomic.AtomicInteger()
        val rig = Rig(online = { readings.incrementAndGet() > 1 })
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.msn++
        val (body, uri, _) = rig.reload()
        assertEquals(rig.logical, uri.toString())
        assertTrue(body.contains("/hls/tok2/"))
        assertEquals(listOf(HlsPlaylistRenewal.OFFLINE_RECHECK_MS), rig.pauses.toList())
        assertTrue(rig.fallbackEvents().isEmpty())
    }

    // ---------------------------------------------------------------- code review 2026-11-26

    /**
     * (A1) A pinned reload that fails before the response headers (a blip) tries one renewal, adopts nothing that did
     * not succeed, and rethrows the original error: Media3 retries it as before, no fallback is stored, the ladder hears
     * nothing; the next blipped reload inside the cool-down does not ask O again; after the blip the route plays on.
     */
    @Test
    fun aBlipBeforeTheHeadersKeepsMedia3sRetriesAndNoFallback() {
        val rig = Rig()
        rig.start()
        fake.msn++
        rig.reload()
        fake.blipAll = true
        val e = expectFailure { rig.reload() }
        assertTrue("$e", e is HttpDataSource.HttpDataSourceException && e !is HlsRenewalException)
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_OPEN, (e as HttpDataSource.HttpDataSourceException).type)
        assertTrue("retried by Media3 as on main", policyDelay(e) != C.TIME_UNSET)
        assertLeaksNothing(e, rig)
        assertEquals("one renewal attempt", 2, fake.originHits.get())
        assertTrue(lines.any { it.startsWith("failed:") && it.contains("trigger=io:") })
        expectFailure { rig.reload() }
        assertEquals("cool-down: no second request to O", 2, fake.originHits.get())
        assertTrue(rig.fallbackEvents().isEmpty())
        assertNull(rig.renewal.fallbackOf((rig.renewal.plan(Uri.parse(rig.logical)) as HlsPlaylistRenewal.Plan.Pinned).route))
        fake.blipAll = false
        fake.msn++
        val (body, uri, _) = rig.reload()
        assertEquals(rig.logical, uri.toString())
        assertTrue(body.contains("#EXT-X-MEDIA-SEQUENCE:${fake.msn}"))
        assertEquals(0, rig.telemetry.playlistRenewals)
        // The budget was not disabled by the blip: a real expiry later still renews.
        fake.expireAll()
        fake.msn++
        assertTrue(rig.reload().first.contains("/hls/tok3/"))
        assertEquals(1, rig.telemetry.playlistRenewals)
    }

    /** (A1) A failure before the headers that a renewal can route around is adopted (seamless, like an expiry). */
    @Test
    fun aBlipThatARenewalRoutesAroundIsAdopted() {
        val rig = Rig()
        rig.start()
        fake.msn++
        rig.reload()
        fake.blipTokens += "tok1"
        fake.msn++
        val (body, uri, _) = rig.reload()
        assertEquals(rig.logical, uri.toString())
        assertTrue(body.contains("/hls/tok2/"))
        assertTrue(lines.single { it.startsWith("renewed") }.contains("trigger=io:"))
        assertTrue(rig.fallbackEvents().isEmpty())
    }

    /**
     * (A2) A redirect that today's code follows to a working address still plays: the renewal is declined (O now hands
     * out another host), the reload is re-sent with redirects followed, the continuation is served under Media3's URL
     * with its references rebased onto the other host, and the route follows redirects from now on. No fallback.
     */
    @Test
    fun aRedirectToAWorkingPlaylistOnAnotherHostIsStillFollowed() {
        val rig = Rig()
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.originMode = OriginMode.REDIRECT_EDGE2
        fake.expiredLocation = { fake.issue(fake.edge2) }
        fake.msn++
        val (body, uri, headers) = rig.reload()
        assertEquals(rig.logical, uri.toString())
        assertTrue(headers.isEmpty())
        assertTrue(body.contains("#EXT-X-MEDIA-SEQUENCE:${fake.msn}"))
        val seg = rig.lastSegment(body)
        assertTrue(seg, seg.startsWith(fake.edge2.url("/hls/").toString()))
        assertTrue(rig.fallbackEvents().isEmpty())
        assertTrue(lines.any { it.startsWith("declined:host") })
        assertTrue(lines.single { it.startsWith("followed") }.contains("other_host"))
        assertFalse(lines.any { it.contains("tok") || it.contains("/live/") })
        // From now on: today's request for this route (followed).
        assertEquals(HlsPlaylistRenewal.Plan.None, rig.renewal.plan(Uri.parse(rig.logical)))
        fake.msn++
        assertTrue(rig.reload().first.contains("#EXT-X-MEDIA-SEQUENCE:${fake.msn}"))
    }

    /** (A2) The same on the same host (O briefly refusing): the followed address becomes the current URL. */
    @Test
    fun aRedirectToAWorkingPlaylistOnTheSameHostIsAdopted() {
        val rig = Rig()
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.originMode = OriginMode.STATUS
        fake.originStatus = 503
        var target = ""
        fake.expiredLocation = { fake.issue().also { target = it } }
        fake.msn++
        val (body, uri, _) = rig.reload()
        assertEquals(rig.logical, uri.toString())
        // (The unfollowed 302 minted one token too; the followed one is the last.)
        assertTrue(body, body.contains(java.net.URI(target).path.substringBeforeLast('/') + "/seg_"))
        assertTrue(lines.any { it.startsWith("failed:status:503") })
        assertTrue(lines.single { it.startsWith("followed") }.contains("adopted"))
        assertTrue(rig.fallbackEvents().isEmpty())
        fake.expiredLocation = null
        val mark = fake.edgeRequests.size
        fake.msn++
        rig.reload()
        assertEquals("the adopted URL, no redirect", listOf(java.net.URI(target).path), fake.edgePaths().drop(mark))
    }

    /** (A3) O written in another form (upper-case scheme) that OkHttp normalises is not a redirect: strict no-op. */
    @Test
    fun anOriginWrittenInAnotherFormIsNotARedirect() {
        fake.originMode = OriginMode.DIRECT
        val rig = Rig(origin = fake.originUrl.replaceFirst("http://", "HTTP://"))
        rig.start()
        assertEquals(HlsPlaylistRenewal.State.OFF, rig.renewal.currentState)
        assertTrue(debugLines.single(), debugLines.single().startsWith("off:not_redirected"))
        assertTrue(HlsPlaylistRenewal.sameUrl("HTTP://Example.COM:80/a%7e/b", "http://example.com/a%7e/b"))
        assertFalse(HlsPlaylistRenewal.sameUrl("http://example.com/a", "http://example.com/b"))
    }

    /** (A4) Renewal disabled after a renewal (the budget's time box): reloads keep going to the current URL. */
    @Test
    fun aDisabledBudgetAfterARenewalKeepsTheCurrentUrl() {
        val clock = FakeClock()
        val budget = HlsRenewalBudget()
        val rig = Rig(clock = clock, budget = budget)
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.msn++
        assertTrue(rig.reload().first.contains("/hls/tok2/"))
        repeat(HlsPlaylistRenewal.BUDGET_MAX + 1) { budget.tryTake(clock.nowMs()) }
        assertTrue(budget.disabled(clock.nowMs()))
        val plan = rig.renewal.plan(Uri.parse(rig.logical))
        assertTrue(plan is HlsPlaylistRenewal.Plan.Pinned && plan.follow)
        val mark = fake.edgeRequests.size
        fake.msn++
        val (body, _, _) = rig.reload()
        assertTrue(body.contains("#EXT-X-MEDIA-SEQUENCE:${fake.msn}"))
        assertEquals("never the expired logical URL", listOf("/hls/tok2/index.m3u8"), fake.edgePaths().drop(mark))
    }

    /** (A5) A loader that gave up waiting does not mark the route failed; the renewal it waited on still succeeds. */
    @Test
    fun aWaiterThatTimesOutDoesNotFailTheRoute() {
        val rig = Rig(waitMs = 200, client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.originDelayMs = 1_500
        val atOrigin = CountDownLatch(1)
        fake.onOrigin = { atOrigin.countDown() }
        val first = AtomicReference<Result<Triple<String, Uri?, Map<String, List<String>>>>>()
        val t1 = Thread { first.set(runCatching { rig.reload() }) }
        t1.start()
        assertTrue(atOrigin.await(5, TimeUnit.SECONDS))
        val second = rig.factory.createDataSource(C.DATA_TYPE_MANIFEST)
        val waited = runCatching { rig.reload(source = second) }.exceptionOrNull()
        t1.join(5_000)
        assertTrue("busy is today's error, not the typed fallback: $waited", waited != null && waited !is HlsRenewalException)
        assertTrue(lines.any { it.startsWith("declined:busy") })
        assertTrue("${first.get()}", first.get().getOrThrow().first.contains("/hls/tok2/"))
        val route = (rig.renewal.plan(Uri.parse(rig.logical)) as HlsPlaylistRenewal.Plan.Pinned).route
        assertNull(rig.renewal.fallbackOf(route))
        assertTrue(rig.fallbackEvents().isEmpty())
        fake.msn++
        assertTrue(rig.reload().first.contains("/hls/tok2/"))
    }

    @Test
    fun aRedirectOnTheFirstReloadMeansThisProviderRedirectsEveryReload() {
        val rig = Rig()
        rig.start()
        fake.expireAll() // the very first reload is redirected: not an expiry
        val e = expectFailure { rig.reload() }
        // Today's behaviour: the redirect was followed (to the dead address here), the error is Media3's usual one.
        assertFalse(e is HlsRenewalException)
        assertEquals(1, fake.originHits.get())
        assertTrue(lines.any { it.startsWith("declined:loop") })
        assertTrue(rig.renewal.renewalsOff)
        // (Code review A4) The never-renewed route is today's request from now on: Media3's own URL, followed.
        assertEquals(HlsPlaylistRenewal.Plan.None, rig.renewal.plan(Uri.parse(rig.logical)))
        assertTrue(rig.fallbackEvents().isEmpty())
    }

    @Test
    fun aMultivariantStartStaysOutEntirely() {
        fake.originMode = OriginMode.MULTIVARIANT
        val rig = Rig()
        val body = rig.start()
        assertTrue(body.contains("#EXT-X-STREAM-INF"))
        assertEquals(HlsPlaylistRenewal.State.OFF, rig.renewal.currentState)
        assertTrue(debugLines.single(), debugLines.single().startsWith("off:multivariant"))
        val variant = UriUtil.resolve(rig.logical, "index.m3u8")
        val (_, uri, _) = rig.reload(url = variant)
        assertEquals(variant, uri.toString())
        fake.expireAll()
        val e = expectFailure { rig.reload(url = variant) }
        assertFalse(e is HlsRenewalException)
        assertEquals(1, fake.originHits.get())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aProviderThatDoesNotRedirectIsANoOp() {
        fake.originMode = OriginMode.DIRECT
        val rig = Rig()
        rig.start()
        assertEquals(fake.originUrl, rig.logical)
        assertEquals(HlsPlaylistRenewal.State.OFF, rig.renewal.currentState)
        val (body, uri, headers) = rig.reload()
        assertEquals(fake.originUrl, uri.toString())
        assertTrue(headers.isNotEmpty()) // pass-through: Media3 gets the real response headers, as today
        assertTrue(body.contains("#EXT-X-MEDIA-SEQUENCE"))
        assertTrue(lines.isEmpty())
        assertEquals(2, fake.originHits.get())
    }

    @Test
    fun aSegmentRefusedSendsTheFastSignalOnceAndNoRenewal() {
        val rig = Rig()
        rig.start()
        fake.msn++
        val (body, _, _) = rig.reload()
        fake.segmentStatus = 403
        repeat(2) {
            val e = expectFailure { rig.lastSegment(body) }
            // The segment's own error, unchanged (Media3's URL, Media3's exception type).
            assertTrue(e is HttpDataSource.InvalidResponseCodeException)
            assertEquals(403, (e as HttpDataSource.InvalidResponseCodeException).responseCode)
        }
        val seg = rig.events.events.filter { it.second.startsWith(HlsPlaylistRenewal.SEGMENT_CODE) }
        assertEquals(listOf("${HlsPlaylistRenewal.SEGMENT_CODE} status=403"), seg.map { it.second })
        assertEquals(1, fake.originHits.get())
        // A 404 (packager lag) is left to Media3's retries.
        val rig2 = Rig()
        fake.segmentStatus = 404
        rig2.start()
        val (b2, _, _) = rig2.reload()
        expectFailure { rig2.lastSegment(b2) }
        assertTrue(rig2.events.events.none { it.second.startsWith(HlsPlaylistRenewal.SEGMENT_CODE) })
    }

    @Test
    fun deliveryDirectivesAreKeptOnTheCurrentUrl() {
        val rig = Rig()
        rig.start()
        val plan = rig.renewal.plan(Uri.parse(rig.logical + "?_HLS_msn=12&_HLS_part=1"))
        assertTrue(plan is HlsPlaylistRenewal.Plan.Pinned)
        assertEquals(rig.logical + "?_HLS_msn=12&_HLS_part=1", (plan as HlsPlaylistRenewal.Plan.Pinned).requestUrl)
        assertEquals(HlsPlaylistRenewal.Plan.None, rig.renewal.plan(Uri.parse(rig.logical + "?other=1")))
        assertEquals(HlsPlaylistRenewal.Plan.Origin, rig.renewal.plan(Uri.parse(fake.originUrl)))
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun aZapDuringARenewalReleasesBothLoadersPromptly() {
        val rig = Rig(client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.originMode = OriginMode.STALL // O holds its headers for 5 s
        val atOrigin = CountDownLatch(1)
        fake.onOrigin = { atOrigin.countDown() }
        val second = rig.factory.createDataSource(C.DATA_TYPE_MANIFEST)
        val errors = arrayOfNulls<Throwable>(2)
        val t1 = Thread { errors[0] = runCatching { rig.reload() }.exceptionOrNull() }
        t1.start()
        assertTrue(atOrigin.await(5, TimeUnit.SECONDS))
        val t2 = Thread { errors[1] = runCatching { rig.reload(source = second) }.exceptionOrNull() }
        t2.start()
        Thread.sleep(200) // t2 now waits on t1's renewal (single flight)
        val endAt = System.nanoTime()
        rig.session.end()
        t1.join(3_000)
        t2.join(3_000)
        val tookMs = (System.nanoTime() - endAt) / 1_000_000
        assertFalse(t1.isAlive || t2.isAlive)
        assertTrue("took $tookMs ms", tookMs < 2_000)
        assertEquals(2, fake.originHits.get()) // the start and one renewal: single flight, the waiter never asked O
        for (e in errors) {
            assertTrue("$e", e is LiveStreamException && e.kind == LiveFailureKind.PREEMPTED || e is InterruptedIOException)
            assertFalse(e is HlsRenewalException)
        }
        assertTrue(rig.fallbackEvents().isEmpty())
        assertEquals(1, rig.broker.lease.releaseCount.get())
    }

    @Test
    fun aWaitingLoaderIsInterruptible() {
        val rig = Rig(client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build())
        rig.start()
        fake.msn++
        rig.reload()
        fake.expireAll()
        fake.originMode = OriginMode.STALL
        val atOrigin = CountDownLatch(1)
        fake.onOrigin = { atOrigin.countDown() }
        val first = Thread { runCatching { rig.reload() } }
        first.start()
        assertTrue(atOrigin.await(5, TimeUnit.SECONDS))
        val err = AtomicReference<Throwable?>()
        val second = rig.factory.createDataSource(C.DATA_TYPE_MANIFEST)
        val waiter = Thread { err.set(runCatching { rig.reload(source = second) }.exceptionOrNull()) }
        waiter.start()
        Thread.sleep(200)
        waiter.interrupt() // Loader.cancelLoading
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertTrue("${err.get()}", err.get()?.let(::isInterruption) == true)
        rig.session.end()
        first.join(3_000)
    }
}
