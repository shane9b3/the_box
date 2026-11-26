package app.thebox.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import java.util.Locale

/**
 * (HLS edge hand-over, stage 0) The live HLS source's data-source factory while [HlsEdgeProbe] is on: it asks
 * [upstream] (the transport's live HLS factory, playlist renewal inside) for the same data type and wraps the result in
 * an observe-only [HlsEdgeDataSource] that knows the type (manifest, media, key).
 */
@OptIn(UnstableApi::class)
internal class HlsEdgeDataSourceFactory(
    private val upstream: HlsDataSourceFactory,
    private val session: HlsEdgeSession,
) : HlsDataSourceFactory {
    constructor(upstream: DataSource.Factory, session: HlsEdgeSession) : this(HlsDataSourceFactory { upstream.createDataSource() }, session)

    init {
        factories.incrementAndGet()
    }

    override fun createDataSource(dataType: Int): DataSource {
        created.incrementAndGet()
        return HlsEdgeDataSource(upstream.createDataSource(dataType), dataType, session)
    }

    companion object {
        /** Wrappers built in this process (test hook: TS, archive, clip and ring paths must leave it unchanged). */
        val created = java.util.concurrent.atomic.AtomicInteger()

        /** Factories built in this process (test hook). */
        val factories = java.util.concurrent.atomic.AtomicInteger()
    }
}

/**
 * A transparent pass-through: every call goes to [upstream] unchanged, its return value and its exception (the same
 * instance) come back unchanged, and [getUri]/[getResponseHeaders] are [upstream]'s. The only extra work is bookkeeping
 * around `open`/`close` and, for playlists only, a line scan of the bytes already in Media3's buffer after `read`.
 * Every probe call is guarded: a probe bug cannot throw into Media3. One instance per loader, used by one thread at a
 * time (Media3's contract); shared state lives in [HlsEdgeSession] under its lock.
 */
@OptIn(UnstableApi::class)
internal class HlsEdgeDataSource(
    private val upstream: DataSource,
    private val dataType: Int,
    private val session: HlsEdgeSession,
) : DataSource {
    private var trace: ReqTrace? = null
    private var spec: DataSpec? = null
    private var scanner: HlsPlaylistScanner? = null
    private var bytes = 0L
    private var length = C.LENGTH_UNSET.toLong()
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val t = try { session.newTrace(dataType, dataSpec) } catch (_: Throwable) { null }
        trace = t
        spec = dataSpec
        scanner = null
        bytes = 0L
        ended = false
        length = C.LENGTH_UNSET.toLong()
        HlsEdgeProbe.setCurrent(t)
        val result = try {
            upstream.open(dataSpec)
        } catch (e: Throwable) {
            HlsEdgeProbe.setCurrent(null)
            if (t != null) HlsEdgeProbe.guard { session.onFailed(t, dataSpec, e, inBody = false, bytes = 0) }
            trace = null
            throw e
        }
        HlsEdgeProbe.setCurrent(null)
        length = result
        if (t != null && dataType == C.DATA_TYPE_MANIFEST) {
            HlsEdgeProbe.guard { scanner = HlsPlaylistScanner(upstream.uri?.let(::authorityHost), HlsEdgeProbe::hash) }
        }
        if (result == 0L) ended = true
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = try {
            upstream.read(buffer, offset, length)
        } catch (e: Throwable) {
            val t = trace
            if (t != null) {
                trace = null
                val s = spec
                HlsEdgeProbe.guard { if (s != null) session.onFailed(t, s, e, inBody = true, bytes = bytes) }
            }
            throw e
        }
        if (n == C.RESULT_END_OF_INPUT) {
            ended = true
        } else if (n > 0) {
            bytes += n
            if (this.length != C.LENGTH_UNSET.toLong() && bytes >= this.length) ended = true
            val sc = scanner
            if (sc != null) {
                try {
                    sc.feed(buffer, offset, n)
                } catch (_: Throwable) {
                    scanner = null
                }
            }
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            val t = trace
            val s = spec
            trace = null
            if (t != null && s != null) {
                val sc = scanner
                HlsEdgeProbe.guard { session.onDone(t, s, bytes, ended, if (ended) sc?.finish() else null) }
            }
            scanner = null
        }
    }

    private companion object {
        fun authorityHost(uri: Uri): String? = uri.host?.lowercase(Locale.ROOT)
    }
}

