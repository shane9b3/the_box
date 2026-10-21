package app.thebox.ui.editor

import android.content.Context
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [EditorTexts] over `strings_editor.xml` (docs/design/m6-channel-editor.md §5): one resource per key, plurals through [count]. */
@Singleton
class ResourceEditorTexts @Inject constructor(@ApplicationContext private val context: Context) : EditorTexts {
    override fun get(key: EditorText, vararg args: Any): String =
        if (key in EditorText.PLURAL) count(key, (args.firstOrNull() as? Int) ?: 0, *args.drop(1).toTypedArray())
        else context.getString(stringId(key), *args)

    override fun count(key: EditorText, n: Int, vararg args: Any): String =
        context.resources.getQuantityString(pluralId(key), n, n, *args) // [n] is %1$d, [args] follow (EditorTexts.count)

    private fun pluralId(key: EditorText): Int = when (key) {
        EditorText.GROUPS_COUNT -> R.plurals.editor_groups_count
        EditorText.CHANNELS_COUNT -> R.plurals.editor_channels_count
        EditorText.SELECTED_COUNT -> R.plurals.editor_selected_count
        EditorText.MENU_TITLE_CHANNELS -> R.plurals.editor_menu_title_channels
        EditorText.MENU_TITLE_GROUPS -> R.plurals.editor_menu_title_groups
        EditorText.PICKER_TITLE -> R.plurals.editor_picker_title
        EditorText.N_HIDDEN -> R.plurals.editor_n_hidden
        EditorText.N_SHOWN -> R.plurals.editor_n_shown
        EditorText.N_GROUPS_HIDDEN -> R.plurals.editor_n_groups_hidden
        EditorText.N_GROUPS_SHOWN -> R.plurals.editor_n_groups_shown
        EditorText.N_SKIPPED_RULE -> R.plurals.editor_n_skipped_rule
        EditorText.N_MOVED -> R.plurals.editor_n_moved
        EditorText.N_FAVORITE_ADDED -> R.plurals.editor_n_favorite_added
        EditorText.N_FAVORITE_REMOVED -> R.plurals.editor_n_favorite_removed
        EditorText.N_LOCKED -> R.plurals.editor_n_locked
        EditorText.N_UNLOCKED -> R.plurals.editor_n_unlocked
        EditorText.N_RESET -> R.plurals.editor_n_reset
        EditorText.N_GROUPS_RESET -> R.plurals.editor_n_groups_reset
        EditorText.N_UNSELECTED -> R.plurals.editor_n_unselected
        EditorText.C_RESET_TITLE -> R.plurals.editor_c_reset_title
        EditorText.C_RESET_GROUPS_TITLE -> R.plurals.editor_c_reset_groups_title
        EditorText.GROUP_VALUE_HIDDEN -> R.plurals.editor_group_value_hidden
        EditorText.GROUPS_COUNT_NONE -> R.plurals.editor_groups_count_none
        EditorText.CHANNELS_COUNT_NONE -> R.plurals.editor_channels_count_none
        else -> throw IllegalArgumentException("not a quantity string: $key")
    }

