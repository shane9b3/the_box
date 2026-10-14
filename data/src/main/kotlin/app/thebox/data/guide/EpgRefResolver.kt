package app.thebox.data.guide

import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchMethod
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.EpgSourceDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.entity.EpgSourceEntity
import app.thebox.data.repo.DataSettings
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.ResolvedEpg
import app.thebox.data.repo.read
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The M1 §2.2 resolution rule (docs/design/m3-guide.md §3.2.2), pure: mapping row → explicit ref or explicit none; else
 * the first AUTO match with data in source priority order (active sources whose `playlist_id` is NULL or the channel's);
 * else the first AUTO match; shift = `source.shift_min + channelShift + global`, where `channelShift` is already
 * `prefs ?: channel tvg-shift ?: header tvg-shift ?: 0`. `EpgRepository.resolvedRefs` and [EpgRefResolver] both call it.
 */
object EpgRefRule {
    data class Mapping(val sourceId: Long?, val xmltvId: String?)
    data class Auto(val sourceId: Long, val xmltvId: String, val hasData: Boolean, val method: String)

    /** Active sources in priority order (`EpgSourceDao.active()`), indexed once per call site. */
    class Sources(active: List<EpgSourceEntity>) {
        val rank: Map<Long, Int> = active.withIndex().associate { (i, s) -> s.id to i }
        val byId: Map<Long, EpgSourceEntity> = active.associateBy { it.id }
    }

    fun resolve(playlistId: Long, channelShiftMin: Int, mapping: Mapping?, autos: List<Auto>, sources: Sources, globalShiftMin: Int): ResolvedEpg {
        if (mapping != null) {
            val ref = if (mapping.sourceId != null && mapping.xmltvId != null) EpgChannelRef(mapping.sourceId, mapping.xmltvId) else null
            val srcShift = ref?.let { sources.byId[it.sourceId]?.shiftMin } ?: 0
            return ResolvedEpg(ref, srcShift + channelShiftMin + globalShiftMin, MatchMethod.OVERRIDE, ref != null)
        }
        val candidates = autos
            .filter { it.sourceId in sources.rank && (sources.byId[it.sourceId]?.playlistId ?: playlistId) == playlistId }
            .sortedBy { sources.rank.getValue(it.sourceId) }
        val pick = candidates.firstOrNull { it.hasData } ?: candidates.firstOrNull()
            ?: return ResolvedEpg(null, channelShiftMin + globalShiftMin, null, false)
        return ResolvedEpg(
            EpgChannelRef(pick.sourceId, pick.xmltvId),
            (sources.byId[pick.sourceId]?.shiftMin ?: 0) + channelShiftMin + globalShiftMin,
            runCatching { MatchMethod.valueOf(pick.method) }.getOrNull(),
            pick.hasData,
        )
    }

    /** `tvg-shift` of the playlist header in minutes, or null. */
    fun headerShiftMin(p: app.thebox.data.db.entity.PlaylistEntity?): Int? =
        p?.let { PlaylistRepository.header(it)?.tvgShiftHours }?.let { (it * 60).roundToInt() }
}

/**
 * [EpgRefRule] for a handful of identities (a 32-row block, search candidates) instead of every channel of every
 * playlist: `epg_mapping`/`epg_match` rows are read with `stable_key IN (…)` per playlist.
 */
@Singleton
class EpgRefResolver @Inject constructor(
    private val db: LibraryDatabase,
    private val sourceDao: EpgSourceDao,
    private val playlistDao: PlaylistDao,
    private val settings: DataSettings,
) {
    /** Per channel: its own `epg_shift_min` and the `channel_prefs.epg_shift_min` override (both nullable). */
    data class Input(val identity: ChannelIdentity, val channelShiftMin: Int?, val prefShiftMin: Int?)

    suspend fun resolve(inputs: List<Input>): Map<ChannelIdentity, ResolvedEpg> {
        if (inputs.isEmpty()) return emptyMap()
        val sources = EpgRefRule.Sources(sourceDao.active())
        val global = settings.current.globalShiftMin
        val out = HashMap<ChannelIdentity, ResolvedEpg>(inputs.size * 2)
        for ((playlistId, group) in inputs.groupBy { it.identity.playlistId }) {
            val headerShift = EpgRefRule.headerShiftMin(playlistDao.get(playlistId))
            val keys = group.map { it.identity.stableKey.value }.distinct()
            val mappings = HashMap<String, EpgRefRule.Mapping>()
            val autos = HashMap<String, MutableList<EpgRefRule.Auto>>()
            db.read {
                for (chunk in keys.chunked(MAX_VARS)) {
                    val marks = chunk.joinToString(",") { "?" }
                    usePrepared("SELECT stable_key, source_id, xmltv_id FROM epg_mapping WHERE playlist_id = ? AND stable_key IN ($marks)") { st ->
                        st.bindLong(1, playlistId)
                        chunk.forEachIndexed { i, k -> st.bindText(i + 2, k) }
                        while (st.step()) {
                            mappings[st.getText(0)] = EpgRefRule.Mapping(
                                if (st.isNull(1)) null else st.getLong(1), if (st.isNull(2)) null else st.getText(2),
                            )
                        }
                    }
                    usePrepared(
                        "SELECT stable_key, source_id, xmltv_id, has_data, method FROM epg_match " +
                            "WHERE playlist_id = ? AND status = 0 AND stable_key IN ($marks) ORDER BY playlist_id, stable_key",
                    ) { st ->
                        st.bindLong(1, playlistId)
                        chunk.forEachIndexed { i, k -> st.bindText(i + 2, k) }
                        while (st.step()) {
                            autos.getOrPut(st.getText(0)) { ArrayList(2) } +=
                                EpgRefRule.Auto(st.getLong(1), st.getText(2), st.getLong(3) != 0L, st.getText(4))
                        }
                    }
                }
            }
            for (input in group) {
                val k = input.identity.stableKey.value
                val channelShift = input.prefShiftMin ?: input.channelShiftMin ?: headerShift ?: 0
                out[input.identity] = EpgRefRule.resolve(playlistId, channelShift, mappings[k], autos[k].orEmpty(), sources, global)
            }
        }
        return out
    }

    /** Convenience for one identity (search confirmation). */
    suspend fun resolveOne(playlistId: Long, stableKey: String, channelShiftMin: Int?, prefShiftMin: Int?): ResolvedEpg? =
        ChannelIdentity(playlistId, StableKey(stableKey)).let { id -> resolve(listOf(Input(id, channelShiftMin, prefShiftMin)))[id] }

    companion object {
        /** Bound variables per statement (SQLite's default limit is 999 on old builds). */
        const val MAX_VARS = 500
    }
}
