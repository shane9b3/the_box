package app.thebox.ui.sports

import androidx.compose.runtime.compositionLocalOf

/**
 * (Review H1, H2) Whether the hub's focus targets under this point may take focus. `SportsScreen` provides false for the
 * page (cards, chips, the day strip, the panel's slots, Retry) while a sheet is open, and for the page and the sheets while
 * a clip plays, so a D-pad search or a late focus request can never land behind the sheet or under the clip layer (§2.5:
 * "the grid behind is not focusable"). Every focusable leaf of the page and the sheets reads it in its `focusProperties`.
 * The default (true) is for a sheet or a card composed on its own (tests).
 */
internal val LocalHubFocusable = compositionLocalOf { true }