/**
 * One live HLS session's probe state (tune [tuneId]). Loader threads, OkHttp threads and the main thread call in; all
 * state is under [lock], and lines are emitted after it is released.
 */
@OptIn(UnstableApi::class)
internal class HlsEdgeSession(val tuneId: Long, val chan: String, val origin: String, val startedAt: Long) {
    private class Line(val text: String, val always: Boolean)

    private class PlState(var last: HlsPlaylistScanner.Summary, var edge: String, var loggedAt: Long) { var same = 0 }

    private val lock = Any()
    private val clock = HlsEdgeProbe.clock

    /** Set by [HlsEdgeProbe.end] before the transport cancels the session's calls: later failures are that cancel. */
    @Volatile var ended = false
    private var seq = 0
    private var verboseUntil = startedAt + HlsEdgeProbe.VERBOSE_WINDOW_MS
    private val recorder = ArrayDeque<Pair<Long, String>>()
    private val playlists = HashMap<String, PlState>()
    private val mapNames = LinkedHashSet<String>()

    private var lastPlOkAt = -1L
    private var lastSegOkAt = -1L
    private var firstReqFailAt: Long? = null
    private var reqFails = 0
    private var playerFails = 0
    private var reqTotal = 0
    private var plTotal = 0
    private var lastMediaPl: HlsPlaylistScanner.Summary? = null
    private var lastMediaEdge: String? = null
    private var lastMediaAt = -1L
    private var firstMediaPl: HlsPlaylistScanner.Summary? = null
    private var firstMediaEdge: String? = null
    private var firstMediaAt = -1L

    private var aggStart = startedAt
    private var aggReq = 0
    private var aggPl = 0
    private var aggSeg = 0
    private var aggKey = 0
    private var aggOther = 0
    private var aggFail = 0
    private var aggNew = 0
    private var aggRedir = 0
    private var aggTtfbSum = 0L
    private var aggTtfbN = 0
    private var aggTtfbMax = 0L
    private var aggBytes = 0L
    private var aggAdvance = 0L
    private val aggEdges = LinkedHashMap<String, Int>()

    fun newTrace(dataType: Int, spec: DataSpec): ReqTrace {
        var kind = HlsEdgeProbe.kindOf(dataType)
        val n: Int
        synchronized(lock) {
            n = ++seq
            if (kind == "seg") {
                val name = HlsPlaylistScanner.fileName(spec.uri.path ?: "")
                if (HlsEdgeProbe.hash(name) in mapNames || name.lowercase(Locale.ROOT).contains("init")) kind = "init"
            }
        }
        return ReqTrace(n, kind, clock(), clock)
    }

    fun onDone(t: ReqTrace, spec: DataSpec, bytes: Long, ended: Boolean, summary: HlsPlaylistScanner.Summary?) {
        val now = clock()
        val out = ArrayList<Line>(4)
        synchronized(lock) {
            var kind = t.kind
            if (summary != null) kind = if (summary.master) "mvp" else if (summary.isMedia) "media" else "pl"
            val ok = t.status == 0 || t.status in 200..299
            val text = reqLine(t, spec, kind, now, bytes, if (ended) null else "cancel", null)
            count(t, kind, bytes, failed = false)
            val notable = !ok || t.redirects > 0 || t.connLabel().contains('N') || !ended ||
                kind == "mvp" || kind == "key" || kind == "init" || kind == "steer"
            route(out, text, now, notable)
            if (ended && ok) {
                if (kind == "seg" || kind == "init") lastSegOkAt = now
                if (summary != null) {
                    lastPlOkAt = now
                    plTotal++
                    playlist(out, t, spec, summary, now)
                }
            }
            agg(out, now)
        }
        emit(out)
    }

