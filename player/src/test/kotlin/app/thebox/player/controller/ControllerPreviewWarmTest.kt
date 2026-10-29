package app.thebox.player.controller

import app.thebox.core.playback.Viewport
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TimeshiftSession
import app.thebox.player.internal.TimeshiftSessionFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Q-D1 (save-location closure fix pass 2026-10-28, lead ruling R2): the guide's preview start is a §5.3 keeper trigger.
 * A live tune in MINI (or MINI over a running cycle) asks the factory to warm the save location, off the zap path; a
 * full-screen tune does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerPreviewWarmTest {
    private val warms = AtomicInteger()
    private val factory = object : TimeshiftSessionFactory {
        override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared = TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
        override fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession = error("unused")
        override fun warmSaveLocation() { warms.incrementAndGet() }
    }
    private val f = ControllerFixture(factory)

    @After fun tearDown() = f.close()

    @Test fun aPreviewTuneWarmsTheSaveLocation() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals("a full-screen tune is no preview", 0, warms.get())
        f.controller.setViewport(Viewport.MINI)
        f.idle()
        assertEquals("MINI over a running cycle: the preview started", 1, warms.get())
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertEquals("a tune in the preview", 2, warms.get())
    }
}
