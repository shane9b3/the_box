package app.thebox.ui.parental

import android.content.res.Resources
import android.view.KeyEvent
import app.thebox.core.input.ActionGroup
import app.thebox.core.input.BoxAction
import app.thebox.core.input.Keys
import app.thebox.core.settings.tree.ButtonMappingRows
import app.thebox.core.settings.tree.ParentalRows
import app.thebox.core.settings.tree.RowTexts
import app.thebox.ui.R

/**
 * `strings_parental.xml` lookups for WP-C's page builders (explicit tables: release builds shrink resources, so no
 * name-based lookups).
 */
object ParentalStrings {
    private val PARENTAL: Map<String, Int> = mapOf(
        ParentalRows.T.TITLE to R.string.parental_page_title,
        ParentalRows.T.SET_PIN to R.string.parental_row_set_pin,
        ParentalRows.T.CHANGE_PIN to R.string.parental_row_change_pin,
        ParentalRows.T.PIN_NOT_SET to R.string.parental_value_not_set,
        ParentalRows.T.PIN_SET to R.string.parental_value_set,
        ParentalRows.T.AUTO_LOCK to R.string.parental_row_auto_lock,
        ParentalRows.T.AUTO_LOCK_DESC to R.string.parental_row_auto_lock_desc,
        ParentalRows.T.ON to R.string.parental_value_on,
        ParentalRows.T.OFF to R.string.parental_value_off,
        ParentalRows.T.NOT_PROTECTED to R.string.parental_value_not_protected,
        ParentalRows.T.LOCKED_IN_GUIDE to R.string.parental_row_locked_in_guide,
        ParentalRows.T.SHOW_WITH_LOCK to R.string.parental_option_show_with_lock,
        ParentalRows.T.HIDE to R.string.parental_option_hide,
        ParentalRows.T.LOCK_SETTINGS to R.string.parental_row_lock_settings,
        ParentalRows.T.NEEDS_PIN_FIRST to R.string.parental_reason_set_pin_first,
        ParentalRows.T.STAY_UNLOCKED to R.string.parental_row_stay_unlocked,
        ParentalRows.T.UNTIL_CLOSE to R.string.parental_option_until_close,
        ParentalRows.T.ONE_HOUR to R.string.parental_option_one_hour,
        ParentalRows.T.ASK_EVERY_TIME to R.string.parental_option_ask_every_time,
        ParentalRows.T.LOCK_NOW to R.string.parental_row_lock_now,
        ParentalRows.T.REMOVE_PIN to R.string.parental_row_remove_pin,
        ParentalRows.T.REMOVE_TITLE to R.string.parental_confirm_remove_title,
        ParentalRows.T.REMOVE_BODY to R.string.parental_confirm_remove_body,
        ParentalRows.T.REMOVE_CONFIRM to R.string.parental_confirm_remove_ok,
        ParentalRows.T.CANCEL to R.string.parental_confirm_cancel,
        ParentalRows.T.FORGOT to R.string.parental_info_forgot,
        ParentalRows.T.THREAT to R.string.parental_info_threat,
        ParentalRows.T.KEY_RESET to R.string.parental_info_key_reset,
    )

    private val MAPPING: Map<String, Int> = mapOf(
        ButtonMappingRows.T.TITLE to R.string.mapping_page_title,
        ButtonMappingRows.T.TEST to R.string.mapping_row_test,
        ButtonMappingRows.T.TEST_DESC to R.string.mapping_row_test_desc,
        ButtonMappingRows.T.RESET_ALL to R.string.mapping_row_reset_all,
        ButtonMappingRows.T.RESET_ALL_TITLE to R.string.mapping_confirm_reset_all_title,
        ButtonMappingRows.T.RESET_ALL_BODY to R.string.mapping_confirm_reset_all_body,
        ButtonMappingRows.T.RESET_ALL_OK to R.string.mapping_confirm_reset_all_ok,
        ButtonMappingRows.T.CANCEL to R.string.mapping_confirm_cancel,
        ButtonMappingRows.T.NO_BUTTON to R.string.mapping_value_no_button,
        ButtonMappingRows.T.HOLD to R.string.mapping_value_hold,
        ButtonMappingRows.T.SEPARATOR to R.string.mapping_value_separator,
        ButtonMappingRows.T.DPAD_NOTE to R.string.mapping_info_dpad_note,
        ButtonMappingRows.T.ADD_SHORT to R.string.mapping_row_add_short,
        ButtonMappingRows.T.ADD_LONG to R.string.mapping_row_add_long,
        ButtonMappingRows.T.RESET_ACTION to R.string.mapping_row_reset_action,
        ButtonMappingRows.T.DEFAULT to R.string.mapping_value_default,
        ButtonMappingRows.T.CAPTURED_FROM to R.string.mapping_value_captured_from,
        ButtonMappingRows.T.PRESS to R.string.mapping_value_press,
        ButtonMappingRows.T.MENU_REMOVE to R.string.mapping_menu_remove,
        ButtonMappingRows.T.MENU_RESET_KEY to R.string.mapping_menu_reset_key,
    )

