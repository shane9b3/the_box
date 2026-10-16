package app.thebox.settings

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.refresh.KeyRule
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.ChannelOptionsPage
import app.thebox.core.settings.state.ChannelOptionsSnapshot
import app.thebox.core.settings.state.IdentityKind
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.PlaybackDomain
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsText
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.WpaPages
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.ChannelPrefsDao
import app.thebox.data.db.dao.EpgMappingDao
import app.thebox.data.db.dao.FavouriteDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.entity.ChannelPrefsEntity
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.di.WallClock
import app.thebox.external.ExternalPlayerLauncher
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.PlaybackController
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * The Channel options page, route `Channel(identity)` (docs/design/m5-setup-settings.md §3.3.12; rows built by the pure
 * [ChannelOptionsPage]). Storage is the existing `channel_prefs` columns and favourites, keyed by `(playlist_id,
 * stable_key)`. When the channel is the tuned one, aspect and decoder go through the controller's live setters; buffer
 * is REPREPARE (applied when Settings closes). "Open in external player" hands the stream off (§3.3.4): the controller
 * stops our stream first (the provider's connection limit), then [ExternalPlayerLauncher] starts the chosen player.
 */
@Singleton
class ChannelOptionsPageSource @Inject constructor(
    private val channels: ChannelDao,
    private val prefs: ChannelPrefsDao,
    private val playlists: PlaylistDao,
    private val groups: GroupDao,
    private val favourites: FavouriteDao,
    private val mappings: EpgMappingDao,
    private val memory: ChannelMemory,
    private val controller: PlaybackController,
    private val launcher: ExternalPlayerLauncher,
    private val store: SettingsStore,
    private val texts: SettingsTexts,
    private val clock: WallClock,
) : SettingsPageSource {
    private val version = AtomicLong(0)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notices: Flow<String> = _notices.asSharedFlow()

    override fun handles(route: SettingsRoute): Boolean = route is SettingsRoute.Channel

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel {
        val r = route as SettingsRoute.Channel
        return ChannelOptionsPage.build(r, snapshot(r.identity), texts, version.incrementAndGet())
    }

    private suspend fun snapshot(id: ChannelIdentity): ChannelOptionsSnapshot? = try {
        val key = id.stableKey.value
        val ch = channels.get(id.playlistId, key)
        val pl = playlists.get(id.playlistId)
        if (ch == null || pl == null) {
            null
        } else {
            val p = prefs.get(id.playlistId, key)
            val m = memory.read(id)
            val lists = favourites.lists().map { it.id to it.name }
            val member = favourites.listsOf(id.playlistId, key).map { it.listId }.toSet()
            val group = ch.groupId?.let { gid -> groups.all(id.playlistId).firstOrNull { it.id == gid }?.name }
            val manual = mappings.get(id.playlistId, key) != null
            ChannelOptionsSnapshot(
                name = p?.customName ?: ch.name,
                number = p?.customNumber ?: ch.number,
                group = group,
                playlistName = pl.name,
                identityKind = when (KeyRule.entries.firstOrNull { it.name == ch.keyRule }) {
                    KeyRule.XTREAM -> IdentityKind.PROVIDER_ID
                    KeyRule.TVG -> IdentityKind.GUIDE_ID_AND_NAME
                    KeyRule.URL -> IdentityKind.ADDRESS
                    KeyRule.NAME_GROUP, null -> IdentityKind.NAME
                },
                streamHost = Redact.host(ch.url).takeIf { it != "?" },
                format = ch.containerExt,
                archiveMode = ch.catchupType,
                archiveDays = pl.catchupDaysOverride ?: ch.catchupDays ?: ch.tvArchiveDays,
                catchupWinner = pl.catchupProbeWinner,
                epgMatch = when {
                    manual -> texts[SettingsText.EPG_MANUAL]
                    ch.tvgId != null -> texts[SettingsText.EPG_TVG_ID]
                    else -> null
                },
                fixedOffsetZone = fixedOffset(pl.serverZone),
                hidden = p?.hidden == true,
                locked = p?.locked == true,
                shiftMin = p?.epgShiftMin,
                aspect = m.aspect,
                decoder = m.videoDecoder,
                buffer = m.bufferMode,
                audioLanguage = m.audioLanguage,
                subtitleLanguage = m.subtitleLanguage,
                favouriteLists = lists,
                memberOf = member,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BoxLog.e(TAG, "channel options read failed", e)
        null
    }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
        val id = (route as? SettingsRoute.Channel)?.identity ?: return false
        if (intent.row !in OWN) return false
        val key = id.stableKey.value
        val now = clock.now()
        val choose = (intent as? RowIntent.Choose)?.optionId
        when (intent.row) {
            ChannelOptionsRows.FAVOURITES -> (intent as? RowIntent.ChooseMany)?.let { m ->
                val want = m.optionIds.mapNotNull { it.toLongOrNull() }.toSet()
                val have = favourites.listsOf(id.playlistId, key).map { it.listId }.toSet()
                for (l in want - have) favourites.insert(FavouriteEntity(l, id.playlistId, key, favourites.maxSortIndex(l) + 1, now))
                for (l in have - want) favourites.delete(l, id.playlistId, key)
            }
            ChannelOptionsRows.HIDE -> (intent as? RowIntent.Toggle)?.let { prefs.upsertHidden(id.playlistId, key, it.on, now) }
            ChannelOptionsRows.LOCK -> (intent as? RowIntent.Toggle)?.let { t ->
                // Off returns the channel to automatic (its group / adult detection); an explicit unlock is WP-C's PIN path.
                editPrefs(id, now) { it.copy(locked = if (t.on) true else null) }
                if (t.on) _notices.tryEmit(texts[SettingsText.CH_LOCKED_NOTICE])
            }
            ChannelOptionsRows.SHIFT -> choose?.let { c -> editPrefs(id, now) { it.copy(epgShiftMin = c.toIntOrNull()) } }
            ChannelOptionsRows.ASPECT -> choose?.let { c ->
                val mode = WpaPages.enumOf<AspectMode>(c)
                memory.update(id) { it.copy(aspect = mode) }
                if (isTuned(id)) withContext(Dispatchers.Main.immediate) {
                    controller.setAspectMode(mode ?: store.flow(PlaybackDomain).value.defaultAspect, remember = mode != null)
                }
            }
            ChannelOptionsRows.DECODER -> choose?.let { c ->
                val mode = WpaPages.enumOf<VideoDecoderMode>(c)
                memory.update(id) { it.copy(videoDecoder = mode) }
                if (isTuned(id)) withContext(Dispatchers.Main.immediate) { controller.setVideoDecoderOverride(mode, remember = mode != null) }
            }
            ChannelOptionsRows.BUFFER -> choose?.let { c -> memory.update(id) { it.copy(bufferMode = WpaPages.enumOf<BufferMode>(c)) } }
            ChannelOptionsRows.AUDIO_LANG -> if (intent is RowIntent.Run) memory.update(id) { it.copy(audioLanguage = null) }
            ChannelOptionsRows.SUBTITLES -> if (intent is RowIntent.Run) memory.update(id) { it.copy(subtitleLanguage = null) }
            ChannelOptionsRows.EXTERNAL -> if (intent is RowIntent.Run) handOff(id)
            else -> Unit
        }
        return true
    }

    private suspend fun handOff(id: ChannelIdentity) {
        val chosen = store.flow(PlaybackDomain).value.externalPlayer
        val pkg = chosen ?: launcher.installed().singleOrNull()?.packageName
        if (pkg == null) { _notices.tryEmit(texts[SettingsText.CH_EXTERNAL_NONE]); return }
        withContext(Dispatchers.Main.immediate) {
            if (!isTuned(id)) { _notices.tryEmit(texts[SettingsText.CH_EXTERNAL_NOTHING]); return@withContext }
            val handOff = controller.handOffExternal()
            if (handOff == null) { _notices.tryEmit(texts[SettingsText.CH_EXTERNAL_NOTHING]); return@withContext }
            if (!launcher.launch(handOff, pkg)) _notices.tryEmit(texts[SettingsText.CH_EXTERNAL_FAILED])
        }
    }

    private fun isTuned(id: ChannelIdentity): Boolean = controller.state.value.target?.let { it.identity == id && !it.isArchive } == true

    private suspend fun editPrefs(id: ChannelIdentity, now: Long, change: (ChannelPrefsEntity) -> ChannelPrefsEntity) {
        val cur = prefs.get(id.playlistId, id.stableKey.value) ?: ChannelPrefsEntity(id.playlistId, id.stableKey.value, updatedAt = now)
        prefs.upsert(change(cur).copy(updatedAt = now))
    }

    companion object {
        private const val TAG = "ChannelOptions"
        private val OWN = ChannelOptionsRows.rows.map { it.id }.toSet()

        /** A fixed-offset server zone ("+03:00", "GMT+3", "UTC") does not follow daylight saving (M4 §8 Q15). */
        fun fixedOffset(zone: String?): Boolean {
            if (zone.isNullOrBlank()) return false
            return try {
                ZoneId.of(zone).rules.isFixedOffset
            } catch (e: Exception) {
                zone.startsWith("+") || zone.startsWith("-") || zone.startsWith("GMT") || zone.startsWith("UTC")
            }
        }
    }
}
