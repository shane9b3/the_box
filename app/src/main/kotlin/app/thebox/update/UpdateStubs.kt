package app.thebox.update

import app.thebox.core.update.UpdateAction
import app.thebox.core.update.UpdateActions
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.UpdateState
import app.thebox.core.update.Updater
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Commit-0 stubs of WP-E's updater (docs/design/m5-setup-settings.md §1.3a, §2.10, §3.12). WP-E replaces them with its
 * own files under app/.../update/ (UpdaterImpl, the UpdateActions implementation, the scheduler, UpdateModule) and
 * deletes this one. Behaviour: no check is ever made, so no request leaves the device.
 */

@Singleton
class UpdaterImpl @Inject constructor() : Updater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override fun check(manual: Boolean) = Unit
    override fun download() = Unit
    override fun install() = Unit
    override fun skip(versionCode: Long) = Unit
}

class UpdateActionsImpl @Inject constructor() : UpdateActions {
    override fun act(action: UpdateAction) = Unit
}

class UpdateCheckSchedulerImpl @Inject constructor() : UpdateCheckScheduler {
    override fun scheduleCheck() = Unit
}

/** WP-E's `:app` bindings (§2.13 UpdateModule). */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds @Singleton abstract fun updater(impl: UpdaterImpl): Updater
    @Binds abstract fun updateActions(impl: UpdateActionsImpl): UpdateActions
    @Binds abstract fun updateScheduler(impl: UpdateCheckSchedulerImpl): UpdateCheckScheduler
}
