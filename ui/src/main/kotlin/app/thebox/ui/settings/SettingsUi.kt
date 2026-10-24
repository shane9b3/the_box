package app.thebox.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.thebox.core.settings.Badge
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Tone
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.theme.DesignTokens

/**
 * Tokens and geometry of the settings kit (docs/design/m5-setup-settings.md §3.1), in design px (1.dp = 1 design px
 * inside `BoxDesignScale`). Same language as the guide: glass panels with no blur, solid FG focus with dark text and a
 * 3 px accent ring. The kit draws its own toggle, radio and rows (N7: no tv-material `Switch`, `RadioButton`, `ListItem`).
 */
object SettingsUi {
    // ---- colours
    val Canvas = DesignTokens.Colors.Canvas
    val Fg = DesignTokens.Colors.Fg
    val Accent = DesignTokens.Colors.Accent
    val GlassFill = DesignTokens.Colors.GlassFill
    val GlassBorder = DesignTokens.Colors.GlassBorder
    /** Normative amber of the error lines (§3.1). */
    val Amber = Color(0xFFF2B25C)
    val Good = Color(0xFF6FD39A)
    val Bad = DesignTokens.Colors.Live
    /** Confirm card and context menu fill: rgba(20,25,36,.96), no blur. */
    val CardFill = Color(0xF5141924)
    val CardBorder = Color.White.copy(alpha = 0.12f)
    val Fg92 = Fg.copy(alpha = 0.92f)
    val Fg85 = Fg.copy(alpha = 0.85f)
    val Fg80 = Fg.copy(alpha = 0.80f)
    val Fg70 = Fg.copy(alpha = 0.70f)
    val Fg65 = Fg.copy(alpha = 0.65f)
    val Fg60 = Fg.copy(alpha = 0.60f)
    val Fg55 = Fg.copy(alpha = 0.55f)
    val Fg45 = Fg.copy(alpha = 0.45f)
    val Fg40 = Fg.copy(alpha = 0.40f)
    val White03 = Color.White.copy(alpha = 0.03f)
    val White08 = Color.White.copy(alpha = 0.08f)
    val White12 = Color.White.copy(alpha = 0.12f)
    val White16 = Color.White.copy(alpha = 0.16f)
    val White22 = Color.White.copy(alpha = 0.22f)
    const val DISABLED_ALPHA = 0.4f

    // ---- geometry (§3.1 table)
    const val HEADER_X = 64; const val HEADER_Y = 48; const val CLOCK_RIGHT = 64
    const val BREADCRUMB_Y = 104
    const val PANE_Y = 144; const val PANE_H = 872
    const val SECTIONS_X = 64; const val SECTIONS_W = 420
    const val PAGE_X = 516; const val PAGE_W = 1340
    /** Page pane with no sections pane (FirstRun, detached routes). */
    const val PAGE_SOLO_X = 64; const val PAGE_SOLO_W = 1792
    const val PANE_RADIUS = 24
    const val SECTIONS_PADDING = 16
    const val SECTION_ROW_H = 64; const val SECTION_ROW_GAP = 6; const val SECTION_LABEL_X = 24
    const val PAGE_PAD_H = 32; const val PAGE_PAD_V = 24
    const val ROWS_TOP = 96
    const val ROW_H = 76; const val ROW_H_DESC = 104; const val ROW_GAP = 8; const val ROW_RADIUS = 14; const val ROW_PAD_H = 24
    const val ITEM_ROW_H = 72
    const val TOGGLE_W = 56; const val TOGGLE_H = 30; const val TOGGLE_KNOB = 24
    const val CHOICE_INDENT = 48; const val CHOICE_ROW_H = 60; const val RADIO = 18
    const val NOTICE_H = 40
    const val CONFIRM_W = 720; const val CARD_RADIUS = 24; const val CARD_PAD = 32
    const val MENU_W = 420; const val MENU_ROW_H = 60
    const val EDITOR_W = 1100; const val EDITOR_TOP = 120; const val FIELD_H = 64
    const val PAD_W = 560; const val PAD_KEY_W = 112; const val PAD_KEY_H = 80; const val PAD_GAP = 12
    const val FOCUS_RING = 3
    const val BADGE_RADIUS = 7

