// M6 time-shift (docs/design/m6-timeshift.md §18): commit 0's bindings plus WP-B's indexer provider. The writer transport
// is `TransportFactory.writer` and the settings come in `PlaybackSettings` (lead reconciliation R9, R10).
package app.thebox.player.timeshift

import app.thebox.core.catchup.archive.TimelineRouter
import app.thebox.core.catchup.archive.TimelineRouterImpl
import app.thebox.core.playback.timeshift.RingWriterFactory
import app.thebox.core.playback.timeshift.RingWriterImpl
import app.thebox.core.playback.timeshift.TimeshiftPolicy
import app.thebox.core.playback.timeshift.TimeshiftPolicyImpl
import app.thebox.core.playback.timeshift.TsRingFactory
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.player.internal.TimeshiftSessionFactory
import app.thebox.core.storage.VolumeCatalog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Time-shift bindings (docs/design/m6-timeshift.md §18): WP-B's session factory, WP-A's ring, writer and policy, and
 * WP-C's pure [TimelineRouter] (`:core`, used by `:app`'s `LocalTimelineBackend`). Commit 0 binds the stubs.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TimeshiftModule {
    @Binds abstract fun sessions(impl: TimeshiftSessionFactoryImpl): TimeshiftSessionFactory

    // (Q-D10, closure fix pass 2026-10-28) `RingTargetSource` is bound by `:data`'s StorageModule in the app graph, the only
    // graph with this module; the `@BindsOptionalOf` switch of commit 0 is gone.

    /** (Storage locations commit 0, §6) USB volumes for `:data` (the app graph); WP-S2 fills [VolumeWatcher]. */
    @Binds abstract fun volumes(impl: VolumeWatcher): VolumeCatalog

    companion object {
        @Provides fun ringFactory(): TsRingFactory = TsRingImpl.FACTORY
        @Provides fun writerFactory(): RingWriterFactory = RingWriterImpl.FACTORY
        @Provides fun policy(): TimeshiftPolicy = TimeshiftPolicyImpl()
        /** One indexer per writer (unscoped): `TimeshiftSessionFactoryImpl.start` asks for a fresh one. */
        @Provides fun indexer(): app.thebox.core.playback.timeshift.TsIndexer = app.thebox.core.playback.timeshift.TsIndexerImpl()
        @Provides fun timelineRouter(): TimelineRouter = TimelineRouterImpl()
    }
}
