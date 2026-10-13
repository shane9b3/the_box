package app.thebox.data.testutil

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.ImportGate
import app.thebox.core.common.SourceException
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgImportReport
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgProgress
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgSearchHit
import app.thebox.core.epg.EpgSourceStat
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for WP-B's store (§1.7). [catalogue] is the `<channel>` list of each source (programme counts are
 * what the "XMLTV" holds); a build calls the whitelist once per source, imports programmes only for accepted
 * ids, and publishes a generation. [failSource] makes that source fail (report.failedSourceId) unless its
 * file name ends with ".src" (the last-good fallback).
 */
class FakeEpgStore(
    val catalogue: MutableMap<Long, List<EpgChannelInfo>> = HashMap(),
) : EpgStore {
    private val state = MutableStateFlow<EpgGenerationInfo?>(null)
    override val info: StateFlow<EpgGenerationInfo?> = state

    /** sourceId → accepted xmltv ids of the current generation. */
    val imported = HashMap<Long, MutableSet<String>>()
    val builds = AtomicInteger()
    val patches = AtomicInteger()
    val recovers = AtomicInteger()
    var lastBuild: EpgBuildRequest? = null
    var lastPatch: EpgPatchRequest? = null
    var failSource: Long? = null
    var throwOnBuild: SourceException? = null
    var openReaders = AtomicInteger()

    /** Called inside build (to test lock serialisation). */
    var duringBuild: () -> Unit = {}

    override fun openReader(): EpgReader? {
        val gen = state.value ?: return null
        openReaders.incrementAndGet()
        return Reader(gen)
    }

    override fun build(request: EpgBuildRequest, whitelist: EpgWhitelistResolver, gate: ImportGate, cancel: CancelCheck, progress: (EpgProgress) -> Unit): EpgImportReport {
        builds.incrementAndGet()
        lastBuild = request
        throwOnBuild?.let { throw it }
        duringBuild()
        val failing = request.sources.firstOrNull { it.sourceId == failSource && !it.file.name.endsWith(".src") }
        if (failing != null) {
            return EpgImportReport(EpgImportReport.Mode.REBUILD, state.value?.generation ?: 0, emptyList(), 0, 0, 0, 1, "fts4", failedSourceId = failing.sourceId)
        }
        imported.clear()
        val stats = ArrayList<EpgSourceStat>()
        var programmes = 0
        for (s in request.sources) {
            val channels = catalogue[s.sourceId].orEmpty()
            val wl = whitelist.whitelistFor(s.sourceId, channels.map { it.copy(imported = false, programmeCount = 0) }.asSequence())
            val accepted = channels.filter { wl.accepts(it.xmltvId) }.mapTo(HashSet()) { it.xmltvId }
            imported[s.sourceId] = accepted
            val kept = channels.filter { it.xmltvId in accepted }.sumOf { it.programmeCount }
            programmes += kept
            stats += EpgSourceStat(s.sourceId, channels.size, kept, kept, 0, 0, 0, 0, 0, s.file.length(), false, null)
        }
        val gen = (state.value?.generation ?: 0) + 1
        state.value = EpgGenerationInfo(gen, 0, request.nowMs, programmes, catalogue.values.sumOf { it.size }, 1000, 0, 0, "fts4")
        return EpgImportReport(EpgImportReport.Mode.REBUILD, gen, stats, programmes, programmes, 1000, 5, "fts4")
    }

    override fun patch(request: EpgPatchRequest, gate: ImportGate, cancel: CancelCheck, progress: (EpgProgress) -> Unit): EpgImportReport {
        patches.incrementAndGet()
        lastPatch = request
        for (r in request.refs) imported.getOrPut(r.sourceId) { HashSet() } += r.xmltvId
        val cur = state.value!!
        state.value = cur.copy(patchSeq = cur.patchSeq + 1)
        return EpgImportReport(EpgImportReport.Mode.PATCH, cur.generation, emptyList(), 0, 0, 1000, 1, "fts4")
    }

    override fun recover() { recovers.incrementAndGet() }
    override fun clear() { state.value = null; imported.clear() }

    /** Publishes a generation without a build (all catalogue channels, [importedIds] imported). */
    fun publish(importedIds: Map<Long, Set<String>> = emptyMap()) {
        imported.clear()
        importedIds.forEach { (k, v) -> imported[k] = v.toMutableSet() }
        state.value = EpgGenerationInfo(1, 0, 0, 0, 0, 0, 0, 0, "fts4")
    }

    private inner class Reader(override val info: EpgGenerationInfo) : EpgReader {
        override fun forEachChannel(sourceId: Long?, block: (EpgChannelInfo) -> Unit) {
            for ((sid, list) in catalogue) {
                if (sourceId != null && sid != sourceId) continue
                val imp = imported[sid].orEmpty()
                for (c in list) {
                    val isImported = c.xmltvId in imp
                    block(c.copy(imported = isImported, programmeCount = if (isImported) c.programmeCount else 0))
                }
            }
        }

        override fun resolve(refs: List<EpgChannelRef>, caseInsensitive: Boolean) = IntArray(refs.size) { -1 }
        override fun window(channels: IntArray, shiftMin: IntArray, fromMin: Int, toMin: Int, out: EpgWindow) = out.clear()
        override fun detail(channel: Int, rawStartMin: Int, shiftMin: Int): ProgrammeDetail? = null
        override fun at(channel: Int, atMin: Int, shiftMin: Int): ProgrammeDetail? = null
        override fun search(query: String, nowMin: Int, limit: Int): List<EpgSearchHit> = emptyList()
        override fun findChannels(text: String, limit: Int): List<EpgChannelInfo> = emptyList()
        override fun refOf(channel: Int): EpgChannelRef? = null
        override fun close() { openReaders.decrementAndGet() }
    }
}

fun epgChannel(sourceId: Long, id: String, vararg names: String, programmes: Int = 10) =
    EpgChannelInfo(sourceId, id, names.toList().ifEmpty { listOf(id) }, null, false, programmes)
