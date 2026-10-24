package app.thebox.core.settings.state

import app.thebox.core.settings.SectionId
import app.thebox.core.settings.TextHint
import app.thebox.core.settings.RowId

/**
 * Every user text WP-A's machine and page builders put into a [app.thebox.core.settings.PageModel] (docs/design/
 * m5-setup-settings.md §5 item 11: no hard-coded user text). `:ui`'s `ResourceSettingsTexts` maps each key to
 * `R.string.settings_<key in lower case>` in `strings_settings.xml` (an exhaustive `when`, so a missing string fails the
 * build); JVM tests use [SettingsTexts.KEYS], which returns the key name and its arguments. Generated from one table
 * (keep the enum, the strings file and the mapping in step).
 */
enum class SettingsText {
    SECTION_PLAYLISTS,
    SECTION_GUIDE,
    SECTION_PLAYBACK,
    SECTION_CHANNELS,
    SECTION_INTERFACE,
    SECTION_REMOTE,
    SECTION_PARENTAL,
    SECTION_BACKUP,
    SECTION_ABOUT,
    SECTION_NETWORK,
    ADVANCED_TOGGLE,
    ANNOUNCE_ROW,
    ANNOUNCE_ROW_NO_VALUE,
    CAPTURE_TIMED_OUT,
    ERR_NUMBER_RANGE,
    ERR_EMPTY,
    ERR_URL,
    ERR_HTTPS,
    ERR_PATTERN,
    ERR_HEADER,
    ERR_PASSPHRASE,
    ERR_NUMBER,
    PAGE_NOT_AVAILABLE,
    SEP,
    ON,
    OFF,
    DEFAULT,
    DEFAULT_WITH,
    NONE,
    AUTO,
    NOT_SET,
    AUTOMATIC,
    CANCEL,
    FORGET,
    EFFECT_NEXT_TUNE,
    EFFECT_REPREPARE,
    EFFECT_NEXT_REFRESH,
    EFFECT_NEXT_EPG_IMPORT,
    EFFECT_NEXT_LAUNCH,
    EFFECT_RESTART,
    GUIDE_SOURCES,
    GUIDE_SOURCES_DESC,
    GUIDE_STATUS,
    GUIDE_FIX,
    GUIDE_FIX_DESC,
    GUIDE_INTERVAL,
    INTERVAL_HOURS,
    INTERVAL_DAILY,
    GUIDE_DAILY_HOUR,
    HOUR_VALUE,
    GUIDE_MATCHING,
    MATCH_EXACT,
    MATCH_NAMES,
    MATCH_SUGGEST,
    MATCH_AUTO,
    GUIDE_SHIFT,
    SHIFT_VALUE,
    SHIFT_NONE,
    GUIDE_PAST_DAYS,
    PAST_AUTO,
    DAYS_ONE,
    DAYS_N,
    GUIDE_FUTURE_DAYS,
    GUIDE_DESCRIPTIONS,
    GUIDE_DESCRIPTIONS_DESC,
    // (owner rulings 2026-10-18/19) Programme posters through the app's built-in TMDB key.
    GUIDE_POSTERS,
    GUIDE_POSTERS_DESC,
    GUIDE_POSTERS_DESC_NO_KEY,
    POSTERS_APP_KEY_REJECTED,
    PB_BUFFER,
    /** (M6 time-shift, docs/design/m6-timeshift.md §12, U-N7) */
    PB_BUFFER_DESC,
    BUF_AUTO,
    BUF_NONE,
    BUF_SMALL,
    BUF_MEDIUM,
    BUF_LARGE,
    BUF_XL,
    PB_AFR,
    AFR_OFF,
    AFR_SEAMLESS,
    AFR_ALWAYS,
    AFR_OLD_NOTE,
    PB_AUDIO_OUTPUT,
    AO_AUTO,
    AO_PCM,
    AO_STEREO,
    AO_PASSTHROUGH,
    AO_PASSTHROUGH_DETAIL,
    PB_ASPECT,
    ASPECT_FIT,
    ASPECT_FILL,
    ASPECT_ZOOM,
    ASPECT_16_9,
    ASPECT_4_3,
    PB_ON_LAUNCH,
    LAUNCH_GUIDE_LAST,
    LAUNCH_FULL_LAST,
    LAUNCH_GUIDE_NOTHING,
    // (M6 time-shift commit 0, docs/design/m6-timeshift.md §12) Pause and rewind, Keep recording while paused, Protect built-in storage.
    PB_TIMESHIFT,
    PB_TIMESHIFT_DESC,
    PB_TIMESHIFT_PAUSE_ONLY_DESC,
    TS_AUTO,
    TS_AUTO_LIMITED,
    TS_MIN,
    TS_HOUR,
    TS_HOURS,
    TS_GB,
    TS_PAUSE_ONLY,
    PB_TIMESHIFT_HOLD,
    PB_TIMESHIFT_HOLD_DESC,
    HOLD_UNTIL_FULL,
    PB_TIMESHIFT_PROTECT,
    PB_TIMESHIFT_PROTECT_DESC,
    PB_AUDIO_LANGUAGE,
    LANG_DEVICE,
    PB_SUBTITLES,
    SUBS_OFF,
    SUBS_ON,
    PB_DECODER,
    DEC_AUTO,
    DEC_HW,
    DEC_SW,
    PB_FFMPEG,
    PB_FFMPEG_DESC,
    PB_RESTART_NOW,
    PB_RESTART_NOW_DESC,
    PB_TUNNELING,
    PB_TUNNELING_DESC,
    PB_RECONNECT,
    RECONNECT_UNLIMITED,
    COUNT,
    PB_FAILOVER,
    PB_FAILOVER_DESC,
    PB_AFTER_STANDBY,
    STANDBY_RESUME,
    STANDBY_GUIDE,
    PB_HDR_PREVIEW,
    HDR_POSTER,
    HDR_PLAY,
    PB_INFO_BAR,
    SECONDS,
    PB_NUMBER_WAIT,
    PB_EXTERNAL,
    PB_EXTERNAL_NONE_INSTALLED,
    IF_CLOCK,
    CLOCK_SYSTEM,
    CLOCK_12,
    CLOCK_24,
    IF_MINI,
    MINI_VIDEO,
    MINI_STILL,
    IF_PERFORMANCE,
    PERF_AUTO,
    PERF_HIGH,
    PERF_LOW,
    IF_LOGOS,
    IF_AMBIENT,
    IF_NUMBERS,
    IF_REMINDER_SWITCH,
    IF_REMINDER_LEAD,
    LEAD_MIN,
    RM_MAPPING,
    RM_DEFAULTS,
    RM_ONE_CHANGE,
    RM_CHANGES,
    RM_UPDOWN,
    UD_QUICK,
    UD_ZAP,
    UD_LIST,
    RM_INVERT,
    RM_INVERT_REASON,
    RM_BACK,
    BACK_GUIDE,
    BACK_LIST,
    RM_EXIT,
    EXIT_TWICE,
    EXIT_ASK,
    EXIT_NEVER,
    NET_DNS,
    NET_DNS_DESC,
    DNS_SYSTEM,
    DNS_CLOUDFLARE,
    DNS_GOOGLE,
    DNS_QUAD9,
    DNS_CUSTOM,
    NET_CUSTOM_DOH,
    NET_IPV4,
    IPV4_AUTO,
    IPV4_AUTO_DETAIL,
    NET_CONNECT_TIMEOUT,
    NET_READ_TIMEOUT,
    NET_TIMEOUT_DESC,
    CH_FAVOURITES,
    CH_NO_LISTS,
    CH_HIDE,
    CH_LOCK,
    CH_LOCK_DESC,
    CH_LOCKED_NOTICE,
    CH_FIX_GUIDE,
    CH_SHIFT,
    CH_ASPECT,
    CH_DECODER,
    CH_BUFFER,
    CH_AUDIO_LANG,
    CH_SUBTITLES,
    CH_NOT_REMEMBERED,
    CH_FORGOTTEN,
    CH_EXTERNAL,
    CH_EXTERNAL_NONE,
    CH_EXTERNAL_FAILED,
    CH_EXTERNAL_NOTHING,
    CH_EXTERNAL_CONFIRM_TITLE,
    CH_EXTERNAL_CONFIRM_BODY,
    CH_EXTERNAL_CONFIRM_OK,
    CH_INFO,
    INFO_NUMBER,
    INFO_GROUP,
    INFO_PLAYLIST,
    INFO_IDENTITY,
    ID_PROVIDER,
    ID_GUIDE,
    ID_ADDRESS,
    ID_NAME,
    INFO_HOST,
    INFO_FORMAT,
    INFO_ARCHIVE,
    INFO_NO_ARCHIVE,
    INFO_CATCHUP_WINNER,
    INFO_EPG,
    INFO_EPG_NONE,
    INFO_SHIFT,
    INFO_DST_NOTE,
    CH_UNKNOWN,
    SAVED,
    RESTART_FAILED,
    DOH_FALLBACK,
    EPG_MANUAL,
    EPG_TVG_ID,
}

