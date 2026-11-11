package app.thebox.data.parental

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.thebox.core.common.BoxLog
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.PIN_KDF_ITERATIONS
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.PinCheck
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.security.KeyResetFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The persisted wrong-PIN counter (§3.9.4: SharedPreferences `parental_attempts`, not backed up, not restored). */
interface AttemptsStore {
    fun read(): LockoutPolicy.Attempts
    fun write(a: LockoutPolicy.Attempts)
}

@SuppressLint("ApplySharedPref")
@Singleton
class PrefsAttemptsStore @Inject constructor(@ApplicationContext context: Context) : AttemptsStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * (F7, 2026-11-11) Decoding never throws. A **missing** key is its neutral default (a fresh install, or nothing
     * written yet). A **damaged** value (wrong type, or a negative counter or wait) falls back to the worst case for its
     * field, so damage can only lengthen a lockout, never shorten it or reset the ladder (review 2026-11-11 S1):
     * - `failures` → [LockoutPolicy.TOP_FAILURES] (the top step);
     * - `wait_ms` → [LockoutPolicy.WAIT_MAX_MS], re-anchored now;
     * - `locked_elapsed` / `locked_boot` → no anchor, so a running wait is re-anchored now at its full length.
     * A stored wait above 30 min is clamped to 30 min, so damage never locks for longer than the top step.
     *
     * A file written before F7 has the same keys plus `locked_wall`, which is ignored (and removed on the next write);
     * its running wait is then judged by [LockoutPolicy.resolve] on elapsed realtime alone: on the same boot it
     * continues, after a reboot it restarts in full, so an old-format wait never ends earlier than it would have.
     */
    override fun read(): LockoutPolicy.Attempts {
        val failures = int(K_FAILURES, 0)?.takeIf { it >= 0 } ?: LockoutPolicy.TOP_FAILURES
        val storedWait = long(K_WAIT, 0L)?.takeIf { it >= 0 }
        val elapsed = long(K_ELAPSED, -1L) // -1 = no anchor (written so); null = damaged
        val boot = int(K_BOOT, -1)
        val anchorUsable = storedWait != null && elapsed != null && boot != null
        return LockoutPolicy.Attempts(
            failures = failures,
            lockedAtElapsedMs = elapsed?.takeIf { anchorUsable && it >= 0 },
            lockedAtBoot = boot?.takeIf { anchorUsable && it >= 0 },
            waitMs = (storedWait ?: LockoutPolicy.WAIT_MAX_MS).coerceAtMost(LockoutPolicy.WAIT_MAX_MS),
        )
    }

    /** The stored value, [def] when missing, null when it has the wrong type (damage). */
    private fun int(key: String, def: Int): Int? = try { prefs.getInt(key, def) } catch (e: ClassCastException) { null }
    private fun long(key: String, def: Long): Long? = try { prefs.getLong(key, def) } catch (e: ClassCastException) { null }

    /** Synchronous (`commit`, on IO): a process kill right after a wrong PIN must not reset the counter. */
    override fun write(a: LockoutPolicy.Attempts) {
        prefs.edit()
            .putInt(K_FAILURES, a.failures)
            .putLong(K_ELAPSED, a.lockedAtElapsedMs ?: -1L)
            .putInt(K_BOOT, a.lockedAtBoot ?: -1)
            .putLong(K_WAIT, a.waitMs)
            .remove(K_WALL_LEGACY)
            .commit()
    }

    companion object {
        /** The same file as `PrefsKeyResetFlag` (§4.1). */
        const val PREFS = "parental_attempts"
        const val K_FAILURES = "failures"
        const val K_ELAPSED = "locked_elapsed"
        /** Written before F7 (2026-11-11): the wall clock at the start of the wait. Ignored; removed on write. */
        const val K_WALL_LEGACY = "locked_wall"
        const val K_BOOT = "locked_boot"
        const val K_WAIT = "wait_ms"
    }
}

/**
 * Elapsed realtime and the boot count for [LockoutPolicy] (§3.9.4, §9 F26). The wall clock passed in is only carried
 * through for the countdown display; the lockout decision never reads it (F7).
 */
fun interface LockoutClock {
    fun now(wallMs: Long): LockoutPolicy.Clock
}

class AndroidLockoutClock @Inject constructor(@ApplicationContext private val context: Context) : LockoutClock {
    override fun now(wallMs: Long): LockoutPolicy.Clock {
        val boot = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
        } catch (e: RuntimeException) {
            null // F26 unverified on Fire OS: unknown boot = only "elapsed went backwards" detects a reboot (LockoutPolicy)
        }
        return LockoutPolicy.Clock(elapsedWithDebugOffset(), wallMs, boot)
    }
}

/**
 * Elapsed realtime plus the debug harness's clock offset (DebugOverrides, §6.5; 0 in release builds), never negative:
 * a negative offset larger than the uptime would otherwise give a negative anchor, which is stored as "no anchor" and
 * re-anchored on every read, so the wait would never end (review 2026-11-11 L2).
 */
internal fun elapsedWithDebugOffset(): Long =
    (SystemClock.elapsedRealtime() + app.thebox.core.diag.DebugOverrides.clockOffsetMs).coerceAtLeast(0L)

/**
 * The parental PIN (docs/design/m5-setup-settings.md §2.6, §3.9.4, §3.9.6): a PBKDF2 verifier ([PinVerifier]) inside the
 * Keystore-wrapped AEAD (`SecureBlobStore`, ref `parental-pin`), compared in constant time; the lockout counter in
 * `parental_attempts`. Every PBKDF2 and store access runs on [io]; checks are serialised (one at a time), so two quick
 * submits cannot both slip under the counter. PINs and digits are never logged; every array is zeroed.
 */
