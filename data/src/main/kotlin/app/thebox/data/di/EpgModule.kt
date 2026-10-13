package app.thebox.data.di

import android.content.Context
import android.util.Xml
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.store.EpgStoreImpl
import app.thebox.data.epg.AndroidFreeSpace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Provides the `EpgStore` singleton (§3.10). Commit 0 only constructs it; WP-D adds the `recover()` call
 * on `@ApplicationScope` + IO once WP-B's store exists (calling the stub would throw). WP-D owns this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgModule {
    @Provides
    @Singleton
    fun epgStore(@ApplicationContext context: Context): EpgStore = EpgStoreImpl(
        dir = File(context.noBackupFilesDir, "epg"),
        driver = BundledSQLiteDriver(),
        freeSpace = AndroidFreeSpace,
        xmlParser = Xml::newPullParser,
        clock = System::currentTimeMillis,
    )
}
