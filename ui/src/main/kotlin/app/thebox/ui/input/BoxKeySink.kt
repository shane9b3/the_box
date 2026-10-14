package app.thebox.ui.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester

/**
 * `Modifier.boxKeySink` (docs/design/m3-guide.md §2.10, §3.18.1, review B1; owner WP-D): applied once by WP-C on the
 * key-sink `Box` in `AppRoot`. Defined by [KeyRouter.sinkModifier].
 */
fun Modifier.boxKeySink(router: KeyRouter, requester: FocusRequester): Modifier = this.then(router.sinkModifier(requester))
