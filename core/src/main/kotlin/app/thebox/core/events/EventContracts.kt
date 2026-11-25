// Contract (Event channels commit 0): docs/design/m6-event-channels.md §4.1. Names are frozen; changes go through the
// lead. Every enum here is append-only. The "Commit 0 rulings" section of the design records what commit 0 chose where
// §4.1 left a signature open ([EventEntry.name], [EventIndex.rebuild], [EventSnapshot.EMPTY], [EventConstants]).
package app.thebox.core.events

import app.thebox.core.common.Hash64
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/** How an event's start zone was found (§3.3). */
enum class EventZoneSource { EXPLICIT, CONVENTION, ASSUMED }

/** Which tail matched (§3.2). Tests and logs only. */
enum class EventShape {
    AT_DAY_MONTH, AT_MONTH_DAY, PAREN_MONTH_DAY, PIPE_MONTH_DAY, ISO,
    /** (Code review 2026-11-25, owner item 17) `… MON D hh:mmPM ZONE` at the end with no "@" ("OHL 03 | 2026 … NOV 25 07:00PM ET"). */
    MONTH_DAY_BARE,
}

/** [nowUtcMs]: `playlist.last_refresh_at`; [assumedZone]: the learned zone (§3.3). */
data class EventParseContext(val nowUtcMs: Long, val assumedZone: ZoneId)

/** What [EventNameParser.parse] reads from one provider channel name (§3.2). */
sealed interface ParsedName {
    /** Not an event shape: an ordinary channel. */
    data object None : ParsedName

    /** "NO EVENT", "NHL 02:", "Offline", the 2098 placeholder. */
    data class Empty(val slot: String?) : ParsedName

    data class Event(
        /** "Sportsnet+ 23", "CA (TSN+ 008)"; null for a slot-less shape C name. */
        val slot: String?,
        /** Cleaned (§3.2), never blank. */
        val title: String,
        /** Shape E's network or a moved scraper id. */
        val subtitle: String?,
        val startUtcMs: Long,
        /** startUtcMs + EventDuration (§3.5). */
        val endUtcMs: Long,
        val zone: EventZoneSource,
        /** The zone id used ("America/New_York", "+10:00"): the learned-zone tally reads it for EXPLICIT rows. */
        val zoneId: String,
        val shape: EventShape,
    ) : ParsedName
}

/** §3.7: folded, cleaned token lists. [ordered]: "@" / "at", so [first] is the away side. */
data class Matchup(val first: List<String>, val second: List<String>, val ordered: Boolean)

/**
 * What the guide, search and the matcher read for one channel. [startMin] / [endMin]: UTC epoch minutes, never shifted
 * by the channel's guide shift (a name carries a true instant). An EMPTY slot has no title and 0 / 0 minutes.
 */
data class ChannelEvent(
    val kind: Kind,
    val title: String?,
    val subtitle: String?,
    val startMin: Int,
    val endMin: Int,
    val zone: EventZoneSource,
) {
    enum class Kind { EVENT, EMPTY }

    /** Part of the guide's row basis (§6.5, arch B5). */
    val basis: Long
        get() = Hash64.str(Hash64.long(Hash64.long(kind.ordinal.toLong(), startMin.toLong()), endMin.toLong()), title)

    companion object {
        /** The value `GuideQueries.channels` gives an empty slot with no index entry (§5.4). */
        val EMPTY: ChannelEvent = ChannelEvent(Kind.EMPTY, null, null, 0, 0, EventZoneSource.ASSUMED)
    }
}

/**
 * One indexed event (§5.2). [group]: the provider group's name. [name] (Commit 0 ruling): the raw provider name
 * (`channel.name`), which `EventGameRule` reads whole for its hints (`EventFacts.name`, §8.4) so stage E needs no second
 * query.
 */
class EventEntry(
    val channelId: Long,
    val playlistId: Long,
    val slot: String?,
    val group: String?,
    val event: ChannelEvent,
    val name: String,
)

