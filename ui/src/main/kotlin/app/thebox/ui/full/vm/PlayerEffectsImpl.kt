package app.thebox.ui.full.vm

import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.settings.SettingsRoute
import app.thebox.ui.root.EffectDelegates
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.TrackKey
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.rawEndMin
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.ui.guide.vm.LineupZapList

/**
 * The player machine's effects over the controller, the archive session and the library (docs/design/m4-player-ux.md
 * §3.10.5). Main-safe and non-blocking: suspend work runs on the view model's scope (main-immediate; the callees switch
 * to IO), answers come back as [PlayerEvent]s. Toast ownership as in M3 (§2.13 item 9): effects toast what they learn
 * asynchronously (favourite, hide, catch-up failure, reminder), the machine toasts the rest.
 */
class PlayerEffectsImpl(
    private val vm: PlayerViewModel,
    /** (M5 commit 0, docs/design/m5-setup-settings.md §1.3a) What the M5 effects delegate to. */
    private val delegates: EffectDelegates = EffectDelegates.NONE,
) : PlayerEffects {
    private val controller get() = vm.controller
    private val archive get() = vm.archive

    override fun zap(delta: Int, isRepeat: Boolean) = controller.zap(delta, isRepeat)

    override fun tune(channel: GuideChannel, lineup: GuideLineup?) {
        if (lineup != null) vm.zapLineup = lineup
        controller.tune(TuneTarget.Channel(channel.identity), lineup?.let { LineupZapList(it, vm.library) })
    }

    override fun previousChannel() {
        if (!controller.previousChannel()) vm.toast(PlayerToast.NO_PREVIOUS, null)
    }

    /**
     * Error card Retry (F5): on an archive error (the session is FAILED) a re-request at the playhead through
     * `ArchivePlayback.resume()` (WP-B: there is no separate retry member; never `controller.retry()`, which restarts the
     * original request at its start offset). Otherwise the controller's fresh ladder cycle.
     */
    override fun retry() {
        val s = archive.status.value
        if (s.phase == ArchivePhase.FAILED && s.channel != null) {
            vm.archiveActiveAtMs = vm.nowMs()
            archive.resume()
        } else {
            controller.retry()
        }
    }

    override fun stopPlayback() = controller.stop(StopReason.USER)

    // ---- M5 commit 0 (docs/design/m5-setup-settings.md §1.3a, §3.14): one-line delegations

    /** Error-card routes open detached and return to FULL on BACK (S2). */
    override fun openSettings(route: SettingsRoute) = delegates.settings.openSettings(route, fromFull = true, detached = true)

    override fun errorAction(button: ErrorButton, card: ErrorCardState) = delegates.errors.run(button, card)

    override fun setChannelLocked(channel: GuideChannel, locked: Boolean) = delegates.parental.setChannelLocked(channel, locked)

    /**
     * (U-S12, code review 2026-10-16) The AUDIO picker's "Back to Auto": the same path as the AUDIO_OUTPUT_FAILED card's
     * button (`ErrorRouteActions` BACK_TO_AUTO: `playback.audioOutput = AUTO`, one re-prepare, the toast). The card
     * argument is not read for this button.
     */
    override fun audioOutputAuto() = delegates.errors.run(
        app.thebox.core.full.ErrorButton.BACK_TO_AUTO,
        app.thebox.core.full.ErrorCardState(
            app.thebox.core.playback.PlaybackError(app.thebox.core.playback.PlaybackErrorCode.AUDIO_OUTPUT_FAILED), null,
            listOf(app.thebox.core.full.ErrorButton.BACK_TO_AUTO), 0, null, archiveFailed = false,
        ),
    )

    /** (M5 integration, §3.6.2) The Sources picker. */
    override fun selectServer(index: Int) = controller.selectServer(index)
    override fun pausePlain() = controller.pause()
    override fun resumePlain() = controller.resume()

    override fun pauseLive(channel: GuideChannel) {
        vm.archiveActiveAtMs = vm.nowMs()
        archive.pauseLive(channel)
    }

    override fun archivePause() = archive.pause()

    override fun archiveResume() {
        vm.archiveActiveAtMs = vm.nowMs()
        archive.resume()
    }

    override fun archiveSeek(programmeMs: Long, seq: Long) = archive.seekTo(programmeMs, seq)

    /** Back to live: the archive session's own jump; with no session left (IDLE) a live tune of the archive's channel. */
    override fun jumpToLive() {
        vm.archiveActiveAtMs = vm.nowMs()
        val s = archive.status.value
        val target = controller.state.value.target
        if (s.phase == ArchivePhase.IDLE && target?.isArchive == true && target.identity != null) {
            controller.tune(TuneTarget.Channel(target.identity!!), null)
        } else {
            archive.jumpToLive()
        }
    }

    override fun startOver(channel: GuideChannel) = launchArchive("startOver") { archive.startOver(channel) }

    override fun rewindLive(channel: GuideChannel, backMs: Long) = launchArchive("rewindLive") { archive.rewindLive(channel, backMs) }

    override fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, timeShift: Boolean) =
        launchArchive("playProgramme") { archive.playProgramme(channel, info, info.rawEndMin, timeShift) } // P-B1

    override fun tryNextCatchupFormat() {
        if (!archive.tryNextVariant()) vm.toast(PlayerToast.CATCHUP_FAILED, ArchiveFailure.NOT_IN_ARCHIVE.name)
    }

    /** (S7) Info bar OK on a future programme: M3's reminder store; set when none is pending, else cancel. */
    override fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo) {
        vm.launchSafely("reminder") {
            val set = vm.reminders.set(channel, info, vm.guideSettings.settings.value.reminderLeadSec)
            if (set) {
                vm.guideToast(GuideToast.REMINDER_SET, null)
            } else {
                vm.reminders.cancel(channel.identity, info.rawStartMin)
                vm.guideToast(GuideToast.REMINDER_CANCELLED, null)
            }
        }
    }

    /** (owner ruling 2026-10-15) Quick bar → Settings: the guide machine's M3 Settings route. */
    override fun openSettings() {
        vm.guide?.dispatch(GuideEvent.OpenSettings)
    }

    /** §3.2.2 (S4): `ProgrammeFeed.around` from `aroundMin − before`, 12 programmes either side. */
    override fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int) {
        vm.launchSafely("programmes") {
            val slice = vm.feed.around(channel, atMin = aroundMin, fromMin = aroundMin - before, before = AROUND_COUNT, after = AROUND_COUNT)
            // `around` rows are title-only (WP-C); the focused programme's synopsis is the view model's own `at` call
            // (PlayerViewModel.focusedDetail, lead ruling 2026-10-15).
            vm.dispatch(PlayerEvent.Programmes(token, channel.identityHash, slice.programmes))
        }
    }

    /** §3.10.5: the current zap lineup's neighbour, then a row lookup (no new list type). */
    override fun loadChannel(token: Long, from: GuideChannel, delta: Int) {
        val lineup = vm.zapLineup
        if (lineup == null) {
            vm.dispatch(PlayerEvent.BrowseChannel(token, null))
            return
        }
        vm.launchSafely("browseChannel") {
            val id = LineupZapList(lineup, vm.library).neighbour(from.identity, delta)
            vm.dispatch(PlayerEvent.BrowseChannel(token, id?.let { vm.channelOf(it) }))
        }
    }

    override fun loadListLineup(source: GuideSource) = vm.loadListLineup(source)
    override fun ensureListRows(from: Int, to: Int) = vm.ensureListRows(from, to)
    override fun ensureListNow(from: Int, to: Int) = vm.ensureListNow(from, to)

    override fun queryFavourite(token: Long, channel: GuideChannel) {
        vm.launchSafely("isFavourite") { vm.dispatch(PlayerEvent.Favourite(token, vm.library.isFavourite(channel.identity))) }
    }

    override fun setFavourite(channel: GuideChannel, on: Boolean) {
        vm.launchSafely("favourite") {
            vm.library.setFavourite(channel.identity, on)
            vm.toast(if (on) PlayerToast.ADDED_FAVOURITE else PlayerToast.REMOVED_FAVOURITE, channel.displayName)
        }
    }

    override fun hideChannel(channel: GuideChannel) {
        vm.launchSafely("hide") {
            vm.library.hideChannel(channel.identity)
            vm.toast(PlayerToast.CHANNEL_HIDDEN, channel.displayName)
        }
    }

    override fun selectAudio(key: TrackKey?) = controller.selectAudioTrack(key?.let(PlayerMappers::ref), remember = true)
    override fun selectText(key: TrackKey?) = controller.selectTextTrack(key?.let(PlayerMappers::ref), remember = true)
    override fun setAspect(mode: AspectMode, remember: Boolean) = controller.setAspectMode(mode, remember)
    override fun setDecoder(mode: VideoDecoderMode?) = controller.setVideoDecoderOverride(mode, remember = true)

    override fun setStatsEnabled(on: Boolean) {
        vm.statsOn.value = on
        controller.setStatsEnabled(on)
    }

    /**
     * §3.10.5: the root maps the SCREEN_OFF layer to `HoleMode.COVERED` (viewport HIDDEN) and back, through its one
     * viewport rule; nothing to do here, so the viewport has a single owner.
     */
    override fun setScreenOff(on: Boolean) = Unit

    override fun toast(kind: PlayerToast, arg: String?) = vm.toast(kind, arg)
    override fun announce(text: String) = vm.announce(text)

    /** Archive starts run on the view model's scope; a synchronous failure toasts CATCHUP_FAILED with the reason. */
    private fun launchArchive(what: String, start: suspend () -> ArchiveStart) {
        vm.archiveActiveAtMs = vm.nowMs()
        vm.launchSafely(what) {
            when (val r = start()) {
                ArchiveStart.Started -> Unit
                is ArchiveStart.Failed -> {
                    vm.toast(PlayerToast.CATCHUP_FAILED, r.reason.name)
                    // U-S7 = N17: the machine returns the info bar's focus to now (the toast stays).
                    vm.machine.dispatch(PlayerEvent.ArchiveStartFailed(r.reason))
                }
            }
        }
    }

    private companion object {
        /** `around` before / after (S4, Appendix B). */
        const val AROUND_COUNT = 12
    }
}
