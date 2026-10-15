package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.EmptyKind
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val EMPTY_STATE_TAG = "empty_state"

/**
 * Empty states inside the grid panel (docs/design/m3-guide.md §3.8.4, §3.15): each says what is missing and what to do
 * next (CLAUDE.md). [debugHint] (debug builds) names the seeding deep link for "No playlist yet".
 */
@Composable
fun EmptyState(empty: EmptyKind?, modifier: Modifier = Modifier, debugHint: Boolean = false) {
    val kind = empty ?: return
    val (title, step) = when (kind) {
        EmptyKind.NO_PLAYLIST -> R.string.guide_empty_no_playlist to R.string.guide_empty_no_playlist_step
        EmptyKind.EMPTY_SOURCE -> R.string.guide_empty_source to R.string.guide_empty_source_step
        EmptyKind.NO_GUIDE_DATA -> R.string.guide_empty_no_guide to R.string.guide_empty_no_guide_step
    }
    if (kind == EmptyKind.NO_GUIDE_DATA) {
        // A banner, not a replacement: the grid stays usable with NO_EPG rows (WP-D hand-off gap 8, §2.13 item 20).
        Box(
            modifier.offset(81.dp, 892.dp).width(1758.dp).height(56.dp).background(BannerFill, RoundedCornerShape(12.dp)).testTag(EMPTY_STATE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Line(stringResource(title), GuideUi.sans(20, FontWeight.SemiBold))
                Line(stringResource(step), GuideUi.sans(18, color = GuideUi.Fg70))
            }
        }
        return
    }
    Box(modifier.offset(64.dp, 549.dp).width(1792.dp).height(400.dp).testTag(EMPTY_STATE_TAG), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Line(stringResource(title), GuideUi.sans(30, FontWeight.SemiBold))
            Line(stringResource(step), GuideUi.sans(20, color = GuideUi.Fg70))
            if (debugHint && kind == EmptyKind.NO_PLAYLIST) {
                Line(stringResource(R.string.guide_empty_no_playlist_debug), GuideUi.mono(15, color = GuideUi.Fg50))
            }
        }
    }
}

/** Opaque so the banner reads over NO_EPG rows: 8 % white over the canvas colour. */
private val BannerFill = androidx.compose.ui.graphics.Color(0xFF1E2026)
