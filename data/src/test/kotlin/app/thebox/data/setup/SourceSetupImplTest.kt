package app.thebox.data.setup

import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.setup.ImportStage
import app.thebox.core.setup.M3uForm
import app.thebox.core.setup.ProbeResult
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupProgress
import app.thebox.core.setup.SetupStrings
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.StreamProbe
import app.thebox.core.setup.XtreamForm
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.contracts.PlaylistSecret
import app.thebox.data.contracts.XtreamLogin
import app.thebox.data.db.Converters
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.RefreshLogEntity
import app.thebox.data.repo.Trigger
import app.thebox.data.repo.exec
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.scalar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §6.1 WP-B (Robolectric DB): saving creates or edits in place (same `playlist_id`, user rows kept), the UA fallback is
 * stored only after a working VLC probe, the groups review writes one transaction, and the progress fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceSetupImplTest {
    private lateinit var db: LibraryDatabase
    private lateinit var g: TestGraph
    private val work = FakeImportWork()
    private val probe = ScriptedProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var setup: SourceSetupImpl

    @Before
    fun setUp() {
        db = TestDb.create()
        g = TestGraph(db)
        setup = SourceSetupImpl(
            db, g.playlists, db.playlistDao(), db.refreshLogDao(), db.epgSourceDao(), g.epg, g.matches, g.xtream, work, probe,
            KeyStrings, scope, g.clock,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        g.scope.cancel()
        db.close()
    }

    /** Real time on Dispatchers.Default: Room's invalidation and the fold's ticker run on real threads. */
    private fun real(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private val form = XtreamForm(name = null, server = "http://panel.example.com:8080", username = "user1", password = "pa+ss", output = "TS", backups = listOf("http://backup.example.net"))

    @Test
    fun testXtreamLogsInWithEveryServerAndStoresNothing() = real {
        g.xtream.login = Outcome.Ok(XtreamLogin(XtreamAccountInfo(true, AccountStatus.ACTIVE, measuredAtDeviceMs = 1), 0))
        val r = setup.testXtream(form)
        assertTrue(r is Outcome.Ok)
        val conn = g.xtream.logins.single()
        assertEquals(listOf("http://panel.example.com:8080", "http://backup.example.net"), conn.baseUrls)
        assertEquals("pa+ss", conn.password)
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM playlist"))
        assertTrue(g.creds.secrets.isEmpty())
    }

    @Test
    fun saveXtreamCreatesThePlaylistAndStartsTheOnboardingImport() = real {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        val p = db.playlistDao().get(id)!!
        assertEquals("panel.example.com:8080", p.name)
        assertEquals("panel.example.com:8080", p.displaySource)
        assertEquals("TS", p.xtreamOutput)
        assertEquals(PlaylistSecret(listOf("http://panel.example.com:8080", "http://backup.example.net"), "user1", "pa+ss"), g.creds.secrets[p.credentialRef])
        assertEquals(listOf(Triple(id, Trigger.ONBOARDING, false)), work.starts)
    }

    @Test
    fun editInPlaceKeepsThePlaylistIdAndEveryUserRow() = real {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        val ref = db.playlistDao().get(id)!!.credentialRef
        g.creds.secrets[ref] = g.creds.secrets[ref]!!.copy(headers = mapOf("Authorization" to "Bearer t"))
        g.playlists.clearSecretCache()
        val ch = db.newChannel(id, "xc:1", "One")
        db.channelPrefsDao().upsert(app.thebox.data.db.entity.ChannelPrefsEntity(id, "xc:1", hidden = true, updatedAt = 1))
        db.favouriteDao().let { dao ->
            val list = dao.insertList(app.thebox.data.db.entity.FavouriteListEntity(name = "Fav", sortIndex = 0, createdAt = 1))
            dao.insert(app.thebox.data.db.entity.FavouriteEntity(list, id, "xc:1", 0, 1))
        }
        val edited = setup.saveXtream(form.copy(server = "http://new.example.com", username = "user2", output = "AUTO", backups = emptyList()), existingId = id)
        assertEquals(Outcome.Ok(id), edited)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM playlist"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM channel_prefs WHERE playlist_id = ? AND stable_key = 'xc:1' AND hidden = 1", id))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM favourite WHERE playlist_id = ?", id))
        assertEquals(ch, db.scalar("SELECT id FROM channel WHERE playlist_id = ? AND stable_key = 'xc:1'", id))
        val s = g.creds.secrets[ref]!!
        assertEquals(listOf("http://new.example.com"), s.urls)
        assertEquals("user2", s.username)
        assertEquals("secret headers survive the edit", mapOf("Authorization" to "Bearer t"), s.headers)
        val p = db.playlistDao().get(id)!!
        assertEquals("new.example.com", p.displaySource)
        assertEquals("AUTO", p.xtreamOutput)
        assertEquals(Triple(id, Trigger.MANUAL, false), work.starts.last())
        // An M3U form cannot turn an Xtream playlist into another type in place.
        assertTrue((setup.saveM3u(M3uForm(null, "http://x/list.m3u", null, emptyList()), id) as Outcome.Err).error is SourceError.Unsupported)
    }

    @Test
    fun saveM3uAddsTheUrlPlaylistAndItsGuideLinks() = real {
        val id = (setup.saveM3u(M3uForm("My list", "http://lists.example.com/a.m3u", null, listOf("http://epg.example.com/g.xml.gz"))) as Outcome.Ok).value
        val p = db.playlistDao().get(id)!!
        assertEquals(PlaylistType.M3U_URL, p.type)
        assertEquals("My list", p.name)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM epg_source WHERE origin = 'USER'"))
        assertEquals(Trigger.ONBOARDING, work.starts.single().second)
        assertTrue((setup.saveM3u(M3uForm(null, null, null, emptyList())) as Outcome.Err).error is SourceError.NotASource)
    }

    // ------------------------------------------------------------------ UA probe (§3.4.5)

    private suspend fun xtreamWithChannel(): Long {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        db.newChannel(id, "radio", kind = 1, sortIndex = 0)
        db.newChannel(id, "xc:77", sortIndex = 1)
        // The stored form of an Xtream API channel URL (templated, no credentials).
        db.writeTransaction { exec("UPDATE channel SET url = ? WHERE stable_key = 'xc:77'") { bindText(1, "{{base}}/live/{{u}}/{{p}}/77.{{ext}}") } }
        return id
    }

    @Test
    fun theVlcUserAgentIsStoredOnlyAfterAWorkingVlcProbe() = real {
        val id = xtreamWithChannel()
        probe.answers += ProbeVerdict.FORBIDDEN
        probe.answers += ProbeVerdict.MEDIA
        assertEquals("UA_FALLBACK_NOTE", setup.uaProbe(id))
        assertEquals(SetupConstants.VLC_USER_AGENT, db.playlistDao().get(id)!!.userAgent)
        val (url, ua) = probe.calls[0]
        assertEquals("http://panel.example.com:8080/live/user1/pa+ss/77.ts", url)
        assertNull("first with the playlist's UA (the default)", ua)
        assertEquals(SetupConstants.VLC_USER_AGENT, probe.calls[1].second)
        assertEquals(setOf(id), probe.playlistIds)
    }

    @Test
    fun aRefusedVlcProbeChangesNothing() = real {
        val id = xtreamWithChannel()
        probe.answers += ProbeVerdict.FORBIDDEN
        probe.answers += ProbeVerdict.FORBIDDEN
        assertNull(setup.uaProbe(id))
        assertNull(db.playlistDao().get(id)!!.userAgent)
    }

    @Test
    fun aBusyConnectionSkipsTheTestAndAWorkingStreamProbesOnce() = real {
        val id = xtreamWithChannel()
        probe.answers += ProbeVerdict.LIMIT
        assertEquals("UA_PROBE_LIMIT_NOTE", setup.uaProbe(id))
        assertNull(db.playlistDao().get(id)!!.userAgent)
        probe.calls.clear()
        probe.answers += ProbeVerdict.MEDIA
        assertNull(setup.uaProbe(id))
        assertEquals(1, probe.calls.size)
        assertNull(db.playlistDao().get(id)!!.userAgent)
    }

    // ------------------------------------------------------------------ groups review

    @Test
    fun theGroupsReviewWritesHiddenAndLockDecisionsInOneTransaction() = real {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        db.newGroup(id, "news"); db.newGroup(id, "sport"); db.newGroup(id, "xxx", adult = true); db.newGroup(id, "kids")
        db.groupDao().upsertGroupHidden(id, 0, "kids", true, 1)
        setup.applyGroupsReview(id, hidden = setOf("sport"), locked = setOf("news"), unlocked = setOf("xxx"))
        assertEquals(1L, db.scalar("SELECT hidden FROM group_prefs WHERE playlist_id = ? AND group_key = 'sport'", id))
        assertEquals("shown again", 0L, db.scalar("SELECT hidden FROM group_prefs WHERE playlist_id = ? AND group_key = 'kids'", id))
        assertEquals(1L, db.scalar("SELECT locked FROM group_prefs WHERE playlist_id = ? AND group_key = 'news'", id))
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM group_prefs WHERE playlist_id = ? AND group_key = 'xxx' AND locked = 1", id))
        assertEquals("explicit unlock (S5)", 1L, db.scalar("SELECT COUNT(*) FROM group_prefs WHERE playlist_id = ? AND group_key = 'xxx' AND locked = 0", id))
        assertEquals("no lock decision: automatic", 1L, db.scalar("SELECT COUNT(*) FROM group_prefs WHERE playlist_id = ? AND group_key = 'sport' AND locked IS NULL", id))
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun progressFoldsTheRefreshTheLogRowAndTheGuide() = real {
        val id = (setup.saveM3u(M3uForm("L", "http://lists.example.com/a.m3u", null, emptyList())) as Outcome.Ok).value
        db.epgSourceDao().insert(
            app.thebox.data.db.entity.EpgSourceEntity(name = "g", origin = "USER", credentialRef = "c", urlHash = 1, displaySource = "g", priority = 0, createdAt = 1),
        )
        val got = CopyOnWriteArrayList<SetupProgress>()
        val job = async { setup.progress(id).collect { got += it } }
        work.phase.value = WorkPhase(true, "Downloading playlist")
        waitFor { got.any { it is SetupProgress.Stage && it.stage == ImportStage.DOWNLOADING_PLAYLIST } }
        work.phase.value = WorkPhase(true, "Updating channels")
        waitFor { got.any { it is SetupProgress.Stage && it.stage == ImportStage.READING_CHANNELS } }
        work.guide.value = true
        val report = RefreshReport(id, 1, g.clock.now(), g.clock.now(), RefreshOutcome.SUCCESS, entries = 18_402, live = 2_100, radio = 40, movies = 16_000, series = 262, groupsTotal = 64)
        db.refreshLogDao().insert(
            RefreshLogEntity(
                targetType = RefreshLogEntity.TARGET_PLAYLIST, targetId = id, trigger = "ONBOARDING", startedAt = g.clock.now(), finishedAt = g.clock.now(),
                outcome = "SUCCESS", bytes = 4L shl 20, reportJson = Converters.json.encodeToString(RefreshReport.serializer(), report),
            ),
        )
        work.phase.value = WorkPhase(false, null)
        waitFor { got.any { it is SetupProgress.ReadyToWatch } }
        assertTrue(got.any { it is SetupProgress.Stage && it.stage == ImportStage.DOWNLOADING_GUIDE && !it.done })
        work.guide.value = false
        withTimeout(10_000) { job.await() }
        val stages = got.filterIsInstance<SetupProgress.Stage>().filter { it.done }.associateBy { it.stage }
        assertEquals("STAGE_COUNT_MB(4)", stages[ImportStage.DOWNLOADING_PLAYLIST]?.count)
        assertEquals("STAGE_COUNT_CHANNELS(18,402|2,140|16,262)", stages[ImportStage.READING_CHANNELS]?.count)
        assertEquals("64", stages[ImportStage.GROUPS]?.count)
        assertEquals("STAGE_COUNT_MATCHED(0|0|0)", stages[ImportStage.MATCHING_GUIDE]?.count)
        val fin = got.last() as SetupProgress.Finished
        assertEquals(2_140, fin.report.live)
        assertEquals(16_262, fin.report.vodKept)
        assertEquals(64, fin.report.groups)
        assertTrue("order: ready before finished", got.indexOfFirst { it is SetupProgress.ReadyToWatch } < got.size - 1)
    }

    @Test
    fun queuedGuideWorkIsWaitedForEvenWhenItIsNotYetVisible() = real {
        // 2026-11-06 "0 of 13 matched": the refresh queued a guide import, but WorkManager had not surfaced it yet.
        val id = (setup.saveM3u(M3uForm("L", "http://lists.example.com/a.m3u", null, emptyList())) as Outcome.Ok).value
        db.epgSourceDao().insert(
            app.thebox.data.db.entity.EpgSourceEntity(name = "g", origin = "USER", credentialRef = "c", urlHash = 1, displaySource = "g", priority = 0, createdAt = 1),
        )
        val got = CopyOnWriteArrayList<SetupProgress>()
        val job = async { setup.progress(id).collect { got += it } }
        work.phase.value = WorkPhase(true, "Downloading playlist")
        waitFor { got.isNotEmpty() }
        val report = RefreshReport(id, 1, g.clock.now(), g.clock.now(), RefreshOutcome.SUCCESS, entries = 13, live = 13, groupsTotal = 4, guideWorkQueued = true)
        db.refreshLogDao().insert(
            RefreshLogEntity(
                targetType = RefreshLogEntity.TARGET_PLAYLIST, targetId = id, trigger = "ONBOARDING", startedAt = g.clock.now(), finishedAt = g.clock.now(),
                outcome = "SUCCESS", bytes = 1L, reportJson = Converters.json.encodeToString(RefreshReport.serializer(), report),
            ),
        )
        work.phase.value = WorkPhase(false, null)
        waitFor { got.any { it is SetupProgress.ReadyToWatch } }
        delay((SourceSetupImpl.SETTLE_TICKS + 3) * SourceSetupImpl.TICK_MS) // past the old quiet-period guess
        assertTrue("no guide signal yet: not finished", got.none { it is SetupProgress.Finished })
        db.refreshLogDao().insert(
            RefreshLogEntity(targetType = RefreshLogEntity.TARGET_EPG_BUILD, targetId = null, trigger = "PLAYLIST_CHANGED", startedAt = g.clock.now(), finishedAt = g.clock.now(), outcome = "SUCCESS"),
        )
        withTimeout(10_000) { job.await() }
        assertTrue(got.last() is SetupProgress.Finished)
    }

    @Test
    fun aFailedRefreshEndsTheProgressWithItsCard() = real {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        db.refreshLogDao().insert(
            RefreshLogEntity(targetType = RefreshLogEntity.TARGET_PLAYLIST, targetId = id, trigger = "ONBOARDING", startedAt = g.clock.now(), finishedAt = g.clock.now(), outcome = "FAILED", errorCode = "http.403"),
        )
        val got = withTimeout(10_000) { setup.progress(id).toList() }
        val failed = got.single() as SetupProgress.Failed
        assertEquals("CARD_HTTP_LOGIN_REFUSED(403)", failed.card.headline)
    }

    @Test
    fun aCancelledImportEndsAsCancelled() = real {
        val id = (setup.saveXtream(form) as Outcome.Ok).value
        val got = CopyOnWriteArrayList<SetupProgress>()
        val job = async { setup.progress(id).collect { got += it } }
        work.phase.value = WorkPhase(true, "Downloading playlist")
        waitFor { got.isNotEmpty() }
        setup.cancelImport(id)
        assertEquals(listOf(id), work.cancels)
        work.phase.value = WorkPhase(false, null)
        withTimeout(10_000) { job.await() }
        assertEquals("CARD_CANCELLED", (got.last() as SetupProgress.Failed).card.headline)
    }

    private suspend fun waitFor(cond: () -> Boolean) = withTimeout(10_000) { while (!cond()) delay(20) }

    /** [ImportWork] driven by the test. */
    class FakeImportWork : ImportWork {
        val starts = CopyOnWriteArrayList<Triple<Long, Trigger, Boolean>>()
        val cancels = CopyOnWriteArrayList<Long>()
        val phase = MutableStateFlow(WorkPhase(false, null))
        val guide = MutableStateFlow(false)
        override fun start(playlistId: Long, trigger: Trigger, forceAcceptShrink: Boolean) { starts += Triple(playlistId, trigger, forceAcceptShrink) }
        override fun cancel(playlistId: Long) { cancels += playlistId }
        override fun refresh(playlistId: Long): Flow<WorkPhase> = phase
        override fun guideBusy(): Flow<Boolean> = guide
    }

    /** [StreamProbe] answering [answers] in order; records URL and UA per call. */
    class ScriptedProbe : StreamProbe {
        val answers = ArrayDeque<ProbeVerdict>()
        val calls = CopyOnWriteArrayList<Pair<String, String?>>()
        val playlistIds = HashSet<Long>()
        override suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult {
            calls += url to userAgent
            playlistIds += playlistId
            return ProbeResult(answers.removeFirstOrNull() ?: ProbeVerdict.OTHER, 200, 0, 1)
        }
    }

    object KeyStrings : SetupStrings {
        override fun text(key: SetupText, vararg args: Any): String = if (args.isEmpty()) key.name else key.name + args.joinToString("|", "(", ")")
    }
}
