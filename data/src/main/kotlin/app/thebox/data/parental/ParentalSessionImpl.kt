package app.thebox.data.parental

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import app.thebox.core.common.BoxLog
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalControls
import app.thebox.core.parental.ParentalDomain
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.RelockReason
import app.thebox.core.parental.StayUnlocked
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.SettingsStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import app.thebox.data.repo.exec
import app.thebox.data.repo.writeTransaction
import app.thebox.data.security.KeyResetFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The parental session (docs/design/m5-setup-settings.md §2.6, §3.9.1, §3.9.5, §3.9.6; reviewed B1, B3, S8).
 *
 * [state] = the PIN's presence ([ParentalPinStoreImpl.pinSet]; D-S1: while unknown it is published as set and locked,
 * so access is LOCKED_* and every lock holds until the first read lands), the policy (the
 * `parental` settings domain), `unlocked`, `pinLostToKeyReset` ([KeyResetFlag]) and `openedAnyway`.
 *
 * Stay unlocked: **Until The Box closes** relocks on app stop; **1 hour** at unlock + 60 min or app stop; **Ask every
 * time** when the user leaves the unlocked content (a tune outside locked content, leaving Settings) or app stop. Only
 * a timer expiry in the foreground waits for the user to tune away from locked content that is playing (a programme is
 * never cut off mid-watch, B1); an app stop is never "mid-programme" (resume goes through the guard and asks). App stop
 * = the last started activity stopped (`ActivityLifecycleCallbacks`; `ProcessLifecycleOwner` is not a `:data`
 * dependency); `openedAnyway` empties then.
 *
 * Thread-safe; [unlock], [relock] and [onTick] are main-thread calls that never block.
 */
