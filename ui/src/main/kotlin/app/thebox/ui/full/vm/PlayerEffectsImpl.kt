package app.thebox.ui.full.vm

import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.TrackKey
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.VideoDecoderMode

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.10.5): the player machine's effects over the controller, the archive
 * session and the library. Main-safe and non-blocking; suspend work runs on the view model's scope.
 */
class PlayerEffectsImpl(private val vm: PlayerViewModel) : PlayerEffects {
    override fun zap(delta: Int, isRepeat: Boolean): Unit = TODO("WP-D")
    override fun tune(channel: GuideChannel, lineup: GuideLineup?): Unit = TODO("WP-D")
    override fun previousChannel(): Unit = TODO("WP-D")
    override fun retry(): Unit = TODO("WP-D")
    override fun stopPlayback(): Unit = TODO("WP-D")
    override fun pausePlain(): Unit = TODO("WP-D")
    override fun resumePlain(): Unit = TODO("WP-D")
    override fun pauseLive(channel: GuideChannel): Unit = TODO("WP-D")
    override fun archivePause(): Unit = TODO("WP-D")
    override fun archiveResume(): Unit = TODO("WP-D")
    override fun archiveSeek(programmeMs: Long, seq: Long): Unit = TODO("WP-D")
    override fun jumpToLive(): Unit = TODO("WP-D")
    override fun startOver(channel: GuideChannel): Unit = TODO("WP-D")
    override fun rewindLive(channel: GuideChannel, backMs: Long): Unit = TODO("WP-D")
    override fun playProgramme(channel: GuideChannel, info: ProgrammeInfo): Unit = TODO("WP-D")
    override fun tryNextCatchupFormat(): Unit = TODO("WP-D")
    override fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo): Unit = TODO("WP-D")
    override fun openSettings(): Unit = TODO("WP-D")
    override fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int): Unit = TODO("WP-D")
    override fun loadChannel(token: Long, from: GuideChannel, delta: Int): Unit = TODO("WP-D")
    override fun loadListLineup(source: GuideSource): Unit = TODO("WP-D")
    override fun ensureListRows(from: Int, to: Int): Unit = TODO("WP-D")
    override fun ensureListNow(from: Int, to: Int): Unit = TODO("WP-D")
    override fun queryFavourite(token: Long, channel: GuideChannel): Unit = TODO("WP-D")
    override fun setFavourite(channel: GuideChannel, on: Boolean): Unit = TODO("WP-D")
    override fun hideChannel(channel: GuideChannel): Unit = TODO("WP-D")
    override fun selectAudio(key: TrackKey?): Unit = TODO("WP-D")
    override fun selectText(key: TrackKey?): Unit = TODO("WP-D")
    override fun setAspect(mode: AspectMode, remember: Boolean): Unit = TODO("WP-D")
    override fun setDecoder(mode: VideoDecoderMode?): Unit = TODO("WP-D")
    override fun setStatsEnabled(on: Boolean): Unit = TODO("WP-D")
    override fun setScreenOff(on: Boolean): Unit = TODO("WP-D")
    override fun toast(kind: PlayerToast, arg: String?): Unit = TODO("WP-D")
    override fun announce(text: String): Unit = TODO("WP-D")
}
