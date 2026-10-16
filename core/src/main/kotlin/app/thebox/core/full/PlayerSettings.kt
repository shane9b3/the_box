// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.2.
package app.thebox.core.full

import kotlinx.coroutines.flow.StateFlow

/** Product §4.7 "Up/Down in player", as amended by the owner ruling of 2026-10-15. */
enum class UpDownInPlayer {
    /** Default: Up opens the quick bar, Down the active info bar. */
    QUICK_BAR_AND_INFO,
    /** Up/Down change channel (Up = DPAD_UP_ZAP_DELTA, inverted by [PlayerSettings.invertChannelDirection]). */
    ZAP,
    /** Up/Down open the channel list on the tuned row moved one row. */
    CHANNEL_LIST,
}
/** Product §4.7 "Back in player". */
enum class BackInPlayer { GUIDE, CHANNEL_LIST }
/** Product §4.4 "On launch". */
enum class OnLaunch { GUIDE_WITH_LAST, FULL_LAST, GUIDE_NOTHING }

/**
 * Rows of product §4.4/§4.7 the player reads. (M5, docs/design/m5-setup-settings.md §2.12 item 4) `@Serializable`: stored
 * as is in the `player` settings domain (`app_setting`).
 */
@kotlinx.serialization.Serializable
data class PlayerSettings(
    /** 3 / 5 / 8 s (product §4.4 "Info bar duration"). */
    val infoBarMs: Long = 5_000,
    /** 1 / 1.5 / 2.5 s (product §4.4 "Number entry wait"); also used by the guide's number entry. */
    val numberEntryWaitMs: Long = 1_500,
    val upDown: UpDownInPlayer = UpDownInPlayer.QUICK_BAR_AND_INFO,
    /** Under [UpDownInPlayer.ZAP] only: swaps the D-pad Up/Down zap direction (CH± never invert). */
    val invertChannelDirection: Boolean = false,
    val back: BackInPlayer = BackInPlayer.GUIDE,
    val onLaunch: OnLaunch = OnLaunch.GUIDE_WITH_LAST,
)

/** Zap delta of D-pad Up under UpDownInPlayer.ZAP (+1 = higher number, like CH+). One constant (lead sign-off item 3). */
const val DPAD_UP_ZAP_DELTA = +1

interface PlayerSettingsSource {
    val settings: StateFlow<PlayerSettings>
    fun update(transform: (PlayerSettings) -> PlayerSettings)
}
