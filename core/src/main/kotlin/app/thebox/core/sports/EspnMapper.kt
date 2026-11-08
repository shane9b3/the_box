// Contract (NFL commit 0, docs/design/m6-nfl.md §1.3, B3): the signatures are fixed; WP-1 writes the bodies.
package app.thebox.core.sports

/**
 * ESPN site scoreboard to snapshots (docs/design/m6-nfl.md §1.3; WP-1). Pure: no I/O, no clock. [serverDateMs] is the
 * body's `Date` (the PRE window); [logoBase] is the ESPN logo base (`https://a.espncdn.com/`, or the debug override).
 * A board fails (null) only when `events` is missing; an event that fails to map is dropped from it.
 */
object EspnMapper {
    /** The week or a dated board. [DayBoard.dayKey] is the week key (`w2026-2-9`) or the ET date ("2026-11-08"). */
    fun board(dto: EspnBoardDto, serverDateMs: Long?, logoBase: String): DayBoard? = TODO("NFL WP-1: §1.3")

    /** The event [id] of a dated (or week) board; null when it is absent or fails to map. */
    fun game(dto: EspnGameDto, id: GameId, serverDateMs: Long?, logoBase: String): GameSnapshot? = TODO("NFL WP-1: §1.3")

    /** "w{year}-{seasontype}-{week}", e.g. "w2026-2-9" (S6): never readable as a date. */
    fun weekKey(year: Int, type: Int, week: Int): String = TODO("NFL WP-1: §1.3")

    /** The one week-key parser (S6): (year, seasontype, week), or null for anything else (a date key included). */
    fun parseWeekKey(key: String): Triple<Int, Int, Int>? = TODO("NFL WP-1: §1.3")
}