fun interface SettingsTexts {
    /** The text of [key], formatted with [args] (Android `%1$s`-style placeholders); also `texts[KEY, args]`. */
    operator fun get(key: SettingsText, vararg args: Any): String

    fun section(id: SectionId): String = get(SECTION_TEXT.getValue(id))

    companion object {
        /** Key names, for JVM tests: `KEY` or `KEY(a,b)`. */
        val KEYS = SettingsTexts { key, args -> if (args.isEmpty()) key.name else key.name + args.joinToString(",", "(", ")") }

        val SECTION_TEXT: Map<SectionId, SettingsText> = SectionId.entries.associateWith { SettingsText.valueOf("SECTION_" + it.name) }
    }
}

/** Done in the editor (§3.2.2): null = valid, else the error line (the editor stays open). */
fun interface SettingsValidator {
    fun validate(row: RowId, hint: TextHint, text: String): String?
}

/**
 * The validation every text row gets from its [TextHint] (§3.2.2 "Done validates through the row's validator"). Rows
 * with a stricter rule (the custom DoH address is https only) are checked by [rowRules] first.
 */
class HintValidator(
    private val texts: SettingsTexts,
    private val rowRules: Map<RowId, (String) -> String?> = emptyMap(),
) : SettingsValidator {
    override fun validate(row: RowId, hint: TextHint, text: String): String? {
        rowRules[row]?.invoke(text)?.let { return it }
        val t = text.trim()
        return when (hint) {
            TextHint.URL -> if (isHttpUrl(t)) null else texts[SettingsText.ERR_URL]
            // C-L7: a pattern is a regex only in its `/regex/` form (the custom prefix); literals, Contains rules and ""
            // (clears the prefix) are valid here. A hide rule in Regex mode is checked by its live preview (§3.7.3).
            TextHint.PATTERN -> if (t.length >= 2 && t.startsWith("/") && t.endsWith("/")) try {
                app.thebox.core.text.UserRegex.compile(t.substring(1, t.length - 1)); null
            } catch (e: IllegalArgumentException) {
                texts[SettingsText.ERR_PATTERN]
            } else null
            TextHint.HEADER -> if (HEADER.matches(t)) null else texts[SettingsText.ERR_HEADER]
            TextHint.PASSPHRASE -> if (text.length >= PASSPHRASE_MIN) null else texts[SettingsText.ERR_PASSPHRASE, PASSPHRASE_MIN]
            TextHint.NUMBER -> if (t.isNotEmpty() && t.all { it.isDigit() }) null else texts[SettingsText.ERR_NUMBER]
            TextHint.NAME, TextHint.USERNAME -> if (t.isEmpty()) texts[SettingsText.ERR_EMPTY] else null
            TextHint.PASSWORD -> null
            // (Storage locations commit 0, §7.2) not empty; WP-S4 checks the form with ManualAddressParser (a row rule).
            TextHint.HOST -> if (t.isEmpty()) texts[SettingsText.ERR_EMPTY] else null
        }
    }

    companion object {
        /** `BACKUP_PASSPHRASE_MIN` (Appendix B). */
        const val PASSPHRASE_MIN = 8
        private val HEADER = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+\\s*[:=]\\s*\\S.*$")

        fun isHttpUrl(t: String): Boolean {
            val m = Regex("^(https?)://([^/\\s:?#]+)(:\\d{1,5})?([/?#]\\S*)?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return false
            return m.groupValues[2].isNotEmpty()
        }

        fun isHttpsUrl(t: String): Boolean = isHttpUrl(t.trim()) && t.trim().startsWith("https://", ignoreCase = true)
    }
}
