package app.thebox.core.full.feed

import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.events.ChannelEvent
import app.thebox.core.events.EpgFiller
import app.thebox.core.events.EventZoneSource
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.fakes.GuideFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Owner report 2026-11-26: the player's programmes carry the event read from the channel name, as the guide does. */
class EventProgrammesTest {
    private val ev = ChannelEvent(ChannelEvent.Kind.EVENT, "Montreal @ Colorado", null, 1_000, 1_180, EventZoneSource.EXPLICIT)
    private val plain = GuideFixtures.channel(0, epg = null)
    private val event = plain.copy(event = ev)
    private val empty = plain.copy(event = ChannelEvent.EMPTY)
    private val filler = EpgFiller::isFiller

    private fun p(start: Int, end: Int, title: String, raw: Int = start) =
        ProgrammeInfo(raw, start, end, title, null, null, null, null, 0, Hash64.of(title))

    private fun List<ProgrammeInfo>.shape() = map { Triple(it.startMin, it.endMin, it.title) }

    @Test
    fun atAnswersTheEventOverNothingAndOverAPlaceholder() {
        assertEquals("Montreal @ Colorado", EventProgrammes.at(event, 1_000, null, filler)?.title)
        assertEquals("Montreal @ Colorado", EventProgrammes.at(event, 1_179, p(900, 1_500, "No Event Today"), filler)?.title)
        val info = EventProgrammes.at(event, 1_050, null, filler)!!
        assertEquals(Triple(1_000, 1_000, 1_180), Triple(info.rawStartMin, info.startMin, info.endMin))
    }

    @Test
    fun atKeepsARealListingAndTimesOutsideTheSpan() {
        val real = p(960, 1_200, "NHL Hockey")
        assertSame(real, EventProgrammes.at(event, 1_050, real, filler))
        assertNull(EventProgrammes.at(event, 999, null, filler))
        assertNull(EventProgrammes.at(event, 1_180, null, filler))
        val place = p(900, 1_500, "No Event Today")
        assertSame(place, EventProgrammes.at(event, 1_300, place, filler))
    }

    @Test
    fun ordinaryAndEmptySlotChannelsAreUntouched() {
        val list = listOf(p(900, 1_500, "No Event Today"))
        assertSame(list, EventProgrammes.overlay(plain, list, 0, 2_000, filler))
        assertSame(list, EventProgrammes.overlay(empty, list, 0, 2_000, filler))
        assertNull(EventProgrammes.at(empty, 1_050, null, filler))
    }

    @Test
    fun overlayInsertsTheEventWithNoListings() {
        assertEquals(listOf(Triple(1_000, 1_180, "Montreal @ Colorado")), EventProgrammes.overlay(event, emptyList(), 900, 2_000, filler).shape())
        assertEquals(emptyList(), EventProgrammes.overlay(event, emptyList(), 1_180, 2_000, filler))
        assertEquals(emptyList(), EventProgrammes.overlay(event, emptyList(), 0, 1_000, filler))
    }

    @Test
    fun overlayClipsPlaceholdersAroundTheEvent() {
        val list = listOf(p(600, 900, "Earlier show"), p(900, 1_100, "No Event Today"), p(1_100, 1_500, "Upcoming: something"), p(1_500, 1_600, "Later show"))
        val out = EventProgrammes.overlay(event, list, 0, 2_000, filler)
        assertEquals(
            listOf(
                Triple(600, 900, "Earlier show"), Triple(900, 1_000, "No Event Today"), Triple(1_000, 1_180, "Montreal @ Colorado"),
                Triple(1_180, 1_500, "Upcoming: something"), Triple(1_500, 1_600, "Later show"),
            ),
            out.shape(),
        )
        // A clipped piece keeps its raw start.
        assertEquals(1_100, out[3].rawStartMin)
    }

    @Test
    fun onePlaceholderAcrossTheWholeSpanLeavesAHeadAndATail() {
        val out = EventProgrammes.overlay(event, listOf(p(0, 2_000, "No Event Today")), 0, 2_000, filler)
        assertEquals(listOf(Triple(0, 1_000, "No Event Today"), Triple(1_000, 1_180, "Montreal @ Colorado"), Triple(1_180, 2_000, "No Event Today")), out.shape())
    }

    @Test
    fun aPlaceholderStartingOnTheEventsMinuteIsDroppedWhole() {
        val out = EventProgrammes.overlay(event, listOf(p(1_000, 1_440, "No Event Today")), 0, 2_000, filler)
        assertEquals(listOf(Triple(1_000, 1_180, "Montreal @ Colorado")), out.shape())
    }

    @Test
    fun aRealListingInTheSpanWins() {
        val list = listOf(p(900, 1_060, "No Event Today"), p(1_060, 1_240, "NHL Hockey"))
        assertSame(list, EventProgrammes.overlay(event, list, 0, 2_000, filler))
    }

    @Test
    fun channelWithAnEpgReferenceBehavesTheSame() {
        val ch = GuideFixtures.channel(1, epg = EpgChannelRef(1, "x")).copy(event = ev)
        assertEquals("Montreal @ Colorado", EventProgrammes.at(ch, 1_100, null, filler)?.title)
    }
}
