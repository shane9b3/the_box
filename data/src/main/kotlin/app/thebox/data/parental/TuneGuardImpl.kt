package app.thebox.data.parental

import app.thebox.core.common.BoxLog
import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalState
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.repo.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every channel that could be locked content, with what locks it, as an in-memory snapshot (docs/design/m5-setup-
 * settings.md §4.3, B1). [TuneGuardImpl] answers on main from it without IO; it is rebuilt on IO whenever the lock
 * inputs change (Room invalidation of `channel`, `channel_prefs`, `group_prefs`, `channel_group`). The SQL is the §4.3
 * predicate for one identity: `auto` = locked under LOCKED_AUTO / FILTERED_NO_PIN, `manual` = locked under
 * LOCKED_MANUAL_ONLY.
 */
@Singleton
class LockedChannelIndex @Inject constructor(
    private val db: LibraryDatabase,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    class Entry(val groupKey: String?, val auto: Boolean, val manual: Boolean)

    @Volatile private var snapshot: Map<ChannelIdentity, Entry>? = null
    private val _version = MutableStateFlow(0L)
    /** Bumped after every rebuild (tests wait on it). */
    val version: StateFlow<Long> = _version.asStateFlow()
    private var started = false

    /** Starts watching the lock tables (idempotent). The singleton starts on first use. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            db.invalidationTracker.createFlow(*TABLES, emitInitialState = true).collect {
                try {
                    rebuild()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.w(TAG, "locked-channel index not rebuilt: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Re-reads the snapshot now (tests; the flow calls it on every change). */
    suspend fun rebuild() {
        val out = HashMap<ChannelIdentity, Entry>()
        db.read {
            usePrepared(SQL) { st ->
                while (st.step()) {
                    val id = ChannelIdentity(st.getLong(0), StableKey(st.getText(1)))
                    out[id] = Entry(if (st.isNull(2)) null else st.getText(2), st.getLong(3) != 0L, st.getLong(4) != 0L)
                }
            }
        }
        snapshot = out
        _version.value = _version.value + 1
    }

    val loaded: Boolean get() = snapshot != null

    /** True when [identity] is locked content under [access] (OPEN: never). Unknown before the first load: false. */
    fun locked(identity: ChannelIdentity, access: ParentalAccess): Boolean {
        start()
        val e = snapshot?.get(identity) ?: return false
        return when (access) {
            ParentalAccess.OPEN -> false
            ParentalAccess.LOCKED_AUTO, ParentalAccess.FILTERED_NO_PIN -> e.auto
            ParentalAccess.LOCKED_MANUAL_ONLY -> e.manual
        }
    }

    /** Locked under any access (auto or manual): "locked content" for the session's stay-unlocked rules. */
    fun lockedContent(identity: ChannelIdentity): Boolean {
        start()
        val e = snapshot?.get(identity) ?: return false
        return e.auto || e.manual
    }

    /** The channel's group, for "Open anyway" on a refused tune. */
    fun groupOf(identity: ChannelIdentity): GuideSource.Group? {
        start()
        val key = snapshot?.get(identity)?.groupKey ?: return null
        return GuideSource.Group(identity.playlistId, key)
    }

    companion object {
        private const val TAG = "LockedChannels"
        val TABLES = arrayOf("channel", "channel_prefs", "group_prefs", "channel_group")

        /**
         * The §4.3 lock clauses evaluated per channel (hidden and rule_hidden do not matter to a tune). v5
         * (docs/design/m6-channel-editor.md §3.6 row 8): `g` is the **effective** group, so a channel moved into a locked
         * group is locked (also while that group is in its grace period, ruling a) and one moved out is not; `group_key`
         * is the effective key ([groupOf], Open anyway).
         */
        const val SQL = """
        SELECT c.playlist_id, c.stable_key, g.group_key,
               CASE WHEN COALESCE(cp.locked, CASE WHEN gp.locked IS NULL THEN c.is_adult ELSE 0 END) != 0
                      OR COALESCE(gp.locked, g.is_adult, 0) != 0 THEN 1 ELSE 0 END AS auto_locked,
               CASE WHEN COALESCE(cp.locked, 0) != 0 OR COALESCE(gp.locked, 0) != 0 THEN 1 ELSE 0 END AS manual_locked
        FROM channel c
        """ + ChannelDao.EFFECTIVE_GROUP_JOINS + """
        WHERE c.missing_since IS NULL AND c.kind IN (0, 1)
          AND (cp.locked = 1 OR gp.locked = 1 OR c.is_adult = 1 OR g.is_adult = 1)
        """
    }
}

/**
 * (reviewed 2026-10-16, B1) The guard every tune funnels through in the controller (WP-A's calls): ALLOWED for OPEN and
 * for content that is not locked; NEEDS_PIN for locked content under LOCKED_*; NOT_PROTECTED under FILTERED_NO_PIN unless
 * the channel's group was opened anyway. Main-safe: one map lookup in [LockedChannelIndex]'s snapshot.
 */
@Singleton
class TuneGuardImpl @Inject constructor(
    private val index: LockedChannelIndex,
    private val session: ParentalSessionImpl,
) : TuneGuard {
    init {
        index.start()
    }

    override fun check(identity: ChannelIdentity): TuneVerdict = verdict(index, session.state.value, identity)

    override val ready: Boolean get() = index.loaded

    override suspend fun awaitReady(timeoutMs: Long) {
        index.start()
        if (index.loaded) return
        withTimeoutOrNull(timeoutMs) { index.version.first { index.loaded } }
    }

    companion object {
        fun verdict(index: LockedChannelIndex, state: ParentalState, identity: ChannelIdentity): TuneVerdict {
            val access = state.access
            if (access == ParentalAccess.OPEN) return TuneVerdict.ALLOWED
            // D-S1 (§3.9.6 fail closed): before the lock data loaded once nothing is known to be unlocked.
            index.start()
            if (!index.loaded) return TuneVerdict.NEEDS_PIN
            if (!index.locked(identity, access)) return TuneVerdict.ALLOWED
            if (access == ParentalAccess.FILTERED_NO_PIN) {
                val g = index.groupOf(identity)
                return if (g != null && g in state.openedAnyway) TuneVerdict.ALLOWED else TuneVerdict.NOT_PROTECTED
            }
            return TuneVerdict.NEEDS_PIN
        }
    }
}
