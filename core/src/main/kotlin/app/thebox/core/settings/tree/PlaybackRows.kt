package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.EffectTiming.NEXT_LAUNCH
import app.thebox.core.settings.EffectTiming.NEXT_TUNE
import app.thebox.core.settings.EffectTiming.NONE
import app.thebox.core.settings.EffectTiming.REPREPARE
import app.thebox.core.settings.EffectTiming.RESTART
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/**
 * WP-A: the Playback section (docs/design/m5-setup-settings.md §3.3.3). `playback.audio_output` is REPREPARE for every
 * value, Always passthrough included (lead ruling §1.9a, F19); RESTART stays only for `playback.ffmpeg`, whose
 * `playback.restart_now` row (N9) appears while the stored value differs from the running engine's.
 * (M6 time-shift commit 0) "On launch" is ADVANCED. (Storage locations commit 0) The time-shift rows moved to
 * [PauseRewindRows]; this page links to them with [PAUSE_REWIND].
 */
object PlaybackRows : RowSource {
    val BUFFER = RowId("playback.buffer")
    val AFR = RowId("playback.afr")
    val AUDIO_OUTPUT = RowId("playback.audio_output")
    val ASPECT = RowId("playback.aspect")
    val ON_LAUNCH = RowId("playback.on_launch")
    val AUDIO_LANGUAGE = RowId("playback.audio_language")
    val SUBTITLES = RowId("playback.subtitles")
    val DECODER = RowId("playback.decoder")
    val FFMPEG = RowId("playback.ffmpeg")
    val RESTART_NOW = RowId("playback.restart_now")
    val TUNNELING = RowId("playback.tunneling")
    val RECONNECT = RowId("playback.reconnect")
    val FAILOVER = RowId("playback.failover")
    val AFTER_STANDBY = RowId("playback.after_standby")
    val HDR_PREVIEW = RowId("playback.hdr_preview")
    val INFO_BAR = RowId("playback.info_bar")
    val NUMBER_WAIT = RowId("playback.number_wait")
    val EXTERNAL_PLAYER = RowId("playback.external_player")
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §9.1) "Pause and rewind": a SIMPLE Link to the
     * [PauseRewindRows] sub-page, effect NONE. The three time-shift rows of M6 moved there.
     */
    val PAUSE_REWIND = RowId("playback.pause_rewind")

    override val rows: List<RowSpec> = listOf(
        a(BUFFER, SIMPLE, REPREPARE),
        a(AFR, SIMPLE, LIVE),
        a(AUDIO_OUTPUT, SIMPLE, REPREPARE),
        a(ASPECT, SIMPLE, NEXT_TUNE),
        a(PAUSE_REWIND, SIMPLE, NONE),
        // (M6 time-shift, lead ruling 1) demoted to ADVANCED so the simple PLAYBACK rows stay at 5 with "Pause and rewind".
        a(ON_LAUNCH, ADVANCED, NEXT_LAUNCH),
        a(AUDIO_LANGUAGE, ADVANCED, NEXT_TUNE),
        a(SUBTITLES, ADVANCED, NEXT_TUNE),
        a(DECODER, ADVANCED, REPREPARE),
        a(FFMPEG, ADVANCED, RESTART),
        a(RESTART_NOW, ADVANCED, NONE),
        a(TUNNELING, ADVANCED, REPREPARE),
        a(RECONNECT, ADVANCED, LIVE),
        a(FAILOVER, ADVANCED, LIVE),
        a(AFTER_STANDBY, ADVANCED, LIVE),
        a(HDR_PREVIEW, ADVANCED, LIVE),
        a(INFO_BAR, ADVANCED, LIVE),
        a(NUMBER_WAIT, ADVANCED, LIVE),
        a(EXTERNAL_PLAYER, ADVANCED, NEXT_TUNE),
    )

    private fun a(id: RowId, v: Visibility, e: EffectTiming) = RowSpec(id, SectionId.PLAYBACK, v, e, "A")
}
