package app.thebox.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.thebox.core.settings.PhonePurpose
import app.thebox.core.setup.lan.LanFailure
import app.thebox.core.setup.lan.LanLimits
import app.thebox.core.setup.lan.LanServerState
import app.thebox.core.setup.lan.PairingSession
import app.thebox.core.setup.lan.PhoneScreenModel
import app.thebox.core.setup.lan.PhoneScreenState
import app.thebox.core.setup.lan.ServerPhase
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val PHONE_SCREEN_TAG = "phone_setup"
const val PHONE_HINT_TAG = "phone_isolation_hint"
const val PHONE_FOOTER_TAG = "phone_footer"
const val PHONE_TYPE_ON_TV_TAG = "phone_type_on_tv"
const val PHONE_CODE_TAG = "phone_code"
const val PHONE_PLAIN_HTTP_TAG = "phone_plain_http"
/** U-N1 (closure fix pass): the failed phone sign-in line of a NETWORK_FOLDER page. */
const val PHONE_SIGN_IN_FAILED_TAG = "phone_sign_in_failed"

/** Everything the phone screen draws (docs/design/m5-setup-settings.md §3.5.1). */
data class PhoneSetupUi(
    val purpose: PhonePurpose,
    val server: LanServerState,
    val screen: PhoneScreenState = PhoneScreenState(),
    /** When the screen was shown, and now (the isolation hint's clock; a fake clock in tests). */
    val shownAtMs: Long,
    val nowMs: Long,
    val vpn: Boolean = false,
)

/**
 * The TV side of phone-assisted setup (S2, §3.5.1), in design px on the 1920 × 1080 canvas: the QR card at
 * (160, 260), 440 × 440 with 20 padding, black modules on white; the right column at x 680 with the typed URL (mono 34)
 * and the code (mono 64, letter-spacing .2em); a status line; the plain-HTTP footer, replaced by the amber isolation
 * hint after [LanLimits.NO_REQUEST_HINT_MS] without any request, with "Type on the TV instead" when the hint or a VPN
 * shows (SETUP only). OK ("I can't scan") draws the URL and code large instead of the QR. Stateless: keys go through
 * [PhoneScreenModel]; the view model owns the server.
 */
