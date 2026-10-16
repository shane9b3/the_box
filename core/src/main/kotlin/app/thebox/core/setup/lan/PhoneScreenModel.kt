package app.thebox.core.setup.lan

import app.thebox.core.input.BoxAction

/** The phone setup screen's own focus state (docs/design/m5-setup-settings.md §3.5.1). */
data class PhoneScreenState(
    /** "I can't scan": the URL and code drawn large instead of the QR. */
    val large: Boolean = false,
    /** Focus on the "Type on the TV instead" row (only while it is shown). */
    val typeOnTvFocused: Boolean = false,
)

enum class PhoneScreenEffect { TYPE_ON_TV, LEAVE }

/**
 * Keys of the phone screen (§3.5.1 table), pure: OK toggles the large view (or, on the "Type on the TV instead" row,
 * leaves for the form); ↓/↑ move between the main block and that row when it is shown; BACK stops the server and
 * returns. WP-A's machine hands the screen's actions here and runs the effect.
 */
object PhoneScreenModel {
    fun act(s: PhoneScreenState, action: BoxAction, typeOnTvShown: Boolean): Pair<PhoneScreenState, PhoneScreenEffect?> = when (action) {
        BoxAction.SELECT -> if (s.typeOnTvFocused && typeOnTvShown) s to PhoneScreenEffect.TYPE_ON_TV else s.copy(large = !s.large) to null
        BoxAction.DOWN -> s.copy(typeOnTvFocused = typeOnTvShown) to null
        BoxAction.UP -> s.copy(typeOnTvFocused = false) to null
        BoxAction.BACK -> s to PhoneScreenEffect.LEAVE
        else -> s to null
    }

    /** The amber isolation hint replaces the footer after [LanLimits.NO_REQUEST_HINT_MS] with no request at all. */
    fun showIsolationHint(state: LanServerState, shownAtMs: Long, nowMs: Long): Boolean =
        state.lastRequestAtMs == null && state.phase == ServerPhase.WAITING && nowMs - shownAtMs >= LanLimits.NO_REQUEST_HINT_MS

    /** "Type on the TV instead" is offered with the hint, and at once while a VPN is on (S4). */
    fun showTypeOnTv(hint: Boolean, vpn: Boolean, purpose: app.thebox.core.settings.PhonePurpose): Boolean =
        purpose == app.thebox.core.settings.PhonePurpose.SETUP && (hint || vpn)
}
