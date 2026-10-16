package app.thebox.core.settings.state

import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.net.DnsChoice
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.GuideRows
import app.thebox.core.settings.tree.InterfaceRows
import app.thebox.core.settings.tree.NetworkRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.settings.tree.RemoteRows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Page-builder tables for WP-A's sections (docs/design/m5-setup-settings.md §6.1): per row, the value shown, the
 * visibility, enabled/disabled reason and EffectTiming for the default store, and the store write of every intent.
 */
class WpaPagesTest {
    private val store = FakeSettingsStore()
    private val env = FakePageEnv()
    private val pages = WpaPages(store, SettingsTexts.KEYS, env)

    private fun page(id: SectionId, advanced: Boolean = true) = pages.build(SettingsRoute.Section(id), advanced)
    private fun row(id: SectionId, row: RowId, advanced: Boolean = true): SettingsRow = assertNotNull(page(id, advanced).rows.firstOrNull { it.id == row }, row.key)

    /** (section, row id, value shown with defaults, effect, visibility) */
    private val table = listOf(
        Triple(SectionId.GUIDE, GuideRows.INTERVAL, "INTERVAL_HOURS(12)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.GUIDE, GuideRows.MATCHING, "MATCH_SUGGEST") to (EffectTiming.NEXT_EPG_IMPORT to Visibility.ADVANCED),
        Triple(SectionId.GUIDE, GuideRows.SHIFT, "SHIFT_NONE") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.GUIDE, GuideRows.PAST_DAYS, "PAST_AUTO") to (EffectTiming.NEXT_EPG_IMPORT to Visibility.ADVANCED),
        Triple(SectionId.GUIDE, GuideRows.FUTURE_DAYS, "DAYS_N(7)") to (EffectTiming.NEXT_EPG_IMPORT to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.BUFFER, "BUF_AUTO") to (EffectTiming.REPREPARE to Visibility.SIMPLE),
        Triple(SectionId.PLAYBACK, PlaybackRows.AFR, "AFR_SEAMLESS") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.PLAYBACK, PlaybackRows.AUDIO_OUTPUT, "AO_AUTO") to (EffectTiming.REPREPARE to Visibility.SIMPLE),
        Triple(SectionId.PLAYBACK, PlaybackRows.ASPECT, "ASPECT_FIT") to (EffectTiming.NEXT_TUNE to Visibility.SIMPLE),
        Triple(SectionId.PLAYBACK, PlaybackRows.ON_LAUNCH, "LAUNCH_GUIDE_LAST") to (EffectTiming.NEXT_LAUNCH to Visibility.SIMPLE),
        Triple(SectionId.PLAYBACK, PlaybackRows.AUDIO_LANGUAGE, "LANG_DEVICE") to (EffectTiming.NEXT_TUNE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.SUBTITLES, "SUBS_OFF") to (EffectTiming.NEXT_TUNE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.DECODER, "DEC_AUTO") to (EffectTiming.REPREPARE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.RECONNECT, "COUNT(5)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.AFTER_STANDBY, "STANDBY_RESUME") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.HDR_PREVIEW, "HDR_POSTER") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.INFO_BAR, "SECONDS(5)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.NUMBER_WAIT, "SECONDS(1.5)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.PLAYBACK, PlaybackRows.EXTERNAL_PLAYER, "NONE") to (EffectTiming.NEXT_TUNE to Visibility.ADVANCED),
        Triple(SectionId.INTERFACE, InterfaceRows.CLOCK, "CLOCK_SYSTEM") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.INTERFACE, InterfaceRows.MINI_PLAYER, "AUTOMATIC(MINI_VIDEO)") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.INTERFACE, InterfaceRows.PERFORMANCE, "PERF_AUTO") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.INTERFACE, InterfaceRows.AMBIENT, "AUTOMATIC(ON)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.INTERFACE, InterfaceRows.REMINDER_LEAD, "LEAD_MIN(1)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.REMOTE, RemoteRows.MAPPING, "RM_DEFAULTS") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.REMOTE, RemoteRows.UPDOWN, "UD_QUICK") to (EffectTiming.LIVE to Visibility.SIMPLE),
        Triple(SectionId.REMOTE, RemoteRows.BACK, "BACK_GUIDE") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.REMOTE, RemoteRows.EXIT, "EXIT_TWICE") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.NETWORK, NetworkRows.DNS, "DNS_SYSTEM") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.NETWORK, NetworkRows.IPV4, "IPV4_AUTO") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.NETWORK, NetworkRows.CONNECT_TIMEOUT, "SECONDS(10)") to (EffectTiming.LIVE to Visibility.ADVANCED),
        Triple(SectionId.NETWORK, NetworkRows.READ_TIMEOUT, "SECONDS(20)") to (EffectTiming.LIVE to Visibility.ADVANCED),
    )

    @TestFactory
    fun defaults(): List<DynamicTest> = table.map { (where, how) ->
        val (section, id, value) = where
        DynamicTest.dynamicTest("${id.key} = $value") {
            val r = row(section, id)
            assertEquals(value, r.value)
            assertEquals(how.first, r.effect)
            assertEquals(how.second, r.visibility)
            assertTrue(r.enabled)
            if (how.first != EffectTiming.LIVE) assertNotNull(r.description, "a non-LIVE row states its effect")
        }
    }

    @Test
    fun advancedRowsAreAbsentInTheSimpleView() {
        for (s in WpaPages.SECTIONS) {
            val simple = page(s, advanced = false).rows
            assertTrue(simple.all { it.visibility == Visibility.SIMPLE }, "$s")
            assertEquals(page(s).rows.count { it.visibility == Visibility.SIMPLE }, simple.size)
        }
    }

    @Test
    fun conditionalRows() {
        assertNull(page(SectionId.GUIDE).rows.firstOrNull { it.id == GuideRows.DAILY_HOUR })
        pages.apply(RowIntent.Choose(GuideRows.INTERVAL, WpaPages.DAILY))
        assertEquals(3, store.flow(DataDomain).value.epgDailyAtHour)
        assertEquals("HOUR_VALUE(3)", row(SectionId.GUIDE, GuideRows.DAILY_HOUR).value)
        pages.apply(RowIntent.SetNumber(GuideRows.DAILY_HOUR, 22))
        assertEquals(22, store.flow(DataDomain).value.epgDailyAtHour)
        pages.apply(RowIntent.Choose(GuideRows.INTERVAL, "6"))
        assertEquals(6 to null, store.flow(DataDomain).value.let { it.epgRefreshIntervalH to it.epgDailyAtHour })

        assertNull(page(SectionId.NETWORK).rows.firstOrNull { it.id == NetworkRows.CUSTOM_DOH })
        pages.apply(RowIntent.Choose(NetworkRows.DNS, DnsChoice.CUSTOM.name))
        assertEquals("NOT_SET", row(SectionId.NETWORK, NetworkRows.CUSTOM_DOH).value)
        pages.apply(RowIntent.Edit(NetworkRows.CUSTOM_DOH, " https://dns.example/dns-query "))
        assertEquals("https://dns.example/dns-query", store.flow(NetworkDomain).value.customDohUrl)

        val invert = row(SectionId.REMOTE, RemoteRows.INVERT)
        assertFalse(invert.enabled)
        assertEquals("RM_INVERT_REASON", invert.disabledReason)
        pages.apply(RowIntent.Choose(RemoteRows.UPDOWN, UpDownInPlayer.ZAP.name))
        assertTrue(row(SectionId.REMOTE, RemoteRows.INVERT).enabled)

        assertNull(page(SectionId.PLAYBACK).rows.firstOrNull { it.id == PlaybackRows.RESTART_NOW })
        pages.apply(RowIntent.Toggle(PlaybackRows.FFMPEG, false))
        assertNotNull(page(SectionId.PLAYBACK).rows.firstOrNull { it.id == PlaybackRows.RESTART_NOW }, "N9: Restart now while the engine differs")
        pages.apply(RowIntent.Run(PlaybackRows.RESTART_NOW))
        assertEquals(1, env.restarts)
    }

    @Test
    fun frameRateMatchingBelowApi30ShowsOffAndAlwaysWithTheNote() {
        env.sdkInt = 28
        val r = row(SectionId.PLAYBACK, PlaybackRows.AFR)
        val k = r.kind as RowKind.Choice
        assertEquals(listOf(AfrMode.OFF.name, AfrMode.ALWAYS.name), k.options.map { it.id })
        assertEquals("AFR_OFF", r.value, "a stored SEAMLESS shows as Off (N5)")
        assertTrue(r.description!!.startsWith("AFR_OLD_NOTE"))
    }

    @Test
    fun alwaysPassthroughIsRepreparedAndSaysHowToUndo() {
        pages.apply(RowIntent.Choose(PlaybackRows.AUDIO_OUTPUT, AudioOutputMode.FORCE_PASSTHROUGH.name))
        assertEquals(AudioOutputMode.FORCE_PASSTHROUGH, store.flow(PlaybackDomain).value.audioOutput)
        val r = row(SectionId.PLAYBACK, PlaybackRows.AUDIO_OUTPUT)
        assertEquals(EffectTiming.REPREPARE, r.effect)
        assertTrue(r.description!!.startsWith("AO_PASSTHROUGH_DETAIL"))
    }

    @Test
    fun intentsWriteTheirDomains() {
        pages.apply(RowIntent.Choose(PlaybackRows.BUFFER, BufferMode.LARGE.name))
        pages.apply(RowIntent.Toggle(PlaybackRows.TUNNELING, true))
        pages.apply(RowIntent.Choose(PlaybackRows.RECONNECT, "-1"))
        pages.apply(RowIntent.Choose(PlaybackRows.INFO_BAR, "8000"))
        pages.apply(RowIntent.Choose(PlaybackRows.AUDIO_LANGUAGE, "de"))
        pages.apply(RowIntent.Choose(PlaybackRows.EXTERNAL_PLAYER, "org.videolan.vlc"))
        pages.apply(RowIntent.Choose(InterfaceRows.CLOCK, ClockMode.H12.name))
        pages.apply(RowIntent.Choose(InterfaceRows.MINI_PLAYER, WpaPages.STILL))
        pages.apply(RowIntent.Toggle(InterfaceRows.AMBIENT, false))
        pages.apply(RowIntent.Choose(GuideRows.SHIFT, "-90"))
        pages.apply(RowIntent.Choose(GuideRows.PAST_DAYS, "3"))
        pages.apply(RowIntent.Toggle(SettingsMachineImpl.ADVANCED_ROW, true))
        val p = store.flow(PlaybackDomain).value
        assertEquals(BufferMode.LARGE, p.bufferMode)
        assertTrue(p.tunneling)
        assertEquals(-1, p.reconnectAttempts)
        assertEquals(listOf("de"), p.preferredAudioLanguages)
        assertEquals("org.videolan.vlc", p.externalPlayer)
        assertEquals(8000L, store.flow(PlayerDomain).value.infoBarMs)
        val g = store.flow(GuideDomain).value
        assertEquals(ClockMode.H12, g.clock)
        assertEquals(true, g.miniStill)
        assertEquals(false, g.ambient)
        assertTrue(g.advancedSettings)
        assertEquals(-90, store.flow(DataDomain).value.globalShiftMin)
        assertEquals(3, store.flow(DataDomain).value.epgPastDays)
        assertEquals("SHIFT_VALUE(−1.5)", row(SectionId.GUIDE, GuideRows.SHIFT).value)
        assertFalse(pages.apply(RowIntent.Run(RowId("playlist.refresh"))), "other WPs' rows are not WP-A's")
    }

    @Test
    fun lowEndDeviceMakesTheAutomaticValuesStillAndOff() {
        env.deviceLowEnd = true
        assertEquals("AUTOMATIC(MINI_STILL)", row(SectionId.INTERFACE, InterfaceRows.MINI_PLAYER).value)
        assertEquals("AUTOMATIC(OFF)", row(SectionId.INTERFACE, InterfaceRows.AMBIENT).value)
    }

    @Test
    fun mappingShowsTheNumberOfChanges() {
        env.overrides = 3
        assertEquals("RM_CHANGES(3)", row(SectionId.REMOTE, RemoteRows.MAPPING).value)
    }

    @Test
    fun noExternalPlayerInstalledSaysWhatToDo() {
        env.players = emptyList()
        val r = row(SectionId.PLAYBACK, PlaybackRows.EXTERNAL_PLAYER)
        assertTrue(r.description!!.startsWith("PB_EXTERNAL_NONE_INSTALLED"))
    }

    @Test
    fun validatorRules() {
        val v = HintValidator(SettingsTexts.KEYS, WpaPages.rowRules(SettingsTexts.KEYS))
        assertNull(v.validate(RowId("x"), app.thebox.core.settings.TextHint.URL, "http://panel.example.com:8080/get.php?u=1"))
        assertEquals("ERR_URL", v.validate(RowId("x"), app.thebox.core.settings.TextHint.URL, "panel.example.com"))
        assertEquals("ERR_HTTPS", v.validate(NetworkRows.CUSTOM_DOH, app.thebox.core.settings.TextHint.URL, "http://a.example/dns-query"))
        assertEquals("ERR_PATTERN", v.validate(RowId("x"), app.thebox.core.settings.TextHint.PATTERN, "(unclosed"))
        assertNull(v.validate(RowId("x"), app.thebox.core.settings.TextHint.HEADER, "Referer: http://a.example"))
        assertEquals("ERR_HEADER", v.validate(RowId("x"), app.thebox.core.settings.TextHint.HEADER, "no separator"))
        assertEquals("ERR_PASSPHRASE(8)", v.validate(RowId("x"), app.thebox.core.settings.TextHint.PASSPHRASE, "short"))
    }
}
