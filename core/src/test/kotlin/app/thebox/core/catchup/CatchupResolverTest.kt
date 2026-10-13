package app.thebox.core.catchup

import app.thebox.core.model.ArchiveDaysSource
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.source.PlaylistType
import app.thebox.core.source.RawCatchup
import kotlin.test.Test
import kotlin.test.assertEquals

class CatchupResolverTest {
    private val r = CatchupResolverImpl()
    private val m3u = PlaylistCatchupDefaults(PlaylistType.M3U_URL)
    private val xc = PlaylistCatchupDefaults(PlaylistType.XTREAM)
    private val plain = "http://cdn.example/hls/chan/index.m3u8"
    private val xcUrl = "http://h:8080/live/{{u}}/{{p}}/101.ts"

    private fun mode(raw: RawCatchup, url: String = plain, d: PlaylistCatchupDefaults = m3u) = r.resolve(raw, url, null, d).mode

    @Test
    fun aChannelAttribute() {
        assertEquals(CatchupMode.FLUSSONIC, mode(RawCatchup(type = "fs")))
        assertEquals(CatchupMode.XC, mode(RawCatchup(type = "xc", days = 3)))
        assertEquals(CatchupMode.DEFAULT, mode(RawCatchup(type = "default", source = "http://a/{utc}")))
        // attribute beats timeshift= and Xtream flags
        assertEquals(CatchupMode.APPEND, mode(RawCatchup(type = "append", source = "?a={utc}", timeshiftDays = 2, xtreamTvArchive = false)))
        // unrecognised values fall through (to e: a source alone → DEFAULT)
        assertEquals(CatchupMode.DEFAULT, mode(RawCatchup(type = "1", source = "http://a/{utc}")))
    }

    @Test
    fun bTimeshiftAndTvgRecForceShift() {
        assertEquals(CatchupMode.SHIFT, mode(RawCatchup(timeshiftDays = 2)))
        assertEquals(CatchupMode.SHIFT, mode(RawCatchup(tvgRecDays = 3, xtreamTvArchive = true)))
        assertEquals(CatchupMode.OFF, mode(RawCatchup(timeshiftDays = 0)))
    }

    @Test
    fun cXtreamArchiveFlag() {
        assertEquals(CatchupMode.XC, mode(RawCatchup(xtreamTvArchive = true), "{{base}}/live/{{u}}/{{p}}/1.{{ext}}", xc))
        // The panel said no: stop even when the playlist header has a default.
        val withHeader = m3u.copy(header = RawCatchup(type = "shift"))
        assertEquals(CatchupMode.OFF, mode(RawCatchup(xtreamTvArchive = false), plain, withHeader))
    }

    @Test
    fun dHeaderDefaultAndESource() {
        assertEquals(CatchupMode.SHIFT, mode(RawCatchup(), plain, m3u.copy(header = RawCatchup(type = "shift"))))
        assertEquals(CatchupMode.APPEND, mode(RawCatchup(), plain, m3u.copy(header = RawCatchup(type = "append", source = "?utc={utc}"))))
        assertEquals(CatchupMode.DEFAULT, mode(RawCatchup(source = "http://a/{utc}")))
        assertEquals(CatchupMode.DEFAULT, mode(RawCatchup(), plain, m3u.copy(header = RawCatchup(source = "http://a/{utc}"))))
    }

    @Test
    fun fXtreamUrlOnlyWhenTheVotePassed() {
        val shaped = m3u.copy(xtreamShaped = true)
        assertEquals(CatchupMode.XC, mode(RawCatchup(days = 3), xcUrl, shaped))
        assertEquals(CatchupMode.XC, mode(RawCatchup(catchupTimeSec = 3600), "http://h:8080/{{u}}/{{p}}/101", shaped))
        // B6: a CDN path that merely looks like Xtream, in a non-shaped list
        assertEquals(CatchupMode.OFF, mode(RawCatchup(days = 3), "http://cdn/live/bbc/hd/1.m3u8", m3u))
        // shaped but no days advertised
        assertEquals(CatchupMode.OFF, mode(RawCatchup(), xcUrl, shaped))
        // shaped, days, but not an Xtream URL
        assertEquals(CatchupMode.OFF, mode(RawCatchup(days = 3), plain, shaped))
    }

