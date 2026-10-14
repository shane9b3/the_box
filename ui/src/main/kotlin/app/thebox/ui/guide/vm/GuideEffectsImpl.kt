package app.thebox.ui.guide.vm

import app.thebox.core.guide.GroupAction
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ProgrammeInfo

/** WP-C stub (docs/design/m3-guide.md §1.4, §3.16.3): the state machine's side effects. */
class GuideEffectsImpl : GuideEffects {
    override fun loadLineup(source: GuideSource): Unit = TODO("WP-C")
    override fun ensureRows(from: Int, to: Int): Unit = TODO("WP-C")
    override fun ensureWindow(topRow: Int, windowStartMin: Int, holdDirection: Int): Unit = TODO("WP-C")
    override fun loadInfo(token: Long, channel: GuideChannel, rawStartMin: Int): Unit = TODO("WP-C")
    override fun loadTunedNow(channel: GuideChannel, atMin: Int): Unit = TODO("WP-C")
    override fun tune(channel: GuideChannel, lineup: GuideLineup): Unit = TODO("WP-C")
    override fun playArchive(channel: GuideChannel, info: ProgrammeInfo): Unit = TODO("WP-C")
    override fun setFullScreen(full: Boolean): Unit = TODO("WP-C")
    override fun zap(delta: Int, isRepeat: Boolean): Unit = TODO("WP-C")
    override fun previousChannel(): Unit = TODO("WP-C")
    override fun retry(): Unit = TODO("WP-C")
    override fun setReminder(channel: GuideChannel, info: ProgrammeInfo): Unit = TODO("WP-C")
    override fun cancelReminder(channel: GuideChannel, rawStartMin: Int): Unit = TODO("WP-C")
    override fun answerReminder(reminderId: Long, accept: Boolean): Unit = TODO("WP-C")
    override fun queryFavourite(token: Long, channel: GuideChannel): Unit = TODO("WP-C")
    override fun setFavourite(channel: GuideChannel, on: Boolean): Unit = TODO("WP-C")
    override fun hideChannel(channel: GuideChannel): Unit = TODO("WP-C")
    override fun groupAction(entry: GuideSourceEntry, action: GroupAction): Unit = TODO("WP-C")
    override fun lookupNumber(token: Long, number: Int): Unit = TODO("WP-C")
    override fun persistSource(source: GuideSource): Unit = TODO("WP-C")
    override fun openSearch(): Unit = TODO("WP-C")
    override fun openSettings(): Unit = TODO("WP-C")
    override fun exitApp(): Unit = TODO("WP-C")
    override fun toast(kind: GuideToast, arg: String?): Unit = TODO("WP-C")
    override fun announce(text: String): Unit = TODO("WP-C")
}
