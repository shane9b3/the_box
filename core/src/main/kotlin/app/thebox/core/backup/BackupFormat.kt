package app.thebox.core.backup

import app.thebox.core.settings.BackupPrefs
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain
import kotlinx.serialization.json.Json

/** Appendix B (docs/design/m5-setup-settings.md §3.10.4, §3.10.5, §3.3.9). */
const val BACKUP_MAX_BYTES = 67_108_864L
const val BACKUP_MAX_LINE_BYTES = 1_048_576
const val AUTO_BACKUP_KEEP = 4
const val BACKUP_NUDGE_DAYS = 30
/** The only KDF this version writes; the name and the iteration count travel in the `Secrets` record (S15). */
const val BACKUP_KDF_NAME = "PBKDF2-HMAC-SHA256"
/** A file asking for more is refused as CORRUPT_SECRETS (a crafted file must not hang the TV). */
const val BACKUP_KDF_MAX_ITERATIONS = 5_000_000

/**
 * The `backup` settings domain (§2.2: device-bound, never backed up). WP-A's domain registry should reference this
 * object rather than declare a second one with the same name.
 */
val BackupDomain: SettingsDomain<BackupPrefs> = SettingsDomain(Domains.BACKUP, BackupPrefs.serializer(), BackupPrefs(), backedUp = false)

/** JSON of `.thebox` lines: `t` discriminates records; unknown keys are ignored (minor additions, §2.8). */
val BackupJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Record type names this reader understands (an older reader skips the rest, §3.10.1). */
val KNOWN_RECORD_TYPES: Set<String> = setOf(
    "settings", "playlist", "epg_source", "group_pref", "channel_pref", "fav_list", "fav", "reminder", "epg_mapping", "hide_rule",
    "secrets", "end",
)

/** Header `counts` keys (the restore preview reads them before the file). */
object BackupCounts {
    const val SETTINGS = "settings"
    const val PLAYLISTS = "playlists"
    const val EPG_SOURCES = "epg_sources"
    const val GROUP_PREFS = "group_prefs"
    const val CHANNEL_PREFS = "channel_prefs"
    const val FAV_LISTS = "fav_lists"
    const val FAVOURITES = "favourites"
    const val REMINDERS = "reminders"
    const val EPG_MAPPINGS = "epg_mappings"
    const val HIDE_RULES = "hide_rules"
}

/** `TheBox-backup-2026-10-16-1402.thebox` / `TheBox-auto-2026-10-16.thebox` (§3.10.1, §3.10.5). */
object BackupNames {
    const val EXTENSION = ".thebox"
    const val AUTO_PREFIX = "TheBox-auto-"
    const val MANUAL_PREFIX = "TheBox-backup-"

    fun manual(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        "%s%04d-%02d-%02d-%02d%02d%s".format(java.util.Locale.ROOT, MANUAL_PREFIX, year, month, day, hour, minute, EXTENSION)

    fun auto(year: Int, month: Int, day: Int): String =
        "%s%04d-%02d-%02d%s".format(java.util.Locale.ROOT, AUTO_PREFIX, year, month, day, EXTENSION)

    fun isAuto(name: String): Boolean = name.startsWith(AUTO_PREFIX) && name.endsWith(EXTENSION)
}
