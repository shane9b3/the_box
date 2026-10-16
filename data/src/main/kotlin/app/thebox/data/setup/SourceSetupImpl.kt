package app.thebox.data.setup

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.SetupProgress
import app.thebox.core.setup.SourceSetup
import app.thebox.core.setup.XtreamForm
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Onboarding and playlist editing over `PlaylistRepository`/`RefreshScheduler` (docs/design/m5-setup-settings.md §2.4,
 * §3.4). **Commit-0 stub (WP-B replaces the body):** every call answers Unsupported; nothing is stored.
 */
@Singleton
class SourceSetupImpl @Inject constructor() : SourceSetup {
    private fun <T> notYet(): Outcome<T> = Outcome.Err(SourceError.Unsupported("setup arrives with WP-B"))

    override suspend fun testXtream(form: XtreamForm): Outcome<XtreamAccountInfo> = notYet()
    override suspend fun saveXtream(form: XtreamForm, existingId: Long?): Outcome<Long> = notYet()
    override suspend fun saveM3u(form: M3uForm, existingId: Long?): Outcome<Long> = notYet()
    override fun progress(playlistId: Long): Flow<SetupProgress> = emptyFlow()
    override suspend fun applyGroupsReview(playlistId: Long, hidden: Set<String>, locked: Set<String>, unlocked: Set<String>) = Unit
    override suspend fun cancelImport(playlistId: Long) = Unit
}
