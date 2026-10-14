package app.thebox.ui.guide.grid

import android.content.Context
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideMetricsFactory
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.TimeLabels
import app.thebox.core.guide.layout.GuideMetricsFactoryImpl
import app.thebox.core.guide.layout.GuideNavigatorImpl
import app.thebox.core.guide.layout.RowCellsBuilderImpl
import app.thebox.core.guide.layout.TimeTables
import app.thebox.core.guide.render.GuideRendererImpl
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Grid engine bindings (docs/design/m3-guide.md §1.2; owner WP-A). Commit 0 binds the stubs. */
@Module
@InstallIn(SingletonComponent::class)
object GuideGridModule {
    @Provides fun navigator(): GuideNavigator = GuideNavigatorImpl()
    @Provides fun renderer(@ApplicationContext context: Context): GuideRenderer = GuideRendererImpl(AndroidTextMeasurer(context, 1f))
    @Provides @Singleton fun timeLabels(): TimeLabels = TimeTables()
    @Provides fun metricsFactory(): GuideMetricsFactory = GuideMetricsFactoryImpl()
    @Provides fun rowCellsBuilder(): RowCellsBuilder = RowCellsBuilderImpl()
    @Provides @Singleton fun images(imageLoader: ImageLoader): GuideImages = LogoCache(imageLoader)
}
