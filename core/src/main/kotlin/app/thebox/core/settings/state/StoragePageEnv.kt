// WP-S4 (docs/design/m6-storage-locations.md §9, §10, §17): what StoragePages needs besides its injected sources.
package app.thebox.core.settings.state

/**
 * The device facts [StoragePages] reads (implemented in `:app` by `StoragePageEnvImpl`; a plain fake in tests). Main-safe.
 * The ring's own volume (built-in storage) comes from [WpaPageEnv.timeshiftStorage], as for the old three rows.
 */
interface StoragePageEnv {
    /** A keyboard app or a hardware keyboard exists (M5 §9 F5). False: sign in from the phone is the first row (U-B1). */
    fun hasKeyboard(): Boolean = true

    /**
     * `memoryClass` is below `REMOTE_POOL_MEMORY_CLASS_MB`: a network folder gets the 96-block pool (lead ruling 5), and
     * the Test says "short network drops stop rewind for the channel" (§7.8).
     */
    fun smallRemotePool(): Boolean = false

    /** Runs [block] on the main thread (the settings machine and its shell link are main-thread only). Tests run it inline. */
    fun runOnMain(block: () -> Unit) = block()
}

/** Zero-width marks the settings rows carry for the UI (a `SettingsRow` has no tone for a Link's value). */
object StorageMarks {
    /** A value or description that starts with it is drawn amber (the "NAS offline" fallback value, §9.1). */
    const val WARN = "​"

    fun warn(text: String): String = WARN + text
    fun isWarn(text: String?): Boolean = text != null && text.startsWith(WARN)
    fun plain(text: String): String = text.removePrefix(WARN)
}

/**
 * (WP-S4) A page source that needs the shell link (push, replace, popTo) to move on after a suspend (a sign-in that
 * succeeded, a Test that ended). `SettingsViewModel` hands the machine to every source that implements it, like the
 * adapters in `SettingsPageAdapters` get theirs. Main thread.
 */
interface SettingsNavigationAware {
    fun attachNavigation(link: () -> SettingsShellLink?)
}

/**
 * Every user text [StoragePages] puts on a page (§9, §7.3, §7.7, §7.8, §10). `:app`'s `ResourceStorageTexts` maps each key
 * to `R.string.storage_<key in lower case>` in `strings_storage.xml` (an exhaustive `when`: a missing string fails the
 * build); JVM tests use [StorageTexts.KEYS]. "Rewind", never "buffer"; reason, then next step; no path to a row that the
 * simple view hides (TS "Words on screen").
 */