    // ---- type
    val Title: TextStyle get() = GuideUi.sans(44, FontWeight.SemiBold)
    val Breadcrumb: TextStyle get() = GuideUi.mono(22, color = Fg55)
    val SectionLabel: TextStyle get() = GuideUi.sans(28, FontWeight.SemiBold)
    val PageTitle: TextStyle get() = GuideUi.sans(36, FontWeight.SemiBold)
    val PageSubtitle: TextStyle get() = GuideUi.sans(24, color = Fg60)
    val RowLabel: TextStyle get() = GuideUi.sans(28, FontWeight.Medium)
    val RowValue: TextStyle get() = GuideUi.sans(26)
    /** The sections pane's "Show advanced settings" row: one size down so it fits beside the toggle in 420 px. */
    val AdvancedLabel: TextStyle get() = GuideUi.sans(22)
    val RowValueMono: TextStyle get() = GuideUi.mono(26)
    val RowDesc: TextStyle get() = GuideUi.sans(22)
    val OptionLabel: TextStyle get() = GuideUi.sans(26)
    val OptionDetail: TextStyle get() = GuideUi.sans(22)
    val Notice: TextStyle get() = GuideUi.sans(24, color = Fg70)
    val CardTitle: TextStyle get() = GuideUi.sans(36, FontWeight.SemiBold)
    val CardBody: TextStyle get() = GuideUi.sans(26, color = Fg80).copy(lineHeight = androidx.compose.ui.unit.TextUnit(1.45f, androidx.compose.ui.unit.TextUnitType.Em))
    val Button: TextStyle get() = GuideUi.sans(24, FontWeight.SemiBold)
    val BadgeText: TextStyle get() = GuideUi.mono(18)