    private class Table(private val res: Resources, private val table: Map<String, Int>) : RowTexts {
        override fun text(key: String, vararg args: Any): String {
            val id = table[key] ?: return key
            return if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
        }
    }

    fun parental(res: Resources): RowTexts = Table(res, PARENTAL)
    fun mapping(res: Resources): RowTexts = Table(res, MAPPING)

    /** Every key the two tables use resolves (tests). */
    val parentalKeys: Set<String> get() = PARENTAL.keys
    val mappingKeys: Set<String> get() = MAPPING.keys

    fun actionLabel(action: BoxAction): Int = when (action) {
        BoxAction.PLAY_PAUSE -> R.string.mapping_action_play_pause
        BoxAction.PLAY -> R.string.mapping_action_play
        BoxAction.PAUSE -> R.string.mapping_action_pause
        BoxAction.SEEK_BACK -> R.string.mapping_action_seek_back
        BoxAction.SEEK_FORWARD -> R.string.mapping_action_seek_forward
        BoxAction.START_OVER -> R.string.mapping_action_start_over
        BoxAction.JUMP_TO_LIVE -> R.string.mapping_action_jump_to_live
        BoxAction.TIME_SHIFT -> R.string.mapping_action_time_shift
        BoxAction.PREV_PROGRAMME -> R.string.mapping_action_prev_programme
        BoxAction.NEXT_PROGRAMME -> R.string.mapping_action_next_programme
        BoxAction.ZAP_UP -> R.string.mapping_action_zap_up
        BoxAction.ZAP_DOWN -> R.string.mapping_action_zap_down
        BoxAction.PREVIOUS_CHANNEL -> R.string.mapping_action_previous_channel
        BoxAction.NUMBER_ENTRY -> R.string.mapping_action_number_entry
        BoxAction.CHANNEL_LIST -> R.string.mapping_action_channel_list
        BoxAction.GROUPS_PANEL -> R.string.mapping_action_groups_panel
        BoxAction.GROUP_NEXT -> R.string.mapping_action_group_next
        BoxAction.GROUP_PREV -> R.string.mapping_action_group_prev
        BoxAction.FAVOURITES -> R.string.mapping_action_favourites
        BoxAction.TOGGLE_FAVOURITE -> R.string.mapping_action_favourite_toggle
        BoxAction.OPEN_GUIDE -> R.string.mapping_action_guide
        BoxAction.PAGE_UP -> R.string.mapping_action_page_up
        BoxAction.PAGE_DOWN -> R.string.mapping_action_page_down
        BoxAction.JUMP_TO_NOW -> R.string.mapping_action_jump_to_now
        BoxAction.DAY_STRIP -> R.string.mapping_action_day_strip
        BoxAction.REMIND -> R.string.mapping_action_remind
        BoxAction.INFO_BAR -> R.string.mapping_action_info_bar
        BoxAction.QUICK_MENU -> R.string.mapping_action_quick_menu
        BoxAction.AUDIO -> R.string.mapping_action_audio
        BoxAction.SUBTITLES -> R.string.mapping_action_subtitles
        BoxAction.ASPECT -> R.string.mapping_action_aspect
        BoxAction.VIDEO_DECODER -> R.string.mapping_action_video_decoder
        BoxAction.STATS_OVERLAY -> R.string.mapping_action_stats_overlay
        BoxAction.SCREEN_OFF -> R.string.mapping_action_screen_off
        BoxAction.CONTEXT_MENU -> R.string.mapping_action_context_menu
        BoxAction.OPEN_SEARCH -> R.string.mapping_action_open_search
        BoxAction.OPEN_SETTINGS -> R.string.mapping_action_open_settings
        BoxAction.EXIT -> R.string.mapping_action_exit
        // Not remappable: never listed on the mapping screen; navigation names for completeness.
        BoxAction.UP -> R.string.key_up
        BoxAction.DOWN -> R.string.key_down
        BoxAction.LEFT -> R.string.key_left
        BoxAction.RIGHT -> R.string.key_right
        BoxAction.SELECT -> R.string.key_ok
        BoxAction.BACK -> R.string.key_back
        BoxAction.DIGIT -> R.string.mapping_action_number_entry
    }

