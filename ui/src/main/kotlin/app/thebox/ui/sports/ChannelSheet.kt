package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Market
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.ui.R
import app.thebox.ui.full.ChannelLogo
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import java.util.Locale

const val CHANNEL_SHEET_TAG = "hub_channel_sheet"
const val CHANNEL_SHEET_NOTE_TAG = "hub_channel_sheet_note"

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §5.3, §5.5, §5.6; WP-5) The Channels sheet: one 96 px row per candidate
 * (logo, name, the §5.3 reason, the playlist line when candidates come from more than one playlist, "Also: FHD, 4K"),
 * ending with the regional note. [archiveOnly]: the final NFL / CHL "Watch from start" rows (OK → [onPlayArchive]).
 * Otherwise OK → [onTune] when [tunable] (live, or starting within `HUB_IMMINENT_MIN`), else [onNotYet] (the page's
 * "The game starts at …" toast). Focus on the first row; Back calls [onClose].
 */
@Composable
fun ChannelSheet(
    game: GameSnapshot,
    candidates: List<ChannelCandidate>,
    tunable: Boolean,
    archiveOnly: Boolean,
    clock24: Boolean,
    onTune: (ChannelCandidate) -> Unit,
    onPlayArchive: (ChannelCandidate) -> Unit,
    onNotYet: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val rows = remember(candidates, archiveOnly) { if (archiveOnly) candidates.filter { it.airing != null } else candidates }
    val manyPlaylists = remember(rows) { rows.map { it.playlistName }.distinct().size > 1 }
    HubSheetFrame(
        title = res.getString(R.string.sports_hub_sheet_channels, game.away.abbrev, game.home.abbrev),
        rowCount = rows.size,
        initialRow = 0,
        focusKey = game.id,
        onClose = onClose,
        modifier = modifier.testTag(CHANNEL_SHEET_TAG),
        footer = {
            item(key = "note") {
                Line(
                    res.getString(R.string.sports_hub_regional_note),
                    GuideUi.sans(22, FontWeight.Medium, GuideUi.Fg70),
                    Modifier.padding(horizontal = 16.dp).testTag(CHANNEL_SHEET_NOTE_TAG),
                    maxLines = 2,
                )
            }
        },
    ) { requesters ->
        rows.forEachIndexed { i, c ->
            item(key = "ch:$i:${c.channel.identityHash}") {
                val name = c.channel.displayName
                val reason = channelReason(res, c, game, clock24)
                val also = alsoLine(res, c)
                HubSheetRow(
                    requesters[i], first = i == 0, last = i == rows.lastIndex, key = "ch:$i", height = 96,
                    description = res.getString(R.string.sports_hub_desc_channel_row, name, c.playlistName, reason),
                    onClick = {
                        when {
                            archiveOnly -> onPlayArchive(c)
                            tunable -> onTune(c)
                            else -> onNotYet()
                        }
                    },
                ) { color ->
                    ChannelLogo(c.channel, 96.dp, 54.dp, 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Line(name, GuideUi.sans(26, FontWeight.SemiBold, color))
                        val second = GuideUi.secondary(color, focusedOf(color), 0.70f)
                        Line(reason, GuideUi.sans(22, FontWeight.Medium, second), Modifier.fillMaxWidth())
                        val third = listOfNotNull(c.playlistName.takeIf { manyPlaylists }, also).joinToString(hubSep(res))
                        if (third.isNotEmpty()) Line(third, GuideUi.sans(22, FontWeight.Medium, second))
                    }
                }
            }
        }
    }
}

/**
 * §5.3 wording for one candidate: the guide listing (CONFIRMED), a sport listing or a national broadcaster (LIKELY), a
 * home / away local channel or the generic line (POSSIBLE).
 */
internal fun channelReason(res: Resources, c: ChannelCandidate, game: GameSnapshot, clock24: Boolean): String {
    fun time(ms: Long) = SportsText.startTime(ms, clock24)
    c.reasons.filterIsInstance<MatchReason.Guide>().firstOrNull()?.let { g ->
        return res.getString(R.string.sports_hub_reason_guide, g.title, timeRange(g.startMs, g.endMs, clock24))
    }
    if (c.confidence != MatchConfidence.POSSIBLE) {
        c.reasons.filterIsInstance<MatchReason.SportListing>().firstOrNull()?.let { s ->
            return res.getString(R.string.sports_hub_reason_sport_listing, s.title, time(s.startMs))
        }
        c.reasons.filterIsInstance<MatchReason.Broadcaster>().firstOrNull { it.market == Market.NATIONAL }?.let { b ->
            val country = b.country?.let { countryName(it) }
            return if (country != null) res.getString(R.string.sports_hub_reason_network, b.network, country)
            else res.getString(R.string.sports_hub_reason_network_any, b.network)
        }
    }
    val b = c.reasons.filterIsInstance<MatchReason.Broadcaster>().firstOrNull()
    return when (b?.market) {
        Market.HOME -> res.getString(R.string.sports_hub_reason_home_local)
        Market.AWAY -> res.getString(R.string.sports_hub_reason_away_local)
        else -> res.getString(R.string.sports_hub_reason_possible)
    }
}

/** "9:00–11:30 PM" (12 h: the period once, at the end, when both ends share it) or "21:00–23:30". */
internal fun timeRange(startMs: Long, endMs: Long, clock24: Boolean): String {
    val a = SportsText.startTime(startMs, clock24)
    val b = SportsText.startTime(endMs, clock24)
    if (clock24) return "$a–$b"
    val sa = a.substringAfterLast(' ', "")
    val sb = b.substringAfterLast(' ', "")
    return if (sa.isNotEmpty() && sa == sb) "${a.substringBeforeLast(' ')}–$b" else "$a–$b"
}

/** "Canada" for "CA"; the code itself when the platform has no name for it. */
private fun countryName(code: String): String? {
    if (code.isBlank()) return null
    val name = Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault())
    return name.takeIf { it.isNotBlank() } ?: code
}

/**
 * "Also: FHD, 4K": for each collapsed variant, the words of its name that the shown channel's name lacks (the quality
 * tag), else its whole name.
 */
internal fun alsoLine(res: Resources, c: ChannelCandidate): String? {
    if (c.variants.isEmpty()) return null
    val mine = words(c.channel.displayName).toSet()
    val tags = c.variants.map { v ->
        val extra = words(v.displayName).filter { it !in mine }
        if (extra.isEmpty()) v.displayName else extra.joinToString(" ")
    }.distinct()
    return res.getString(R.string.sports_hub_also, tags.joinToString(", "))
}

private fun words(s: String): List<String> = s.split(' ', '|', ':', '-', '_', '.', '(', ')', '[', ']').filter { it.isNotBlank() }.map { it.uppercase(Locale.ROOT) }
