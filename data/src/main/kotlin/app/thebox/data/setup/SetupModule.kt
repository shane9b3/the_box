package app.thebox.data.setup

import app.thebox.core.setup.GroupsReviewSource
import app.thebox.core.setup.GuideMatching
import app.thebox.core.setup.PlaylistAdmin
import app.thebox.core.setup.SourceInputNormalizer
import app.thebox.core.setup.SourceInputNormalizerImpl
import app.thebox.core.setup.SourceSetup
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WP-B's `:data` bindings (docs/design/m5-setup-settings.md §2.13): onboarding, the playlist and matching ports of the
 * page builders, the input normaliser and the import-work seam. `SetupStrings` is bound in `:ui` (`SetupUiModule`),
 * `StreamProbe` in `:player` (`ProbeModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SetupModule {
    @Binds @Singleton abstract fun sourceSetup(impl: SourceSetupImpl): SourceSetup
    @Binds abstract fun importWork(impl: WorkManagerImportWork): ImportWork
    @Binds abstract fun playlistAdmin(impl: PlaylistSettingsRepository): PlaylistAdmin
    @Binds abstract fun groupsReview(impl: PlaylistSettingsRepository): GroupsReviewSource
    @Binds abstract fun guideMatching(impl: PlaylistSettingsRepository): GuideMatching

    companion object {
        @Provides fun normalizer(): SourceInputNormalizer = SourceInputNormalizerImpl()
    }
}
