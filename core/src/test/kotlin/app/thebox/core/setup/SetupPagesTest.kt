package app.thebox.core.setup

import app.thebox.core.common.AuthReason
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.net.DnsChoice
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.Badge
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.Tone
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.pages.GroupsReviewPage
import app.thebox.core.setup.pages.ImportProgressPage
import app.thebox.core.setup.pages.M3uFormPage
import app.thebox.core.setup.pages.PageAction
import app.thebox.core.setup.pages.PlaylistPage
import app.thebox.core.setup.pages.PlaylistsSectionPage
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.core.setup.pages.SetupPages
import app.thebox.core.setup.pages.WelcomePage
import app.thebox.core.setup.pages.XtreamFormPage
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Onboarding and playlist page builders (§3.4, §3.3.1) over fake ports: rows, intents and navigation (§6.1 WP-B). */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPagesTest {
    private val setup = FakeSourceSetup()
    private val admin = FakePlaylistAdmin()
    private val groups = FakeGroups()
    private val store = FakeSettingsStore()
    private val parental = MutableStateFlow(ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy()))
    private val host = RecordingHost()
    private val now = 1_790_000_000_000L

    private fun pages(scope: CoroutineScope) = SetupPages(
        SetupPageDeps(
            strings = KeyStrings(), normalizer = SourceInputNormalizerImpl(), setup = setup, admin = admin, groups = groups,
            matching = FakeMatching(), store = store, parental = parental, scope = scope, nowMs = { now },
            locale = { Locale.UK }, zone = { ZoneOffset.UTC },
        ),
    )

    private fun edit(p: SetupPages, route: SettingsRoute, row: String, text: String) = p.onIntent(route, RowIntent.Edit(RowId(row), text), host)
    private fun run(p: SetupPages, route: SettingsRoute, row: String) = p.onIntent(route, RowIntent.Run(RowId(row)), host)

    private fun progress(id: Long): MutableSharedFlow<SetupProgress> = setup.progressOf.getOrPut(id) { MutableSharedFlow(replay = 16) }

    // ------------------------------------------------------------------ Welcome

    @Test
    fun welcomeHasTheFourCardsInOrderAndTheNote() = runTest(UnconfinedTestDispatcher()) {
        pages(backgroundScope).open(SettingsRoute.FirstRun, host)
        assertEquals(listOf(WelcomePage.ROW_PHONE, WelcomePage.ROW_XTREAM, WelcomePage.ROW_M3U, WelcomePage.ROW_RESTORE, WelcomePage.ROW_NOTE), host.ids())
        assertEquals(SettingsRoute.PhoneSetup(firstRun = true), (host.row(WelcomePage.ROW_PHONE).kind as RowKind.Link).route)
        assertEquals(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true), (host.row(WelcomePage.ROW_XTREAM).kind as RowKind.Link).route)
        assertEquals(SettingsRoute.Backup, (host.row(WelcomePage.ROW_RESTORE).kind as RowKind.Link).route)
        assertEquals("WELCOME_TITLE", host.last.title)
        assertEquals(0, WelcomePage.defaultCard(hasPhysicalKeyboard = false))
        assertEquals(1, WelcomePage.defaultCard(hasPhysicalKeyboard = true))
    }

    // ------------------------------------------------------------------ Xtream form

    @Test
    fun pastingAGetPhpLinkIntoServerFillsTheLoginAndConnectShowsTheCard() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)
        p.open(r, host)
        edit(p, r, XtreamFormPage.ROW_SERVER, "​http://panel.example.com:8080/get.php?username=u1&password=p+1&output=ts ")
        assertEquals(PageAction.Notice("NOTICE_FILLED_FROM_LINK"), host.actions.last())
        assertEquals("http://panel.example.com:8080", host.row(XtreamFormPage.ROW_SERVER).value)
        assertEquals("u1", host.row(XtreamFormPage.ROW_USERNAME).value)
        assertEquals("•••", host.row(XtreamFormPage.ROW_PASSWORD).value, "masked")
        assertTrue((host.row(XtreamFormPage.ROW_PASSWORD).kind as RowKind.Text).masked)

        setup.loginResult = Outcome.Ok(XtreamAccountInfo(true, AccountStatus.ACTIVE, expiresAtSec = null, activeConnections = 0, maxConnections = 1, measuredAtDeviceMs = now))
        run(p, r, XtreamFormPage.ROW_CONNECT)
        assertEquals(listOf("testXtream(http://panel.example.com:8080)"), setup.calls)
        assertEquals("CARD_CONNECTED", host.row("setup.card").label)
        assertEquals(Tone.GOOD, (host.row("setup.card").kind as RowKind.Info).tone)
        assertEquals("ACTION_CONTINUE", host.row("setup.card.primary").label)
        assertNull(host.rowOrNull("setup.card.secondary"))

        setup.saveResult = Outcome.Ok(7)
        run(p, r, "setup.card.primary")
        assertEquals("saveXtream(http://panel.example.com:8080,null)", setup.calls.last())
        assertEquals(PageAction.Replace(SettingsRoute.ImportProgress(7, firstRun = true)), host.actions.last())
    }

    @Test
    fun aRejectedLoginExplainsAndEditKeepsTheFields() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = false)
        p.open(r, host)
        run(p, r, XtreamFormPage.ROW_CONNECT)
        assertEquals("CARD_INPUT_EMPTY", host.row("setup.card").label, "no server yet: the normaliser's reason")
        assertTrue(setup.calls.isEmpty())
        edit(p, r, XtreamFormPage.ROW_SERVER, "panel.example.com")
        run(p, r, XtreamFormPage.ROW_CONNECT)
        assertEquals("CARD_LOGIN_EMPTY", host.row("setup.card").label)
        edit(p, r, XtreamFormPage.ROW_USERNAME, "me")
        edit(p, r, XtreamFormPage.ROW_PASSWORD, " pa ss+ ")
        setup.loginResult = Outcome.Err(SourceError.Auth(AuthReason.BAD_CREDENTIALS))
        run(p, r, XtreamFormPage.ROW_CONNECT)
        assertEquals("CARD_BAD_LOGIN", host.row("setup.card").label)
        assertEquals("ACTION_PHONE_SETUP", host.row("setup.card.secondary").label)
        run(p, r, "setup.card.primary") // Edit
        assertNull(host.rowOrNull("setup.card"))
        assertEquals("me", host.row(XtreamFormPage.ROW_USERNAME).value)
        assertEquals("pa ss+", (host.row(XtreamFormPage.ROW_PASSWORD).kind as RowKind.Text).value, "only the ends are trimmed")
    }

    @Test
    fun useSecureDnsSwitchesTheResolverAndRetries() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.XTREAM)
        p.open(r, host)
        edit(p, r, XtreamFormPage.ROW_SERVER, "http://blocked.example.com:8080")
        edit(p, r, XtreamFormPage.ROW_USERNAME, "u")
        edit(p, r, XtreamFormPage.ROW_PASSWORD, "p")
        setup.loginResult = Outcome.Err(SourceError.Network(app.thebox.core.common.NetworkKind.DNS, "java.net.UnknownHostException"))
        run(p, r, XtreamFormPage.ROW_CONNECT)
        assertEquals("CARD_DNS_HOST(blocked.example.com)", host.row("setup.card").label)
        run(p, r, "setup.card.primary")
        assertEquals(DnsChoice.CLOUDFLARE, store.flow(app.thebox.core.setup.pages.SetupNetworkDomain).value.dns)
        assertEquals(2, setup.calls.count { it.startsWith("testXtream") })
    }

    @Test
    fun theDraftSurvivesLeavingAndReopeningTheForm() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)
        p.open(r, host)
        edit(p, r, XtreamFormPage.ROW_SERVER, "panel.example.com")
        p.close(r)
        p.open(r, host)
        assertEquals("http://panel.example.com", host.row(XtreamFormPage.ROW_SERVER).value)
    }

    // ------------------------------------------------------------------ M3U form

    @Test
    fun anXtreamLinkInTheM3uFieldOffersXtreamLogin() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.M3U, firstRun = true)
        p.open(r, host)
        edit(p, r, M3uFormPage.ROW_URL, "http://panel.example.com/get.php?username=a&password=b&type=m3u_plus")
        assertEquals("CARD_XTREAM_LINK", host.row("setup.card").label)
        assertEquals("ACTION_USE_XTREAM", host.row("setup.card.primary").label)
        assertEquals("ACTION_KEEP_M3U", host.row("setup.card.secondary").label)
        run(p, r, "setup.card.primary")
        assertEquals(PageAction.Replace(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)), host.actions.last())
        // The Xtream form now holds the split login.
        p.open(SettingsRoute.AddSource(SourceKind.XTREAM, true), host)
        assertEquals("a", host.row(XtreamFormPage.ROW_USERNAME).value)
    }

    @Test
    fun keepAsM3uSavesThePlaylistWithItsGuideUrls() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.M3U)
        p.open(r, host)
        edit(p, r, M3uFormPage.ROW_URL, "panel.example.com/get.php?username=a&password=b")
        run(p, r, "setup.card.secondary") // Keep as M3U
        edit(p, r, M3uFormPage.ROW_EPG_NEW, "epg.example.com/guide.xml.gz")
        edit(p, r, M3uFormPage.ROW_EPG_NEW, "ftp://nope")
        assertEquals("CARD_INPUT_SCHEME", host.row("setup.card").label)
        run(p, r, "setup.card.primary") // Edit
        assertEquals("FORM_EPG_URL_N(1)", host.row("setup.m3u.epg.0").label)
        setup.saveResult = Outcome.Ok(3)
        run(p, r, M3uFormPage.ROW_ADD)
        assertEquals("saveM3u(http://panel.example.com/get.php?username=a&password=b,null)", setup.calls.last())
        assertEquals(PageAction.Replace(SettingsRoute.ImportProgress(3, firstRun = false)), host.actions.last())
    }

    @Test
    fun aStalkerPortalIsRefusedWithItsReason() = runTest(UnconfinedTestDispatcher()) {
        val p = pages(backgroundScope)
        val r = SettingsRoute.AddSource(SourceKind.M3U)
        p.open(r, host)
        edit(p, r, M3uFormPage.ROW_URL, "http://h.tv/stalker_portal/c/")
        assertEquals("CARD_INPUT_STALKER", host.row("setup.card").label)
        run(p, r, M3uFormPage.ROW_ADD)
        assertTrue(setup.calls.isEmpty())
    }

    // ------------------------------------------------------------------ Import progress

    @Test
    fun startWatchingEnablesAtStageThreeAndCompletesTheSetup() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        groups.list = (1..12).map { ReviewGroup("g$it", "UK | G$it", 10, adult = false, hidden = false, lockedPref = null) }
        val p = pages(backgroundScope)
        val r = SettingsRoute.ImportProgress(1, firstRun = true)
        val flow = progress(1)
        p.open(r, host)
        assertFalse(host.row(ImportProgressPage.ROW_START).enabled)
        run(p, r, ImportProgressPage.ROW_START)
        assertEquals(PageAction.Notice("NOTICE_STILL_READING"), host.actions.last())

        flow.emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.DOWNLOADING_PLAYLIST, true, "STAGE_COUNT_MB(4)"))
        flow.emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.READING_CHANNELS, true, "18,402 → 2,140"))
        assertEquals("STAGE_COUNT_MB(4)", host.row(ImportProgressPage.stageRow(app.thebox.core.setup.ImportStage.DOWNLOADING_PLAYLIST)).value)
        assertEquals("STAGE_WAITING", host.row(ImportProgressPage.stageRow(app.thebox.core.setup.ImportStage.MATCHING_GUIDE)).value)
        assertFalse(store.flow(SetupDomain).value.completed)
        flow.emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.GROUPS, true, "12"))
        flow.emit(SetupProgress.ReadyToWatch(1))
        assertTrue(host.row(ImportProgressPage.ROW_START).enabled)
        assertEquals(app.thebox.core.settings.SetupState(completed = true, lastAddedPlaylistId = 1), store.flow(SetupDomain).value, "§1.9a: written at stage 3")

        run(p, r, ImportProgressPage.ROW_START)
        assertEquals(PageAction.Replace(SettingsRoute.GroupsReview(1, firstRun = true)), host.actions.last(), "> 8 groups: the review first")
    }

    @Test
    fun fewGroupsGoStraightToTheGuideAndOutsideFirstRunStartWatchingLeaves() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        groups.list = (1..3).map { ReviewGroup("g$it", "G$it", 10, adult = false, hidden = false, lockedPref = null) }
        val p = pages(backgroundScope)
        progress(1).emit(SetupProgress.ReadyToWatch(1))
        progress(2).emit(SetupProgress.ReadyToWatch(2))
        p.open(SettingsRoute.ImportProgress(1, firstRun = true), host)
        run(p, SettingsRoute.ImportProgress(1, true), ImportProgressPage.ROW_START)
        assertEquals(PageAction.LeaveToGuide, host.actions.last())
        p.open(SettingsRoute.ImportProgress(2, firstRun = false), host)
        run(p, SettingsRoute.ImportProgress(2, false), ImportProgressPage.ROW_START)
        assertEquals(PageAction.LeaveToGuide, host.actions.last())
    }

    @Test
    fun backAsksFirstAndStopReturnsToTheForm() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(5, type = PlaylistType.M3U_URL)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.ImportProgress(5, firstRun = true)
        progress(5).emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.DOWNLOADING_PLAYLIST, false, null))
        p.open(r, host)
        assertTrue(p.onBack(r, host))
        val confirm = assertIs<PageAction.Confirm>(host.actions.last())
        assertEquals("IMPORT_STOP_TITLE", confirm.spec.title)
        p.onIntent(r, confirm.intent, host)
        assertEquals("cancelImport(5)", setup.calls.last())
        assertEquals(PageAction.Replace(SettingsRoute.AddSource(SourceKind.M3U, firstRun = true)), host.actions.last())
    }

    @Test
    fun aFailedImportShowsItsCardAndRetryRefreshesTheSamePlaylist() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(4)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.ImportProgress(4, firstRun = false)
        val mapper = SetupResultMapper(KeyStrings())
        progress(4).emit(SetupProgress.Failed(mapper.error(SourceError.SuspiciousShrink(2_140, 12))))
        p.open(r, host)
        assertEquals("ACTION_ACCEPT_ANYWAY", host.row("setup.card.primary").label)
        run(p, r, "setup.card.primary")
        assertEquals("refreshNow(4,true)", admin.calls.last())
    }

    @Test
    fun outsideFirstRunAnImportWithAdultGroupsOffersAPinOncePerPlaylist() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(8)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.ImportProgress(8, firstRun = false)
        progress(8).emit(SetupProgress.Finished(ImportSummary(8, 100, 0, 10, adultGroups = 2, epgMatched = 90, epgTotal = 100, uaNote = null)))
        p.open(r, host)
        assertEquals("PIN_OFFER(2)", host.row("setup.pin.offer").label)
        assertEquals(setOf("uid8"), store.flow(SetupDomain).value.pinOfferedFor)
        run(p, r, "setup.pin.set")
        assertEquals(PageAction.CreatePin, host.actions.last())
        assertNull(host.rowOrNull("setup.pin.offer"))
        p.close(r)
        p.open(r, host)
        assertNull(host.rowOrNull("setup.pin.offer"), "at most once per playlist (B3)")
    }

    @Test
    fun noPinOfferWhenAPinExists() = runTest(UnconfinedTestDispatcher()) {
        parental.value = parental.value.copy(pinSet = true)
        admin.put(detail(summary(8)))
        val p = pages(backgroundScope)
        progress(8).emit(SetupProgress.Finished(ImportSummary(8, 100, 0, 10, 2, 90, 100, null)))
        p.open(SettingsRoute.ImportProgress(8, false), host)
        assertNull(host.rowOrNull("setup.pin.offer"))
    }

    // ------------------------------------------------------------------ Groups review and report

    @Test
    fun groupsReviewTogglesHidesLocksAndAppliesInOneCall() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        groups.list = listOf(
            ReviewGroup("uk-news", "UK | News", 20, adult = false, hidden = false, lockedPref = null),
            ReviewGroup("us-news", "US: News", 30, adult = false, hidden = false, lockedPref = null),
            ReviewGroup("xxx", "Adult", 5, adult = true, hidden = false, lockedPref = null),
        )
        val p = pages(backgroundScope)
        val r = SettingsRoute.GroupsReview(1, firstRun = true)
        progress(1).emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.DOWNLOADING_GUIDE, true, null))
        p.open(SettingsRoute.ImportProgress(1, true), host) // creates the tracker
        p.open(r, host)
        val adult = host.row(GroupsReviewPage.groupRow("xxx")).kind as RowKind.Item
        assertEquals(setOf(Badge.LOCKED, Badge.ADULT), adult.badges, "adult groups start locked")
        assertEquals("PIN_OFFER(1)", host.row("setup.pin.offer").label)
        assertEquals(setOf("uid1"), store.flow(SetupDomain).value.pinOfferedFor)

        val chips = host.row(GroupsReviewPage.ROW_COUNTRIES).kind as RowKind.MultiChoice
        assertEquals(listOf("UK", "US"), chips.options.map { it.id })
        p.onIntent(r, RowIntent.ChooseMany(RowId(GroupsReviewPage.ROW_COUNTRIES), setOf("UK")), host)
        assertTrue(Badge.HIDDEN in (host.row(GroupsReviewPage.groupRow("us-news")).kind as RowKind.Item).badges)
        run(p, r, GroupsReviewPage.groupRow("uk-news")) // OK: toggle hidden
        p.onIntent(r, RowIntent.Menu(RowId(GroupsReviewPage.groupRow("xxx")), GroupsReviewPage.MENU_UNLOCK), host)
        p.onIntent(r, RowIntent.Menu(RowId(GroupsReviewPage.groupRow("us-news")), GroupsReviewPage.MENU_LOCK), host)

        assertTrue(p.onBack(r, host), "Back = Done")
        assertEquals("applyGroupsReview(1,h=2,l=1,u=1)", setup.calls.last())
        // (M5 integration) The report has its own route: Done replaces the review with it.
        val report = SettingsRoute.MatchingReport(1, firstRun = true)
        assertEquals(PageAction.Replace(report), host.actions.last())
        p.open(report, host)
        assertEquals("REPORT_SUMMARY(1,912|2,140|89)", host.row(GroupsReviewPage.ROW_REPORT_SUMMARY).label)
        assertEquals(SettingsRoute.FixGuide(null), (host.row(GroupsReviewPage.ROW_REPORT_FIX).kind as RowKind.Link).route)
        run(p, report, GroupsReviewPage.ROW_REPORT_LATER)
        assertEquals(PageAction.LeaveToGuide, host.actions.last())
        assertTrue(p.onBack(report, host), "Back on the report = Later")
    }

    @Test
    fun theReportIsSkippedWhileTheGuideStillDownloads() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        groups.list = listOf(ReviewGroup("a", "A", 1, false, false, null))
        val p = pages(backgroundScope)
        progress(1).emit(SetupProgress.ReadyToWatch(1))
        p.open(SettingsRoute.ImportProgress(1, true), host)
        val r = SettingsRoute.GroupsReview(1, true)
        p.open(r, host)
        run(p, r, GroupsReviewPage.ROW_DONE)
        assertEquals(PageAction.LeaveToGuide, host.actions.last())
    }

    // ------------------------------------------------------------------ Playlists

    @Test
    fun thePlaylistsSectionListsPlaylistsWithTheirStateAndTheActions() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1, "Main")))
        admin.put(detail(summary(2, "Spare", PlaylistType.M3U_URL, status = "http.403")))
        val p = pages(backgroundScope)
        val r = SettingsRoute.Section(SectionId.PLAYLISTS)
        p.open(r, host)
        assertEquals(listOf("playlists.p.1", "playlists.p.2", "playlists.add", "playlists.phone", "playlists.refresh_all"), host.ids())
        assertTrue(host.row("playlists.p.1").value!!.startsWith("TYPE_XTREAM · PLAYLIST_CHANNELS(2,140) · PLAYLIST_UPDATED("))
        assertEquals("LINE_REASON_NEXT(CARD_HTTP_REFUSED(403)|ACTION_EDIT_SOURCE)", host.row("playlists.p.2").value)
        assertEquals(setOf(Badge.FAILED), (host.row("playlists.p.2").kind as RowKind.Item).badges)
        assertEquals(Visibility.ADVANCED, host.row("playlists.refresh_all").visibility)

        p.onIntent(r, RowIntent.Menu(RowId("playlists.p.2"), PlaylistsSectionPage.MENU_EDIT), host)
        assertEquals(PageAction.Push(SettingsRoute.PlaylistSource(2)), host.actions.last())
        p.onIntent(r, RowIntent.Menu(RowId("playlists.p.2"), PlaylistsSectionPage.MENU_REMOVE), host)
        val confirm = assertIs<PageAction.Confirm>(host.actions.last())
        assertEquals("REMOVE_BODY(2,140|14|3)", confirm.spec.body)
        p.onIntent(r, confirm.intent, host)
        assertEquals("remove(2)", admin.calls.last())
        assertEquals(listOf("playlists.p.1", "playlists.add", "playlists.phone", "playlists.refresh_all"), host.ids())
    }

    @Test
    fun thePlaylistPageFollowsTheTableOfDesign331() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        admin.put(detail(summary(2, type = PlaylistType.M3U_URL)))
        val p = pages(backgroundScope)
        p.open(SettingsRoute.Playlist(1), host)
        val ids = host.ids()
        val simple = host.last.rows.filter { it.visibility == Visibility.SIMPLE && SettingsTree.spec(it.id) != null }.map { it.id.key }
        assertEquals(
            listOf("playlist.name", "playlist.source", "playlist.refresh", "playlist.update_on_start", "playlist.numbering", "playlist.name_prefix", "playlist.remove"),
            simple, "seven simple rows (§3.3.13)",
        )
        assertTrue("playlist.stream_format" in ids && "playlist.backup_servers" in ids)
        assertEquals(EffectTiming.NEXT_TUNE, host.row("playlist.user_agent").effect)
        assertEquals(EffectTiming.NEXT_LAUNCH, host.row("playlist.update_on_start").effect)
        assertIs<RowKind.Info>(host.row("playlist.max_connections").kind, "Xtream: from the account")
        // Every registered row this page builds carries its spec's visibility and effect.
        for (row in host.last.rows) SettingsTree.spec(row.id)?.let { assertEquals(it.visibility to it.effect, row.visibility to row.effect, row.id.key) }

        p.open(SettingsRoute.Playlist(2), host)
        assertFalse("playlist.stream_format" in host.ids(), "Xtream only")
        assertIs<RowKind.Number>(host.row("playlist.max_connections").kind)
    }

    @Test
    fun playlistRowsWriteThroughThePort() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.Playlist(1)
        p.open(r, host)
        edit(p, r, PlaylistPage.ROW_NAME, " ")
        assertEquals("rename(panel.example.com:8080)", admin.calls.last(), "blank name = the host")
        p.onIntent(r, RowIntent.Choose(RowId(PlaylistPage.ROW_USER_AGENT), PlaylistPage.UA_VLC), host)
        assertEquals("ua(${SetupConstants.VLC_USER_AGENT})", admin.calls.last())
        assertEquals("UA_VLC_NOTE", host.row(PlaylistPage.ROW_USER_AGENT).description)
        p.onIntent(r, RowIntent.Choose(RowId(PlaylistPage.ROW_NUMBERING), "START_AT"), host)
        p.onIntent(r, RowIntent.SetNumber(RowId(PlaylistPage.ROW_NUMBERING_START), 100), host)
        assertEquals("numbering(START_AT:100)", admin.calls.last())
        p.onIntent(r, RowIntent.Choose(RowId(PlaylistPage.ROW_NAME_PREFIX), "CUSTOM"), host)
        edit(p, r, PlaylistPage.ROW_NAME_PREFIX_CUSTOM, "UK | , US:")
        assertEquals("""prefix(CUSTOM,{"literals":["UK |","US:"]})""", admin.calls.last())
        assertEquals("PREFIX_PREVIEW(1,204|2,140)", host.row(PlaylistPage.ROW_NAME_PREFIX).description)
        edit(p, r, PlaylistPage.ROW_HEADERS, "Range: bytes=0-")
        assertEquals(PageAction.Notice("HEADERS_INVALID"), host.actions.last(), "Range is never stored")
        edit(p, r, PlaylistPage.ROW_HEADERS, "Referer: http://x")
        assertEquals("header(Referer,http://x)", admin.calls.last())
        p.onIntent(r, RowIntent.Toggle(RowId(PlaylistPage.ROW_IGNORE_TLS), true), host)
        val confirm = assertIs<PageAction.Confirm>(host.actions.last())
        p.onIntent(r, confirm.intent, host)
        assertEquals("tls(true)", admin.calls.last())
        run(p, r, PlaylistPage.ROW_REMOVE)
        p.onIntent(r, assertIs<PageAction.Confirm>(host.actions.last()).intent, host)
        assertEquals("remove(1)", admin.calls.last())
        assertEquals(PageAction.PopTo(SettingsRoute.Section(SectionId.PLAYLISTS)), host.actions.last())
    }

    @Test
    fun editingTheSourceInPlaceKeepsThePlaylistId() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(6)))
        admin.sources[6] = EditableSource.Xtream(XtreamForm("Main", "http://old.example.com", "u", "p"))
        val p = pages(backgroundScope)
        val r = SettingsRoute.PlaylistSource(6)
        p.open(r, host)
        assertEquals("http://old.example.com", host.row(XtreamFormPage.ROW_SERVER).value)
        edit(p, r, XtreamFormPage.ROW_SERVER, "new.example.com:8080")
        setup.loginResult = Outcome.Ok(XtreamAccountInfo(true, AccountStatus.ACTIVE, measuredAtDeviceMs = now))
        setup.saveResult = Outcome.Ok(6)
        run(p, r, XtreamFormPage.ROW_CONNECT)
        run(p, r, "setup.card.primary")
        assertEquals("saveXtream(http://new.example.com:8080,6)", setup.calls.last())
        assertEquals(PageAction.Replace(SettingsRoute.ImportProgress(6, firstRun = false)), host.actions.last())
        // A failure after an in-place edit sends Edit back to the source page, not to a new-playlist form.
        progress(6).emit(SetupProgress.Failed(SetupResultMapper(KeyStrings()).error(SourceError.Http(403))))
        p.open(SettingsRoute.ImportProgress(6, false), host)
        run(p, SettingsRoute.ImportProgress(6, false), "setup.card.primary")
        assertEquals(PageAction.Replace(SettingsRoute.PlaylistSource(6)), host.actions.last())
    }

    // ------------------------------------------------------------------ M5 code review (2026-10-16)

    /** C-L4 (N12): stopping an import opened from Settings returns to the Playlists page already on the path. */
    @Test
    fun stopFromSettingsPopsToThePlaylistsPage() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(6, type = PlaylistType.M3U_URL)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.ImportProgress(6, firstRun = false)
        progress(6).emit(SetupProgress.Stage(app.thebox.core.setup.ImportStage.DOWNLOADING_PLAYLIST, false, null))
        p.open(r, host)
        assertTrue(p.onBack(r, host))
        val confirm = assertIs<PageAction.Confirm>(host.actions.last())
        p.onIntent(r, confirm.intent, host)
        assertEquals(PageAction.PopTo(SettingsRoute.Section(SectionId.PLAYLISTS)), host.actions.last())
    }

    /** C-B2 (§3.9.2): with "Lock settings" on, the Playlists menu's Edit and Remove ask for the PIN as the Playlist page does. */
    @Test
    fun playlistMenuEditAndRemoveAskWithLockSettings() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1, "Main")))
        parental.value = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy(lockSettings = true))
        pages(backgroundScope).open(SettingsRoute.Section(SectionId.PLAYLISTS), host)
        val gated = (host.row("playlists.p.1").kind as RowKind.Item).menu.associate { it.id to it.needsPin }
        assertEquals(
            mapOf(PlaylistsSectionPage.MENU_REFRESH to false, PlaylistsSectionPage.MENU_EDIT to true, PlaylistsSectionPage.MENU_TEST to false, PlaylistsSectionPage.MENU_REMOVE to true),
            gated,
        )
        parental.value = parental.value.copy(policy = ParentalPolicy(lockSettings = false))
        pages(backgroundScope).open(SettingsRoute.Section(SectionId.PLAYLISTS), host)
        assertTrue((host.row("playlists.p.1").kind as RowKind.Item).menu.none { it.needsPin }, "Lock settings off: no PIN")
    }
}
