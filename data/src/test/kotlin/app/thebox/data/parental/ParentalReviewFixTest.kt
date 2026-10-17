package app.thebox.data.parental

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.model.StableKey
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalDomain
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.repo.writeTransaction
import app.thebox.data.rules.ChannelsPagesImpl
import app.thebox.data.rules.HideRulesImpl
import app.thebox.data.security.KeyResetFlag
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parental fixes of the M5 code review (2026-10-16), adopted from the data reviewer's reproductions. Fake PINs only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalReviewFixTest {
    private lateinit var db: LibraryDatabase
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val flag = object : KeyResetFlag {
        var lost = false
        override fun markPinLost() { lost = true }
        override fun pinLost() = lost
        override fun clearPinLost() { lost = false }
    }
    private val attempts = object : AttemptsStore {
        var a = LockoutPolicy.Attempts()
        override fun read() = a
        override fun write(a: LockoutPolicy.Attempts) { this.a = a }
    }

    /** A blob store whose reads wait for [gate] (a cold Keystore / Tink keyset on a slow box). */
    private class SlowBlobs : SecureBlobStore {
        val map = HashMap<String, ByteArray>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> { map[ref] = bytes.copyOf(); return Outcome.Ok(Unit) }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> {
            gate?.await()
            return map[ref]?.let { Outcome.Ok(it.copyOf()) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        }
        override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    }

    @Before fun setUp() { db = TestDb.create() }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun pins(blobs: SecureBlobStore) = ParentalPinStoreImpl(blobs, attempts, { w -> LockoutPolicy.Clock(0, w, 1) }, flag, Dispatchers.Unconfined).also { it.iterations = 10 }

    /**
     * C-B2 / D-B2 (defence in depth): §3.9.2 Lock/Unlock in the group menu asks the PIN when one is set. An Unlock that
     * reaches the group manager with the session locked (LOCKED_AUTO) is refused, so the adult channel still needs the PIN.
     */
    @Test
    fun groupManagerUnlockDoesNotBypassTheLockedSession() = runBlocking {
        val p = db.newPlaylist()
        val gAdult = db.newGroup(p, "g:adult", "Adult", adult = true)
        db.newChannel(p, "late", "Late", gAdult, sortIndex = 0)
        val adult = ChannelIdentity(p, StableKey("late"))
        val settings = FakeSettingsStore()
        val pins = pins(SlowBlobs())
        val index = LockedChannelIndex(db, scope, Dispatchers.Unconfined)
        index.rebuild()
        val session = ParentalSessionImpl(TestDb.context, settings, pins, flag, index, db, db.groupDao(), clock, scope, Dispatchers.Unconfined)
        val guard = TuneGuardImpl(index, session)
        pins.set(charArrayOf('2', '5', '8', '0'))
        session.refresh()
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
        assertEquals(TuneVerdict.NEEDS_PIN, guard.check(adult))

        val pages = ChannelsPagesImpl(db, HideRulesImpl(db, ImportMutex(), clock), BackupTexts { k, _ -> k.name }, settings, session, clock, session)
        pages.onIntent(SettingsRoute.Groups(p), RowIntent.Menu(RowId("group/0/g:adult"), "unlock"))
        pages.onIntent(SettingsRoute.Groups(p), RowIntent.Menu(RowId("group/0/g:adult"), "reset"))
        session.setGroupLocked(app.thebox.core.guide.GuideSource.Group(p, "g:adult"), false)
        index.rebuild()
        assertEquals("no PIN was entered: the adult channel must still need it", TuneVerdict.NEEDS_PIN, guard.check(adult))

        // After the PIN (the session unlocked), the same Unlock is applied.
        session.unlock(clock.now())
        pages.onIntent(SettingsRoute.Groups(p), RowIntent.Menu(RowId("group/0/g:adult"), "unlock"))
        session.relock(app.thebox.core.parental.RelockReason.APP_STOPPED)
        index.rebuild()
        assertEquals(TuneVerdict.ALLOWED, guard.check(adult))
    }

    /**
     * D-S1: with a PIN set and "auto-lock adult" off the access is LOCKED_MANUAL_ONLY. Until the PIN's first read lands the
     * session must not publish OPEN, and the guard must not allow a user-locked channel.
     */
    @Test
    fun accessIsNeverOpenBeforeThePinIsKnown() = runBlocking {
        val p = db.newPlaylist()
        val g = db.newGroup(p, "g:news", "News")
        db.newChannel(p, "mine", "Mine", g, sortIndex = 0)
        val mine = ChannelIdentity(p, StableKey("mine"))
        db.writeTransaction {
            usePrepared("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, locked, watch_count, updated_at) VALUES (?, 'mine', 0, 1, 0, 0)") { st ->
                st.bindLong(1, p); st.step()
            }
        }
        val settings = FakeSettingsStore()
        settings.update(ParentalDomain) { it.copy(autoLockAdult = false) }
        val blobs = SlowBlobs()
        pins(blobs).set(charArrayOf('2', '5', '8', '0')) // a PIN exists from an earlier run
        val index = LockedChannelIndex(db, scope, Dispatchers.Unconfined)
        index.rebuild()
        blobs.gate = CompletableDeferred() // the next process start: the first read is slow
        val fresh = ParentalPinStoreImpl(blobs, attempts, { w -> LockoutPolicy.Clock(0, w, 1) }, flag, Dispatchers.Unconfined)
        val session = ParentalSessionImpl(TestDb.context, settings, fresh, flag, index, db, db.groupDao(), clock, scope, Dispatchers.Unconfined)
        val guard = TuneGuardImpl(index, session)
        val early = session.state.value.access
        val earlyVerdict = guard.check(mine)
        blobs.gate!!.complete(Unit)
        assertEquals(ParentalAccess.LOCKED_MANUAL_ONLY, session.state.value.access)
        assertNotEquals("access before the PIN read landed", ParentalAccess.OPEN, early)
        assertNotEquals("a user-locked channel before the PIN read landed", TuneVerdict.ALLOWED, earlyVerdict)
    }

    /** D-S1 (§3.9.6 fail closed): before the lock index loaded once, a tune under a locked access is refused, not allowed. */
    @Test
    fun guardFailsClosedBeforeTheIndexLoads() = runBlocking {
        val p = db.newPlaylist()
        val g = db.newGroup(p, "g:news", "News")
        db.newChannel(p, "news", "News 1", g, sortIndex = 0)
        val news = ChannelIdentity(p, StableKey("news"))
        val pins = pins(SlowBlobs())
        pins.set(charArrayOf('2', '5', '8', '0'))
        // A scope that never runs the index's own start: the index stays unloaded.
        val idle = CoroutineScope(SupervisorJob())
        idle.cancel()
        val index = LockedChannelIndex(db, idle, Dispatchers.Unconfined)
        val session = ParentalSessionImpl(TestDb.context, FakeSettingsStore(), pins, flag, index, db, db.groupDao(), clock, scope, Dispatchers.Unconfined)
        session.refresh()
        val guard = TuneGuardImpl(index, session)
        assertEquals(false, guard.ready)
        assertEquals(TuneVerdict.NEEDS_PIN, guard.check(news))
        index.rebuild()
        assertEquals(true, guard.ready)
        assertEquals("an ordinary channel plays once the index is known", TuneVerdict.ALLOWED, guard.check(news))
    }
}