@Composable
fun PhoneSetupScreen(ui: PhoneSetupUi, modifier: Modifier = Modifier) {
    val s = ui.server
    val hint = PhoneScreenModel.showIsolationHint(s, ui.shownAtMs, ui.nowMs)
    val typeOnTv = PhoneScreenModel.showTypeOnTv(hint, ui.vpn, ui.purpose)
    Box(modifier.fillMaxSize().background(DesignTokens.Colors.Canvas).testTag(PHONE_SCREEN_TAG)) {
        Line(stringResource(titleOf(ui.purpose)), GuideUi.sans(44, FontWeight.SemiBold), Modifier.offset(64.dp, 48.dp))
        val folder = ui.purpose == PhonePurpose.NETWORK_FOLDER
        if (folder) {
            // WP-S6 (docs/design/m6-storage-locations.md §7.4): the plain-HTTP line below the title, on the TV too.
            Line(
                stringResource(R.string.storage_phone_plain_http), GuideUi.sans(22, color = GuideUi.Fg65),
                Modifier.offset(64.dp, 124.dp).width(1792.dp).testTag(PHONE_PLAIN_HTTP_TAG), maxLines = 2,
            )
        }
        val failure = s.failure
        if (s.phase == ServerPhase.FAILED && failure != null) {
            Column(Modifier.offset(160.dp, 260.dp).width(1600.dp)) {
                val reason = if (PhoneScreenModel.tooManySignIns(s, ui.purpose)) R.string.storage_phone_fail_sign_ins else failureOf(failure)
                Line(stringResource(reason), GuideUi.sans(36, FontWeight.SemiBold, GuideUi.Warn), maxLines = 3)
                // U-N1: after too many failed sign-ins, the last one's reason too.
                PhoneScreenModel.failedSignIn(s, ui.purpose)?.let { text ->
                    Spacer(Modifier.height(16.dp))
                    Line(text, GuideUi.sans(26, color = GuideUi.Warn), Modifier.testTag(PHONE_SIGN_IN_FAILED_TAG), maxLines = 3)
                }
                Spacer(Modifier.height(24.dp))
                Line(stringResource(R.string.backup_phone_back_hint), GuideUi.sans(26, color = GuideUi.Fg60))
            }
            return@Box
        }
        val base = s.baseUrl
        val code = s.code?.let(PairingSession::displayCode)
        if (base == null || s.qrUrl == null || code == null) {
            Line(stringResource(R.string.backup_phone_starting), GuideUi.sans(36), Modifier.offset(160.dp, 260.dp))
            return@Box
        }
        if (ui.screen.large) {
            Column(Modifier.offset(160.dp, 240.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Line(stringResource(R.string.backup_phone_or_open), GuideUi.sans(36, color = GuideUi.Fg70))
                Line(base, GuideUi.mono(72, FontWeight.Medium))
                Line(stringResource(R.string.backup_phone_and_code), GuideUi.sans(36, color = GuideUi.Fg70))
                Line(code, GuideUi.mono(120, FontWeight.Medium).copy(letterSpacing = 0.2.em), Modifier.testTag(PHONE_CODE_TAG))
            }
        } else {
            Box(Modifier.offset(160.dp, 260.dp).size(440.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp)) {
                QrImage(s.qrUrl!!, 400.dp, stringResource(R.string.backup_phone_scan))
            }
            Column(Modifier.offset(680.dp, 260.dp).width(1176.dp)) {
                Line(stringResource(R.string.backup_phone_scan), GuideUi.sans(36, FontWeight.SemiBold))
                Spacer(Modifier.height(28.dp))
                Line(stringResource(R.string.backup_phone_or_open), GuideUi.sans(26, color = GuideUi.Fg65))
                Line(base, GuideUi.mono(40, FontWeight.Medium))
                Spacer(Modifier.height(20.dp))
                Line(stringResource(R.string.backup_phone_and_code), GuideUi.sans(26, color = GuideUi.Fg65))
                Line(code, GuideUi.mono(64, FontWeight.Medium).copy(letterSpacing = 0.2.em), Modifier.testTag(PHONE_CODE_TAG))
                if (s.wrongCodes >= LanLimits.PAIR_MAX_WRONG) Line(stringResource(R.string.backup_phone_new_code), GuideUi.sans(24, color = GuideUi.Warn))
                Spacer(Modifier.height(28.dp))
                Line(statusText(s, ui.purpose), GuideUi.sans(26))
                // U-N1 (closure fix pass): a failed phone sign-in shows on the TV too, in amber (reason + next step).
                PhoneScreenModel.failedSignIn(s, ui.purpose)?.let { text ->
                    Line(text, GuideUi.sans(24, color = GuideUi.Warn), Modifier.testTag(PHONE_SIGN_IN_FAILED_TAG), maxLines = 3)
                }
                if (folder && s.phase == ServerPhase.PAIRED) {
                    Line(stringResource(R.string.storage_phone_paired_hint), GuideUi.sans(22, color = GuideUi.Fg65), maxLines = 2)
                }
                if (ui.vpn) Line(stringResource(R.string.backup_phone_vpn), GuideUi.sans(24, color = GuideUi.Warn), maxLines = 2)
                Spacer(Modifier.height(16.dp))
                Line(stringResource(R.string.backup_phone_cant_scan), GuideUi.sans(22, color = GuideUi.Fg55))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, end = 64.dp, bottom = 40.dp)) {
            if (typeOnTv) {
                val focused = ui.screen.typeOnTvFocused
                Box(
                    Modifier.testTag(PHONE_TYPE_ON_TV_TAG)
                        .background(if (focused) GuideUi.Fg else GuideUi.White10, RoundedCornerShape(14.dp))
                        .then(if (focused) Modifier.border(GuideUi.FocusRing, GuideUi.Accent, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                ) {
                    Line(stringResource(R.string.backup_phone_type_on_tv), GuideUi.sans(24, FontWeight.SemiBold, if (focused) GuideUi.Canvas else GuideUi.Fg))
                }
                Spacer(Modifier.height(16.dp))
            }
            if (hint) {
                Line(stringResource(R.string.backup_phone_isolation_hint), GuideUi.sans(22, color = GuideUi.Warn), Modifier.width(1792.dp).testTag(PHONE_HINT_TAG), maxLines = 3)
            } else {
                Line(stringResource(R.string.backup_phone_footer), GuideUi.sans(22, color = GuideUi.Fg55), Modifier.testTag(PHONE_FOOTER_TAG), maxLines = 2)
            }
        }
    }
}

@Composable
private fun statusText(s: LanServerState, purpose: PhonePurpose): String = when (s.phase) {
    ServerPhase.PAIRED -> stringResource(R.string.backup_phone_connected, s.pairedFrom ?: "")
    ServerPhase.RECEIVED ->
        stringResource(if (purpose == PhonePurpose.NETWORK_FOLDER) R.string.storage_phone_received else R.string.backup_phone_received)
    else -> stringResource(R.string.backup_phone_waiting)
}

private fun titleOf(p: PhonePurpose): Int = when (p) {
    PhonePurpose.SETUP -> R.string.backup_phone_title_setup
    PhonePurpose.RESTORE -> R.string.backup_phone_title_restore
    PhonePurpose.BACKUP_DOWNLOAD -> R.string.backup_phone_title_backup
    PhonePurpose.LOGS_DOWNLOAD -> R.string.backup_phone_title_logs
    PhonePurpose.NETWORK_FOLDER -> R.string.storage_phone_title // WP-S6, docs/design/m6-storage-locations.md §7.4
}

private fun failureOf(f: LanFailure): Int = when (f) {
    LanFailure.NO_LAN_ADDRESS -> R.string.backup_phone_fail_no_lan
    LanFailure.BIND_FAILED -> R.string.backup_phone_fail_bind
    LanFailure.TOO_MANY_WRONG_CODES -> R.string.backup_phone_fail_wrong_codes
    LanFailure.EXPIRED -> R.string.backup_phone_fail_expired
}