    fun onFailed(t: ReqTrace, spec: DataSpec, error: Throwable, inBody: Boolean, bytes: Long) {
        val now = clock()
        val out = ArrayList<Line>(RECORDER_DUMP)
        synchronized(lock) {
            val phase = when {
                inBody -> "body"
                t.requestHost == null -> "local" // refused before any call (lease, limit, pre-emption)
                t.stage == "body" -> "status" // headers arrived at open, and the status was refused (4xx/5xx)
                else -> t.stage
            }
            val text = reqLine(t, spec, t.kind, now, bytes, null, "${HlsEdgeProbe.errName(error)}@$phase" + (t.listenerErr?.let { " lerr=$it" } ?: ""))
            if (ended) {
                // The session's own end cancelled this call (zap, re-prepare): one line, no dump, not a request failure.
                out += Line("$text after=end", always = false)
            } else {
                count(t, t.kind, bytes, failed = true)
                reqFails++
                if (firstReqFailAt == null) firstReqFailAt = now
                dump(out, now)
                out += Line(text, always = true)
                verboseUntil = now + HlsEdgeProbe.VERBOSE_WINDOW_MS
            }
            agg(out, now)
        }
        emit(out)
    }

    class FailureResult(val dump: List<String>, val line: String, val boundary: PendingBoundary)

    fun onPlayerFailure(now: Long, signal: String, facts: HlsEdgeProbe.PlayerFacts?): FailureResult {
        synchronized(lock) {
            playerFails++
            val out = ArrayList<Line>()
            dump(out, now)
            verboseUntil = now + HlsEdgeProbe.VERBOSE_WINDOW_MS
            val line = "fail t=$tuneId sig=$signal ${facts ?: "state=-"} sincePl=${since(now, lastPlOkAt)} sinceSeg=${since(now, lastSegOkAt)}" +
                " sinceReqFail=${firstReqFailAt?.let { now - it } ?: "-"} reqFails=$reqFails edge=${lastMediaEdge ?: "-"}" +
                " msn=${lastMediaPl?.msn ?: "-"} td=${lastMediaPl?.targetDurationS ?: "-"} age=${now - startedAt}"
            val pb = PendingBoundary(tuneId, chan, now, firstReqFailAt, signal, lastMediaEdge, lastMediaPl, lastMediaAt.takeIf { it >= 0 })
            return FailureResult(out.map { it.text }, line, pb)
        }
    }

