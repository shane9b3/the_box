package app.thebox.core.settings.state

import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmSpec
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.SettingsTree
import java.util.Locale

/** How a channel's stable key was derived, for "Channel info" (the key itself is never shown, §3.3.12). */
enum class IdentityKind { PROVIDER_ID, GUIDE_ID_AND_NAME, ADDRESS, NAME }

/** Everything the Channel options page shows (docs/design/m5-setup-settings.md §3.3.12); read in `:app` from `library.db`. */
data class ChannelOptionsSnapshot(
    val name: String,
    val number: Int?,
    val group: String?,
    val playlistName: String,
    val identityKind: IdentityKind,
    /** Host only, never the path or query (credentials). */
    val streamHost: String?,
    val format: String?,
    val archiveMode: String?,
    val archiveDays: Int?,
    val catchupWinner: String?,
    /** "tvg-id (exact)", "name (fuzzy)" …, or null when unmatched. */
    val epgMatch: String?,
    /** The playlist's server zone is a fixed offset (no daylight saving, M4 §8 Q15). */
    val fixedOffsetZone: Boolean,
    val hidden: Boolean,
    val locked: Boolean,
    /** null = default (the playlist's/guide's shift). */
    val shiftMin: Int?,
    val aspect: AspectMode?,
    val decoder: VideoDecoderMode?,
    val buffer: BufferMode?,
    val audioLanguage: String?,
    /** null = follow settings; "" = off on this channel. */
    val subtitleLanguage: String?,
    /** (list id, name) of every favourites list. */
    val favouriteLists: List<Pair<Long, String>>,
    val memberOf: Set<Long>,
)

/**
 * The Channel options page builder (§3.3.12), pure so its rows are table-tested. Option ids: `default` for "use the
 * global setting", enum names otherwise; the shift's ids are minutes. External hand-off asks first (the address carries
 * the login, §3.3.4).
 */
object ChannelOptionsPage {
    const val DEFAULT = "default"

    fun build(route: SettingsRoute.Channel, s: ChannelOptionsSnapshot?, texts: SettingsTexts, version: Long): PageModel {
        if (s == null) {
            return PageModel(route, texts[SettingsText.CH_INFO], rows = emptyList(), version = version, placeholder = texts[SettingsText.CH_UNKNOWN])
        }
        fun t(k: SettingsText, vararg a: Any) = texts.get(k, *a)
        val rows = ArrayList<SettingsRow>()
        val favOptions = s.favouriteLists.map { (id, name) -> ChoiceOption(id.toString(), name) }
        val favSelected = s.favouriteLists.indices.filter { s.favouriteLists[it].first in s.memberOf }.toSet()
        rows += row(ChannelOptionsRows.FAVOURITES, t(SettingsText.CH_FAVOURITES), RowKind.MultiChoice(favOptions, favSelected), texts,
            value = favSelected.joinToString(", ") { favOptions[it].label }.ifEmpty { t(SettingsText.NONE) },
        ).let { if (favOptions.isEmpty()) it.copy(enabled = false, disabledReason = t(SettingsText.CH_NO_LISTS)) else it }
        rows += row(ChannelOptionsRows.HIDE, t(SettingsText.CH_HIDE), RowKind.Toggle(s.hidden), texts)
        rows += row(ChannelOptionsRows.LOCK, t(SettingsText.CH_LOCK), RowKind.Toggle(s.locked), texts, description = t(SettingsText.CH_LOCK_DESC))
        rows += row(ChannelOptionsRows.FIX_GUIDE, t(SettingsText.CH_FIX_GUIDE), RowKind.Link(SettingsRoute.FixGuide(route.identity)), texts)
        val shifts = listOf(ChoiceOption(DEFAULT, t(SettingsText.DEFAULT))) + (-WpaPages.SHIFT_STEPS..WpaPages.SHIFT_STEPS).map { st ->
            val m = st * 30
            ChoiceOption(m.toString(), if (m == 0) t(SettingsText.SHIFT_NONE) else t(SettingsText.SHIFT_VALUE, WpaPages.shiftHours(m)))
        }
        rows += choice(ChannelOptionsRows.SHIFT, t(SettingsText.CH_SHIFT), shifts, s.shiftMin?.toString() ?: DEFAULT, texts)
        rows += choice(ChannelOptionsRows.ASPECT, t(SettingsText.CH_ASPECT), withDefault(texts, ASPECTS.map { (m, k) -> ChoiceOption(m.name, t(k)) }), s.aspect?.name ?: DEFAULT, texts)
        rows += choice(ChannelOptionsRows.DECODER, t(SettingsText.CH_DECODER), withDefault(texts, DECODERS.map { (m, k) -> ChoiceOption(m.name, t(k)) }), s.decoder?.name ?: DEFAULT, texts)
        rows += choice(ChannelOptionsRows.BUFFER, t(SettingsText.CH_BUFFER), withDefault(texts, BUFFERS.map { (m, k) -> ChoiceOption(m.name, t(k)) }), s.buffer?.name ?: DEFAULT, texts)
        rows += forgetRow(ChannelOptionsRows.AUDIO_LANG, t(SettingsText.CH_AUDIO_LANG), s.audioLanguage?.let { languageName(it) }, texts)
        val subs = when (s.subtitleLanguage) { null -> null; "" -> t(SettingsText.OFF); else -> languageName(s.subtitleLanguage) }
        rows += forgetRow(ChannelOptionsRows.SUBTITLES, t(SettingsText.CH_SUBTITLES), subs, texts)
        rows += row(
            ChannelOptionsRows.EXTERNAL, t(SettingsText.CH_EXTERNAL),
            RowKind.Action(confirm = ConfirmSpec(t(SettingsText.CH_EXTERNAL_CONFIRM_TITLE), t(SettingsText.CH_EXTERNAL_CONFIRM_BODY), t(SettingsText.CH_EXTERNAL_CONFIRM_OK), t(SettingsText.CANCEL))),
            texts,
        )
        rows += row(ChannelOptionsRows.INFO, t(SettingsText.CH_INFO), RowKind.Info(infoLines(s, texts)), texts)
        return PageModel(route, s.name, subtitle = s.playlistName, rows = rows, version = version)
    }

