package app.thebox.data.parental

import android.app.Activity
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.guide.GuideSource
import app.thebox.core.model.StableKey
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalDomain
import app.thebox.core.parental.RelockReason
import app.thebox.core.parental.StayUnlocked
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.security.KeyResetFlag
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.scalar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m5-setup-settings.md §3.9.5, §3.9.6 (`ParentalSessionTest`): the three stay-unlocked modes, never relock
 * mid-programme, app stop, Open anyway, the key-reset flag; and (`ParentalBypassTest`, B1) the guard's verdicts for the
 * previous, zap and resume targets after a relock and while unlocked. The controller's own guard calls are WP-A's
 * (`PlaybackControllerImpl`); `:ui`'s GuardedPlaybackControllerTest covers the effects' path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalSessionTest {
    private lateinit var db: LibraryDatabase
    private val settings = FakeSettingsStore()
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val flag = object : KeyResetFlag {
        var lost = false
        override fun markPinLost() { lost = true }
        override fun pinLost() = lost
        override fun clearPinLost() { lost = false }
    }
    private val blobs = object : SecureBlobStore {
        val map = HashMap<String, ByteArray>()
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> { map[ref] = bytes.copyOf(); return Outcome.Ok(Unit) }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> =
            map[ref]?.let { Outcome.Ok(it.copyOf()) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    }
    private val attempts = object : AttemptsStore {
        var a = LockoutPolicy.Attempts()
        override fun read() = a
        override fun write(a: LockoutPolicy.Attempts) { this.a = a }
    }
    private lateinit var pins: ParentalPinStoreImpl
    private lateinit var index: LockedChannelIndex
    private lateinit var session: ParentalSessionImpl
    private lateinit var guard: TuneGuardImpl
    private var p = 0L
    private lateinit var adult: ChannelIdentity
    private lateinit var news: ChannelIdentity
    private lateinit var locked: ChannelIdentity

    @Before
    fun setUp() = runBlocking {
        db = TestDb.create()
        p = db.newPlaylist()
        val gNews = db.newGroup(p, "g:news", "News")
        val gAdult = db.newGroup(p, "g:adult", "Adult", adult = true)
        db.newChannel(p, "bbc", "BBC", gNews, sortIndex = 0)
        db.newChannel(p, "late", "Late", gAdult, sortIndex = 1)
        db.newChannel(p, "mine", "Mine", gNews, sortIndex = 2)
        news = ChannelIdentity(p, StableKey("bbc")); adult = ChannelIdentity(p, StableKey("late")); locked = ChannelIdentity(p, StableKey("mine"))
        pins = ParentalPinStoreImpl(blobs, attempts, { w -> LockoutPolicy.Clock(0, w, 1) }, flag, Dispatchers.Unconfined).also { it.iterations = 100 }
        index = LockedChannelIndex(db, scope, Dispatchers.Unconfined)
        index.rebuild()
        session = ParentalSessionImpl(TestDb.context, settings, pins, flag, index, db, db.groupDao(), clock, scope, Dispatchers.Unconfined)
        guard = TuneGuardImpl(index, session)
    }

    @After fun tearDown() { db.close() }

    private fun withPin() = runBlocking { pins.set(charArrayOf('2', '5', '8', '0')); session.refresh() }
    private fun policy(stay: StayUnlocked) = settings.update(ParentalDomain) { it.copy(stayUnlocked = stay) }

    @Test fun noPinIsFilteredAndAPinLocks() {
        assertEquals(ParentalAccess.FILTERED_NO_PIN, session.state.value.access)
        session.unlock(1)
        assertEquals("no PIN: nothing to unlock", ParentalAccess.FILTERED_NO_PIN, session.state.value.access)
        withPin()
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
        settings.update(ParentalDomain) { it.copy(autoLockAdult = false) }
        assertEquals(ParentalAccess.LOCKED_MANUAL_ONLY, session.state.value.access)
        session.unlock(1)
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
    }

    @Test fun untilTheBoxClosesRelocksOnAppStopOnly() {
        withPin()
        session.unlock(0)
        session.onTick(5 * ParentalSessionImpl.ONE_HOUR_MS)
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        activity.pause().stop()
        assertEquals("app stopped", ParentalAccess.LOCKED_AUTO, session.state.value.access)
    }

    @Test fun oneHourNeverRelocksMidProgramme() {
        withPin()
        policy(StayUnlocked.ONE_HOUR)
        session.unlock(0)
        session.onTick(ParentalSessionImpl.ONE_HOUR_MS - 1)
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        session.watching(adult) // locked content plays
        session.onTick(ParentalSessionImpl.ONE_HOUR_MS)
        assertEquals("the programme is not cut off", ParentalAccess.OPEN, session.state.value.access)
        session.watching(adult)
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        session.watching(news) // tuned away
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
        // Without locked content playing the hour relocks at once.
        session.unlock(10)
        session.watching(null)
        session.onTick(10 + ParentalSessionImpl.ONE_HOUR_MS)
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
    }

    /** (F7 sibling, 2026-11-11) The hour is elapsed realtime: a wall clock moved back cannot extend the unlock. */
    @Test fun oneHourIsRealTimeWhateverTheWallClock() {
        withPin()
        policy(StayUnlocked.ONE_HOUR)
        var mono = 5_000L
        session.elapsed = { mono }
        session.unlock(10_000_000)
        mono += ParentalSessionImpl.ONE_HOUR_MS - 1
        session.onTick(10_000_000 - 86_400_000) // the wall clock was moved a day back
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        mono += 1
        session.onTick(10_000_000 - 86_400_000)
        assertEquals("an hour of real time passed", ParentalAccess.LOCKED_AUTO, session.state.value.access)
        // A wall clock moved forward may end it early (harmless: it only relocks sooner).
        session.unlock(20_000_000)
        session.onTick(20_000_000 + ParentalSessionImpl.ONE_HOUR_MS)
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
    }

    /** (Review 2026-11-11 L4) refresh() settles the stored lockout: here a wait anchored past "now" is re-anchored. */
    @Test fun refreshSettlesTheStoredLockout() {
        withPin()
        // The pins' clock reads elapsed 0, boot 1: an anchor at 5 000 means elapsed went backwards (a reboot).
        attempts.a = LockoutPolicy.Attempts(failures = 3, lockedAtElapsedMs = 5_000, lockedAtBoot = 1, waitMs = 30_000)
        session.refresh()
        assertEquals(LockoutPolicy.Attempts(3, 0, 1, 30_000), attempts.a)
    }

    @Test fun askEveryTimeRelocksWhenLeavingTheContent() {
        withPin()
        policy(StayUnlocked.ASK_EVERY_TIME)
        session.unlock(0)
        session.watching(adult)
        assertEquals(ParentalAccess.OPEN, session.state.value.access)
        session.watching(news)
        assertEquals(ParentalAccess.LOCKED_AUTO, session.state.value.access)
        session.unlock(1)
        session.leftSettings()
        assertEquals("leaving Settings", ParentalAccess.LOCKED_AUTO, session.state.value.access)
    }

    @Test fun openAnywayUntilTheAppCloses() {
        val g = GuideSource.Group(p, "g:adult")
        assertEquals(TuneVerdict.NOT_PROTECTED, guard.check(adult))
        session.openAnyway(adult)
        assertTrue(g in session.state.value.openedAnyway)
        assertEquals(TuneVerdict.ALLOWED, guard.check(adult))
        session.relock(RelockReason.APP_STOPPED)
        assertTrue(session.state.value.openedAnyway.isEmpty())
        assertEquals(TuneVerdict.NOT_PROTECTED, guard.check(adult))
    }

    @Test fun keyResetKeepsContentFilteredUntilANewPin() {
        flag.lost = true
        settings.update(ParentalDomain) { it.copy(autoLockAdult = false) }
        session.refresh()
        assertTrue(session.state.value.pinLostToKeyReset)
        assertEquals(ParentalAccess.FILTERED_NO_PIN, session.state.value.access)
        withPin()
        assertFalse(session.state.value.pinLostToKeyReset)
    }

    /** B1 (`ParentalBypassTest`): previous, zap neighbour and resume targets are refused after a relock, allowed while unlocked. */
    @Test fun bypassRowsThroughTheGuard() = runBlocking {
        withPin()
        session.setChannelLocked(locked, true)
        index.rebuild()
        for (target in listOf(adult, locked)) assertEquals("relocked: $target", TuneVerdict.NEEDS_PIN, guard.check(target))
        assertEquals(TuneVerdict.ALLOWED, guard.check(news))
        session.unlock(0)
        for (target in listOf(adult, locked, news)) assertEquals("unlocked: $target", TuneVerdict.ALLOWED, guard.check(target))
        session.relock(RelockReason.USER)
        assertEquals(TuneVerdict.NEEDS_PIN, guard.check(adult))
        // Auto-lock off: only the manual lock is enforced.
        settings.update(ParentalDomain) { it.copy(autoLockAdult = false) }
        assertEquals(TuneVerdict.ALLOWED, guard.check(adult))
        assertEquals(TuneVerdict.NEEDS_PIN, guard.check(locked))
    }

    @Test fun lockWritesAreExplicit() = runBlocking {
        session.setChannelLocked(news, true)
        assertEquals(1L, db.scalar("SELECT locked FROM channel_prefs WHERE stable_key = 'bbc'"))
        session.setChannelLocked(news, false)
        assertEquals(0L, db.scalar("SELECT locked FROM channel_prefs WHERE stable_key = 'bbc'"))
        session.setGroupLocked(GuideSource.Group(p, "g:adult"), false)
        assertEquals(0L, db.scalar("SELECT locked FROM group_prefs WHERE group_key = 'g:adult'"))
        index.rebuild()
        assertEquals("an explicit group unlock wins over detection (S5)", TuneVerdict.ALLOWED, guard.check(adult))
    }
}
