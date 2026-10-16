// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.6. Verbatim; changes go through the lead.
package app.thebox.core.parental

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSourceEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * (M5 commit 0, S15; Appendix B) PBKDF2-HMAC-SHA256 iterations of the PIN verifier, stored in the blob (§3.9.6), so a
 * later change needs no migration. Set by the commit-0 microbenchmark on `TheBox_TV_API30` (x86, 4 cores, API 30;
 * `app_process`, ART JIT, 3 warm-ups then 5 runs, host under build load), for the design's RFC 8018 loop over
 * `Mac("HmacSHA256")` (Conscrypt): 10,000 → max 80–82 ms (median 68–76); 20,000 → max 141–175 ms, over
 * [PIN_KDF_BUDGET_MS]. `SecretKeyFactory("PBKDF2WithHmacSHA256")` is absent on API 25 (verified on an API 25 emulator).
 * A pure-Java HMAC-SHA256 with precomputed pads measured 50,000 → max 73 ms; switching to it is a lead decision.
 * Re-measured on the Fire TV Stick 4K in M6.
 */
const val PIN_KDF_ITERATIONS = 10_000
/** (S15) Emulator proxy budget of one PIN check (Appendix B). */
const val PIN_KDF_BUDGET_MS = 150L

enum class LockedInGuide { SHOW_WITH_LOCK, HIDE }
enum class StayUnlocked { UNTIL_APP_CLOSES, ONE_HOUR, ASK_EVERY_TIME }

@kotlinx.serialization.Serializable
data class ParentalPolicy(
    val autoLockAdult: Boolean = true,
    val lockedInGuide: LockedInGuide = LockedInGuide.SHOW_WITH_LOCK,
    val lockSettings: Boolean = false,
    val stayUnlocked: StayUnlocked = StayUnlocked.UNTIL_APP_CLOSES,
)

/**
 * How library queries treat locks (§4.3). Ordinals are bound into SQL: append only.
 * (reviewed 2026-10-16, B3) FILTERED_NO_PIN: no PIN exists and auto-lock is on (or the PIN was lost to a Keystore
 * reset, S8): locked content is kept out of every mixed list exactly as LOCKED_AUTO, but the Groups panel may open a
 * locked group after the "not protected" card ("Open anyway", until the app closes).
 */
enum class ParentalAccess { OPEN, LOCKED_AUTO, LOCKED_MANUAL_ONLY, FILTERED_NO_PIN }

data class ParentalState(
    val pinSet: Boolean,
    val unlocked: Boolean,
    val policy: ParentalPolicy,
    /** Set when a Keystore reset wiped the PIN (§3.9.6); cleared when a new PIN is set. */
    val pinLostToKeyReset: Boolean = false,
    /** Groups opened with "Open anyway" in FILTERED_NO_PIN (until the app closes). */
    val openedAnyway: Set<app.thebox.core.guide.GuideSource> = emptySet(),
) {
    /** Contract body (no stub; reviewed 2026-10-16, B3/S8). */
    val access: ParentalAccess get() = when {
        pinSet && unlocked -> ParentalAccess.OPEN
        pinSet && policy.autoLockAdult -> ParentalAccess.LOCKED_AUTO
        pinSet -> ParentalAccess.LOCKED_MANUAL_ONLY
        policy.autoLockAdult || pinLostToKeyReset -> ParentalAccess.FILTERED_NO_PIN
        else -> ParentalAccess.OPEN
    }
    /** The Parental row shows "On · not protected: set a PIN" (amber) in this state. */
    val unprotected: Boolean get() = access == ParentalAccess.FILTERED_NO_PIN
    /** Feeds and VMs restart their library flows when this changes. */
    val queryKey: Int get() = access.ordinal * 2 + if (policy.lockedInGuide == LockedInGuide.HIDE) 1 else 0
}

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val attemptsBeforeLockout: Int) : PinCheck
    data class LockedOut(val untilMs: Long) : PinCheck
    /** Keystore unavailable right now (CredentialFailure.UNAVAILABLE): nothing unlocks; "Try again" (§3.9.6). */
    data object Unavailable : PinCheck
}

