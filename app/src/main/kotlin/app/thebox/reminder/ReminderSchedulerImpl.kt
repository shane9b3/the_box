package app.thebox.reminder

import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderAlerts
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP-D stub (docs/design/m3-guide.md §1.5, §3.11.3): the foreground runner and [ReminderAlerts]; active only while the
 * process is STARTED (`foreground: StateFlow<Boolean>` fed from `ProcessLifecycleOwner`).
 */
@Singleton
class ReminderSchedulerImpl @Inject constructor() : ReminderAlerts {
    override val current: StateFlow<ReminderAlert?> get() = TODO("WP-D")
    override suspend fun accept(reminderId: Long): Unit = TODO("WP-D")
    override suspend fun dismiss(reminderId: Long): Unit = TODO("WP-D")
}
