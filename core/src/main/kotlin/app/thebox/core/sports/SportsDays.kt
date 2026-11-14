// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §2.3, P7, Q7, S11). Changes go through the lead.
package app.thebox.core.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (CHL §2.3) A league's "today": the date in its reference zone, rolling over [SportsConstants.CHL_DAY_ROLLOVER_H] hours
 * after midnight for the CHL, so a late game stays on its own day. Pure; the caller passes the wall clock.
 *
 * | League | Zone | Rollover |
 * |---|---|---|
 * | OHL, QMJHL | `America/Toronto` | 05:00 |
 * | WHL | `America/Vancouver` | 05:00 |
 * | NHL, NFL | `America/New_York` | midnight (unchanged: `SportsViewModel`'s Eastern date, used only for the date fallback) |
 */
object SportsDays {
    val EASTERN: ZoneId = ZoneId.of("America/New_York")
    val TORONTO: ZoneId = ZoneId.of("America/Toronto")
    val VANCOUVER: ZoneId = ZoneId.of("America/Vancouver")

    /** The league's reference zone. */
    fun zone(league: League): ZoneId = when (league) {
        League.NHL, League.NFL -> EASTERN
        League.OHL, League.QMJHL -> TORONTO
        League.WHL -> VANCOUVER
    }

    /** Hours after local midnight at which the league's day turns over. */
    fun rolloverH(league: League): Int = when (league.group) {
        LeagueGroup.NHL, LeagueGroup.NFL -> 0
        LeagueGroup.CHL -> SportsConstants.CHL_DAY_ROLLOVER_H
    }

    /**
     * `(nowUtc at zone − rollover).toLocalDate()`, on the local wall clock: the day turns over at 05:00 local time on
     * every day, DST change days included (an instant-based − 5 h would turn over at 04:00 or 06:00 on those).
     */
    fun today(league: League, nowUtcMs: Long): LocalDate =
        Instant.ofEpochMilli(nowUtcMs).atZone(zone(league)).toLocalDateTime().minusHours(rolloverH(league).toLong()).toLocalDate()

    /**
     * (CHL integration review S1) The end of the league day of the **calendar date** of [utcMs] in the reference zone: the
     * next day's rollover (05:00 local the next morning for the CHL; midnight for the NHL and NFL). Meant for a "Time TBD"
     * game, whose start is local midnight of its game date (`HockeyTechMapper`): the end of that day is the earliest it can
     * be known to be over, so its hard expiry is measured from here, not from the placeholder.
     */
    fun dayEndUtcMs(league: League, utcMs: Long): Long {
        val z = zone(league)
        val date = Instant.ofEpochMilli(utcMs).atZone(z).toLocalDate()
        return date.plusDays(1).atTime(rolloverH(league), 0).atZone(z).toInstant().toEpochMilli() // local wall clock (DST days too)
    }
}
