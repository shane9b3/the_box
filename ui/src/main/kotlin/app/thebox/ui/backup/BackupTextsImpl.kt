package app.thebox.ui.backup

import android.content.Context
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [BackupTexts] from `strings_backup.xml` (one resource per [BackupText]; `ResourcesTextTest` checks the mapping). */
@Singleton
class BackupTextsImpl @Inject constructor(@ApplicationContext private val context: Context) : BackupTexts {
    override fun get(key: BackupText, vararg args: Any): String = context.getString(res(key), *args)

    companion object {
        fun res(key: BackupText): Int = when (key) {
            BackupText.CHANNELS_TITLE -> R.string.backup_channels_title
            BackupText.CHANNELS_PLAYLIST -> R.string.backup_channels_playlist
            BackupText.CHANNELS_GROUPS -> R.string.backup_channels_groups
            BackupText.CHANNELS_GROUPS_VALUE -> R.string.backup_channels_groups_value
            BackupText.CHANNELS_HIDDEN -> R.string.backup_channels_hidden
            BackupText.CHANNELS_FAVOURITES -> R.string.backup_channels_favourites
            BackupText.CHANNELS_FAVOURITES_VALUE -> R.string.backup_channels_favourites_value
            BackupText.CHANNELS_OPEN_GUIDE_ON -> R.string.backup_channels_open_guide_on
            BackupText.OPEN_ALL -> R.string.backup_open_all
            BackupText.OPEN_FAVOURITES -> R.string.backup_open_favourites
            BackupText.OPEN_LAST -> R.string.backup_open_last
            BackupText.OPEN_GUIDE_ON_EFFECT -> R.string.backup_open_guide_on_effect
            BackupText.CHANNELS_HIDE_RULES -> R.string.backup_channels_hide_rules
            BackupText.CHANNELS_HIDE_RULES_VALUE -> R.string.backup_channels_hide_rules_value
            BackupText.NO_PLAYLIST -> R.string.backup_no_playlist
            BackupText.GROUPS_TITLE -> R.string.backup_groups_title
            BackupText.GROUPS_SHOW_ALL -> R.string.backup_groups_show_all
            BackupText.GROUP_VALUE -> R.string.backup_group_value
            BackupText.GROUP_SHOW -> R.string.backup_group_show
            BackupText.GROUP_HIDE -> R.string.backup_group_hide
            BackupText.GROUP_LOCK -> R.string.backup_group_lock
            BackupText.GROUP_UNLOCK -> R.string.backup_group_unlock
            BackupText.GROUP_MOVE -> R.string.backup_group_move
            BackupText.GROUP_RESET -> R.string.backup_group_reset
            BackupText.GROUP_LOCK_NEEDS_PIN -> R.string.backup_group_lock_needs_pin
            BackupText.NO_GROUPS -> R.string.backup_no_groups
            BackupText.GROUPS_SHOWN_ALL -> R.string.backup_groups_shown_all
            BackupText.HIDDEN_TITLE -> R.string.backup_hidden_title
            BackupText.HIDDEN_UNHIDE_ALL -> R.string.backup_hidden_unhide_all
            BackupText.HIDDEN_UNHIDE_ALL_CONFIRM_TITLE -> R.string.backup_hidden_unhide_all_confirm_title
            BackupText.HIDDEN_UNHIDE_ALL_CONFIRM_BODY -> R.string.backup_hidden_unhide_all_confirm_body
            BackupText.HIDDEN_UNHIDE -> R.string.backup_hidden_unhide
            BackupText.HIDDEN_BY_RULE -> R.string.backup_hidden_by_rule
            BackupText.HIDDEN_BY_YOU -> R.string.backup_hidden_by_you
            BackupText.NO_HIDDEN -> R.string.backup_no_hidden
            BackupText.UNHIDDEN -> R.string.backup_unhidden
            BackupText.RULES_TITLE -> R.string.backup_rules_title
            BackupText.RULE_TARGET -> R.string.backup_rule_target
            BackupText.RULE_TARGET_GROUP -> R.string.backup_rule_target_group
            BackupText.RULE_TARGET_CHANNEL -> R.string.backup_rule_target_channel
            BackupText.RULE_PATTERN -> R.string.backup_rule_pattern
            BackupText.RULE_KIND -> R.string.backup_rule_kind
            BackupText.RULE_KIND_CONTAINS -> R.string.backup_rule_kind_contains
            BackupText.RULE_KIND_REGEX -> R.string.backup_rule_kind_regex
            BackupText.RULE_PREVIEW -> R.string.backup_rule_preview
            BackupText.RULE_PREVIEW_EMPTY -> R.string.backup_rule_preview_empty
            BackupText.RULE_SAVE -> R.string.backup_rule_save
            BackupText.RULE_VALUE_CONTAINS -> R.string.backup_rule_value_contains
            BackupText.RULE_VALUE_REGEX -> R.string.backup_rule_value_regex
            BackupText.RULE_CHANNELS -> R.string.backup_rule_channels
            BackupText.RULE_DISABLE -> R.string.backup_rule_disable
            BackupText.RULE_ENABLE -> R.string.backup_rule_enable
            BackupText.RULE_DELETE -> R.string.backup_rule_delete
            BackupText.RULE_TOO_SLOW -> R.string.backup_rule_too_slow
            BackupText.RULE_TOO_LONG -> R.string.backup_rule_too_long
            BackupText.RULE_BAD_REGEX -> R.string.backup_rule_bad_regex
            BackupText.RULE_EMPTY -> R.string.backup_rule_empty
            BackupText.RULE_SAVED -> R.string.backup_rule_saved
            BackupText.RULE_DELETED -> R.string.backup_rule_deleted
            BackupText.RULE_DUPLICATE -> R.string.backup_rule_duplicate
            BackupText.RULE_OFF -> R.string.backup_rule_off
            BackupText.FAV_TITLE -> R.string.backup_fav_title
            BackupText.FAV_NEW -> R.string.backup_fav_new
            BackupText.FAV_LIST_VALUE -> R.string.backup_fav_list_value
            BackupText.FAV_LIST_NAME -> R.string.backup_fav_list_name
            BackupText.FAV_LIST_DELETE -> R.string.backup_fav_list_delete
            BackupText.FAV_LIST_DELETE_CONFIRM_TITLE -> R.string.backup_fav_list_delete_confirm_title
            BackupText.FAV_LIST_DELETE_CONFIRM_BODY -> R.string.backup_fav_list_delete_confirm_body
            BackupText.FAV_LIST_ONLY_ONE -> R.string.backup_fav_list_only_one
            BackupText.FAV_REMOVE -> R.string.backup_fav_remove
            BackupText.FAV_MOVE -> R.string.backup_fav_move
            BackupText.NO_FAVOURITES -> R.string.backup_no_favourites
            BackupText.NO_LISTS -> R.string.backup_no_lists
            BackupText.FAV_NAME_EMPTY -> R.string.backup_fav_name_empty
            BackupText.CONFIRM -> R.string.backup_confirm
            BackupText.CANCEL -> R.string.backup_cancel
            BackupText.DELETE -> R.string.backup_delete
            BackupText.MOVE_HINT -> R.string.backup_move_hint
            BackupText.BACKUP_TITLE -> R.string.backup_backup_title
            BackupText.BACKUP_NOW -> R.string.backup_backup_now
            BackupText.BACKUP_NOW_DESC -> R.string.backup_backup_now_desc
            BackupText.BACKUP_TO_PHONE -> R.string.backup_backup_to_phone
            BackupText.BACKUP_TO_FILE -> R.string.backup_backup_to_file
            BackupText.BACKUP_TO_DOWNLOADS -> R.string.backup_backup_to_downloads
            BackupText.BACKUP_INCLUDE_LOGINS -> R.string.backup_backup_include_logins
            BackupText.BACKUP_INCLUDE_LOGINS_DESC -> R.string.backup_backup_include_logins_desc
            BackupText.BACKUP_PASS1 -> R.string.backup_backup_pass1
            BackupText.BACKUP_PASS2 -> R.string.backup_backup_pass2
            BackupText.BACKUP_GO -> R.string.backup_backup_go
            BackupText.BACKUP_RUNNING -> R.string.backup_backup_running
            BackupText.BACKUP_DONE -> R.string.backup_backup_done
            BackupText.BACKUP_FAILED -> R.string.backup_backup_failed
            BackupText.BACKUP_PASS_SHORT -> R.string.backup_backup_pass_short
            BackupText.BACKUP_PASS_MISMATCH -> R.string.backup_backup_pass_mismatch
            BackupText.RESTORE -> R.string.backup_restore
            BackupText.RESTORE_DESC -> R.string.backup_restore_desc
            BackupText.RESTORE_FROM_PHONE -> R.string.backup_restore_from_phone
            BackupText.RESTORE_FROM_FILE -> R.string.backup_restore_from_file
            BackupText.RESTORE_FROM_DOWNLOADS -> R.string.backup_restore_from_downloads
            BackupText.RESTORE_NONE_FOUND -> R.string.backup_restore_none_found
            BackupText.RESTORE_NOT_LISTED -> R.string.backup_restore_not_listed
            BackupText.RESTORE_DOWNLOADS_UNREADABLE -> R.string.backup_restore_downloads_unreadable
            BackupText.WEEKLY -> R.string.backup_weekly
            BackupText.WEEKLY_VALUE -> R.string.backup_weekly_value
            BackupText.WEEKLY_NEVER -> R.string.backup_weekly_never
            BackupText.WEEKLY_DURABLE -> R.string.backup_weekly_durable
            BackupText.WEEKLY_APP_ONLY -> R.string.backup_weekly_app_only
            BackupText.WEEKLY_NO_LOGINS_AFTER_RESET -> R.string.backup_weekly_no_logins_after_reset
            BackupText.PASSPHRASE_AUTO -> R.string.backup_passphrase_auto
            BackupText.PASSPHRASE_AUTO_SET -> R.string.backup_passphrase_auto_set
            BackupText.PASSPHRASE_AUTO_NOT_SET -> R.string.backup_passphrase_auto_not_set
            BackupText.PASSPHRASE_AUTO_DESC -> R.string.backup_passphrase_auto_desc
            BackupText.PASSPHRASE_SAVED -> R.string.backup_passphrase_saved
            BackupText.PASSPHRASE_REMOVED -> R.string.backup_passphrase_removed
            BackupText.NUDGE -> R.string.backup_nudge
            BackupText.NUDGE_DESC -> R.string.backup_nudge_desc
            BackupText.PHONE_ROUTE_DESC -> R.string.backup_phone_route_desc
            BackupText.PASSPHRASE_REMOVE -> R.string.backup_passphrase_remove // C-S7
            BackupText.PASSPHRASE_REMOVE_DESC -> R.string.backup_passphrase_remove_desc
            BackupText.PASSPHRASE_REMOVE_CONFIRM_BODY -> R.string.backup_passphrase_remove_confirm_body
            BackupText.REMOVE -> R.string.backup_remove
            BackupText.PREVIEW_TITLE -> R.string.backup_preview_title
            BackupText.PREVIEW_SUMMARY -> R.string.backup_preview_summary
            BackupText.PREVIEW_WILL_UPDATE -> R.string.backup_preview_will_update
            BackupText.PREVIEW_WILL_ADD -> R.string.backup_preview_will_add
            BackupText.PREVIEW_PASSPHRASE -> R.string.backup_preview_passphrase
            BackupText.PREVIEW_PASSPHRASE_DESC -> R.string.backup_preview_passphrase_desc
            BackupText.PREVIEW_MODE -> R.string.backup_preview_mode
            BackupText.PREVIEW_MERGE -> R.string.backup_preview_merge
            BackupText.PREVIEW_MERGE_DETAIL -> R.string.backup_preview_merge_detail
            BackupText.PREVIEW_REPLACE -> R.string.backup_preview_replace
            BackupText.PREVIEW_REPLACE_DETAIL -> R.string.backup_preview_replace_detail
            BackupText.PREVIEW_GO -> R.string.backup_preview_go
            BackupText.PREVIEW_GONE -> R.string.backup_preview_gone
            BackupText.PREVIEW_RUNNING -> R.string.backup_preview_running
            BackupText.RESTORE_DONE -> R.string.backup_restore_done
            BackupText.RESTORE_NEEDS_LOGIN -> R.string.backup_restore_needs_login
            BackupText.PREVIEW_PROBLEMS -> R.string.backup_preview_problems
            BackupText.RESTORE_START_WATCHING -> R.string.backup_restore_start_watching // C-S8
            BackupText.RESTORE_PIN_OFFER -> R.string.backup_restore_pin_offer
            BackupText.RESTORE_PIN_OFFER_CHANNELS -> R.string.backup_restore_pin_offer_channels
            BackupText.RESTORE_PIN_OFFER_BODY -> R.string.backup_restore_pin_offer_body
            BackupText.RESTORE_PIN_SET -> R.string.backup_restore_pin_set
            BackupText.RESTORE_PIN_LATER -> R.string.backup_restore_pin_later
            BackupText.FAIL_NOT_A_BACKUP -> R.string.backup_fail_not_a_backup
            BackupText.FAIL_NEWER_SCHEMA -> R.string.backup_fail_newer_schema
            BackupText.FAIL_TRUNCATED -> R.string.backup_fail_truncated
            BackupText.FAIL_WRONG_PASSPHRASE -> R.string.backup_fail_wrong_passphrase
            BackupText.FAIL_CORRUPT_SECRETS -> R.string.backup_fail_corrupt_secrets
            BackupText.FAIL_TOO_LARGE -> R.string.backup_fail_too_large
            BackupText.FAIL_IO -> R.string.backup_fail_io
            BackupText.PHONE_SCAN -> R.string.backup_phone_scan
            BackupText.PHONE_OR_OPEN -> R.string.backup_phone_or_open
            BackupText.PHONE_AND_CODE -> R.string.backup_phone_and_code
            BackupText.PHONE_WAITING -> R.string.backup_phone_waiting
            BackupText.PHONE_CONNECTED -> R.string.backup_phone_connected
            BackupText.PHONE_RECEIVED -> R.string.backup_phone_received
            BackupText.PHONE_FOOTER -> R.string.backup_phone_footer
            BackupText.PHONE_ISOLATION_HINT -> R.string.backup_phone_isolation_hint
            BackupText.PHONE_VPN -> R.string.backup_phone_vpn
            BackupText.PHONE_TYPE_ON_TV -> R.string.backup_phone_type_on_tv
            BackupText.PHONE_NEW_CODE -> R.string.backup_phone_new_code
            BackupText.PHONE_FAIL_NO_LAN -> R.string.backup_phone_fail_no_lan
            BackupText.PHONE_FAIL_BIND -> R.string.backup_phone_fail_bind
            BackupText.PHONE_FAIL_WRONG_CODES -> R.string.backup_phone_fail_wrong_codes
            BackupText.PHONE_FAIL_EXPIRED -> R.string.backup_phone_fail_expired
            BackupText.PHONE_STARTING -> R.string.backup_phone_starting
            BackupText.PHONE_CANT_SCAN -> R.string.backup_phone_cant_scan
            BackupText.PHONE_TITLE_SETUP -> R.string.backup_phone_title_setup
            BackupText.PHONE_TITLE_RESTORE -> R.string.backup_phone_title_restore
            BackupText.PHONE_TITLE_BACKUP -> R.string.backup_phone_title_backup
            BackupText.PHONE_TITLE_LOGS -> R.string.backup_phone_title_logs
            BackupText.PHONE_BACK_HINT -> R.string.backup_phone_back_hint
        }
    }
}