@Singleton
class ParentalSessionImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsStore,
    private val pins: ParentalPinStoreImpl,
    private val keyReset: KeyResetFlag,
    private val index: LockedChannelIndex,
    private val db: LibraryDatabase,
    private val groupDao: GroupDao,
    private val wall: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ParentalSession, ParentalControls {

    // D-S1: the PIN's presence is unknown until the first read; fail closed (a PIN, locked) until then.
    private val _state = MutableStateFlow(ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy()))
    override val state: StateFlow<ParentalState> = _state.asStateFlow()

    private val lock = Any()
    private var pinSet = false
    /** D-S1: false until [ParentalPinStoreImpl.pinSet] reported a definite answer. */
    private var pinKnown = false
    private var policy = ParentalPolicy()
    private var pinLost = false
    private var unlocked = false
    private var unlockedAtMs = 0L
    private var openedAnyway: Set<GuideSource> = emptySet()
    /** A 1-hour expiry arrived while locked content plays: relock once the user tunes away (B1). */
    private var timeoutPending = false
    private var watchingLocked = false
    private var startedActivities = 0

    init {
        scope.launch {
            settings.flow(ParentalDomain).collect { p -> synchronized(lock) { policy = p }; publish() }
        }
        scope.launch {
            pins.pinSet.collect { set ->
                val now = set == true
                synchronized(lock) {
                    if (pinSet && !now) unlocked = false // the PIN went away (removed, or lost to a key reset)
                    pinSet = now
                    pinKnown = set != null
                }
                publish()
            }
        }
        refresh()
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(lock) { startedActivities++ }
                refresh()
            }
            override fun onActivityStopped(activity: Activity) {
                val stopped = synchronized(lock) { startedActivities = (startedActivities - 1).coerceAtLeast(0); startedActivities == 0 }
                if (stopped) relock(RelockReason.APP_STOPPED)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Re-reads the PIN's presence and the key-reset flag on IO (start, every return to the foreground). */
    fun refresh() {
        scope.launch {
            try {
                withContext(io) {
                    pins.isSet()
                    val lost = runCatching { keyReset.pinLost() }.getOrDefault(false)
                    synchronized(lock) { pinLost = lost }
                }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "parental refresh failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun publish() {
        val s = synchronized(lock) {
            ParentalState(
                // D-S1: an unknown PIN is published as set and locked (LOCKED_*), never as OPEN.
                pinSet = pinSet || !pinKnown,
                unlocked = unlocked && pinSet && pinKnown,
                policy = policy,
                pinLostToKeyReset = pinLost && !pinSet && pinKnown,
                openedAnyway = openedAnyway,
            )
        }
        if (s != _state.value) _state.value = s
    }

    override fun unlock(nowMs: Long) {
        synchronized(lock) {
            if (!pinSet) return
            unlocked = true
            unlockedAtMs = nowMs
            timeoutPending = false
        }
        publish()
    }

    override fun relock(reason: RelockReason) {
        synchronized(lock) {
            unlocked = false
            timeoutPending = false
            if (reason == RelockReason.APP_STOPPED) openedAnyway = emptySet()
        }
        publish()
    }

    override fun onTick(nowMs: Long) {
        val expire = synchronized(lock) {
            if (!unlocked || policy.stayUnlocked != StayUnlocked.ONE_HOUR || nowMs - unlockedAtMs < ONE_HOUR_MS) return
            if (watchingLocked) { timeoutPending = true; false } else true
        }
        if (expire) relock(RelockReason.TIMEOUT)
    }

    // ---- ParentalControls

    override fun openAnyway(source: GuideSource) {
        synchronized(lock) { openedAnyway = openedAnyway + source }
        publish()
    }

    override fun openAnyway(identity: ChannelIdentity) {
        val g = index.groupOf(identity) ?: GuideSource.All(identity.playlistId)
        openAnyway(g)
    }

    override fun watching(identity: ChannelIdentity?) {
        val locked = identity != null && index.lockedContent(identity)
        val reason = synchronized(lock) {
            watchingLocked = locked
            when {
                !unlocked -> null
                timeoutPending && !locked -> RelockReason.TIMEOUT
                policy.stayUnlocked == StayUnlocked.ASK_EVERY_TIME && identity != null && !locked -> RelockReason.LEFT_LOCKED_CONTENT
                else -> null
            }
        }
        if (reason != null) relock(reason)
    }

    override fun leftSettings() {
        val ask = synchronized(lock) { unlocked && policy.stayUnlocked == StayUnlocked.ASK_EVERY_TIME && !watchingLocked }
        if (ask) relock(RelockReason.LEFT_LOCKED_CONTENT)
    }

    /** Channel menu Lock / Unlock (§3.9.1): an explicit `channel_prefs.locked` (unlock = 0 overrides detection, S5). */
    override suspend fun setChannelLocked(identity: ChannelIdentity, locked: Boolean) = withContext(io) {
        db.writeTransaction {
            exec(
                "INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, watch_count, updated_at) VALUES (?, ?, 0, ?, 0, ?) " +
                    "ON CONFLICT(playlist_id, stable_key) DO UPDATE SET locked = excluded.locked, updated_at = excluded.updated_at",
            ) {
                bindLong(1, identity.playlistId)
                bindText(2, identity.stableKey.value)
                bindLong(3, if (locked) 1L else 0L)
                bindLong(4, wall.now())
            }
        }
    }

    /**
     * Group menu Lock / Unlock (§3.9.1): an explicit `group_prefs.locked` (an explicit unlock wins over detection, S5).
     * C-B2 (defence in depth): an unlock is refused while a PIN is set and the session is not unlocked; every UI path asks
     * for the PIN first (a granted PIN unlocks the session), so only a path that skipped it lands here.
     */
    override suspend fun setGroupLocked(source: GuideSource.Group, locked: Boolean) = withContext(io) {
        if (!locked && refusesLowering()) {
            BoxLog.w(TAG, "group unlock refused: the session is locked")
            return@withContext
        }
        groupDao.upsertGroupLocked(source.playlistId, 0, source.groupKey, locked, wall.now())
    }

    /**
     * C-B2 / D-B1 (§3.9.2): true while a PIN is set (or not yet known, D-S1) and the session is not unlocked; writes that
     * lower protection (group unlock, a group-prefs reset, a restore) refuse then.
     */
    fun refusesLowering(): Boolean = _state.value.let { it.pinSet && it.access != app.thebox.core.parental.ParentalAccess.OPEN }

    companion object {
        private const val TAG = "ParentalSession"
        const val ONE_HOUR_MS = 3_600_000L
    }
}