/** An immutable index of every live event of the enabled playlists. */
interface EventSnapshot {
    /** Changes whenever any keying input changes (§5.2). */
    val version: Long
    fun of(channelId: Long): EventEntry?
    /** Entries whose start is in [fromMin, toMin], in start order (binary search). */
    fun startingIn(fromMin: Int, toMin: Int): List<EventEntry>
    /** Entries whose event ends after [nowMin] (search). */
    fun current(nowMin: Int): List<EventEntry>
    fun count(playlistId: Long): Int

    companion object {
        /** (Commit 0 ruling) No events: the stub index's answer, and a test default. Version 0. */
        val EMPTY: EventSnapshot = object : EventSnapshot {
            override val version: Long get() = 0L
            override fun of(channelId: Long): EventEntry? = null
            override fun startingIn(fromMin: Int, toMin: Int): List<EventEntry> = emptyList()
            override fun current(nowMin: Int): List<EventEntry> = emptyList()
            override fun count(playlistId: Long): Int = 0
        }
    }
}

/** `:data` `EventIndexImpl` (WP-2), bound in `GuideDataModule`. */
interface EventIndex {
    /** Emits the snapshot version after every rebuild; the guide's rows cache reloads on it (§6.5). */
    val version: StateFlow<Long>

    /** The current snapshot, building it first if it is missing or stale (IO; under 100 ms on a Fire TV Stick, §5.2). */
    suspend fun current(): EventSnapshot

    /**
     * (Commit 0 ruling; §5.2, §10) Rebuild now, whether or not the key changed, and return the new snapshot. The refresh
     * path (`RefreshRepository`, WP-2) calls it after `finish`, before the library signal settles. Serialised with
     * [current]'s builds.
     */
    suspend fun rebuild(): EventSnapshot

    /**
     * (Code review 2026-11-25, data Q1) The last published snapshot, possibly stale, or null before the first build. No
     * I/O, no suspension: the guide's row loader reads it instead of waiting for a cold build.
     */
    fun peek(): EventSnapshot? = null

    /**
     * (Code review 2026-11-25, data Q1) Starts a build on the application scope when none is running and the snapshot is
     * missing or stale; returns at once. The [version] tick that follows reloads the guide's rows.
     */
    fun prefetch() {}
}

/**
 * (Commit 0 ruling) Appendix A's event constants that the guide, search, the parser and refresh read. The hub's own
 * (`HUB_EVENT_*`, `HUB_CITIES_CONFIRM_MIN`, `HUB_BOARD_CLASH_MIN`, `HUB_LOOKAHEAD_MIN`, `HUB_TEAM_QUERY_MAX`) are in `app.thebox.core.sports.SportsConstants`, so `guide`, `epg` and `data.work` never import
 * `sports`.
 */
object EventConstants {
    /** Search's event hits (§7). */
    const val EVENT_SEARCH_LIMIT = 60
    /** An event hit is dropped when the same channel has a kept guide hit starting within ± this (minutes; §7, E4). */
    const val EVENT_SEARCH_DEDUP_MIN = 5
    /** After an event's estimated end, the panel says it may still be on for this long (minutes; §6.3). */
    const val EVENT_MAY_BE_ON_MIN = 180
    /** The default update interval (hours) of a playlist with at least [EVENT_REFRESH_MIN] live events (§10). */
    const val EVENT_REFRESH_H = 6
    const val EVENT_REFRESH_MIN = 50
    /** The learned zone (§3.3): at least this many EXPLICIT names to choose a zone, or to replace one … */
    const val EVENT_ZONE_MIN_SAMPLES = 20
    /** … and, to replace one, at least this many times the stored zone's current count. */
    const val EVENT_ZONE_HYSTERESIS = 2
    /** The year window around `now` for names without a year (days; §3.4). */
    const val EVENT_YEAR_PAST_DAYS = 300
    const val EVENT_YEAR_FUTURE_DAYS = 90
    /** `EventDuration` (§3.5), minutes: a football matchup, a soccer matchup, any other matchup, a show. */
    const val EVENT_MIN_FOOTBALL = 210
    const val EVENT_MIN_SOCCER = 135
    const val EVENT_MIN_MATCHUP = 180
    const val EVENT_MIN_SHOW = 60
    /** The synthetic `cellTextId` of an inserted event cell (§6.1, arch N2): negative ids are never cached. */
    const val EVENT_TEXT_ID = -2
}
