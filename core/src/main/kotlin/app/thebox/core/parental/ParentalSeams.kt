package app.thebox.core.parental

import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain

/*
 * WP-C seams around the §2.6 contract (docs/design/m5-setup-settings.md §3.9). Not contract files: they live in WP-C's
 * package and carry what the contract leaves out (the "not protected" card's Open anyway, PIN creation from the guide,
 * the lockout countdown on open, "never relock mid-programme"). Each is listed as a proposed amendment in §1.6a.
 */

/** The `parental` settings domain (§4.1). Backed up (the policy, never the PIN). WP-A references it, never redefines it. */
val ParentalDomain: SettingsDomain<ParentalPolicy> = SettingsDomain(
    name = Domains.PARENTAL,
    serializer = ParentalPolicy.serializer(),
    default = ParentalPolicy(),
)

/**
 * Session operations the §2.6 [ParentalSession] interface does not name. `:data`'s `ParentalSessionImpl` implements
 * both; the UI injects this one. Main-safe, non-blocking.
 */
interface ParentalControls {
    /** "Open anyway" on the not-protected card (FILTERED_NO_PIN): [source] joins `openedAnyway` until the app closes. */
    fun openAnyway(source: GuideSource)
    /** "Open anyway" for a refused tune: the channel's group joins `openedAnyway` (resolved on IO). */
    fun openAnyway(identity: ChannelIdentity)
    /**
     * What plays now (null = nothing). Drives "never relock mid-programme" (a timer expiry waits for the user to tune
     * away from locked content) and ASK_EVERY_TIME's "left the unlocked content" relock.
     */
    fun watching(identity: ChannelIdentity?)
    /** Settings closed (ASK_EVERY_TIME relocks when the user leaves Settings). */
    fun leftSettings() = Unit
    /** Channel menu Lock / Unlock: an explicit `channel_prefs.locked` (unlock = an explicit 0, S5). Runs on IO. */
    suspend fun setChannelLocked(identity: ChannelIdentity, locked: Boolean) = Unit
    /** Group menu Lock / Unlock: an explicit `group_prefs.locked`. Runs on IO. */
    suspend fun setGroupLocked(source: GuideSource.Group, locked: Boolean) = Unit

    companion object {
        val NONE: ParentalControls = object : ParentalControls {
            override fun openAnyway(source: GuideSource) = Unit
            override fun openAnyway(identity: ChannelIdentity) = Unit
            override fun watching(identity: ChannelIdentity?) = Unit
        }
    }
}

/**
 * What the guide machine's PIN layer asks the host for, beyond `GuideEffects.verifyPin`. Implemented in `:ui`
 * (`ParentalEffectsImpl`); answers come back through [ParentalHost.parentalAnswer] on main.
 */
interface ParentalActions {
    /** Stores a new 4-digit PIN (CREATE_CONFIRM matched) on IO; zeroes [digits]; answers [ParentalAnswer.PinStored]. */
    fun storePin(digits: CharArray)
    /** The running lockout, for a prompt opened during one; answers [ParentalAnswer.LockedUntil]. */
    fun queryLockout()
    fun openAnyway(source: GuideSource)
    fun openAnyway(identity: ChannelIdentity)
}

sealed interface ParentalAnswer {
    data class PinStored(val ok: Boolean) : ParentalAnswer
    data class LockedUntil(val untilMs: Long?) : ParentalAnswer
}

/**
 * The guide machine's PIN layer (decision §0.9): one prompt, one lockout counter. `GuideStateMachineImpl` implements
 * it; the host sets [parentalActions] once and routes controller refusals (`Stopped(LOCKED)`, `PreviousResult.Locked`)
 * here as [PinGate.request] with a `Tune` purpose.
 */
interface ParentalHost : PinGate {
    var parentalActions: ParentalActions?
    fun parentalAnswer(answer: ParentalAnswer)
    /** The Parental page's "Set a PIN" (create twice) or "Change PIN" (verify, then create twice), §3.3.8. */
    fun startPinSetup(change: Boolean)
}

/**
 * Message codes the PIN layer puts into `PinPromptState.message` (the view maps them to `strings_parental.xml`; :core
 * holds no user text). [wrong] carries the tries left when ≤ 2.
 */
object PinMessage {
    const val WRONG = "wrong"
    const val LOCKED_OUT = "locked_out"
    const val UNAVAILABLE = "unavailable"
    const val MISMATCH = "mismatch"
    const val STORE_FAILED = "store_failed"
    /** The soft "Easy to guess. Use it anyway?" card; `focusedKey` is the focused button (0 = Use it, 1 = Choose another). */
    const val EASY = "easy"
    /** FILTERED_NO_PIN's "This group is not protected" card; `focusedKey` 0 = Set a PIN, 1 = Open anyway. */
    const val NOT_PROTECTED = "not_protected"

    fun wrong(triesLeft: Int): String = if (triesLeft in 1..2) "$WRONG:$triesLeft" else WRONG

    /** The tries left a [wrong] code carries, or null. */
    fun triesLeft(message: String?): Int? = message?.takeIf { it.startsWith("$WRONG:") }?.substringAfter(':')?.toIntOrNull()
}

/** True when [this] prompt is the not-protected card rather than the pad. */
val PinPromptState.isNotProtectedCard: Boolean get() = message == PinMessage.NOT_PROTECTED
/** True when [this] prompt shows the "Easy to guess" soft confirm. */
val PinPromptState.isEasyConfirm: Boolean get() = message == PinMessage.EASY

/** Access helpers shared by the view models, the library decorator and the machines (§3.9.1, §4.3, S10). */
object ParentalRules {
    /** The access a lineup of [source] is queried with: a source opened anyway in FILTERED_NO_PIN is queried with OPEN. */
    fun accessFor(state: ParentalState, source: GuideSource?): ParentalAccess {
        val a = state.access
        if (a == ParentalAccess.FILTERED_NO_PIN && source != null && source in state.openedAnyway) return ParentalAccess.OPEN
        return a
    }

    /**
     * The Groups panel / channel-list entries for [state] (§3.9.1): with "Locked channels in guide: Hide" locked groups
     * are left out, except in FILTERED_NO_PIN (otherwise nothing could reach them without a PIN, the M3 trap).
     */
    fun visibleEntries(entries: List<GuideSourceEntry>, state: ParentalState): List<GuideSourceEntry> {
        if (state.policy.lockedInGuide != LockedInGuide.HIDE || state.access == ParentalAccess.FILTERED_NO_PIN) return entries
        if (entries.none { it.locked }) return entries
        return entries.filterNot { it.locked && it.section == SourceSection.GROUP }
    }

    /** True when OK on [entry] needs the PIN or the not-protected card first. */
    fun needsDoor(entry: GuideSourceEntry, state: ParentalState): Boolean =
        entry.locked && state.access != ParentalAccess.OPEN && entry.source !in state.openedAnyway
}
