package app.thebox.core.update

import app.thebox.core.input.BoxAction

/**
 * The S17 update card's pure rules (docs/design/m5-setup-settings.md §3.12.3, docs/design/m5-update-visible-install.md),
 * so the guide machine only wires them: which [UpdateState] shows a card in which [UpdateCardPhase], and what a key does
 * while the card holds focus. Buttons in order: Update (0) · Later (1) · Skip this version (2); ←/→ move, OK presses,
 * BACK = Later. Busy phases (downloading, checking the download, opening the installer) show no buttons: BACK hides the
 * card and the work carries on, every other key is the guide's.
 */
object UpdateCardModel {
    const val BUTTONS = 3

    /**
     * The card for [state], or null when none is shown. [later]: the user pressed Later / BACK this process; it hides
     * every phase except FAILED and NOT_INSTALLED, which must reach the user with a reason and a next step (review S2).
     * [previous] is the last [baseCard] (not the filtered one), so a card hidden by BACK during a download still has its
     * version and notes when a failure brings it back. [failedText] is resolved by the caller (`:ui` strings).
     */
    fun cardFor(state: UpdateState, later: Boolean, previous: UpdateCardState?, failedText: (UpdateFailure) -> String?): UpdateCardState? =
        baseCard(state, previous, failedText)?.takeIf { !hiddenBy(it.phase, later) }

    /**
     * Whether Later / BACK hides a card in [phase]. FAILED, NOT_INSTALLED and NEEDS_PERMISSION (code review S3: the
     * user comes back from the unknown-sources screen and must see "press Update again") always show.
     */
    fun hiddenBy(phase: UpdateCardPhase, later: Boolean): Boolean =
        later && phase != UpdateCardPhase.FAILED && phase != UpdateCardPhase.NOT_INSTALLED && phase != UpdateCardPhase.NEEDS_PERMISSION

    /**
     * The updater's card stream: folds each state into a card, keeping the unfiltered card as the next one's previous
     * (review S2), then applies Later. One instance per stream; not thread-safe (the updater folds sequentially).
     */
    class Fold(private val failedText: (UpdateFailure) -> String?) {
        var previous: UpdateCardState? = null
            private set

        fun next(state: UpdateState, later: Boolean): UpdateCardState? {
            val base = baseCard(state, previous, failedText)
            previous = base
            return base?.takeIf { !hiddenBy(it.phase, later) }
        }
    }

    /** The card [state] stands for, before the Later filter. */
    fun baseCard(state: UpdateState, previous: UpdateCardState?, failedText: (UpdateFailure) -> String?): UpdateCardState? {
        val focused = previous?.focused ?: 0
        val notes = previous?.notes.orEmpty()
        return when (state) {
            is UpdateState.Available -> UpdateCardState(state.manifest.versionName, firstLine(state.manifest.notes), focused = focused, phase = UpdateCardPhase.OFFER)
            is UpdateState.Downloading -> UpdateCardState(
                state.versionName, notes,
                progress = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f, focused = focused,
                phase = UpdateCardPhase.DOWNLOADING,
            )
            is UpdateState.Verifying -> UpdateCardState(state.versionName, notes, focused = focused, phase = UpdateCardPhase.VERIFYING)
            is UpdateState.NeedsPermission -> UpdateCardState(state.versionName, notes, focused = focused, phase = UpdateCardPhase.NEEDS_PERMISSION)
            // Verified and handed to the installer (or waiting for the app to come to the front to do so, review S6).
            is UpdateState.ReadyToInstall -> UpdateCardState(state.versionName, notes, focused = focused, phase = UpdateCardPhase.OPENING_INSTALLER)
            UpdateState.Installing -> previous?.copy(progress = null, failed = null, phase = UpdateCardPhase.OPENING_INSTALLER)
            // Back from the installer without the update: the buttons return with Update focused (review §6).
            is UpdateState.NotInstalled -> UpdateCardState(
                state.versionName, notes,
                focused = if (previous?.phase == UpdateCardPhase.NOT_INSTALLED) focused else 0,
                phase = UpdateCardPhase.NOT_INSTALLED,
            )
            // A failure after the user pressed Update stays on the card with its reason; check failures never show one.
            is UpdateState.Failed -> previous?.let { p -> failedText(state.reason)?.let { p.copy(failed = it, progress = null, phase = UpdateCardPhase.FAILED) } }
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

    fun onAction(card: UpdateCardState, action: BoxAction): KeyResult {
        // Busy: no buttons. BACK hides the card (the guide calls Later; the work goes on); OK and the arrows are the
        // guide's (an OK then tunes the focused channel, which the hand-off stops again: accepted, review §6).
        if (card.phase.busy) return if (action == BoxAction.BACK) KeyResult.Pressed(UpdateAction.LATER) else KeyResult.NotMine
        return when (action) {
            BoxAction.LEFT -> KeyResult.Moved(card.copy(focused = (card.focused - 1).coerceAtLeast(0)))
            BoxAction.RIGHT -> KeyResult.Moved(card.copy(focused = (card.focused + 1).coerceAtMost(BUTTONS - 1)))
            BoxAction.UP, BoxAction.DOWN -> KeyResult.Moved(card)
            BoxAction.SELECT -> KeyResult.Pressed(buttonAt(card.focused))
            BoxAction.BACK -> KeyResult.Pressed(UpdateAction.LATER)
            else -> KeyResult.NotMine
        }
    }

    fun buttonAt(index: Int): UpdateAction = when (index.coerceIn(0, BUTTONS - 1)) {
        0 -> UpdateAction.UPDATE
        1 -> UpdateAction.LATER
        else -> UpdateAction.SKIP
    }

    /** The card shows one line of the release notes ("Fixes catch-up on Flussonic servers"). */
    fun firstLine(notes: String): String = notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)
}
