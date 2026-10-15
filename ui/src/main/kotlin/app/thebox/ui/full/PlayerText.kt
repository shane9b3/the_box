package app.thebox.ui.full

import android.content.res.Resources
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.StatLabel

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.10.2, §6.3): every player string, from `strings_player.xml` /
 * `strings_errors.xml` (no hard-coded user text).
 */
object PlayerText {
    fun toast(res: Resources, kind: PlayerToast, arg: String?): String = TODO("WP-D")
    fun statLabel(res: Resources, label: StatLabel): String = TODO("WP-D")
}