    fun boundaryLine(pb: PendingBoundary, now: Long): String = synchronized(lock) {
        val old = pb.oldPl
        val new = firstMediaPl
        val sb = StringBuilder(320)
        sb.append("boundary from=").append(pb.fromTune).append(" to=").append(tuneId)
            .append(" sameChan=").append(if (pb.chan == chan) "y" else "n")
            .append(" sig=").append(pb.signal).append(" fails=").append(pb.fails)
            .append(" oldEdge=").append(pb.oldEdge ?: "-").append(" newEdge=").append(firstMediaEdge ?: "-")
        val oh = pb.oldEdge?.substringBefore('@')?.takeIf { it != "-" }
        val oi = pb.oldEdge?.substringAfter('@', "")?.takeIf { it.isNotEmpty() && it != "-" }
        val nh = firstMediaEdge?.substringBefore('@')?.takeIf { it != "-" }
        val ni = firstMediaEdge?.substringAfter('@', "")?.takeIf { it.isNotEmpty() && it != "-" }
        sb.append(" host=").append(if (oh == null || nh == null) "-" else if (oh == nh) "same" else "changed")
        sb.append(" ip=").append(if (oi == null || ni == null) "-" else if (oi == ni) "same" else "changed")
        sb.append(" old=").append(range(old)).append(" old.last=#").append(old?.lastNameHash ?: "-").append(" old.pdt1=").append(old?.pdtLast ?: "-")
        sb.append(" new=").append(range(new)).append(" new.last=#").append(new?.lastNameHash ?: "-").append(" new.pdt0=").append(new?.pdtFirst ?: "-")
        sb.append(" td=").append(old?.targetDurationS ?: "-").append('>').append(new?.targetDurationS ?: "-")
        sb.append(" dseq=").append(old?.discontinuitySequence ?: "-").append('>').append(new?.discontinuitySequence ?: "-")
        val oldLast = old?.entries?.lastOrNull()
        if (oldLast != null && new != null) {
            val byName = new.entries.firstOrNull { it.nameHash == oldLast.nameHash }
            if (byName != null) {
                sb.append(" oldLastInNew=msn").append(byName.msn).append(" msnShift=").append(signed(byName.msn - oldLast.msn))
                sb.append(" pdtShift=").append(if (byName.pdtMs != null && oldLast.pdtMs != null) signed(byName.pdtMs - oldLast.pdtMs) else "-")
            } else {
                sb.append(" oldLastInNew=absent msnShift=- pdtShift=-")
            }
            val atMsn = new.entries.firstOrNull { it.msn == oldLast.msn }
            sb.append(" atOldLastMsn=").append(
                when {
                    atMsn == null -> "absent"
                    atMsn.nameHash == oldLast.nameHash -> "name:same"
                    else -> "name:diff"
                },
            )
            sb.append(",pdt:").append(if (atMsn?.pdtMs != null && oldLast.pdtMs != null) signed(atMsn.pdtMs - oldLast.pdtMs) else "-")
            val newFirst = new.entries.firstOrNull()
            val inOld = newFirst?.let { f -> old.entries.firstOrNull { it.nameHash == f.nameHash } }
            sb.append(" newFirstInOld=").append(if (newFirst == null) "-" else if (inOld == null) "absent" else "msn${inOld.msn}")
        } else {
            sb.append(" oldLastInNew=- msnShift=- pdtShift=- atOldLastMsn=- newFirstInOld=-")
        }
        sb.append(" failToFrame=").append(now - pb.failAt)
        sb.append(" reqFailToFail=").append(pb.firstReqFailAt?.let { pb.failAt - it } ?: "-")
        sb.append(" failToNewPl=").append(if (firstMediaAt >= 0) (firstMediaAt - pb.failAt).toString() else "-")
        sb.append(" oldPlAge=").append(pb.oldPlAt?.let { pb.failAt - it } ?: "-")
        sb.toString()
    }

    fun endLine(): String? = synchronized(lock) {
        "end t=$tuneId dur=${clock() - startedAt} req=$reqTotal reqFails=$reqFails fails=$playerFails pl=$plTotal lastEdge=${lastMediaEdge ?: "-"}"
    }

    // ---------------------------------------------------------------- under lock