enum class StorageText {
    // ---- Pause and rewind page (9.1)
    PAUSE_REWIND_SUBTITLE,
    SAVE_TO,
    SAVE_TO_DESC,
    SAVE_TO_DESC_UNREACHABLE,
    SAVE_TO_DESC_SIGN_IN,
    SAVE_TO_OFF_REASON,
    THIS_DEVICE,
    PLACE_THIS_DEVICE,
    PLACE_USB,
    PLACE_USB_DRIVE,
    // ---- Save to (9.2)
    SAVE_SUBTITLE,
    THIS_DEVICE_DESC,
    THIS_DEVICE_DESC_UNKNOWN,
    USB_DRIVE,
    USB_DRIVE_NAMED,
    USB_DRIVE_DESC,
    ADD_NETWORK_FOLDER,
    ADD_NETWORK_FOLDER_DESC,
    // ---- Status of a location
    STATUS_IN_USE,
    STATUS_CANT_REACH,
    STATUS_SIGN_IN_NEEDED,
    STATUS_GOOD_4K,
    STATUS_GOOD_HD,
    STATUS_SLOW,
    STATUS_TOO_SLOW,
    STATUS_NOT_TESTED,
    STATUS_NOT_CONNECTED,
    STATUS_DEMOTED,
    STATUS_TESTED_AGO,
    AGO_TODAY,
    AGO_YESTERDAY,
    AGO_DAYS,
    SIZE_GB,
    SIZE_TB,
    // ---- One location (U-B2)
    DETAIL_STATUS,
    DETAIL_ADDRESS,
    DETAIL_USE,
    DETAIL_USE_DESC,
    DETAIL_TEST,
    DETAIL_NAME,
    DETAIL_SIGN_IN_AGAIN,
    DETAIL_CHANGE_SIGN_IN,
    DETAIL_REMOVE,
    DETAIL_REMOVE_TITLE,
    DETAIL_REMOVE_BODY,
    DETAIL_REMOVE_CONFIRM,
    DETAIL_CONNECT_DRIVE,
    MENU_TEST,
    MENU_SIGN_IN,
    MENU_REMOVE,
    CANCEL,
    NOTICE_WILL_SAVE,
    NOTICE_SIGNED_IN,
    // ---- Find computers (7.1, 7.2)
    FIND_TITLE,
    FIND_SUBTITLE,
    FIND_SEARCHING,
    FIND_SEARCHING_NONET,
    FIND_COUNT,
    FIND_DONE,
    FIND_DONE_ONE,
    FIND_NONE_TITLE,
    FIND_NONE_STEP,
    FIND_VPN_TITLE,
    FIND_VPN_STEP,
    FIND_ENTER,
    FIND_ENTER_DESC,
    FIND_PHONE,
    FIND_PHONE_DESC,
    FIND_AGAIN,
    COMPUTER_NO_NAME,
    COMPUTER_MENU_SIGN_IN,
    // ---- Computer and shares (7.5)
    SHARES_SUBTITLE,
    CONNECTING,
    SHARES_LIST_FAILED,
    SHARES_NONE,
    SHARE_TYPE_NAME,
    SHARE_TYPE_NAME_DESC,
    SHARE_NOT_FOUND,
    SIGN_IN,
    SIGN_IN_AS_SOMEONE,
    TRY_AGAIN,
    // ---- Sign in (7.3)
    SIGN_IN_TITLE,
    SIGN_IN_SUBTITLE,
    SIGN_IN_PHONE,
    SIGN_IN_PHONE_DESC,
    SIGN_IN_PHONE_TITLE,
    SIGN_IN_USER,
    SIGN_IN_PASSWORD,
    SIGN_IN_GO,
    SIGN_IN_GO_NEEDS_USER,
    SIGN_IN_GUEST,
    SIGN_IN_DOMAIN,
    SIGN_IN_DOMAIN_DESC,
    SIGN_IN_INFO_TITLE,
    SIGN_IN_INFO,
    SIGN_IN_NO_KEYBOARD,
    NOT_SET,
    PHONE_SIGNED_IN,
    PHONE_REFUSED,
    PHONE_NO_GUESTS,
    PHONE_NO_ACCESS,
    PHONE_UNREACHABLE,
    // ---- Folder walk (7.6)
    FOLDER_USE,
    FOLDER_UP,
    FOLDER_LOADING,
    FOLDER_TRUNCATED,
    FOLDER_MISSING,
    FOLDER_NONE_BELOW,
    FOLDER_SUBTITLE_ROOT,
    // ---- Test (7.8)
    TEST_TITLE,
    TEST_PHASE_CONNECTING,
    TEST_PHASE_WAKING,
    TEST_PHASE_WRITING,
    TEST_PHASE_READING,
    TEST_PHASE_RTT,
    TEST_PROGRESS_MB,
    TEST_STOP,
    TEST_STOPPED,
    TEST_AGAIN,
    VERDICT_STEP_GOOD_4K,
    VERDICT_STEP_GOOD_HD,
    VERDICT_STEP_SLOW,
    VERDICT_STEP_TOO_SLOW,
    VERDICT_DETAILS,
    TEST_WAKE_NOTE,
    TEST_SMALL_POOL_NOTE,
    TEST_USE_THIS,
    TEST_USE_FOR_REWIND,
    TEST_USE_ANYWAY,
    TEST_CHOOSE_ANOTHER,
    TEST_BACK_TO,
    WIRED,
    WIFI,
    // ---- Errors (7.7): the reason, then its next step
    ERR_UNREACHABLE,
    ERR_UNREACHABLE_NOADDR,
    ERR_UNREACHABLE_STEP,
    ERR_VPN_APPEND,
    ERR_SMB1,
    ERR_SMB1_STEP,
    ERR_AUTH,
    ERR_AUTH_STEP,
    ERR_AUTH_MAC_APPEND,
    ERR_GUEST,
    ERR_GUEST_STEP,
    ERR_ACCESS,
    ERR_ACCESS_STEP,
    ERR_WRITE_USER,
    ERR_WRITE_USER_STEP,
    ERR_WRITE_GUEST,
    ERR_WRITE_GUEST_STEP,
    ERR_NO_NETWORK,
    ERR_NO_NETWORK_STEP,
    ERR_DATA,
    ERR_DATA_STEP,
    ERR_FULL,
    ERR_FULL_STEP,
    ERR_GENERIC,
    ERR_GENERIC_STEP,

}

