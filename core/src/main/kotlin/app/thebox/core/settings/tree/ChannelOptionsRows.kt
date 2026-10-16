package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.EffectTiming.NEXT_TUNE
import app.thebox.core.settings.EffectTiming.NONE
import app.thebox.core.settings.EffectTiming.REPREPARE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * WP-A: the per-channel Channel options page, route `Channel(identity)` (docs/design/m5-setup-settings.md §3.3.12),
 * opened from the channel context menu. Per channel, so not counted by `SimpleRowCountTest`. Aspect and decoder go
 * through the controller's live setters when the channel is the tuned one (else they apply at its next tune); buffer is
 * REPREPARE.
 */
object ChannelOptionsRows : RowSource {
    val FAVOURITES = RowId("channel.favourites")
    val HIDE = RowId("channel.hide")
    val LOCK = RowId("channel.lock")
    val FIX_GUIDE = RowId("channel.fix_guide")
    val SHIFT = RowId("channel.shift")
    val ASPECT = RowId("channel.aspect")
    val DECODER = RowId("channel.decoder")
    val BUFFER = RowId("channel.buffer")
    val AUDIO_LANG = RowId("channel.audio_lang")
    val SUBTITLES = RowId("channel.subtitles")
    val EXTERNAL = RowId("channel.external")
    val INFO = RowId("channel.info")

    override val rows: List<RowSpec> = listOf(
        a(FAVOURITES, LIVE), a(HIDE, LIVE), a(LOCK, LIVE), a(FIX_GUIDE, NONE), a(SHIFT, LIVE), a(ASPECT, NEXT_TUNE),
        a(DECODER, NEXT_TUNE), a(BUFFER, REPREPARE), a(AUDIO_LANG, LIVE), a(SUBTITLES, LIVE), a(EXTERNAL, NONE), a(INFO, NONE),
    )

    private fun a(id: RowId, e: EffectTiming) = RowSpec(id, SectionId.CHANNELS, SIMPLE, e, "A")
}