@Singleton
class ParentalPinStoreImpl @Inject constructor(
    private val blobs: SecureBlobStore,
    private val attempts: AttemptsStore,
    private val clock: LockoutClock,
    private val keyReset: KeyResetFlag,
    @IoDispatcher private val io: CoroutineDispatcher,
    /** (F7) Clears a finished wait when it runs out while the process lives; null in tests (reads still settle it). */
    @ApplicationScope private val scope: CoroutineScope? = null,
) : ParentalPinStore {
    private val mutex = Mutex()
    private var settleJob: Job? = null
    private val _pinSet = MutableStateFlow<Boolean?>(null)
    /** null until the first [isSet]; the session treats unknown as "no PIN" (content filtered, FILTERED_NO_PIN). */
    val pinSet: StateFlow<Boolean?> = _pinSet.asStateFlow()

    /** Iterations for new verifiers (tests lower it; the count is stored in the blob). */
    internal var iterations: Int = PIN_KDF_ITERATIONS

    override suspend fun isSet(): Boolean = withContext(io) {
        val set = when (val r = blobs.getBlob(SecureBlobStore.REF_PARENTAL_PIN)) {
            is Outcome.Ok -> { r.value.fill(0); true }
            is Outcome.Err -> when ((r.error as? SourceError.Credentials)?.reason) {
                CredentialFailure.MISSING, CredentialFailure.KEY_INVALIDATED -> false
                // Keystore unavailable or a row that does not decrypt: a PIN exists, nothing unlocks (fail closed).
                else -> true
            }
        }
        _pinSet.value = set
        set
    }

    override suspend fun set(pin: CharArray): Outcome<Unit> = withContext(io) {
        try {
            if (!PinVerifier.valid(pin)) return@withContext Outcome.Err(SourceError.Internal("PIN must be 4 digits"))
            val blob = PinVerifier.create(pin, iterations)
            val r = blobs.putBlob(SecureBlobStore.REF_PARENTAL_PIN, blob)
            blob.fill(0)
            if (r is Outcome.Ok) {
                mutex.withLock { attempts.write(LockoutPolicy.onCorrect()) }
                runCatching { keyReset.clearPinLost() }.onFailure { BoxLog.w(TAG, "key-reset flag not cleared", it) }
                _pinSet.value = true
            }
            r
        } finally {
            pin.fill('\u0000')
        }
    }

    override suspend fun verify(pin: CharArray, nowMs: Long): PinCheck = withContext(io) {
        try {
            mutex.withLock {
                val now = clock.now(nowMs)
                val a = current(now)
                LockoutPolicy.lockedUntilWallMs(a, now)?.let { return@withLock PinCheck.LockedOut(it) }
                val blob = when (val r = blobs.getBlob(SecureBlobStore.REF_PARENTAL_PIN)) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> {
                        if ((r.error as? SourceError.Credentials)?.reason == CredentialFailure.KEY_INVALIDATED) _pinSet.value = false
                        return@withLock PinCheck.Unavailable
                    }
                }
                val v = PinVerifier.parse(blob)
                blob.fill(0)
                if (v == null) {
                    BoxLog.w(TAG, "PIN verifier unreadable")
                    return@withLock PinCheck.Unavailable
                }
                val ok = PinVerifier.matches(pin, v)
                v.hash.fill(0)
                if (ok) {
                    attempts.write(LockoutPolicy.onCorrect())
                    PinCheck.Ok
                } else {
                    val (next, check) = LockoutPolicy.onWrong(a, now)
                    attempts.write(next)
                    scheduleSettle(next.waitMs)
                    check
                }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    override suspend fun clear() = withContext(io) {
        blobs.deleteBlob(SecureBlobStore.REF_PARENTAL_PIN)
        mutex.withLock { attempts.write(LockoutPolicy.Attempts()) }
        _pinSet.value = false
    }

    override suspend fun lockedUntil(nowMs: Long): Long? = withContext(io) {
        mutex.withLock {
            val now = clock.now(nowMs)
            val until = LockoutPolicy.lockedUntilWallMs(current(now), now)
            if (until != null) scheduleSettle(until - now.wallMs)
            until
        }
    }

    /**
     * (F7) Brings the persisted lockout up to date (re-anchors a wait after a reboot, clears a finished one) without a
     * PIN check: the session calls it on start and on every return to the foreground, so a wait that ran out is
     * recorded as served before a later reboot could restart it.
     */
    suspend fun settleLockout(nowMs: Long) {
        withContext(io) {
            mutex.withLock {
                val now = clock.now(nowMs)
                val remaining = LockoutPolicy.remainingMs(current(now), now)
                if (remaining > 0) scheduleSettle(remaining)
            }
        }
    }

    /** The persisted attempts resolved at [now] ([LockoutPolicy.resolve]); writes them back when they changed. Under [mutex]. */
    private fun current(now: LockoutPolicy.Clock): LockoutPolicy.Attempts {
        val stored = attempts.read()
        val resolved = LockoutPolicy.resolve(stored, now)
        if (resolved != stored) attempts.write(resolved)
        return resolved
    }

    /** Settles the wait just after it runs out, if the process is still alive then. Under [mutex]; one pending at a time. */
    private fun scheduleSettle(remainingMs: Long) {
        val s = scope ?: return
        if (remainingMs <= 0) return
        settleJob?.cancel()
        settleJob = s.launch {
            delay(remainingMs + SETTLE_SLACK_MS)
            runCatching { settleLockout(app.thebox.core.diag.DebugOverrides.nowMs()) }
                .onFailure { if (it is CancellationException) throw it; BoxLog.w(TAG, "lockout settle failed: ${it.javaClass.simpleName}") }
        }
    }

    internal companion object {
        const val TAG = "ParentalPin"
        const val SETTLE_SLACK_MS = 1_000L
    }
}
