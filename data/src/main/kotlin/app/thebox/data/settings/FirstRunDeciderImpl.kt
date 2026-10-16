package app.thebox.data.settings

import app.thebox.core.common.BoxLog
import app.thebox.core.settings.FirstRunDecider
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.data.db.dao.PlaylistDao
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first-run decision (docs/design/m5-setup-settings.md §3.4.1, lead ruling §1.9a): no row in `playlist` and
 * `SetupState.completed == false` → [SettingsRoute.FirstRun] (Welcome; Back exits), else null (the normal startup tune).
 * `GuideViewModel.startup()` calls it once after the store is ready. A playlist count failure falls back to the guide,
 * so a broken database never traps the user on Welcome (the guide's own empty state then offers "Add one in Settings").
 */
@Singleton
class FirstRunDeciderImpl @Inject constructor(
    private val store: SettingsStore,
    private val playlists: PlaylistDao,
) : FirstRunDecider {
    override suspend fun route(): SettingsRoute? {
        if (store.flow(SetupDomain).value.completed) return null
        val any = try {
            playlists.all().isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.e(TAG, "first-run check failed; opening the guide", e)
            return null
        }
        return if (any) null else SettingsRoute.FirstRun
    }

    private companion object {
        const val TAG = "FirstRunDecider"
    }
}