    /**
     * Values in mono when they are numbers, times or hosts (§3.1 "Row"): digits-only, a clock time, or a host/URL.
     * (WP-S4, storage locations §9.2, §9.4) Also a network path (`\\NAS\TV\Rewind`), a Test's "9 of 16 MB" and its
     * "10.5 MB/s · 4 ms · Wi-Fi" details.
     */
    fun isMonoValue(v: String): Boolean =
        v.isNotEmpty() && (MONO.matches(v) || v.contains("://") || HOST.matches(v) || v.startsWith("\\\\") || PROGRESS.matches(v) || DETAILS.matches(v))
    private val MONO = Regex("^[0-9 :.,/+−\\-–%]+[a-z]{0,3}$")
    private val PROGRESS = Regex("^[0-9]+ \\S+ [0-9]+ MB$")
    private val DETAILS = Regex("^[0-9.]+ MB/s · [0-9]+ ms · .+$")
    private val HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:[0-9]+)?$")

    // ---- (WP-S4, storage locations §9.4) badges, the Test's progress row and the searching dots
    /** `Badge.IN_USE` and `Badge.SAVED`: a 1 px accent border; text FG .9 on a row, the canvas colour on a focused (FG-filled) row. */
    fun isAccentBadge(b: Badge): Boolean = b == Badge.IN_USE || b == Badge.SAVED
    fun accentBadgeText(focused: Boolean): Color = if (focused) Canvas else Fg.copy(alpha = 0.9f)
    const val ACCENT_BADGE_BORDER = 1

    const val PROGRESS_TRACK_W = 1228; const val PROGRESS_TRACK_H = 8
    const val PROGRESS_ROW_H = 104
    /** Three dots (6 px, 3 px apart: 24 px wide) at the row's right, alpha .3 → 1, 400 ms per dot. */
    const val DOT_SIZE = 6; const val DOT_GAP = 3; const val DOT_MS = 400
    const val DOTS_RIGHT_X = 1220
    /** An Info row of `storage.*` with next-step lines: one line each under the label (§7.7, §9.4: no clipped next step). */
    const val STORAGE_INFO_LINE_H = 28

    /** "9 of 16 MB" → 0.5625; null when it is not a progress value. */
    fun progressFraction(value: String?): Float? {
        val m = value?.let { Regex("^([0-9]+) \\S+ ([0-9]+) MB$").matchEntire(it) } ?: return null
        val done = m.groupValues[1].toFloat()
        val total = m.groupValues[2].toFloat()
        return if (total > 0f) (done / total).coerceIn(0f, 1f) else null
    }

    /** True for a value or description a page marked amber (the fallback value "NAS offline", §9.1). */
    fun isWarnText(text: String?): Boolean = app.thebox.core.settings.state.StorageMarks.isWarn(text)

    fun toneColor(t: Tone): Color = when (t) {
        Tone.NORMAL -> Fg80
        Tone.GOOD -> Good
        Tone.WARN -> Amber
        Tone.BAD -> Bad
    }

    fun sectionLabel(id: SectionId): Int = when (id) {
        SectionId.PLAYLISTS -> R.string.settings_section_playlists
        SectionId.GUIDE -> R.string.settings_section_guide
        SectionId.PLAYBACK -> R.string.settings_section_playback
        SectionId.CHANNELS -> R.string.settings_section_channels
        SectionId.INTERFACE -> R.string.settings_section_interface
        SectionId.REMOTE -> R.string.settings_section_remote
        SectionId.PARENTAL -> R.string.settings_section_parental
        SectionId.BACKUP -> R.string.settings_section_backup
        SectionId.ABOUT -> R.string.settings_section_about
        SectionId.NETWORK -> R.string.settings_section_network
    }

    fun badgeLabel(b: Badge): Int = when (b) {
        Badge.LOCKED -> R.string.settings_ui_badge_locked
        Badge.HIDDEN -> R.string.settings_ui_badge_hidden
        Badge.ADULT -> R.string.settings_ui_badge_adult
        Badge.FAILED -> R.string.settings_ui_badge_failed
        Badge.NEW -> R.string.settings_ui_badge_new
        Badge.DEFAULT -> R.string.settings_ui_badge_default
        // (Storage locations commit 0, docs/design/m6-storage-locations.md §9.2, §9.4) WP-S4 draws them (accent border).
        Badge.IN_USE -> R.string.settings_ui_badge_in_use
        Badge.SAVED -> R.string.settings_ui_badge_saved
    }
}

/**
 * (WP-S4, §9.4) The Test's progress track: 1,228 x 8 px, `#FFFFFF` at 18 % with the done part in the accent. The row's
 * `contentDescription` ("Testing, 9 of 16 MB") is set by the caller.
 */
@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape((SettingsUi.PROGRESS_TRACK_H / 2).dp)
    Box(
        modifier.width(SettingsUi.PROGRESS_TRACK_W.dp).height(SettingsUi.PROGRESS_TRACK_H.dp).background(Color.White.copy(alpha = 0.18f), shape),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(SettingsUi.PROGRESS_TRACK_H.dp).background(SettingsUi.Accent, shape))
    }
}

/** True when the system animations are off (`ANIMATOR_DURATION_SCALE` == 0): the dots then stay still (U-N5). */
@Composable
private fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * (WP-S4, §9.4) The searching row's three dots, alpha .3 → 1 one after another (400 ms each); static at full alpha when the
 * system animations are off. `contentDescription` "Searching" is the caller's string.
 */
@Composable
fun SearchingDots(description: String, modifier: Modifier = Modifier) {
    val off = animationsOff()
    val transition = rememberInfiniteTransition(label = "searching")
    val phase = if (off) 0f else transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(SettingsUi.DOT_MS * 3, easing = LinearEasing), RepeatMode.Restart), label = "dots",
    ).value
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SettingsUi.DOT_GAP.dp)) {
        for (i in 0 until 3) {
            val a = if (off) 1f else if (phase.toInt() == i) 1f else 0.3f
            Box(Modifier.size(SettingsUi.DOT_SIZE.dp).alpha(a).background(SettingsUi.Fg, CircleShape))
        }
    }
}