/** :data implements. The verifier is PBKDF2-HMAC-SHA256(pin, salt, PIN_KDF_ITERATIONS) inside the AEAD store. */
interface ParentalPinStore {
    suspend fun isSet(): Boolean
    /** [pin] is exactly 4 digits; zeroed by the caller after the call. */
    suspend fun set(pin: CharArray): app.thebox.core.common.Outcome<Unit>
    suspend fun verify(pin: CharArray, nowMs: Long): PinCheck
    suspend fun clear()
    /** Lockout state for the prompt on open (a prompt opened during a lockout shows the countdown at once). */
    suspend fun lockedUntil(nowMs: Long): Long?
}

interface ParentalSession {
    val state: StateFlow<ParentalState>
    fun unlock(nowMs: Long)
    /** Relock now (app stop, ASK_EVERY_TIME triggers, "Lock now" row). */
    fun relock(reason: RelockReason)
    fun onTick(nowMs: Long)
    /**
     * (M5 integration 2026-10-16, WP-C amendment) "Open anyway" on the not-protected card (FILTERED_NO_PIN): [source]
     * joins [ParentalState.openedAnyway] until the app closes. Default: nothing (fakes).
     */
    fun openAnyway(source: app.thebox.core.guide.GuideSource) = Unit
    /**
     * (M5 integration 2026-10-16, WP-C amendment) What plays now (null = nothing): drives "never relock mid-programme"
     * and ASK_EVERY_TIME's relock when the user leaves locked content. Default: nothing (fakes).
     */
    fun watching(identity: app.thebox.core.refresh.ChannelIdentity?) = Unit
}
enum class RelockReason { APP_STOPPED, TIMEOUT, LEFT_LOCKED_CONTENT, USER, PIN_CHANGED }

// (M5 commit 0) TuneGuard and TuneVerdict of this section live in `core/.../playback/TuneGuard.kt` (package
// app.thebox.core.playback), as its KDoc and §1.9 item 2 name the file; the controller in :player imports them there.

/**
 * What a PIN unlocks; the guide machine routes the result back to [owner] (decision §0.9). In FILTERED_NO_PIN the same
 * purposes open the "not protected" card ([Set a PIN] [Open anyway]) instead of the pad (B3).
 */
sealed interface PinPurpose {
    val owner: PinOwner
    data class OpenSource(val entry: GuideSourceEntry, override val owner: PinOwner) : PinPurpose
    data class Tune(val channel: GuideChannel, override val owner: PinOwner) : PinPurpose
    data class EnterSettings(val route: app.thebox.core.settings.SettingsRoute) : PinPurpose { override val owner = PinOwner.GUIDE }
    data class SettingsAction(val token: Long) : PinPurpose { override val owner = PinOwner.SETTINGS }
    /**
     * (M5 integration 2026-10-16, WP-B/WP-C amendment) Create a PIN (enter twice), or change it ([change]: verify the
     * current one first). The onboarding PIN offer and the Parental page ask the guide machine's one prompt through
     * [PinGate.request]; the answer comes back to the settings machine as `SettingsEvent.PinResult(CreatePin, granted)`.
     */
    data class CreatePin(val change: Boolean = false) : PinPurpose { override val owner = PinOwner.SETTINGS }
}
enum class PinOwner { GUIDE, PLAYER, SETTINGS }

/** Injected by the guide machine into the player and settings machines. */
fun interface PinGate {
    /** False when no PIN is set: the caller proceeds at once. True: a prompt opened; the answer arrives later. */
    fun request(purpose: PinPurpose): Boolean
}

enum class PinPadMode { VERIFY, CREATE_FIRST, CREATE_CONFIRM }
data class PinPromptState(
    val purpose: PinPurpose?,
    val mode: PinPadMode,
    /** Number of digits typed (0–4); the digits themselves stay inside the machine and are never in UI state. */
    val filled: Int,
    /** Focused key of the 3 × 4 grid: 0–8 = 1–9, 9 = ⌫, 10 = 0, 11 = OK. */
    val focusedKey: Int = 4,
    val checking: Boolean = false,
    val wrong: Boolean = false,
    val lockedUntilMs: Long? = null,
    val message: String? = null,
)
