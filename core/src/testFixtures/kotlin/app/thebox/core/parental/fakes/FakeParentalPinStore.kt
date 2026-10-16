package app.thebox.core.parental.fakes

import app.thebox.core.common.Outcome
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.PinCheck

/**
 * [ParentalPinStore] for JVM tests: the PIN in clear (tests only), a fixed wrong-attempt budget before [lockoutMs],
 * [unavailable] to simulate a Keystore outage. Zeroes every array it is given, like the real store's callers expect.
 */
class FakeParentalPinStore(
    private var pin: String? = null,
    var attemptsBeforeLockout: Int = 3,
    var lockoutMs: Long = 30_000,
) : ParentalPinStore {
    var unavailable = false
    var wrong = 0
    private var lockedUntil: Long? = null

    override suspend fun isSet(): Boolean = pin != null
    override suspend fun set(pin: CharArray): Outcome<Unit> {
        this.pin = String(pin)
        pin.fill('\u0000')
        wrong = 0
        return Outcome.Ok(Unit)
    }
    override suspend fun verify(pin: CharArray, nowMs: Long): PinCheck {
        val typed = String(pin)
        pin.fill('\u0000')
        if (unavailable) return PinCheck.Unavailable
        lockedUntil?.let { if (nowMs < it) return PinCheck.LockedOut(it) }
        if (typed == this.pin) { wrong = 0; lockedUntil = null; return PinCheck.Ok }
        wrong++
        val left = attemptsBeforeLockout - wrong
        if (left <= 0) {
            val until = nowMs + lockoutMs
            lockedUntil = until
            wrong = 0
            return PinCheck.LockedOut(until)
        }
        return PinCheck.Wrong(left)
    }
    override suspend fun clear() { pin = null }
    override suspend fun lockedUntil(nowMs: Long): Long? = lockedUntil?.takeIf { it > nowMs }
}
