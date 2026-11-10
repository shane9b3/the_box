// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.10. Verbatim; changes go through the lead.
package app.thebox.core.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val schema: Int,
    /** (reviewed 2026-10-16, S7) Monotonic per channel; the client stores the highest accepted and rejects lower ones (replay). (M5 integration 2026-10-16, lead ruling) An equal sequence is accepted: the daily re-check sees the same manifest again. */
    val sequence: Long,
    val packageName: String,
    val channel: String = "stable",
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val publishedAt: String,
    val notes: String,
    val apks: List<ApkEntry>,
    /** SHA-256 of the APK signing certificate(s), hex, upper case without colons. */
    val signingCertSha256: List<String>,
    val withdrawn: List<Long> = emptyList(),
    /** 0–100; an install whose random bucket (0–99, stored once) ≥ this ignores the release. */
    val rolloutPercent: Int = 100,
)
@Serializable data class ApkEntry(val abi: String, val url: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class UpdatePrefs(
    val checkAutomatically: Boolean = true, val lastCheckMs: Long? = null, val skippedVersionCode: Long? = null, val bucket: Int? = null, val lastInstalledNotice: Long? = null, val highestSequence: Long = 0,
    /**
     * (code review 2026-11-10, B1; docs/design/m5-update-visible-install.md) The versionCode whose hand-off through the
     * system installer activity ended without the update, and how often ([installerRouteMisses]). At
     * [InstallRoute.MISSES_BEFORE_SWITCH] the next Update of that versionCode uses the session. Cleared once a version at
     * least that new runs. Defaults keep older stored documents readable.
     */
    val installerRouteVersionCode: Long? = null,
    val installerRouteMisses: Int = 0,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val manifest: UpdateManifest, val apk: ApkEntry) : UpdateState
    data class Downloading(val versionName: String, val bytes: Long, val total: Long) : UpdateState
    /** (2026-11-10, docs/design/m5-update-visible-install.md) The downloaded file is being checked (hash, package, versionCode, signer). */
    data class Verifying(val versionName: String) : UpdateState
    data class NeedsPermission(val versionName: String) : UpdateState
    data class ReadyToInstall(val versionName: String) : UpdateState
    data object Installing : UpdateState
    /** (2026-11-10) The app came back from the system installer with the old version still installed; Update tries again. */
    data class NotInstalled(val versionName: String) : UpdateState
    data class Failed(val reason: UpdateFailure, val detail: String?) : UpdateState
}
/** INSTALL_RESTRICTED (code review 2026-11-10, S6): a user restriction forbids apps from outside the store. */
enum class UpdateFailure { NETWORK, BAD_SIGNATURE, BAD_MANIFEST, REPLAYED, NOT_NEWER, HASH_MISMATCH, WRONG_PACKAGE, WRONG_CERT, NO_SPACE, INSTALL_BLOCKED, INSTALL_FAILED, DISABLED, INSTALL_RESTRICTED }

interface Updater {
    val state: kotlinx.coroutines.flow.StateFlow<UpdateState>
    fun check(manual: Boolean)
    fun download()
    fun install()
    fun skip(versionCode: Long)
}

/**
 * The S17 card in the guide (never in FULL). Buttons: Update / Later / Skip this version. [focused] is the focused button;
 * (C-S2, code review 2026-10-16) −1 while the card is shown but does not hold the focus (it takes it after 3 s with no
 * key, §3.12.3): no button is highlighted and the guide keeps every key.
 */
data class UpdateCardState(val versionName: String, val notes: String, val progress: Float? = null, val failed: String? = null, val focused: Int = 0, val phase: UpdateCardPhase = UpdateCardPhase.OFFER)

/**
 * (2026-11-10, docs/design/m5-update-visible-install.md §1, §5) What the card is doing; it picks the status line under
 * "Version X" and whether the buttons show. [UpdateCardState.failed] holds a failure's text, the phase only the layout
 * (review N6): FAILED always comes with a non-null `failed`.
 */
enum class UpdateCardPhase {
    OFFER, DOWNLOADING, VERIFYING, OPENING_INSTALLER, NEEDS_PERMISSION, NOT_INSTALLED, FAILED;

    /** Busy phases show no buttons; BACK hides the card and the work carries on (review S2). */
    val busy: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == OPENING_INSTALLER
}
enum class UpdateAction { UPDATE, LATER, SKIP }
