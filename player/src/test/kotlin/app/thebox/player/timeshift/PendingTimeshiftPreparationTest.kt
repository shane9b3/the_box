package app.thebox.player.timeshift

import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.*
import app.thebox.core.playback.timeshift.RingWriterListener
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingTimeshiftPreparationTest {
    private class Factory : TimeshiftSessionFactory {
        val gate = CompletableDeferred<Unit>()
        val grant = TimeshiftGrant(FakeTsRing(), 256L shl 20, BufferMode.PERSIST, null)
        val abandoned = mutableListOf<Pair<TimeshiftGrant, Boolean>>()
        var prepares = 0
        var discards = 0
        var off = false
        override suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared {
            prepares++
            gate.await()
            return if (off) TimeshiftPrepared.Off(app.thebox.core.playback.timeshift.BufferOff.SETTING_OFF) else TimeshiftPrepared.Granted(grant)
        }
        override fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession = error("unused")
        override fun abandon(grant: TimeshiftGrant, discardFile: Boolean) { abandoned += grant to discardFile }
        override fun discardUnused() { discards++ }
    }

    @Test fun releaseBeforeCompletionDisposesTheLateGrantExactlyOnceEvenWhenScopeIsCancelled() = runTest {
        val factory = Factory()
        val job = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, CoroutineScope(job + dispatcher), dispatcher, PlaybackSettings(), 1)
        runCurrent()
        pending.release(discardFile = true)
        pending.release(discardFile = false)
        job.cancel()
        factory.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(factory.grant to true), factory.abandoned)
    }

    @Test fun releaseAfterCompletionDisposesTheUnclaimedGrant() = runTest {
        val factory = Factory().also { it.gate.complete(Unit) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, this, dispatcher, PlaybackSettings(), 1)
        advanceUntilIdle()
        pending.release(discardFile = true)
        advanceUntilIdle()
        assertEquals(listOf(factory.grant to true), factory.abandoned)
    }

    @Test fun takeTransfersOwnershipAndReleaseCannotDisposeAnActiveGrant() = runTest {
        val factory = Factory().also { it.gate.complete(Unit) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, this, dispatcher, PlaybackSettings(), 1)
        advanceUntilIdle()
        assertSame(factory.grant, (pending.take() as TimeshiftPrepared.Granted).grant)
        pending.release(discardFile = true)
        advanceUntilIdle()
        assertTrue(factory.abandoned.isEmpty())
    }

    @Test fun aCancelledWaiterDoesNotConsumeTheGrant() = runTest {
        val factory = Factory()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, this, dispatcher, PlaybackSettings(), 1)
        val waiter = launch { pending.take() }
        runCurrent()
        waiter.cancel()
        pending.release(discardFile = true)
        factory.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(factory.grant to true), factory.abandoned)
    }

    @Test fun supersededWorkIsSkippedBeforeItStartsAndIdleStorageStillGetsDiscarded() = runTest {
        val factory = Factory()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, this, dispatcher, PlaybackSettings(), 1)
        pending.release(discardFile = true)
        advanceUntilIdle()
        assertEquals(0, factory.prepares)
        assertEquals(1, factory.discards)
        assertTrue(factory.abandoned.isEmpty())
    }

    @Test fun releasingACompletedOffResultStillDiscardsIdleReusableStorage() = runTest {
        val factory = Factory().also { it.off = true; it.gate.complete(Unit) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingTimeshiftPreparation(factory, this, dispatcher, PlaybackSettings(), 1)
        advanceUntilIdle()
        pending.release(discardFile = true)
        advanceUntilIdle()
        assertEquals(1, factory.discards)
        assertTrue(factory.abandoned.isEmpty())
    }
}
