package app.thebox.settings

import android.content.Context
import android.content.res.Resources
import app.thebox.core.settings.state.StoragePageEnv
import app.thebox.core.settings.state.StorageText
import app.thebox.core.settings.state.StorageTexts
import app.thebox.ui.R
import app.thebox.ui.settings.keyboardAvailable
import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device facts of "Save to" and the network-folder pages (docs/design/m6-storage-locations.md section 9, 17 WP-S4): a
 * keyboard (else sign-in from the phone comes first, U-B1), the memory class (the 96-block pool note of the Test, lead
 * ruling 5), and the main thread (the settings machine's).
 */
@Singleton
class StoragePageEnvImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringTargets: dagger.Lazy<app.thebox.core.playback.timeshift.RingTargetSource>,
) : StoragePageEnv {
    private val main = Handler(Looper.getMainLooper())

    override fun hasKeyboard(): Boolean = keyboardAvailable(context)

    override fun smallRemotePool(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return app.thebox.core.playback.timeshift.TimeshiftConstants.smallRemotePool(am.memoryClass)
    }

    override fun warmSaveLocation() = ringTargets.get().warm()

    override fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** [StorageTexts] over `strings_storage.xml` (an exhaustive `when`: a text without a string fails the build). */
class ResourceStorageTexts(private val res: Resources) : StorageTexts {
    override fun get(key: StorageText, vararg args: Any): String = res.getString(id(key), *args)

    private fun id(key: StorageText): Int = when (key) {
        StorageText.PAUSE_REWIND_SUBTITLE -> R.string.storage_pause_rewind_subtitle
        StorageText.SAVE_TO -> R.string.storage_save_to
        StorageText.SAVE_TO_DESC -> R.string.storage_save_to_desc
        StorageText.SAVE_TO_DESC_UNREACHABLE -> R.string.storage_save_to_desc_unreachable
        StorageText.SAVE_TO_DESC_SIGN_IN -> R.string.storage_save_to_desc_sign_in
        StorageText.SAVE_TO_OFF_REASON -> R.string.storage_save_to_off_reason
        StorageText.THIS_DEVICE -> R.string.storage_this_device
        StorageText.PLACE_THIS_DEVICE -> R.string.storage_place_this_device
        StorageText.PLACE_USB -> R.string.storage_place_usb
        StorageText.PLACE_USB_DRIVE -> R.string.storage_place_usb_drive
        StorageText.SAVE_SUBTITLE -> R.string.storage_save_subtitle
        StorageText.THIS_DEVICE_DESC -> R.string.storage_this_device_desc
        StorageText.THIS_DEVICE_DESC_UNKNOWN -> R.string.storage_this_device_desc_unknown
        StorageText.USB_DRIVE -> R.string.storage_usb_drive
        StorageText.USB_DRIVE_NAMED -> R.string.storage_usb_drive_named
        StorageText.USB_DRIVE_DESC -> R.string.storage_usb_drive_desc
        StorageText.ADD_NETWORK_FOLDER -> R.string.storage_add_network_folder
        StorageText.ADD_NETWORK_FOLDER_DESC -> R.string.storage_add_network_folder_desc
        StorageText.STATUS_IN_USE -> R.string.storage_status_in_use
        StorageText.STATUS_CANT_REACH -> R.string.storage_status_cant_reach
        StorageText.STATUS_SIGN_IN_NEEDED -> R.string.storage_status_sign_in_needed
        StorageText.STATUS_GOOD_4K -> R.string.storage_status_good_4k
        StorageText.STATUS_GOOD_HD -> R.string.storage_status_good_hd
        StorageText.STATUS_SLOW -> R.string.storage_status_slow
        StorageText.STATUS_TOO_SLOW -> R.string.storage_status_too_slow
        StorageText.STATUS_NOT_TESTED -> R.string.storage_status_not_tested
        StorageText.STATUS_NOT_CONNECTED -> R.string.storage_status_not_connected
        StorageText.STATUS_DEMOTED -> R.string.storage_status_demoted
        StorageText.STATUS_TESTED_AGO -> R.string.storage_status_tested_ago
        StorageText.AGO_TODAY -> R.string.storage_ago_today
        StorageText.AGO_YESTERDAY -> R.string.storage_ago_yesterday
        StorageText.AGO_DAYS -> R.string.storage_ago_days
        StorageText.SIZE_GB -> R.string.storage_size_gb
        StorageText.SIZE_TB -> R.string.storage_size_tb
        StorageText.DETAIL_STATUS -> R.string.storage_detail_status
        StorageText.DETAIL_ADDRESS -> R.string.storage_detail_address
        StorageText.DETAIL_USE -> R.string.storage_detail_use
        StorageText.DETAIL_USE_DESC -> R.string.storage_detail_use_desc
        StorageText.DETAIL_TEST -> R.string.storage_detail_test
        StorageText.DETAIL_NAME -> R.string.storage_detail_name
        StorageText.DETAIL_SIGN_IN_AGAIN -> R.string.storage_detail_sign_in_again
        StorageText.DETAIL_CHANGE_SIGN_IN -> R.string.storage_detail_change_sign_in
        StorageText.DETAIL_REMOVE -> R.string.storage_detail_remove
        StorageText.DETAIL_REMOVE_TITLE -> R.string.storage_detail_remove_title
        StorageText.DETAIL_REMOVE_BODY -> R.string.storage_detail_remove_body
        StorageText.DETAIL_REMOVE_CONFIRM -> R.string.storage_detail_remove_confirm
        StorageText.DETAIL_CONNECT_DRIVE -> R.string.storage_detail_connect_drive
        StorageText.MENU_TEST -> R.string.storage_menu_test
        StorageText.MENU_SIGN_IN -> R.string.storage_menu_sign_in
        StorageText.MENU_REMOVE -> R.string.storage_menu_remove
        StorageText.CANCEL -> R.string.storage_cancel
        StorageText.NOTICE_WILL_SAVE -> R.string.storage_notice_will_save
        StorageText.NOTICE_SIGNED_IN -> R.string.storage_notice_signed_in
        StorageText.FIND_TITLE -> R.string.storage_find_title
        StorageText.FIND_SUBTITLE -> R.string.storage_find_subtitle
        StorageText.FIND_SEARCHING -> R.string.storage_find_searching
        StorageText.FIND_SEARCHING_NONET -> R.string.storage_find_searching_nonet
        StorageText.FIND_COUNT -> R.string.storage_find_count
        StorageText.FIND_DONE -> R.string.storage_find_done
        StorageText.FIND_DONE_ONE -> R.string.storage_find_done_one
        StorageText.FIND_NONE_TITLE -> R.string.storage_find_none_title
        StorageText.FIND_NONE_STEP -> R.string.storage_find_none_step
        StorageText.FIND_VPN_TITLE -> R.string.storage_find_vpn_title
        StorageText.FIND_VPN_STEP -> R.string.storage_find_vpn_step
        StorageText.FIND_ENTER -> R.string.storage_find_enter
        StorageText.FIND_ENTER_DESC -> R.string.storage_find_enter_desc
        StorageText.FIND_PHONE -> R.string.storage_find_phone
        StorageText.FIND_PHONE_DESC -> R.string.storage_find_phone_desc
        StorageText.FIND_AGAIN -> R.string.storage_find_again
        StorageText.COMPUTER_NO_NAME -> R.string.storage_computer_no_name
        StorageText.COMPUTER_MENU_SIGN_IN -> R.string.storage_computer_menu_sign_in
        StorageText.SHARES_SUBTITLE -> R.string.storage_shares_subtitle
        StorageText.CONNECTING -> R.string.storage_connecting
        StorageText.SHARES_LIST_FAILED -> R.string.storage_shares_list_failed
        StorageText.SHARES_NONE -> R.string.storage_shares_none
        StorageText.SHARE_TYPE_NAME -> R.string.storage_share_type_name
        StorageText.SHARE_TYPE_NAME_DESC -> R.string.storage_share_type_name_desc
        StorageText.SHARE_NOT_FOUND -> R.string.storage_share_not_found
        StorageText.SHARE_NOT_FOUND_STEP -> R.string.storage_share_not_found_step
        StorageText.SHARE_LIST_FAILED -> R.string.storage_share_list_failed
        StorageText.SHARE_LIST_FAILED_STEP -> R.string.storage_share_list_failed_step
        StorageText.SHARES_LIST_FAILED_STEP -> R.string.storage_shares_list_failed_step
        StorageText.SIGN_IN -> R.string.storage_sign_in
        StorageText.SIGN_IN_AS_SOMEONE -> R.string.storage_sign_in_as_someone
        StorageText.TRY_AGAIN -> R.string.storage_try_again
        StorageText.SIGN_IN_TITLE -> R.string.storage_sign_in_title
        StorageText.SIGN_IN_SUBTITLE -> R.string.storage_sign_in_subtitle
        StorageText.SIGN_IN_PHONE -> R.string.storage_sign_in_phone
        StorageText.SIGN_IN_PHONE_DESC -> R.string.storage_sign_in_phone_desc
        StorageText.SIGN_IN_PHONE_TITLE -> R.string.storage_sign_in_phone_title
        StorageText.SIGN_IN_USER -> R.string.storage_sign_in_user
        StorageText.SIGN_IN_PASSWORD -> R.string.storage_sign_in_password
        StorageText.SIGN_IN_GO -> R.string.storage_sign_in_go
        StorageText.SIGN_IN_GO_NEEDS_USER -> R.string.storage_sign_in_go_needs_user
        StorageText.SIGN_IN_GUEST -> R.string.storage_sign_in_guest
        StorageText.SIGN_IN_DOMAIN -> R.string.storage_sign_in_domain
        StorageText.SIGN_IN_DOMAIN_DESC -> R.string.storage_sign_in_domain_desc
        StorageText.SIGN_IN_INFO_TITLE -> R.string.storage_sign_in_info_title
        StorageText.SIGN_IN_INFO -> R.string.storage_sign_in_info
        StorageText.SIGN_IN_NO_KEYBOARD -> R.string.storage_sign_in_no_keyboard
        StorageText.SIGN_IN_NO_KEYBOARD_STEP -> R.string.storage_sign_in_no_keyboard_step
        StorageText.NOT_SET -> R.string.storage_not_set
        StorageText.PHONE_SIGNED_IN -> R.string.storage_phone_signed_in
        StorageText.PHONE_REFUSED -> R.string.storage_phone_refused
        StorageText.PHONE_NO_GUESTS -> R.string.storage_phone_no_guests
        StorageText.PHONE_NO_ACCESS -> R.string.storage_phone_no_access
        StorageText.PHONE_UNREACHABLE -> R.string.storage_phone_unreachable
        StorageText.FOLDER_USE -> R.string.storage_folder_use
        StorageText.FOLDER_UP -> R.string.storage_folder_up
        StorageText.FOLDER_LOADING -> R.string.storage_folder_loading
        StorageText.FOLDER_TRUNCATED -> R.string.storage_folder_truncated
        StorageText.FOLDER_MISSING -> R.string.storage_folder_missing
        StorageText.FOLDER_MISSING_STEP -> R.string.storage_folder_missing_step
        StorageText.FOLDER_NONE_BELOW -> R.string.storage_folder_none_below
        StorageText.FOLDER_SUBTITLE_ROOT -> R.string.storage_folder_subtitle_root
        StorageText.TEST_TITLE -> R.string.storage_test_title
        StorageText.TEST_PHASE_CONNECTING -> R.string.storage_test_phase_connecting
        StorageText.TEST_PHASE_WAKING -> R.string.storage_test_phase_waking
        StorageText.TEST_PHASE_WRITING -> R.string.storage_test_phase_writing
        StorageText.TEST_PHASE_READING -> R.string.storage_test_phase_reading
        StorageText.TEST_PHASE_RTT -> R.string.storage_test_phase_rtt
        StorageText.TEST_PROGRESS_MB -> R.string.storage_test_progress_mb
        StorageText.TEST_STOP -> R.string.storage_test_stop
        StorageText.TEST_STOPPED -> R.string.storage_test_stopped
        StorageText.TEST_AGAIN -> R.string.storage_test_again
        StorageText.VERDICT_STEP_GOOD_4K -> R.string.storage_verdict_step_good_4k
        StorageText.VERDICT_STEP_GOOD_HD -> R.string.storage_verdict_step_good_hd
        StorageText.VERDICT_STEP_SLOW -> R.string.storage_verdict_step_slow
        StorageText.VERDICT_STEP_TOO_SLOW -> R.string.storage_verdict_step_too_slow
        StorageText.VERDICT_DETAILS -> R.string.storage_verdict_details
        StorageText.TEST_WAKE_NOTE -> R.string.storage_test_wake_note
        StorageText.TEST_WAKE_NOTE_STEP -> R.string.storage_test_wake_note_step
        StorageText.TEST_SMALL_POOL_NOTE -> R.string.storage_test_small_pool_note
        StorageText.TEST_USE_THIS -> R.string.storage_test_use_this
        StorageText.TEST_USE_FOR_REWIND -> R.string.storage_test_use_for_rewind
        StorageText.TEST_USE_ANYWAY -> R.string.storage_test_use_anyway
        StorageText.TEST_CHOOSE_ANOTHER -> R.string.storage_test_choose_another
        StorageText.TEST_BACK_TO -> R.string.storage_test_back_to
        StorageText.WIRED -> R.string.storage_wired
        StorageText.WIFI -> R.string.storage_wifi
        StorageText.ERR_UNREACHABLE -> R.string.storage_err_unreachable
        StorageText.ERR_UNREACHABLE_NOADDR -> R.string.storage_err_unreachable_noaddr
        StorageText.ERR_UNREACHABLE_STEP -> R.string.storage_err_unreachable_step
        StorageText.ERR_VPN_APPEND -> R.string.storage_err_vpn_append
        StorageText.ERR_SMB1 -> R.string.storage_err_smb1
        StorageText.ERR_SMB1_STEP -> R.string.storage_err_smb1_step
        StorageText.ERR_AUTH -> R.string.storage_err_auth
        StorageText.ERR_AUTH_STEP -> R.string.storage_err_auth_step
        StorageText.ERR_AUTH_MAC_APPEND -> R.string.storage_err_auth_mac_append
        StorageText.ERR_GUEST -> R.string.storage_err_guest
        StorageText.ERR_GUEST_STEP -> R.string.storage_err_guest_step
        StorageText.ERR_ACCESS -> R.string.storage_err_access
        StorageText.ERR_ACCESS_STEP -> R.string.storage_err_access_step
        StorageText.ERR_WRITE_USER -> R.string.storage_err_write_user
        StorageText.ERR_WRITE_USER_STEP -> R.string.storage_err_write_user_step
        StorageText.ERR_WRITE_GUEST -> R.string.storage_err_write_guest
        StorageText.ERR_WRITE_GUEST_STEP -> R.string.storage_err_write_guest_step
        StorageText.ERR_NO_NETWORK -> R.string.storage_err_no_network
        StorageText.ERR_NO_NETWORK_STEP -> R.string.storage_err_no_network_step
        StorageText.ERR_DATA -> R.string.storage_err_data
        StorageText.ERR_DATA_STEP -> R.string.storage_err_data_step
        StorageText.ERR_FULL -> R.string.storage_err_full
        StorageText.ERR_FULL_STEP -> R.string.storage_err_full_step
        StorageText.TEST_TOOK_TOO_LONG -> R.string.storage_test_took_too_long // C-N7
        StorageText.TEST_TOOK_TOO_LONG_STEP -> R.string.storage_test_took_too_long_step
    }
}
