package app.thebox.core.update

import app.thebox.core.input.BoxAction

/**
 * The S17 update card's pure rules (docs/design/m5-setup-settings.md §3.12.3), so the guide machine only wires them:
 * which [UpdateState] shows a card, and what a key does while the card holds focus. Buttons in order:
 * Update (0) · Later (1) · Skip this version (2); ←/→ move, OK presses, BACK = Later.
 */
object UpdateCardModel {
    const val BUTTONS = 3

    /**
     * The card for [state], or null when none is shown. [later]: the user pressed Later this process (the card stays
     * away until the next start). [failedText]/[notesLine] are resolved by the caller (`:ui` strings).
     */
    fun cardFor(state: UpdateState, later: Boolean, previous: UpdateCardState?, failedText: (UpdateFailure) -> String?): UpdateCardState? {
        val focused = previous?.focused ?: 0
        return when (state) {
            is UpdateState.Available -> if (later) null else UpdateCardState(state.manifest.versionName, firstLine(state.manifest.notes), focused = focused)
            is UpdateState.Downloading -> UpdateCardState(
                state.versionName, previous?.notes.orEmpty(),
                progress = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f, focused = focused,
            )
            is UpdateState.NeedsPermission -> UpdateCardState(state.versionName, previous?.notes.orEmpty(), focused = focused)
            is UpdateState.ReadyToInstall -> UpdateCardState(state.versionName, previous?.notes.orEmpty(), progress = 1f, focused = focused)
            UpdateState.Installing -> previous?.copy(progress = 1f)
            // A failure after the user pressed Update stays on the card with its reason; check failures never show one.
            is UpdateState.Failed -> previous?.let { p -> failedText(state.reason)?.let { p.copy(failed = it, progress = null) } }
            UpdateState.Idle, UpdateState.Checking, is UpdateState.UpToDate -> null
        }
    }

    sealed interface KeyResult {
        /** The card stays; [card] is its new state (focus moved). */
        data class Moved(val card: UpdateCardState) : KeyResult
        /** A button was pressed. */
        data class Pressed(val action: UpdateAction) : KeyResult
        /** The key is not the card's (the guide handles it as usual). */
        data object NotMine : KeyResult
    }

    fun onAction(card: UpdateCardState, action: BoxAction): KeyResult = when (action) {
        BoxAction.LEFT -> KeyResult.Moved(card.copy(focused = (card.focused - 1).coerceAtLeast(0)))
        BoxAction.RIGHT -> KeyResult.Moved(card.copy(focused = (card.focused + 1).coerceAtMost(BUTTONS - 1)))
        BoxAction.UP, BoxAction.DOWN -> KeyResult.Moved(card)
        BoxAction.SELECT -> KeyResult.Pressed(buttonAt(card.focused))
        BoxAction.BACK -> KeyResult.Pressed(UpdateAction.LATER)
        else -> KeyResult.NotMine
    }

    fun buttonAt(index: Int): UpdateAction = when (index.coerceIn(0, BUTTONS - 1)) {
        0 -> UpdateAction.UPDATE
        1 -> UpdateAction.LATER
        else -> UpdateAction.SKIP
    }

    /** The card shows one line of the release notes ("Fixes catch-up on Flussonic servers"). */
    fun firstLine(notes: String): String = notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)
}