    private fun reqLine(t: ReqTrace, spec: DataSpec, kind: String, now: Long, bytes: Long, end: String?, err: String?): String {
        val sb = StringBuilder(240)
        sb.append("req t=").append(tuneId).append(" n=").append(t.seq).append(" k=").append(kind)
        if (kind == "seg" || kind == "init") sb.append(" sn=#").append(HlsEdgeProbe.hash(HlsPlaylistScanner.fileName(spec.uri.path ?: "")))
        sb.append(" rq=").append(t.requestHost ?: specHost(spec)).append(" fin=").append(t.finalHost ?: "-")
            .append(" hops=").append(t.redirects).append(" st=").append(if (t.status == 0) "-" else t.status.toString())
            .append(" conn=").append(t.connLabel()).append(" ip=").append(t.ipLabel())
            .append(" age=").append(t.finalAgeNow(now).let { if (it < 0) "-" else it.toString() }).append(" use=").append(t.usesLabel())
            .append(" dns=").append(ms(t.dnsMs)).append(" tcp=").append(ms(t.connectMs)).append(" tls=").append(ms(t.tlsMs))
            .append(" ttfb=").append(ms(t.ttfbMs)).append(" tot=").append(now - t.startAt).append(" B=").append(bytes)
        if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) sb.append(" range=1")
        if (end != null) sb.append(" end=").append(end)
        if (err != null) sb.append(" err=").append(err)
        return sb.toString()
    }

    private fun playlist(out: MutableList<Line>, t: ReqTrace, spec: DataSpec, s: HlsPlaylistScanner.Summary, now: Long) {
        s.mapNameHashes.forEach { if (mapNames.size < 16) mapNames += it }
        val edge = "${t.finalHost ?: "-"}@${t.finalIp()}"
        val id = HlsEdgeProbe.playlistId(spec)
        if (s.master) {
            out += Line("pl t=$tuneId id=#$id shape=master edge=$edge var=${s.variants} rend=${s.renditions} ref=${refs(s.segmentRefs)} why=master", always = true)
            return
        }
        if (!s.isMedia) {
            out += Line("pl t=$tuneId id=#$id shape=unknown edge=$edge skipped=${s.linesSkipped} why=unknown", always = true)
            return
        }
        lastMediaPl = s
        lastMediaEdge = edge
        lastMediaAt = now
        if (firstMediaPl == null) {
            firstMediaPl = s
            firstMediaEdge = edge
            firstMediaAt = now
        }
        val st = playlists[id]
        if (st == null) {
            playlists[id] = PlState(s, edge, now)
            out += Line(plLine(id, edge, s, null, 0, "first"), always = true)
            return
        }
        val prev = st.last
        val dMsn = if (s.msn != null && prev.msn != null) s.msn - prev.msn else null
        val anomalies = ArrayList<String>(2)
        if (dMsn != null && dMsn < 0) anomalies += "msn-back"
        if (dMsn != null && dMsn > prev.segments) anomalies += "msn-gap"
        if (s.discontinuitySequence != prev.discontinuitySequence) anomalies += "dseq"
        if (s.targetDurationS != prev.targetDurationS) anomalies += "td"
        if (edge != st.edge) anomalies += "edge"
        if (s.keyMethods != prev.keyMethods) anomalies += "key"
        if (s.segmentRefs != prev.segmentRefs) anomalies += "ref"
        if (s.endList != prev.endList) anomalies += "endlist"
        if (dMsn != null && dMsn > 0) aggAdvance += dMsn
        val changed = dMsn == null || dMsn != 0L
        if (changed) st.same = 0 else st.same++
        st.last = s
        st.edge = edge
        val why = when {
            anomalies.isNotEmpty() -> "anomaly:" + anomalies.joinToString(",")
            !changed && st.same == STALE_RELOADS -> "stale"
            !changed -> null
            now < verboseUntil -> "verbose"
            now - st.loggedAt >= HlsEdgeProbe.PL_STEADY_MS -> "change"
            else -> null
        }
        val text = plLine(id, edge, s, dMsn, st.same, why ?: "routine")
        if (why == null) {
            record(now, text)
        } else {
            st.loggedAt = now
            out += Line(text, always = anomalies.isNotEmpty() || why == "stale")
        }
    }

    private fun plLine(id: String, edge: String, s: HlsPlaylistScanner.Summary, dMsn: Long?, same: Int, why: String): String =
        "pl t=$tuneId id=#$id shape=media edge=$edge msn=${s.msn ?: "-"} n=${s.segments} td=${s.targetDurationS ?: "-"}" +
            " dseq=${s.discontinuitySequence ?: "-"} disc=${s.discontinuities} dur=${s.durationMs} pdt0=${s.pdtFirst ?: "-"} pdt1=${s.pdtLast ?: "-"}" +
            " last=#${s.lastNameHash ?: "-"} ref=${refs(s.segmentRefs)} key=${if (s.keyMethods.isEmpty()) "-" else s.keyMethods.joinToString(",")}" +
            " keyref=${refs(s.keyRefs)} map=${s.mapNameHashes.size} end=${if (s.endList) 1 else 0} ll=${s.parts}" +
            " dMsn=${dMsn?.let(::signed) ?: "-"} same=$same why=$why"

    private fun route(out: MutableList<Line>, text: String, now: Long, notable: Boolean) {
        if (notable || now < verboseUntil) out += Line(text, always = false) else record(now, text)
    }

    private fun record(now: Long, text: String) {
        recorder.addLast(now to text)
        if (recorder.size > HlsEdgeProbe.RECORDER_LINES) recorder.removeFirst()
    }

    private fun dump(out: MutableList<Line>, now: Long) {
        for ((at, text) in recorder) out += Line("fr ago=${now - at} $text", always = true)
        recorder.clear()
    }

    private fun count(t: ReqTrace, kind: String, bytes: Long, failed: Boolean) {
        reqTotal++
        aggReq++
        when (kind) {
            "pl", "media", "mvp" -> aggPl++
            "seg", "init" -> aggSeg++
            "key" -> aggKey++
            else -> aggOther++
        }
        if (failed) aggFail++
        if (t.connLabel().contains('N')) aggNew++
        if (t.redirects > 0) aggRedir++
        if (t.ttfbMs >= 0) {
            aggTtfbSum += t.ttfbMs
            aggTtfbN++
            if (t.ttfbMs > aggTtfbMax) aggTtfbMax = t.ttfbMs
        }
        aggBytes += bytes
        val ip = t.finalIp()
        if (ip != "-" && (aggEdges.size < 6 || ip in aggEdges)) aggEdges[ip] = (aggEdges[ip] ?: 0) + 1
    }

    private fun agg(out: MutableList<Line>, now: Long) {
        if (now - aggStart < HlsEdgeProbe.AGG_WINDOW_MS || aggReq == 0) return
        val edges = if (aggEdges.isEmpty()) "-" else aggEdges.entries.joinToString(",") { "${it.key}:${it.value}" }
        out += Line(
            "agg t=$tuneId win=${now - aggStart} req=$aggReq pl=$aggPl seg=$aggSeg key=$aggKey other=$aggOther fail=$aggFail new=$aggNew" +
                " redir=$aggRedir ips=$edges ttfbAvg=${if (aggTtfbN == 0) "-" else (aggTtfbSum / aggTtfbN).toString()} ttfbMax=$aggTtfbMax" +
                " B=$aggBytes dMsn=+$aggAdvance supp=${HlsEdgeProbe.takeSuppressed()}",
            always = false,
        )
        aggStart = now
        aggReq = 0; aggPl = 0; aggSeg = 0; aggKey = 0; aggOther = 0; aggFail = 0; aggNew = 0; aggRedir = 0
        aggTtfbSum = 0; aggTtfbN = 0; aggTtfbMax = 0; aggBytes = 0; aggAdvance = 0
        aggEdges.clear()
    }

    private fun emit(out: List<Line>) {
        for (l in out) HlsEdgeProbe.emit(l.text, l.always)
    }

    private companion object {
        const val RECORDER_DUMP = 32
        const val STALE_RELOADS = 3

        fun ms(v: Long): String = if (v < 0) "-" else v.toString()
        fun since(now: Long, at: Long): String = if (at < 0) "-" else (now - at).toString()
        fun signed(v: Long): String = if (v >= 0) "+$v" else v.toString()
        fun refs(set: Set<HlsPlaylistScanner.Ref>): String = if (set.isEmpty()) "-" else set.joinToString(",") { it.label }
        fun range(s: HlsPlaylistScanner.Summary?): String = if (s?.msn == null) "-" else "msn${s.msn}..${s.lastMsn}(n${s.segments})"

        fun specHost(spec: DataSpec): String {
            val h = spec.uri.host ?: return "-"
            val port = spec.uri.port
            val label = HlsEdgeProbe.hostOnly(h)
            val scheme = spec.uri.scheme?.lowercase(Locale.ROOT)
            val def = if (scheme == "https") 443 else 80
            return if (port < 0 || port == def) label else "$label:$port"
        }
    }
}
