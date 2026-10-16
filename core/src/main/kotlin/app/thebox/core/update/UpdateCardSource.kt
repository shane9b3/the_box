package app.thebox.core.update

import kotlinx.coroutines.flow.StateFlow

/**
 * What the guide shows of the updater (S17, §3.12.3): the card (null = none), already filtered for "Later" and resolved
 * to user text, and the one-time "Updated to 1.0.4" notice after a self-update. `:app`'s updater implements it; the
 * guide view model dispatches `GuideEvent.Update(card)` from [card] (the guide machine keeps the card's focus and
 * routes its keys through [UpdateCardModel.onAction]; see the WP-E notes in §1.8a for the wiring the integration adds).
 */
interface UpdateCardSource {
    val card: StateFlow<UpdateCardState?>
    /** Non-null once after the app was updated by the updater ("Updated to 1.0.4"); [noticeShown] clears it. */
    val updatedNotice: StateFlow<String?>
    fun noticeShown()
}
