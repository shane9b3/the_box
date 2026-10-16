package app.thebox.data.parental

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.RelockReason
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Commit-0 stubs of WP-C's parental classes (docs/design/m5-setup-settings.md §1.3a, §2.6, §3.9). WP-C replaces each
 * with its own file under data/.../parental/ (ParentalPinStoreImpl, ParentalSessionImpl, TuneGuardImpl) and deletes
 * this one. Behaviour: no PIN exists, nothing can be set, every tune is allowed: the app behaves as before M5.
 */

/** No PIN: [isSet] false, [set] refuses (Unsupported), [verify] answers Unavailable (fail closed). */
@Singleton
class ParentalPinStoreImpl @Inject constructor() : ParentalPinStore {
    override suspend fun isSet(): Boolean = false
    override suspend fun set(pin: CharArray): Outcome<Unit> {
        pin.fill('\u0000')
        return Outcome.Err(SourceError.Unsupported("parental PIN arrives with WP-C"))
    }
    override suspend fun verify(pin: CharArray, nowMs: Long): PinCheck {
        pin.fill('\u0000')
        return PinCheck.Unavailable
    }
    override suspend fun clear() = Unit
    override suspend fun lockedUntil(nowMs: Long): Long? = null
}

/** No PIN, default policy (auto-lock on): access is FILTERED_NO_PIN, which filters exactly like M3's locked lists. */
@Singleton
class ParentalSessionImpl @Inject constructor() : ParentalSession {
    private val _state = MutableStateFlow(ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy()))
    override val state: StateFlow<ParentalState> = _state.asStateFlow()
    override fun unlock(nowMs: Long) = Unit
    override fun relock(reason: RelockReason) = Unit
    override fun onTick(nowMs: Long) = Unit
}

/** Allows every target (the M3 predicate still keeps locked content out of the lists). */
@Singleton
class TuneGuardImpl @Inject constructor() : TuneGuard {
    override fun check(identity: ChannelIdentity): TuneVerdict = TuneVerdict.ALLOWED
}