    private fun stringId(key: EditorText): Int = when (key) {
        EditorText.TITLE -> R.string.editor_title
        EditorText.GROUPS_TITLE -> R.string.editor_groups_title
        EditorText.ALL_CHANNELS -> R.string.editor_all_channels
        EditorText.FIND_RESULTS -> R.string.editor_find_results
        EditorText.SAVING -> R.string.editor_saving
        EditorText.DETAIL_MOVED -> R.string.editor_detail_moved
        EditorText.DETAIL_RENAMED -> R.string.editor_detail_renamed
        EditorText.DETAIL_RULE -> R.string.editor_detail_rule
        EditorText.LOCKED_NOTE -> R.string.editor_locked_note
        EditorText.TB_ACTIONS -> R.string.editor_tb_actions
        EditorText.TB_SORT -> R.string.editor_tb_sort
        EditorText.TB_FIND -> R.string.editor_tb_find
        EditorText.TB_SHOW -> R.string.editor_tb_show
        EditorText.TB_HIDDEN_GROUPS_ON -> R.string.editor_tb_hidden_groups_on
        EditorText.TB_HIDDEN_GROUPS_OFF -> R.string.editor_tb_hidden_groups_off
        EditorText.FILTER_ALL -> R.string.editor_filter_all
        EditorText.FILTER_SHOWN -> R.string.editor_filter_shown
        EditorText.FILTER_HIDDEN -> R.string.editor_filter_hidden
        EditorText.SORT_NAME_AZ -> R.string.editor_sort_name_az
        EditorText.SORT_NAME_ZA -> R.string.editor_sort_name_za
        EditorText.SORT_NUMBER -> R.string.editor_sort_number
        EditorText.SORT_PROVIDER -> R.string.editor_sort_provider
        EditorText.A_HIDE -> R.string.editor_a_hide
        EditorText.A_SHOW -> R.string.editor_a_show
        EditorText.A_MOVE_TO_GROUP -> R.string.editor_a_move_to_group
        EditorText.A_REORDER -> R.string.editor_a_reorder
        EditorText.A_ADD_FAVORITE -> R.string.editor_a_add_favorite
        EditorText.A_REMOVE_FAVORITE -> R.string.editor_a_remove_favorite
        EditorText.A_LOCK -> R.string.editor_a_lock
        EditorText.A_UNLOCK -> R.string.editor_a_unlock
        EditorText.A_RENAME -> R.string.editor_a_rename
        EditorText.A_SET_NUMBER -> R.string.editor_a_set_number
        EditorText.A_CLEAR_NUMBER -> R.string.editor_a_clear_number
        EditorText.A_SELECT_ALL -> R.string.editor_a_select_all
        EditorText.A_FIND -> R.string.editor_a_find
        EditorText.A_SHOW_FILTER -> R.string.editor_a_show_filter
        EditorText.A_SORT -> R.string.editor_a_sort
        EditorText.A_SORT_GROUPS -> R.string.editor_a_sort_groups
        EditorText.A_EDIT_CHANNELS -> R.string.editor_a_edit_channels
        EditorText.A_DROP_RESULTS -> R.string.editor_a_drop_results
        EditorText.A_RESET -> R.string.editor_a_reset
        EditorText.PICKER_HERE -> R.string.editor_picker_here
        EditorText.PICKER_HIDDEN_DETAIL -> R.string.editor_picker_hidden_detail
        EditorText.MOVE_HEADER -> R.string.editor_move_header
        EditorText.EDIT_RENAME_CHANNEL -> R.string.editor_edit_rename_channel
        EditorText.EDIT_RENAME_GROUP -> R.string.editor_edit_rename_group
        EditorText.EDIT_NUMBER -> R.string.editor_edit_number
        EditorText.EDIT_FIND -> R.string.editor_edit_find
        EditorText.N_ORDER_SAVED -> R.string.editor_n_order_saved
        EditorText.N_SORTED -> R.string.editor_n_sorted
        EditorText.N_RENAMED -> R.string.editor_n_renamed
        EditorText.N_NAME_RESET -> R.string.editor_n_name_reset
        EditorText.N_NUMBER_SET -> R.string.editor_n_number_set
        EditorText.N_NUMBER_CLEARED -> R.string.editor_n_number_cleared
        EditorText.N_NUMBER_CLASH -> R.string.editor_n_number_clash
        EditorText.N_LOCKED_NO_PIN -> R.string.editor_n_locked_no_pin
        EditorText.N_QUEUE_FULL -> R.string.editor_n_queue_full
        EditorText.N_NO_NUMBER -> R.string.editor_n_no_number
        EditorText.C_SORT_TITLE -> R.string.editor_c_sort_title
        EditorText.C_SORT_BODY -> R.string.editor_c_sort_body
        EditorText.C_PROVIDER_TITLE -> R.string.editor_c_provider_title
        EditorText.C_PROVIDER_BODY -> R.string.editor_c_provider_body
        EditorText.C_RESET_BODY -> R.string.editor_c_reset_body
        EditorText.C_RESET_GROUPS_BODY -> R.string.editor_c_reset_groups_body
        EditorText.C_CONFIRM_SORT -> R.string.editor_c_confirm_sort
        EditorText.C_CONFIRM_RESET -> R.string.editor_c_confirm_reset
        EditorText.CANCEL -> R.string.editor_cancel
        EditorText.FOOT_BROWSE -> R.string.editor_foot_browse
        EditorText.FOOT_SELECT -> R.string.editor_foot_select
        EditorText.FOOT_CHANNELS -> R.string.editor_foot_channels
        EditorText.FOOT_GROUPS -> R.string.editor_foot_groups
        EditorText.FOOT_ACTIONS -> R.string.editor_foot_actions
        EditorText.FOOT_DONE -> R.string.editor_foot_done
        EditorText.FOOT_CLEAR -> R.string.editor_foot_clear
        EditorText.FOOT_MOVE -> R.string.editor_foot_move
        EditorText.FOOT_DROP -> R.string.editor_foot_drop
        EditorText.FOOT_CANCEL -> R.string.editor_foot_cancel
        EditorText.FOOT_GUIDE -> R.string.editor_foot_guide
        EditorText.E_NO_CHANNELS -> R.string.editor_e_no_channels
        EditorText.E_PLAYLIST_GONE -> R.string.editor_e_playlist_gone
        EditorText.E_EMPTY_HIDDEN -> R.string.editor_e_empty_hidden
        EditorText.E_EMPTY_SHOWN -> R.string.editor_e_empty_shown
        EditorText.E_EMPTY_GROUP -> R.string.editor_e_empty_group
        EditorText.E_FIND_NONE -> R.string.editor_e_find_none
        EditorText.E_LOCKED -> R.string.editor_e_locked
        EditorText.E_PIN_UNLOCK -> R.string.editor_e_pin_unlock
        EditorText.E_PIN_RESET -> R.string.editor_e_pin_reset
        EditorText.E_PIN_MOVE -> R.string.editor_e_pin_move
        EditorText.E_GONE -> R.string.editor_e_gone
        EditorText.E_LOAD -> R.string.editor_e_load
        EditorText.E_WRITE -> R.string.editor_e_write
        EditorText.E_WRITE_BUSY -> R.string.editor_e_write_busy
        EditorText.D_REORDER_LIST -> R.string.editor_d_reorder_list
        EditorText.D_REORDER_FILTER -> R.string.editor_d_reorder_filter
        EditorText.D_REORDER_ALL -> R.string.editor_d_reorder_all
        EditorText.D_REORDER_HIDDEN_GROUPS -> R.string.editor_d_reorder_hidden_groups
        EditorText.D_REORDER_LOCKED -> R.string.editor_d_reorder_locked
        EditorText.C_SORT_GROUPS_TITLE -> R.string.editor_c_sort_groups_title
        EditorText.C_SORT_GROUPS_BODY -> R.string.editor_c_sort_groups_body
        EditorText.C_PROVIDER_GROUPS_BODY -> R.string.editor_c_provider_groups_body
        EditorText.E_SUMMARY -> R.string.editor_e_summary
        EditorText.GROUP_VALUE_HIDDEN -> throw IllegalArgumentException("quantity string: $key")
        EditorText.GROUPS_COUNT_NONE -> throw IllegalArgumentException("quantity string: $key")
        EditorText.CHANNELS_COUNT_NONE -> throw IllegalArgumentException("quantity string: $key")
        EditorText.D_SINGLE -> R.string.editor_d_single
        EditorText.D_RULE -> R.string.editor_d_rule
        EditorText.D_LOCKED_BY_GROUP -> R.string.editor_d_locked_by_group
        EditorText.D_NO_GROUPS -> R.string.editor_d_no_groups
        EditorText.D_GROUP_TICK_FIRST -> R.string.editor_d_group_tick_first
        EditorText.GROUPS_COUNT -> throw IllegalArgumentException("quantity string: $key")
        EditorText.CHANNELS_COUNT -> throw IllegalArgumentException("quantity string: $key")
        EditorText.SELECTED_COUNT -> throw IllegalArgumentException("quantity string: $key")
        EditorText.MENU_TITLE_CHANNELS -> throw IllegalArgumentException("quantity string: $key")
        EditorText.MENU_TITLE_GROUPS -> throw IllegalArgumentException("quantity string: $key")
        EditorText.PICKER_TITLE -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_HIDDEN -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_SHOWN -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_GROUPS_HIDDEN -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_GROUPS_SHOWN -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_SKIPPED_RULE -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_MOVED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_FAVORITE_ADDED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_FAVORITE_REMOVED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_LOCKED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_UNLOCKED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_RESET -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_GROUPS_RESET -> throw IllegalArgumentException("quantity string: $key")
        EditorText.N_UNSELECTED -> throw IllegalArgumentException("quantity string: $key")
        EditorText.C_RESET_TITLE -> throw IllegalArgumentException("quantity string: $key")
        EditorText.C_RESET_GROUPS_TITLE -> throw IllegalArgumentException("quantity string: $key")
    }
}
