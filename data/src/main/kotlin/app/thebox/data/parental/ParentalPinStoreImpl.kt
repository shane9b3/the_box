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
import app.thebox.data.di.IoDispatcher
import app.thebox.data.security.KeyResetFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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

    override fun read(): LockoutPolicy.Attempts = LockoutPolicy.Attempts(
        failures = prefs.getInt(K_FAILURES, 0),
        lockedAtElapsedMs = prefs.getLong(K_ELAPSED, -1L).takeIf { it >= 0 },
        lockedAtWallMs = prefs.getLong(K_WALL, -1L).takeIf { it >= 0 },
        lockedAtBoot = prefs.getInt(K_BOOT, -1).takeIf { it >= 0 },
        waitMs = prefs.getLong(K_WAIT, 0L),
    )

    /** Synchronous (`commit`, on IO): a process kill right after a wrong PIN must not reset the counter. */
    override fun write(a: LockoutPolicy.Attempts) {
        prefs.edit()
            .putInt(K_FAILURES, a.failures)
            .putLong(K_ELAPSED, a.lockedAtElapsedMs ?: -1L)
            .putLong(K_WALL, a.lockedAtWallMs ?: -1L)
            .putInt(K_BOOT, a.lockedAtBoot ?: -1)
            .putLong(K_WAIT, a.waitMs)
            .commit()
    }

    companion object {
        /** The same file as `PrefsKeyResetFlag` (§4.1). */
        const val PREFS = "parental_attempts"
        const val K_FAILURES = "failures"
        const val K_ELAPSED = "locked_elapsed"
        const val K_WALL = "locked_wall"
        const val K_BOOT = "locked_boot"
        const val K_WAIT = "wait_ms"
    }
}

/** Elapsed-realtime and the boot count for [LockoutPolicy] (§3.9.4, §9 F26). */
fun interface LockoutClock {
    fun now(wallMs: Long): LockoutPolicy.Clock
}

class AndroidLockoutClock @Inject constructor(@ApplicationContext private val context: Context) : LockoutClock {
    override fun now(wallMs: Long): LockoutPolicy.Clock {
        val boot = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
        } catch (e: RuntimeException) {
            null // F26 unverified on Fire OS: unknown boot = treat as another boot (wall clock capped at the step)
        }
        // The debug harness's clock offset (DebugOverrides, §6.5; 0 in release builds) moves the elapsed clock too.
        return LockoutPolicy.Clock(SystemClock.elapsedRealtime() + app.thebox.core.diag.DebugOverrides.clockOffsetMs, wallMs, boot)
    }
}

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
) : ParentalPinStore {
    private val mutex = Mutex()
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
                val a = attempts.read()
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
        LockoutPolicy.lockedUntilWallMs(attempts.read(), clock.now(nowMs))
    }

    private companion object {
        const val TAG = "ParentalPin"
    }
}
