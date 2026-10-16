package app.thebox.core.update.fakes

import app.thebox.core.update.UpdateAction
import app.thebox.core.update.UpdateActions
import app.thebox.core.update.UpdateState
import app.thebox.core.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow

/** [Updater] whose [state] the test drives; records every call. Also [UpdateActions] (records the card's buttons). */
class FakeUpdater : Updater, UpdateActions {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val calls = ArrayList<String>()
    override fun check(manual: Boolean) { calls += "check($manual)" }
    override fun download() { calls += "download" }
    override fun install() { calls += "install" }
    override fun skip(versionCode: Long) { calls += "skip($versionCode)" }
    override fun act(action: UpdateAction) { calls += "act($action)" }
}