    @Test
    fun gOff() {
        assertEquals(Catchup(CatchupMode.OFF, 0, null, null, null), r.resolve(RawCatchup(days = 5), plain, null, m3u))
    }

    @Test
    fun postRules() {
        val noSource = r.resolve(RawCatchup(type = "default", days = 2, correctionHours = -4.0), plain, null, m3u)
        assertEquals(CatchupMode.SHIFT, noSource.mode)
        assertEquals(-4.0, noSource.correctionHours)
        assertEquals(CatchupMode.SHIFT, mode(RawCatchup(type = "append")))
        // the header source counts
        val hdr = r.resolve(RawCatchup(type = "append"), plain, null, m3u.copy(header = RawCatchup(source = "?utc={utc}")))
        assertEquals(CatchupMode.APPEND, hdr.mode)
        assertEquals("?utc={utc}", hdr.source)
        // channel source beats header source
        val ch = r.resolve(RawCatchup(type = "default", source = "http://c/{utc}"), plain, null, m3u.copy(header = RawCatchup(source = "?h")))
        assertEquals("http://c/{utc}", ch.source)
    }

    @Test
    fun forcedAndOff() {
        val forced = m3u.copy(modeSetting = CatchupModeSetting.Forced(CatchupMode.FLUSSONIC_TS))
        val c = r.resolve(RawCatchup(), plain, null, forced)
        assertEquals(Catchup(CatchupMode.FLUSSONIC_TS, 3, null, null, ArchiveDaysSource.DEFAULT), c)
        val off = m3u.copy(modeSetting = CatchupModeSetting.Off)
        assertEquals(CatchupMode.OFF, mode(RawCatchup(type = "xc", days = 7, xtreamTvArchive = true), xcUrl, off))
    }

    @Test
    fun archiveDaysChain() {
        val base = RawCatchup(type = "shift")
        fun days(raw: RawCatchup, d: PlaylistCatchupDefaults = m3u) = r.resolve(raw, plain, null, d).let { it.days to it.daysSource }
        assertEquals(10 to ArchiveDaysSource.PLAYLIST_OVERRIDE, days(base.copy(days = 5), m3u.copy(daysOverride = 10)))
        assertEquals(5 to ArchiveDaysSource.CATCHUP_DAYS, days(base.copy(days = 5, timeshiftDays = 4)))
        assertEquals(4 to ArchiveDaysSource.TIMESHIFT, days(base.copy(days = 0, timeshiftDays = 4, tvgRecDays = 2)))
        assertEquals(2 to ArchiveDaysSource.TVG_REC, days(base.copy(tvgRecDays = 2, catchupTimeSec = 900_000)))
        assertEquals(2 to ArchiveDaysSource.CATCHUP_TIME, days(base.copy(catchupTimeSec = 86_401)))
        assertEquals(1 to ArchiveDaysSource.CATCHUP_TIME, days(base.copy(catchupTimeSec = 3600)))
        assertEquals(7 to ArchiveDaysSource.XTREAM_DURATION, days(base.copy(xtreamArchiveDays = 7)))
        assertEquals(6 to ArchiveDaysSource.PLAYLIST_HEADER, days(base, m3u.copy(header = RawCatchup(days = 6))))
        assertEquals(3 to ArchiveDaysSource.DEFAULT, days(base))
        assertEquals(30 to ArchiveDaysSource.CATCHUP_DAYS, days(base.copy(days = 90)), "clamped to 30")
        assertEquals(1 to ArchiveDaysSource.DEFAULT, days(base, m3u.copy(defaultDays = 0)), "clamped to 1")
    }

    @Test
    fun l11TvArchiveWithoutDurationIsThreeDays() {
        val c = r.resolve(RawCatchup(xtreamTvArchive = true, xtreamArchiveDays = null), "{{base}}/live/{{u}}/{{p}}/1.{{ext}}", 1, xc)
        assertEquals(Catchup(CatchupMode.XC, 3, null, null, ArchiveDaysSource.DEFAULT), c)
        val zero = r.resolve(RawCatchup(xtreamTvArchive = true, xtreamArchiveDays = 0), "{{base}}/live/{{u}}/{{p}}/1.{{ext}}", 1, xc)
        assertEquals(3, zero.days)
    }
}