fun interface StorageTexts {
    /** The text of [key], formatted with [args] (Android `%1$s`-style placeholders). */
    operator fun get(key: StorageText, vararg args: Any): String

    companion object {
        /** Key names, for JVM tests: `KEY` or `KEY(a,b)`. */
        val KEYS = StorageTexts { key, args -> if (args.isEmpty()) key.name else key.name + args.joinToString(",", "(", ")") }
    }
}

/**
 * (WP-S4, §9.1) The place rewind is saved, from the stored locations and the mounted drives: the Pause and rewind rows and
 * the Playback link read it ([TimeshiftPlaceSource]). A chosen place that cannot be reached right now reads as
 * "offline": the cycle saves on this device (the link's amber value, §9.1). No I/O.
 */
class TimeshiftPlaces(
    private val locations: app.thebox.core.storage.StorageLocations,
    private val volumes: app.thebox.core.storage.VolumeCatalog,
    private val texts: StorageTexts,
) : TimeshiftPlaceSource {
    override fun current(): TimeshiftPlace {
        val id = locations.chosen(app.thebox.core.storage.StoragePurpose.TIMESHIFT)
        val device = TimeshiftPlace(
            app.thebox.core.storage.StorageKind.INTERNAL, texts[StorageText.THIS_DEVICE], texts[StorageText.PLACE_THIS_DEVICE],
        )
        if (id == app.thebox.core.storage.StorageConstants.INTERNAL_ID) return device
        val status = locations.status.value.firstOrNull { it.location.id == id }
        val loc = status?.location
        val kind = loc?.kind ?: if (id.startsWith(app.thebox.core.storage.StorageConstants.VOLUME_ID_PREFIX)) app.thebox.core.storage.StorageKind.VOLUME else return device
        val usb = kind == app.thebox.core.storage.StorageKind.VOLUME
        val name = if (usb) texts[StorageText.PLACE_USB] else loc?.name.orEmpty().ifBlank { return device }
        val sentence = if (usb) texts[StorageText.PLACE_USB_DRIVE] else name
        val mounted = usb && volumes.volumes.value.any { app.thebox.core.storage.StorageConstants.VOLUME_ID_PREFIX + it.uuid == id }
        val state = when {
            usb && !mounted -> app.thebox.core.storage.LocationState.NOT_CONNECTED
            else -> status?.state ?: app.thebox.core.storage.LocationState.UNTESTED
        }
        val offline = when (state) {
            app.thebox.core.storage.LocationState.UNREACHABLE, app.thebox.core.storage.LocationState.SIGN_IN_NEEDED,
            app.thebox.core.storage.LocationState.NOT_CONNECTED, app.thebox.core.storage.LocationState.DEMOTED -> true
            else -> false
        }
        val free = status?.freeBytes ?: volumes.volumes.value.firstOrNull { app.thebox.core.storage.StorageConstants.VOLUME_ID_PREFIX + it.uuid == id }?.freeBytes
        return TimeshiftPlace(
            kind = kind, label = name, sentence = sentence,
            facts = free?.let { TimeshiftStorageFacts(totalBytes = it, availableBytes = it) },
            offlineName = if (offline) name else null, problem = state.takeIf { offline },
        )
    }
}
