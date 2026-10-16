package app.thebox.ui.setup

import android.content.Context
import app.thebox.core.parental.ParentalSession
import app.thebox.core.settings.SettingsStore
import app.thebox.core.setup.GroupsReviewSource
import app.thebox.core.setup.GuideMatching
import app.thebox.core.setup.PlaylistAdmin
import app.thebox.core.setup.SetupDraftStore
import app.thebox.core.setup.SetupStrings
import app.thebox.core.setup.SourceInputNormalizer
import app.thebox.core.setup.SourceSetup
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.core.setup.pages.SetupPages
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP-B's `:ui` bindings (docs/design/m5-setup-settings.md §2.13): the user text of the setup pages and the drafts,
 * which live for the process (memory only, §3.4.3).
 */
@Module
@InstallIn(SingletonComponent::class)
object SetupUiModule {
    @Provides @Singleton
    fun setupStrings(@ApplicationContext context: Context): SetupStrings = AndroidSetupStrings(context)

    @Provides @Singleton
    fun setupDrafts(): SetupDraftStore = SetupDraftStore()
}

/**
 * Builds WP-B's page provider for one settings machine (WP-A's `SettingsViewModel` calls [create] with its
 * `viewModelScope` on the main dispatcher and routes `loadPage`/`onIntent` for the routes it [SetupPages.handles]).
 */
class SetupPagesFactory @Inject constructor(
    private val strings: SetupStrings,
    private val normalizer: SourceInputNormalizer,
    private val setup: SourceSetup,
    private val admin: PlaylistAdmin,
    private val groups: GroupsReviewSource,
    private val matching: GuideMatching,
    private val store: SettingsStore,
    private val parental: ParentalSession,
    private val drafts: SetupDraftStore,
) {
    fun create(scope: CoroutineScope, nowMs: () -> Long = System::currentTimeMillis): SetupPages = SetupPages(
        SetupPageDeps(
            strings = strings, normalizer = normalizer, setup = setup, admin = admin, groups = groups, matching = matching,
            store = store, parental = parental.state, scope = scope, nowMs = nowMs, drafts = drafts,
        ),
    )
}