    fun infoLines(s: ChannelOptionsSnapshot, texts: SettingsTexts): List<String> {
        fun t(k: SettingsText, vararg a: Any) = texts.get(k, *a)
        val out = ArrayList<String>()
        s.number?.let { out += t(SettingsText.INFO_NUMBER, it.toString()) }
        s.group?.let { out += t(SettingsText.INFO_GROUP, it) }
        out += t(SettingsText.INFO_PLAYLIST, s.playlistName)
        out += t(SettingsText.INFO_IDENTITY, t(when (s.identityKind) {
            IdentityKind.PROVIDER_ID -> SettingsText.ID_PROVIDER
            IdentityKind.GUIDE_ID_AND_NAME -> SettingsText.ID_GUIDE
            IdentityKind.ADDRESS -> SettingsText.ID_ADDRESS
            IdentityKind.NAME -> SettingsText.ID_NAME
        }))
        s.streamHost?.let { out += t(SettingsText.INFO_HOST, it) }
        s.format?.let { out += t(SettingsText.INFO_FORMAT, it) }
        out += if (s.archiveDays != null && s.archiveDays > 0) t(SettingsText.INFO_ARCHIVE, s.archiveMode ?: t(SettingsText.AUTO), s.archiveDays) else t(SettingsText.INFO_NO_ARCHIVE)
        s.catchupWinner?.let { out += t(SettingsText.INFO_CATCHUP_WINNER, it) }
        out += s.epgMatch?.let { t(SettingsText.INFO_EPG, it) } ?: t(SettingsText.INFO_EPG_NONE)
        out += t(SettingsText.INFO_SHIFT, s.shiftMin?.let { if (it == 0) t(SettingsText.SHIFT_NONE) else t(SettingsText.SHIFT_VALUE, WpaPages.shiftHours(it)) } ?: t(SettingsText.DEFAULT))
        if (s.fixedOffsetZone) out += t(SettingsText.INFO_DST_NOTE)
        return out
    }

    private fun forgetRow(id: RowId, label: String, remembered: String?, texts: SettingsTexts): SettingsRow =
        row(id, label, RowKind.Action(), texts, value = remembered ?: texts[SettingsText.CH_NOT_REMEMBERED], description = if (remembered != null) texts[SettingsText.FORGET] else null)
            .let { if (remembered == null) it.copy(enabled = false, disabledReason = texts[SettingsText.CH_NOT_REMEMBERED]) else it }

    private fun withDefault(texts: SettingsTexts, options: List<ChoiceOption>) = listOf(ChoiceOption(DEFAULT, texts[SettingsText.DEFAULT])) + options

    private fun choice(id: RowId, label: String, options: List<ChoiceOption>, selected: String, texts: SettingsTexts): SettingsRow {
        val i = options.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        return row(id, label, RowKind.Choice(options, i), texts, value = options[i].label)
    }

    private fun row(id: RowId, label: String, kind: RowKind, texts: SettingsTexts, value: String? = null, description: String? = null): SettingsRow {
        val spec = requireNotNull(SettingsTree.spec(id)) { "row ${id.key} has no spec" }
        val effect = when (spec.effect) {
            EffectTiming.NEXT_TUNE -> texts[SettingsText.EFFECT_NEXT_TUNE]
            EffectTiming.REPREPARE -> texts[SettingsText.EFFECT_REPREPARE]
            else -> null
        }
        val desc = listOfNotNull(description, effect).joinToString(texts[SettingsText.SEP]).ifEmpty { null }
        return SettingsRow(id = id, label = label, value = value, description = desc, kind = kind, visibility = spec.visibility, effect = spec.effect)
    }

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifEmpty { code }

    private val ASPECTS = listOf(
        AspectMode.FIT to SettingsText.ASPECT_FIT, AspectMode.FILL to SettingsText.ASPECT_FILL, AspectMode.ZOOM to SettingsText.ASPECT_ZOOM,
        AspectMode.RATIO_16_9 to SettingsText.ASPECT_16_9, AspectMode.RATIO_4_3 to SettingsText.ASPECT_4_3,
    )
    private val DECODERS = listOf(
        VideoDecoderMode.AUTO to SettingsText.DEC_AUTO, VideoDecoderMode.HARDWARE_ONLY to SettingsText.DEC_HW,
        VideoDecoderMode.SOFTWARE_PREFERRED to SettingsText.DEC_SW,
    )
    private val BUFFERS = listOf(
        BufferMode.AUTO to SettingsText.BUF_AUTO, BufferMode.NONE to SettingsText.BUF_NONE, BufferMode.SMALL to SettingsText.BUF_SMALL,
        BufferMode.MEDIUM to SettingsText.BUF_MEDIUM, BufferMode.LARGE to SettingsText.BUF_LARGE, BufferMode.EXTRA_LARGE to SettingsText.BUF_XL,
    )
}
