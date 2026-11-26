package app.thebox.core.sports.hub

import app.thebox.core.sports.EspnBoardDto
import app.thebox.core.sports.SportsFixtures
import kotlinx.serialization.json.Json

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §8.1) The reduced ESPN fixtures shared by `:core` (P1) and `:data` (P2)
 * tests, made from the lead's local samples by `tools/espn/reduce_summary.py` (no sample is committed; every media and
 * image URL is on an `.invalid` host). The recorded `expirationDate`s are in the past by release time: tests inject the
 * clock, never read the system clock.
 */
object NflHubFixtures {
    private const val DIR = "sports/nfl/hub/"

    /** MIA @ BUF, final: 0 clips, the recap headline, 25 team stats with the duplicated `interceptions`, "J. Cook III". */
    const val FINAL_MIA_BUF = DIR + "summary-final-401873070.json"
    /** PIT @ PHI, final: one Final Game Highlight (a 96 h window). */
    const val FINAL_PIT_PHI = DIR + "summary-final-401873076.json"
    /** MNF CIN @ WSH, final, ESPN-aired: 4 of the 12 clips (the highlight, two one-plays, the lights-out clip), `header`
     *  before `videos`, a tie record "4-5-1", a field-goal winner. */
    const val FINAL_MNF = DIR + "summary-final-espn-401873078.json"
    /** IND @ HOU, final: a safety ("SF", type 20). */
    const val FINAL_SAFETY = DIR + "summary-final-401873066.json"
    /** MIN @ SF, final: an interception-return touchdown (type 36). */
    const val FINAL_DEF_TD = DIR + "summary-final-401873077.json"
    /** GB @ LAR, pre-game: 8 averages, last five, season leaders (5.5 sacks), injuries incl. IR, an Analysis clip; no
     *  `scoringPlays` / `drives` keys. */
    const val PRE_GB_LAR = DIR + "summary-pre-401873079.json"
    /** Clip records (the `{videos: [ … ]}` wrapper): Burrow to Higgins (OnePlay), the lights-out clip (OnePlay). */
    const val RECORD_50578630 = DIR + "clip-record-50578630.json"
    const val RECORD_50579083 = DIR + "clip-record-50579083.json"
    /** Week 11's board, two events (PIT @ PHI, CIN @ WSH), each with one `highlights[]` entry and `broadcasts`. */
    const val BOARD_WK11 = DIR + "scoreboard-highlights-wk11.json"

    /**
     * GB @ LAR live, `summary-live-401873079-{1,2,3}.json`. `-1` is the **real** capture of 2026-11-26T02:04Z (Q2 11:29,
     * LAR 13–3; integration §18); `-2` (halftime with a clip) and `-3` (Q4) are still P1's provisional hand-built bodies.
     */
    const val LIVE_GB_LAR_1 = DIR + "summary-live-401873079-1.json"
    const val LIVE_GB_LAR_2 = DIR + "summary-live-401873079-2.json"
    const val LIVE_GB_LAR_3 = DIR + "summary-live-401873079-3.json"
    /** The **real** halftime capture of 2026-11-26T02:44Z (`STATUS_HALFTIME`, 13–13), and its board event. */
    const val LIVE_GB_LAR_HALF = DIR + "summary-live-401873079-half.json"
    const val BOARD_EVENT_HALF = DIR + "scoreboard-event-half-401873079.json"
    /** The board's own event (`scoreboard/{id}`, wrapped as `{events: [ … ]}`) at the same instant as [LIVE_GB_LAR_1]. */
    const val BOARD_EVENT_LIVE = DIR + "scoreboard-event-live-401873079.json"
    /**
     * (§20) GB @ LAR after the final, **real** summary (the 2026-11-26T04:45Z capture, `STATUS_FINAL`): five OnePlay clips
     * and **no** Final Game Highlight. The first real single-play clips after a game.
     */
    const val FINAL_GB_LAR = DIR + "summary-final-401873079.json"
    /**
     * (§20) The board's own event for GB @ LAR final: the real 04:45Z capture (week board reduced to the event) plus a
     * **synthesized** `highlights[]` entry (none of the saved boards was captured after ESPN posted it), shaped exactly as
     * week 11's, with the id, headline and duration the emulator showed (50601743, "Rams pick up important home win over
     * the Packers", 80 s) and `.invalid` hosts.
     */
    const val BOARD_EVENT_FINAL_SYNTH = DIR + "scoreboard-event-final-401873079.json"

    val SUMMARIES = listOf(FINAL_MIA_BUF, FINAL_PIT_PHI, FINAL_MNF, FINAL_SAFETY, FINAL_DEF_TD, PRE_GB_LAR)

    /** The decoder the app uses (`EspnHttp`): unknown keys skipped. */
    val JSON = Json { ignoreUnknownKeys = true }

    fun summary(path: String): EspnSummaryDto = JSON.decodeFromString(EspnSummaryDto.serializer(), SportsFixtures.text(path))
    fun record(path: String): EspnClipRecordDto = JSON.decodeFromString(EspnClipRecordDto.serializer(), SportsFixtures.text(path))
    fun board(path: String = BOARD_WK11): EspnBoardDto = JSON.decodeFromString(EspnBoardDto.serializer(), SportsFixtures.text(path))
}
