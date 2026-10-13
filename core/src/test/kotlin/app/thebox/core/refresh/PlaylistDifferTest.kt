package app.thebox.core.refresh

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.ImportGate
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.identity.ContentHasher
import app.thebox.core.identity.GroupKeys
import app.thebox.core.identity.StableKeyDeriverImpl
import app.thebox.core.model.StableKey
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaylistDifferTest {
    private val day = 24L * 3600 * 1000
    private val now = 100 * day

    // ---- helpers -----------------------------------------------------------------------------------

    private fun ch(i: Int, kind: EntryKind = EntryKind.LIVE, name: String = "Chan $i", url: String = "http://h/hls/c$i/index.m3u8", tvg: String? = "c$i.uk", logo: String? = null) =
        ParsedChannel(kind = kind, name = name, url = url, position = i, tvgId = tvg, logoUrl = logo, groupTitle = "G")

    private fun list(vararg c: ParsedChannel) = c.mapIndexed { i, x -> x.copy(position = i) }

    /** An existing row exactly as the differ would have stored [c] last time. */
    private fun existingOf(id: Long, c: ParsedChannel, key: String = StableKeyDeriverImpl().derive(c, KeyContext(PlaylistType.M3U_URL, false)).key.value, missingSince: Long? = null, missingCount: Int = 0) =
        ExistingChannel(id, key, ContentHasher.contentHash(c, GroupKeys.m3u(c.groupTitle)), Hash64.of(c.url), Hash64.of(c.name), c.position, missingSince, missingCount)

    private class Recorder : MergeSink {
        var begun = false
        var groups: List<GroupRow> = emptyList()
        val batches = ArrayList<MergeBatch>()
        var finish: MergeFinish? = null
        var aborted: Throwable? = null
        var failOnBatch = -1
        override fun begin(groups: List<GroupRow>, detected: DetectedCredentials?) { begun = true; this.groups = groups }
        override fun apply(batch: MergeBatch) {
            if (batches.size == failOnBatch) throw IllegalStateException("boom")
            batches += batch
        }
        override fun finish(finish: MergeFinish): FinishStats { this.finish = finish; return FinishStats(finish.purgeIds.size, 0, 0, emptyList()) }
        override fun abort(error: Throwable) { aborted = error }
        val inserts get() = batches.flatMap { it.inserts }
        val updates get() = batches.flatMap { it.updates }
        val touches get() = batches.flatMap { it.touches }
    }

    private fun ctx(prevLive: Int = 0, force: Boolean = false, batch: Int = 2_000, type: PlaylistType = PlaylistType.M3U_URL) =
        MergeContext(playlistId = 1, playlistType = type, refreshSeq = 2, nowMs = now, previousLiveCount = prevLive, forceAcceptShrink = force, batchSize = batch)

    private fun run(
        entries: List<ParsedChannel>, existing: List<ExistingChannel> = emptyList(), sink: Recorder = Recorder(), ctx: MergeContext = ctx(),
        differ: PlaylistDiffer = PlaylistDifferImpl(StableKeyDeriverImpl(), clock = { now + 1 }), cancel: CancelCheck = CancelCheck.Never,
    ): Pair<RefreshReport, Recorder> {
        val r = differ.merge(FakeLibrary.listProducer(entries), { existing.forEach(it) }, sink, ctx, cancel)
        return r to sink
    }

    // ---- §3.3.3 table ------------------------------------------------------------------------------

    @Test fun `no slot - insert`() {
        val (r, s) = run(list(ch(0), ch(1)))
        assertEquals(2, r.added)
        assertEquals(listOf("tvg:c0.uk|chan 0", "tvg:c1.uk|chan 1"), s.inserts.map { it.stableKey.value })
        assertEquals(listOf("Chan 0", "Chan 1"), r.addedSample)
        assertEquals(listOf(0, 1), s.inserts.map { it.sortIndex })
        assertEquals(mapOf("TVG" to 2), r.keyRules)
        assertEquals(RefreshOutcome.SUCCESS, r.outcome)
        assertEquals(now, r.startedAtMs)
        assertEquals(now + 1, r.finishedAtMs)
    }

    @Test fun `found, content differs - update with urlChanged, renamed and restored`() {
        val old = list(ch(0), ch(1), ch(2))
        val existing = listOf(existingOf(10, old[0]), existingOf(11, old[1]), existingOf(12, old[2], missingSince = now - day, missingCount = 1))
        val new = list(ch(0, url = "http://h/hls/c0/index.m3u8?token=new"), ch(1, name = "Chan 1 HD"), ch(2, logo = "http://l/2.png"))
        val (r, s) = run(new, existing)
        assertEquals(3, r.updated)
        assertEquals(1, r.urlChanged)
        assertEquals(1, r.renamed, "HD suffix keeps the key but renames the row")
        assertEquals(1, r.restored)
        assertEquals(listOf(false, false, true), s.updates.map { it.restored })
        assertEquals(listOf(10L, 11L, 12L), s.updates.map { it.id })
        assertEquals(0, r.added)
    }

    @Test fun `found, same content - touch when moved or missing, nothing when unchanged`() {
        val old = list(ch(0), ch(1), ch(2), ch(3))
        val existing = listOf(existingOf(10, old[0]), existingOf(11, old[1]), existingOf(12, old[2]), existingOf(13, old[3], missingSince = now - day, missingCount = 1))
        val new = list(ch(1), ch(0), ch(2), ch(3)) // 0 and 1 swap; 2 unchanged; 3 comes back at the same position
        val (r, s) = run(new, existing)
        assertEquals(2, r.moved)
        assertEquals(1, r.restored)
        assertEquals(1, r.unchanged)
        assertEquals(0, r.updated)
        assertEquals(setOf(Triple(11L, 0, false), Triple(10L, 1, false), Triple(13L, 3, true)), s.touches.map { Triple(it.id, it.sortIndex, it.restored) }.toSet())
    }

    @Test fun `second identical refresh writes nothing`() {
        val l = list(ch(0), ch(1), ch(2))
        val (r, s) = run(l, l.mapIndexed { i, c -> existingOf(10L + i, c) })
        assertEquals(3, r.unchanged)
        assertTrue(s.batches.isEmpty())
        assertEquals(0, s.finish!!.newlyMissingIds.size)
    }

    // ---- finish: missing, grace, purge ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
        // missingSince age (days), missingCount, expected bucket
        "-1, 0, newly",
        "6, 1, still",
        "7, 1, purge",
        "7, 0, still",
        "30, 1, purge",
    )
    fun `grace - 7 days and 2 consecutive refreshes`(ageDays: Long, count: Int, bucket: String) {
        val gone = ch(9)
        val since = if (ageDays < 0) null else now - ageDays * day
        val (_, s) = run(list(ch(0)), listOf(existingOf(10, ch(0)), existingOf(99, gone, missingSince = since, missingCount = count)))
        val f = s.finish!!
        val actual = when {
            99L in f.newlyMissingIds -> "newly"
            99L in f.stillMissingIds -> "still"
            99L in f.purgeIds -> "purge"
            else -> "none"
        }
        assertEquals(bucket, actual)
        if (bucket == "purge") assertEquals(listOf("tvg:c9.uk|chan 9"), f.purgedKeys)
    }

    @Test fun `grace boundary is exact to the millisecond`() {
        val justUnder = now - 7 * day + 1
        val (_, s) = run(list(ch(0)), listOf(existingOf(10, ch(0)), existingOf(99, ch(9), missingSince = justUnder, missingCount = 1)))
        assertTrue(99L in s.finish!!.stillMissingIds)
    }

    @Test fun `groups seen are reported to finish`() {
        val (r, s) = run(list(ch(0), ch(1).copy(groupTitle = "Other"), ch(2, kind = EntryKind.MOVIE)))
        assertEquals(setOf("g:g", "g:other"), s.finish!!.groupKeysSeen)
        assertEquals(3, r.groupsTotal, "LIVE g:g, LIVE g:other, MOVIE g:g")
        assertEquals(listOf(EntryKind.LIVE, EntryKind.LIVE, EntryKind.MOVIE), s.groups.map { it.kind })
        assertEquals(listOf(0, 1, 2), s.groups.map { it.sortIndex })
    }

    @Test fun `adult groups by name rule or adult majority, radio uses the live group space`() {
        val entries = list(
            ch(0).copy(groupTitle = "XXX Movies"), ch(1).copy(groupTitle = "Late", adult = true), ch(2).copy(groupTitle = "Late", adult = true),
            ch(3).copy(groupTitle = "Late"), ch(4).copy(groupTitle = "Mixed", adult = true), ch(5).copy(groupTitle = "Mixed"),
            ch(6, kind = EntryKind.RADIO).copy(groupTitle = "Late"), ch(7).copy(groupTitle = "18+ Only"), ch(8).copy(groupTitle = "Adultswim"),
        )
        val (_, s) = run(entries)
        assertEquals(
            mapOf("g:xxx movies" to true, "g:late" to false, "g:mixed" to false, "g:18+ only" to true, "g:adultswim" to false),
            s.groups.associate { it.groupKey to it.adult },
        )
        assertEquals(5, s.groups.size)
    }

    // ---- pass-1 guards -------------------------------------------------------------------------------

    @Test fun `empty playlist fails before touching the sink`() {
        val sink = Recorder()
        val e = assertFailsWith<SourceException> { run(emptyList(), sink = sink) }
        assertEquals(SourceError.NotASource(NotASourceKind.EMPTY), e.error)
        assertFalse(sink.begun)
    }

    @ParameterizedTest
    @CsvSource(
        // previous live, live now, shrink?
        "50, 9, true",
        "50, 10, false",
        "49, 1, false",
        "100, 19, true",
        "100, 20, false",
    )
    fun `shrink guard`(previous: Int, liveNow: Int, shrinks: Boolean) {
        val entries = (0 until liveNow).map { ch(it) } + (0 until 500).map { ch(1000 + it, kind = EntryKind.MOVIE, url = "http://h/m$it.mp4") }
        val sink = Recorder()
        if (shrinks) {
            val e = assertFailsWith<SourceException> { run(list(*entries.toTypedArray()), sink = sink, ctx = ctx(prevLive = previous)) }
            assertEquals(SourceError.SuspiciousShrink(previous, liveNow), e.error)
            assertEquals("shrink", e.error.code)
            assertFalse(sink.begun, "nothing is written before the guard")
            // Accept anyway.
            val (r, _) = run(list(*entries.toTypedArray()), ctx = ctx(prevLive = previous, force = true))
            assertEquals(liveNow, r.live)
        } else {
            run(list(*entries.toTypedArray()), sink = sink, ctx = ctx(prevLive = previous))
            assertTrue(sink.begun)
        }
    }

    @Test fun `radio counts as live for the shrink guard`() {
        val entries = (0 until 10).map { ch(it, kind = EntryKind.RADIO) }
        run(list(*entries.toTypedArray()), ctx = ctx(prevLive = 50))
    }

    // ---- batches ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource("1999, 1999", "2000, 2000", "2001, 2000;1")
    fun `batch boundaries`(n: Int, sizes: String) {
        val entries = (0 until n).map { ch(it) }
        val (r, s) = run(entries)
        assertEquals(sizes.split(';').map { it.toInt() }, s.batches.map { it.inserts.size + it.updates.size + it.touches.size })
        assertEquals(n, r.added)
    }

    @Test fun `gate is awaited and cancellation checked between batches`() {
        var gates = 0
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl(), gate = ImportGate { gates++ }, clock = { now })
        run((0 until 4500).map { ch(it) }, differ = differ)
        assertEquals(3, gates)
    }

    @Test fun `exception mid pass 2 aborts the sink and rethrows, nothing marked missing`() {
        val sink = Recorder().apply { failOnBatch = 1 }
        val e = assertFailsWith<IllegalStateException> { run((0 until 4500).map { ch(it) }, sink = sink) }
        assertSame(e, sink.aborted)
        assertNull(sink.finish)
        assertEquals(1, sink.batches.size, "the first batch stays applied")
    }

    @Test fun `cancellation mid pass 2 aborts and propagates`() {
        var polls = 0
        val sink = Recorder()
        assertFailsWith<CancellationException> {
            run((0 until 5000).map { ch(it) }, sink = sink, cancel = CancelCheck { ++polls > 7 })
        }
        assertIs<CancellationException>(sink.aborted)
        assertNull(sink.finish)
    }

    @Test fun `a producer that yields a different count in pass 2 fails the merge`() {
        var calls = 0
        val producer = EntryProducer { consumer -> calls++; (0 until if (calls == 1) 10 else 9).forEach { consumer(ch(it)) } }
        val sink = Recorder()
        assertFailsWith<SourceException> { PlaylistDifferImpl(StableKeyDeriverImpl()).merge(producer, { }, sink, ctx()) }
        assertTrue(sink.aborted != null)
        assertNull(sink.finish)
    }

    // ---- duplicates --------------------------------------------------------------------------------

    @Test fun `duplicate keys get occurrence suffixes in playlist order`() {
        val dup = list(ch(0, tvg = "dup.uk", name = "Dup"), ch(1, tvg = "dup.uk", name = "Dup HD"), ch(2, tvg = "x.uk", name = "X"), ch(3, tvg = "dup.uk", name = "Dup"))
        val (r, s) = run(dup)
        assertEquals(listOf("tvg:dup.uk|dup", "tvg:dup.uk|dup~2", "tvg:x.uk|x", "tvg:dup.uk|dup~3"), s.inserts.map { it.stableKey.value })
        assertEquals(2, r.duplicateKeys)
        assertEquals(listOf("DUPLICATE_KEY@1", "DUPLICATE_KEY@3"), r.warnings)
        // The next refresh matches every occurrence to its own row.
        val existing = s.inserts.mapIndexed { i, k -> existingOf(100L + i, dup[i], k.stableKey.value) }
        val (r2, _) = run(dup, existing)
        assertEquals(4, r2.unchanged)
    }

    @Test fun `a literal key that looks like a suffix never collides with an occurrence suffix`() {
        val keys = ArrayDeque(listOf("K~2", "K", "K", "K~3"))
        val fixed = object : StableKeyDeriver {
            override fun derive(entry: ParsedChannel, ctx: KeyContext) = DerivedKey(StableKey(keys.removeFirst()), KeyRule.URL)
        }
        val keysCopy = listOf("K~2", "K", "K", "K~3")
        val entries = (0 until 4).map { ch(it) }
        val sink = Recorder()
        val producerCalls = intArrayOf(0)
        val producer = EntryProducer { c -> producerCalls[0]++; keys.clear(); keys.addAll(keysCopy); entries.forEach(c) }
        PlaylistDifferImpl(fixed).merge(producer, { }, sink, ctx())
        val assigned = sink.inserts.map { it.stableKey.value }
        assertEquals(4, assigned.toSet().size, "all keys distinct: $assigned")
        assertEquals(listOf("K~2", "K", "K~3", "K~3~2"), assigned)
    }

    // ---- hash collisions -------------------------------------------------------------------------------

    @Test fun `colliding key hashes are resolved through the side table`() {
        val a = ch(0); val b = ch(1); val c = ch(2); val d = ch(3)
        val existing = listOf(existingOf(10, a), existingOf(11, b), existingOf(12, c))
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl(), ImportGate.Open, { now }, { 42L }) // every key collides
        val (r, s) = run(list(a, b.copy(name = "Chan 1"), d.copy(position = 2)), existing, differ = differ)
        assertEquals(2, r.unchanged, "a and b still match their own rows although every hash collides")
        assertEquals(listOf("tvg:c3.uk|chan 3"), s.inserts.map { it.stableKey.value })
        assertEquals(listOf(12L), s.finish!!.newlyMissingIds.toList())
    }

    @Test fun `collisions among new keys still give distinct keys`() {
        val differ = PlaylistDifferImpl(StableKeyDeriverImpl(), ImportGate.Open, { now }, { 7L })
        val (r, s) = run((0 until 50).map { ch(it) }, differ = differ)
        assertEquals(50, s.inserts.map { it.stableKey.value }.toSet().size)
        assertEquals(0, r.duplicateKeys)
    }

    // ---- S7 automatic key migration -------------------------------------------------------------------

    @Test fun `S7 - a tvg-id added by the provider remaps the old key 1 to 1`() {
        val before = list(ch(0, tvg = null), ch(1, tvg = null))
        val existing = before.mapIndexed { i, c -> existingOf(10L + i, c) }
        assertEquals("url:c0/index.m3u8", existing[0].stableKey)
        val after = list(ch(0, tvg = "new0.uk"), ch(1, tvg = null))
        val (r, s) = run(after, existing)
        assertEquals(listOf(KeyRemap("url:c0/index.m3u8", "tvg:new0.uk|chan 0", sameGroup = false)), s.finish!!.autoRemaps)
        assertEquals(1, r.keysMigrated)
        assertEquals(listOf(10L), s.finish!!.newlyMissingIds.toList(), "the old row is soft-deleted as usual")
    }

    @Test fun `S7 - duplicate url hash on the inserted side gives no remap`() {
        val existing = listOf(existingOf(10, ch(0, tvg = null)))
        val after = list(ch(0, tvg = "a.uk", name = "A"), ch(0, tvg = "b.uk", name = "B"))
        val (_, s) = run(after, existing)
        assertTrue(s.finish!!.autoRemaps.isEmpty())
    }

    @Test fun `S7 - two missing slots with the same url give no remap`() {
        val existing = listOf(existingOf(10, ch(0, tvg = "x.uk", name = "X")), existingOf(11, ch(0, tvg = "y.uk", name = "Y")))
        val (_, s) = run(list(ch(0, tvg = "z.uk", name = "Z")), existing)
        assertTrue(s.finish!!.autoRemaps.isEmpty())
    }

    @Test fun `S7 - tokenised URLs never match`() {
        val existing = listOf(existingOf(10, ch(0, tvg = null, url = "http://h/live.php?id=1&token=aaa")))
        val (_, s) = run(list(ch(0, tvg = "a.uk", url = "http://h/live.php?id=1&token=bbb")), existing)
        assertTrue(s.finish!!.autoRemaps.isEmpty())
    }

    @Test fun `key strings are fetched with a second scan only when needed`() {
        var scans = 0
        val l = list(ch(0), ch(1))
        val rows = l.mapIndexed { i, c -> existingOf(10L + i, c) }
        PlaylistDifferImpl(StableKeyDeriverImpl()).merge(FakeLibrary.listProducer(l), { scans++; rows.forEach(it) }, Recorder(), ctx())
        assertEquals(1, scans)
        scans = 0
        val purgeRow = existingOf(99, ch(9), missingSince = now - 8 * day, missingCount = 3)
        PlaylistDifferImpl(StableKeyDeriverImpl()).merge(FakeLibrary.listProducer(l), { scans++; (rows + purgeRow).forEach(it) }, Recorder(), ctx())
        assertEquals(2, scans)
    }

    // ---- §3.3.6 mass remap (S18) ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource("30, true", "31, false")
    fun `mass remap at 70 percent of live channels`(kept: Int, expected: Boolean) {
        val old = (0 until 100).map { ch(it) }
        val existing = old.mapIndexed { i, c -> existingOf(1000L + i, c) }
        val new = (0 until kept).map { old[it] } + (0 until 100 - kept).map { ch(500 + it) }
        val (r, _) = run(list(*new.toTypedArray()), existing, ctx = ctx(prevLive = 100))
        assertEquals(expected, r.massRemapSuggested)
    }

    @Test fun `S18 - VOD-heavy additions do not trigger mass remap`() {
        val old = (0 until 100).map { ch(it) }
        val existing = old.mapIndexed { i, c -> existingOf(1000L + i, c) }
        val new = (0 until 30).map { old[it] } + (0 until 5000).map { ch(10_000 + it, kind = EntryKind.MOVIE, url = "http://h/v/$it.mkv") }
        val (r, _) = run(list(*new.toTypedArray()), existing, ctx = ctx(prevLive = 100, force = true))
        assertEquals(70, r.newlyMissing)
        assertFalse(r.massRemapSuggested)
    }

    @Test fun `mass remap needs at least 20 channels before`() {
        val old = (0 until 19).map { ch(it) }
        val (r, _) = run((0 until 19).map { ch(100 + it) }, old.mapIndexed { i, c -> existingOf(1000L + i, c) }, ctx = ctx(prevLive = 19))
        assertFalse(r.massRemapSuggested)
    }

    // ---- Xtream API playlists ----------------------------------------------------------------------

    @Test fun `Xtream API entries key by stream id and group by category`() {
        val x = ParsedChannel(kind = EntryKind.LIVE, name = "BBC", url = "{{base}}/live/{{u}}/{{p}}/5.{{ext}}", position = 0, xtreamStreamId = 5, xtreamCategoryId = "12", groupTitle = "UK")
        val (r, s) = run(listOf(x, x.copy(position = 1, xtreamStreamId = 6, xtreamCategoryId = null, groupTitle = "Uncategorised")), ctx = ctx(type = PlaylistType.XTREAM))
        assertEquals(listOf("xc:5", "xc:6"), s.inserts.map { it.stableKey.value })
        assertEquals(listOf("xcat:12", "xcat:none"), s.inserts.map { it.groupKey })
        assertFalse(r.credentialsTemplated)
    }
}
