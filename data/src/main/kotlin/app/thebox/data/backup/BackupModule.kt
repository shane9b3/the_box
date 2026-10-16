package app.thebox.data.backup

import app.thebox.core.backup.BackupService
import app.thebox.core.settings.tree.DocumentPicker
import app.thebox.core.settings.tree.PhoneBackupFlow
import app.thebox.core.settings.tree.SectionPages
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.data.rules.ChannelsPagesImpl
import app.thebox.data.setup.LanAddressFinderImpl
import app.thebox.data.setup.PhoneLinkImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * WP-D's `:data` bindings (docs/design/m5-setup-settings.md §2.13: BackupModule also binds `PhoneLink`). The two
 * [SectionPages] go into a set WP-A's `SettingsEffectsImpl` injects (`Set<@JvmSuppressWildcards SectionPages>`);
 * `BackupTexts` is bound by `:ui`'s `BackupUiModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @Singleton abstract fun backupService(impl: BackupServiceImpl): BackupService
    @Binds @Singleton abstract fun phoneLink(impl: PhoneLinkImpl): PhoneLink
    @Binds abstract fun lanAddressFinder(impl: LanAddressFinderImpl): LanAddressFinder
    @Binds abstract fun backupStorage(impl: AndroidBackupStorage): BackupStorage
    @Binds abstract fun backupEnvironment(impl: PackageBackupEnvironment): BackupEnvironment
    @Binds abstract fun restoreFollowUp(impl: DefaultRestoreFollowUp): RestoreFollowUp
    @Binds @IntoSet abstract fun channelsPages(impl: ChannelsPagesImpl): SectionPages
    @Binds @IntoSet abstract fun backupPages(impl: BackupPagesImpl): SectionPages
    @Binds abstract fun phoneBackupFlow(impl: BackupPagesImpl): PhoneBackupFlow
    @Binds abstract fun documentPicker(impl: BackupPagesImpl): DocumentPicker
}
