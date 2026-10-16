package app.thebox.ui.settings

import androidx.compose.ui.graphics.Color
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
    val Title: TextStyle get() = GuideUi.sans(40, FontWeight.SemiBold)
    val Breadcrumb: TextStyle get() = GuideUi.mono(16, color = Fg55)
    val SectionLabel: TextStyle get() = GuideUi.sans(22, FontWeight.SemiBold)
    val PageTitle: TextStyle get() = GuideUi.sans(30, FontWeight.SemiBold)
    val PageSubtitle: TextStyle get() = GuideUi.sans(18, color = Fg60)
    val RowLabel: TextStyle get() = GuideUi.sans(22, FontWeight.Medium)
    val RowValue: TextStyle get() = GuideUi.sans(20)
    val RowValueMono: TextStyle get() = GuideUi.mono(20)
    val RowDesc: TextStyle get() = GuideUi.sans(17)
    val OptionLabel: TextStyle get() = GuideUi.sans(21)
    val OptionDetail: TextStyle get() = GuideUi.sans(17)
    val Notice: TextStyle get() = GuideUi.sans(18, color = Fg70)
    val CardTitle: TextStyle get() = GuideUi.sans(30, FontWeight.SemiBold)
    val CardBody: TextStyle get() = GuideUi.sans(21, color = Fg80).copy(lineHeight = androidx.compose.ui.unit.TextUnit(1.45f, androidx.compose.ui.unit.TextUnitType.Em))
    val Button: TextStyle get() = GuideUi.sans(19, FontWeight.SemiBold)
    val BadgeText: TextStyle get() = GuideUi.mono(14)

    /** Values in mono when they are numbers, times or hosts (§3.1 "Row"): digits-only, a clock time, or a host/URL. */
    fun isMonoValue(v: String): Boolean = v.isNotEmpty() && (MONO.matches(v) || v.contains("://") || HOST.matches(v))
    private val MONO = Regex("^[0-9 :.,/+−\\-–%]+[a-z]{0,3}$")
    private val HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:[0-9]+)?$")

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
    }
}
