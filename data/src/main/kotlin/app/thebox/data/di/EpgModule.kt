package app.thebox.data.di

import android.content.Context
import android.util.Xml
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.FreeSpace
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.data.epg.AndroidFreeSpace
import app.thebox.data.epg.EpgRecovery
import app.thebox.data.repo.EpgRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * The `EpgStore` singleton (§3.10). Construction only; `recover()` runs once on `@ApplicationScope` + IO
 * through [EpgRecovery], created lazily the first time something needs the store (EpgRepository). Failures
 * (including WP-B's commit-0 stub throwing NotImplementedError) are logged, never thrown at startup.
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgModule {
    @Provides
    @Singleton
    fun epgStore(@ApplicationContext context: Context, freeSpace: FreeSpace): EpgStore = EpgStoreImpl(
        dir = File(context.noBackupFilesDir, "epg"),
        driver = BundledSQLiteDriver(),
        freeSpace = freeSpace,
        xmlParser = Xml::newPullParser,
        clock = System::currentTimeMillis,
    )

    @Provides
    @Singleton
    fun recovery(store: EpgStore, @ApplicationScope scope: CoroutineScope, @IoDispatcher io: CoroutineDispatcher): EpgRecovery =
        EpgRecovery(store, scope, io)

    @Provides
    fun freeSpace(): FreeSpace = AndroidFreeSpace

    /** `cacheDir/epg`: downloaded XMLTV (`<id>.new`) and the last good copy (`<id>.src`). */
    @Provides
    @Named(EpgRepository.CACHE_DIR)
    fun epgCacheDir(@ApplicationContext context: Context): File = File(context.cacheDir, "epg")
}
