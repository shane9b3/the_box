package app.thebox.data.backup

import app.thebox.core.backup.BackupService
import app.thebox.core.setup.lan.PhoneLink
import app.thebox.data.setup.PhoneLinkImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * WP-D's `:data` bindings (docs/design/m5-setup-settings.md §2.13: BackupModule also binds `PhoneLink`). Commit 0
 * binds the stubs; WP-D owns this file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds @Singleton abstract fun backupService(impl: BackupServiceImpl): BackupService
    @Binds @Singleton abstract fun phoneLink(impl: PhoneLinkImpl): PhoneLink
}