    fun groupLabel(group: ActionGroup): Int = when (group) {
        ActionGroup.WATCHING -> R.string.mapping_group_watching
        ActionGroup.CHANNELS -> R.string.mapping_group_channels
        ActionGroup.GUIDE -> R.string.mapping_group_guide
        ActionGroup.PLAYER_OVERLAYS -> R.string.mapping_group_player_overlays
        ActionGroup.APP -> R.string.mapping_group_app
    }
}

/**
 * Key names for the capture overlay and the mapping rows (§3.8.3): a table of the common codes, else
 * `KeyEvent.keyCodeToString` without the `KEYCODE_` prefix (§9 F2).
 */
object KeyNames {
    fun res(keyCode: Int): Int? = when (keyCode) {
        Keys.CHANNEL_UP -> R.string.key_channel_up
        Keys.CHANNEL_DOWN -> R.string.key_channel_down
        Keys.PAGE_UP -> R.string.key_page_up
        Keys.PAGE_DOWN -> R.string.key_page_down
        Keys.PROG_RED -> R.string.key_red
        Keys.PROG_GREEN -> R.string.key_green
        Keys.PROG_YELLOW -> R.string.key_yellow
        Keys.PROG_BLUE -> R.string.key_blue
        Keys.MEDIA_PLAY_PAUSE -> R.string.key_play_pause
        Keys.MEDIA_PLAY -> R.string.key_play
        Keys.MEDIA_PAUSE -> R.string.key_pause
        Keys.MEDIA_STOP -> R.string.key_stop
        Keys.MEDIA_FAST_FORWARD -> R.string.key_fast_forward
        Keys.MEDIA_REWIND -> R.string.key_rewind
        Keys.MEDIA_NEXT -> R.string.key_next
        Keys.MEDIA_PREVIOUS -> R.string.key_previous
        Keys.MEDIA_RECORD -> R.string.key_record
        Keys.MEDIA_SKIP_FORWARD, Keys.MEDIA_STEP_FORWARD -> R.string.key_skip_forward
        Keys.MEDIA_SKIP_BACKWARD, Keys.MEDIA_STEP_BACKWARD -> R.string.key_skip_backward
        Keys.GUIDE -> R.string.key_guide
        Keys.INFO -> R.string.key_info
        Keys.MENU -> R.string.key_menu
        Keys.SEARCH -> R.string.key_search
        Keys.SETTINGS -> R.string.key_settings
        Keys.LAST_CHANNEL -> R.string.key_last_channel
        Keys.CAPTIONS -> R.string.key_captions
        Keys.MEDIA_AUDIO_TRACK -> R.string.key_audio_track
        Keys.TV_ZOOM_MODE -> R.string.key_zoom
        Keys.DPAD_UP -> R.string.key_up
        Keys.DPAD_DOWN -> R.string.key_down
        Keys.DPAD_LEFT -> R.string.key_left
        Keys.DPAD_RIGHT -> R.string.key_right
        Keys.DPAD_CENTER -> R.string.key_ok
        Keys.ENTER, Keys.NUMPAD_ENTER -> R.string.key_enter
        Keys.BACK -> R.string.key_back
        Keys.ESCAPE -> R.string.key_escape
        Keys.BUTTON_A -> R.string.key_button_a
        Keys.BUTTON_B -> R.string.key_button_b
        0 -> R.string.key_unknown_code
        else -> null
    }

    fun name(res: Resources, keyCode: Int): String {
        val digit = when (keyCode) {
            in Keys.DIGIT_0..Keys.DIGIT_0 + 9 -> keyCode - Keys.DIGIT_0
            in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9 -> keyCode - Keys.NUMPAD_0
            else -> -1
        }
        if (digit >= 0) return res.getString(R.string.key_digit, digit)
        res(keyCode)?.let { return res.getString(it) }
        return KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
    }
}
