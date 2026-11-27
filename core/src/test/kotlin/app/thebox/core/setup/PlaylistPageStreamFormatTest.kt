package app.thebox.core.setup

import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.pages.PlaylistPage
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.core.setup.pages.SetupPages
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * (docs/decisions/2026-11-25-auto-format-retries-ts.md) The Stream format row while Auto has learned HLS: the description
 * says when TS is tried again (a calendar day in the device zone), and Auto can be picked again to forget HLS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPageStreamFormatTest {
    private val admin = FakePlaylistAdmin()
    private val host = RecordingHost()
    /** 2026-09-21 14:13 UTC. */
    private val now = 1_790_000_000_000L
    private val hour = 3_600_000L

    private fun pages(scope: CoroutineScope) = SetupPages(
        SetupPageDeps(
            strings = KeyStrings(), normalizer = SourceInputNormalizerImpl(), setup = FakeSourceSetup(), admin = admin, groups = FakeGroups(),
            matching = FakeMatching(), store = FakeSettingsStore(), parental = MutableStateFlow(ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy())),
            scope = scope, nowMs = { now }, locale = { Locale.UK }, zone = { ZoneOffset.UTC },
        ),
    )

    private fun formatRow(setting: String, learned: String?, formats: List<String>? = null, scope: CoroutineScope): app.thebox.core.settings.SettingsRow {
        val account = formats?.let { XtreamAccountInfo(auth = true, status = AccountStatus.ACTIVE, allowedOutputFormats = it, measuredAtDeviceMs = 0) }
        admin.put(detail(summary(1)).copy(xtreamOutput = setting, xtreamOutputLearned = learned, account = account))
        pages(scope).open(SettingsRoute.Playlist(1), host)
        return host.row(PlaylistPage.ROW_STREAM_FORMAT)
    }

    @Test
    fun theDescriptionSaysWhenTsIsTriedAgain() = runTest(UnconfinedTestDispatcher()) {
        assertEquals("EFFECT_NEXT_TUNE", formatRow("AUTO", null, scope = backgroundScope).description)
        // Stored before the retry existed (the emulator's state): due now.
        assertEquals("FORMAT_LEARNED_HLS_RETRY_NEXT", formatRow("AUTO", "HLS", scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_TOMORROW", formatRow("AUTO", "HLS;t=$now", scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_TODAY", formatRow("AUTO", "HLS;t=${now - 23 * hour}", scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_DAYS(3)", formatRow("AUTO", "HLS;t=$now;n=1", scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_DAYS(7)", formatRow("AUTO", "HLS;t=$now;n=2", scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_TODAY", formatRow("AUTO", "HLS;t=${now - 30 * hour};h=${now + hour}", scope = backgroundScope).description)
    }

    @Test
    fun anAccountWithoutTsKeepsThePlainLearnedText() = runTest(UnconfinedTestDispatcher()) {
        assertEquals("FORMAT_LEARNED_HLS", formatRow("AUTO", "HLS", formats = listOf("m3u8"), scope = backgroundScope).description)
        assertEquals("FORMAT_LEARNED_HLS_RETRY_NEXT", formatRow("AUTO", "HLS", formats = listOf("m3u8", "ts"), scope = backgroundScope).description)
    }

    @Test
    fun manualModesAndLearnedTsAreUntouched() = runTest(UnconfinedTestDispatcher()) {
        for (setting in listOf("TS", "HLS")) {
            val row = formatRow(setting, "HLS;t=$now", scope = backgroundScope)
            assertEquals("EFFECT_NEXT_TUNE", row.description)
            assertFalse(assertIs<RowKind.Choice>(row.kind).options.any { it.reselectable })
        }
        assertEquals("EFFECT_NEXT_TUNE", formatRow("AUTO", "TS", scope = backgroundScope).description)
    }

    @Test
    fun autoCanBePickedAgainWhileHlsIsLearned() = runTest(UnconfinedTestDispatcher()) {
        val row = formatRow("AUTO", "HLS;t=$now", scope = backgroundScope)
        val kind = assertIs<RowKind.Choice>(row.kind)
        assertEquals(0, kind.selected)
        val auto = kind.options[0]
        assertTrue(auto.reselectable)
        assertEquals("FORMAT_AUTO_RESET_DETAIL", auto.detail)
        assertFalse(assertIs<RowKind.Choice>(formatRow("AUTO", null, scope = backgroundScope).kind).options[0].reselectable)
        pages(backgroundScope).let { p ->
            p.open(SettingsRoute.Playlist(1), host)
            admin.put(detail(summary(1)).copy(xtreamOutput = "AUTO", xtreamOutputLearned = "HLS;t=$now"))
            p.onIntent(SettingsRoute.Playlist(1), RowIntent.Choose(RowId(PlaylistPage.ROW_STREAM_FORMAT), "AUTO"), host)
        }
        // setStreamFormat → PlaylistDao.setXtreamOutput, which clears xtream_output_learned (PlaybackMemoryDaoTest).
        assertEquals("format(AUTO)", admin.calls.last())
    }
}
