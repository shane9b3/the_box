package app.thebox.ui.sports

import android.graphics.Paint
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Market
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * (Event channels, docs/design/m6-event-channels.md §8.5, §8.6, §9.1, §11.1; U-E-B1, B3, B4, N5, N6; re-review E10 – E12)
 * The hub line for each case with its never-cut "+n more", the abbreviation fallback, the beyond-guide lines and the
 * panel's description of them, the grey Channels slot, the sheet's reasons and line 3; and every new one-line text
 * measured with the app's font at Default and Largest against its slot (664 px hub line, 770 px sheet reason).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubEventChannelsTextTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val medium = sansTypeface(context, R.font.instrument_sans_medium)

    @After fun resetScale() { UiTextScale.running = TextSize.DEFAULT }

    private val vgk = SportsFixtures.team("VGK", "Golden Knights")
    private val edm = SportsFixtures.team("EDM", "Oilers")
    private val now = 1_764_000_000_000L
    private val upcoming = SportsFixtures.game(id = "U1", phase = GamePhase.SCHEDULED, away = vgk, home = edm, period = null, periodKind = null, clock = null, startUtcMs = now + 3 * 3_600_000L)
    private val live = upcoming.copy(phase = GamePhase.LIVE, startUtcMs = now - 600_000L)

    private fun teamCand(side: Side, name: String = if (side == Side.HOME) "Edmonton Oilers" else "Vegas Golden Knights") = ChannelCandidate(
        GuideFixtures.channel(if (side == Side.HOME) 1 else 2, name = name), "Main", MatchConfidence.CONFIRMED,
        listOf(MatchReason.TeamChannel(name, if (side == Side.HOME) "Oilers" else "Golden Knights", side)), emptyList(), null, "Sports | NHL Teams",
    )
    private fun other(i: Int, name: String = "Channel $i", vararg r: MatchReason) =
        ChannelCandidate(GuideFixtures.channel(10 + i, name = name), "Main", MatchConfidence.CONFIRMED, r.toList(), emptyList(), null)

    private fun model(g: GameSnapshot, m: MatchState, imminent: Boolean = g.phase == GamePhase.LIVE) =
        HubPanelModel(g, match = m, nowMs = now, imminent = imminent)

    private fun line(g: GameSnapshot, m: MatchState, factor: Float = 1f, imminent: Boolean = g.phase == GamePhase.LIVE) =
        HubPanelContents.channelLine(res, model(g, m, imminent), g, clock24 = false, locale = Locale.US, factor = factor)!!

    private fun width(text: String, f: Float): Float = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = 22 * f }.measureText(text)

    private fun nine(top: ChannelCandidate) = listOf(top) + (1..8).map { other(it) }

    // ------------------------------------------------------------------ the hub line

    @Test fun aTeamChannelOnTopNamesTheTeamChannelAndKeepsTheTail() {
        val l = line(live, MatchState.Done(nine(teamCand(Side.HOME))))
        assertEquals("On TV: Oilers team channel", l.text)
        assertEquals("+8 more", l.more)
        val at = line(upcoming, MatchState.Done(nine(teamCand(Side.HOME))))
        val time = SportsText.startTime(upcoming.startUtcMs, false, Locale.US)
        assertEquals("On TV at $time: Oilers team channel", at.text)
        assertEquals("one candidate: no tail", null, line(live, MatchState.Done(listOf(teamCand(Side.HOME)))).more)
    }

    /** E11: "Golden Knights team channel" would not fit at Largest with the tail, so the abbreviation stands in. */
    @Test fun theAbbreviationFallback() {
        val late = upcoming.copy(startUtcMs = java.time.ZonedDateTime.of(2026, 11, 24, 22, 30, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val m = MatchState.Done(nine(teamCand(Side.AWAY)))
        // With the tail the nickname line is 56 characters, over 55: the abbreviation already at Default.
        assertEquals("On TV at 10:30 PM: VGK team channel", line(late, m, 1f, imminent = false).text)
        // Alone (no tail) it is 46 and fits at Default, not at Largest.
        assertEquals("On TV at 10:30 PM: Golden Knights team channel", line(late, MatchState.Done(listOf(teamCand(Side.AWAY))), 1f, imminent = false).text)
        assertEquals("On TV at 10:30 PM: VGK team channel", line(late, MatchState.Done(listOf(teamCand(Side.AWAY))), 1.2f, imminent = false).text)
        val largest = line(late, m, 1.2f, imminent = false)
        assertEquals("On TV at 10:30 PM: VGK team channel", largest.text)
        assertTrue(largest.full(" · ").length <= 45)
        assertEquals("On TV at 10:30 PM: VGK team channel · +8 more", largest.full(" · "))
    }

    /** Code review 2026-11-25, UI open item 4: both team channels when both are listed, top's team first. */
    @Test fun bothTeamChannelsAreNamedWhenTheyFit() {
        val wpg = SportsFixtures.team("WPG", "Jets")
        val bos = SportsFixtures.team("BOS", "Bruins")
        val seven = java.time.ZonedDateTime.of(2026, 11, 24, 19, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val g = upcoming.copy(away = wpg, home = bos, startUtcMs = seven)
        fun tc(side: Side, nick: String) = ChannelCandidate(
            GuideFixtures.channel(if (side == Side.HOME) 1 else 2, name = "$nick TV"), "Main", MatchConfidence.CONFIRMED,
            listOf(MatchReason.TeamChannel("$nick TV", nick, side)), emptyList(), null, "Sports | NHL Teams",
        )
        val list = listOf(tc(Side.HOME, "Bruins"), tc(Side.AWAY, "Jets")) + (1..8).map { other(it) }
        val l = line(g, MatchState.Done(list), imminent = false)
        assertEquals("7:00 PM: Bruins and Jets team channels", l.text)
        assertEquals("+8 more", l.more)
        val now = line(g.copy(phase = GamePhase.LIVE), MatchState.Done(list))
        assertEquals("On TV: Bruins and Jets team channels", now.text)
        // Too long for both nicknames: one nickname, with the other team counted in the tail.
        val late = upcoming.copy(startUtcMs = java.time.ZonedDateTime.of(2026, 11, 24, 22, 30, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val vo = listOf(teamCand(Side.HOME), teamCand(Side.AWAY)) + (1..7).map { other(it) }
        val one = line(late, MatchState.Done(vo), imminent = false)
        assertEquals("On TV at 10:30 PM: Oilers team channel", one.text)
        assertEquals("+8 more", one.more)
        // At Largest neither nickname line fits: the abbreviations of both.
        val largest = line(late, MatchState.Done(listOf(teamCand(Side.AWAY), teamCand(Side.HOME)) + (1..7).map { other(it) }), 1.2f, imminent = false)
        assertEquals("10:30 PM: VGK and EDM team channels", largest.text)
        assertEquals("+7 more", largest.more)
        for (f in listOf(1.0f, 1.2f)) for (x in listOf(l, now, one, largest)) assertTrue(width(x.full(" · "), f) <= HubPanelPlan.INNER_W)
    }

    @Test fun shortReasons() {
        val sep = " · "
        assertEquals("On TV: NHL 01 · from the channel name", line(live, MatchState.Done(listOf(other(1, "NHL 01", MatchReason.EventName("Golden Knights vs. Oilers", now, false, false))))).text)
        assertEquals("On TV: Sportsnet West · in your guide", line(live, MatchState.Done(listOf(other(1, "Sportsnet West", MatchReason.Guide("NHL Hockey", now, now + 1, false), MatchReason.EventName("x", now, false, false))))).text)
        // E12: the fallback is "national broadcaster", never "local channel, may differ".
        assertEquals("On TV: US: TNT · national broadcaster", line(live, MatchState.Done(listOf(other(1, "US: TNT", MatchReason.Broadcaster("TNT", Market.NATIONAL, "US"))))).text)
        assertEquals("On TV: X · national broadcaster", line(live, MatchState.Done(listOf(other(1, "X")))).full(sep))
    }

    @Test fun theBeyondGuideLines() {
        // (Owner ruling 2026-11-25) The chip opens the team channels: the line says the guide does not reach the game yet,
        // names the team channels in the matcher's order (home first by default), and never reads "On TV" (UI m8).
        val both = MatchState.BeyondGuide(listOf(teamCand(Side.HOME), teamCand(Side.AWAY)), noGuide = false)
        val def = line(upcoming, both)
        assertTrue(def.beyond)
        // "Oilers and Golden Knights" would be 57 characters: the abbreviations of both.
        assertEquals("Not in guide yet: EDM and VGK team channels", def.text)
        assertNull(def.more)
        assertEquals("Not in guide yet: EDM and VGK team channels", line(upcoming, both, 1.2f).text)
        val jets = SportsFixtures.team("WPG", "Jets")
        val bos = SportsFixtures.team("BOS", "Bruins")
        fun tc(side: Side, nick: String) = ChannelCandidate(
            GuideFixtures.channel(if (side == Side.HOME) 1 else 2, name = "$nick TV"), "Main", MatchConfidence.CONFIRMED,
            listOf(MatchReason.TeamChannel("$nick TV", nick, side)), emptyList(), null,
        )
        val wpgBos = upcoming.copy(away = jets, home = bos)
        assertEquals("home first", "Not in guide yet: Bruins and Jets team channels", line(wpgBos, MatchState.BeyondGuide(listOf(tc(Side.HOME, "Bruins"), tc(Side.AWAY, "Jets")), false)).text)
        assertEquals("Not in guide yet: Oilers team channel", line(upcoming, MatchState.BeyondGuide(listOf(teamCand(Side.HOME)), false)).text)
        assertEquals("Not in guide yet: Golden Knights team channel", line(upcoming, MatchState.BeyondGuide(listOf(teamCand(Side.AWAY)), false), 1.2f).text)
        // A twin feed counts in the tail, as in the chip's count.
        val twin = MatchState.BeyondGuide(listOf(teamCand(Side.HOME), teamCand(Side.HOME).copy(anotherFeed = true), teamCand(Side.AWAY)), false)
        assertEquals("+1 more", line(upcoming, twin).more)
        assertEquals("Your guide doesn't cover this game yet", line(upcoming, MatchState.BeyondGuide(emptyList(), noGuide = false)).text)
        assertEquals("Channels show 12 hours before the game", line(upcoming, MatchState.BeyondGuide(emptyList(), noGuide = true)).text)
        assertEquals("the team line holds with no guide", def.text, line(upcoming, both.copy(noGuide = true)).text)
    }

    /**
     * §11.1 HubSlotsTest: BeyondGuide never shows "Finding". (Owner ruling 2026-11-25) With team channels the chip is
     * enabled, counts them and the sheet lists them; it is grey only with none.
     */
    @Test fun beyondTheGuideTheChannelsSlotIsGreyOnlyWithoutTeamChannels() {
        for (m in listOf(MatchState.BeyondGuide(emptyList(), true), MatchState.BeyondGuide(emptyList(), false, complete = false))) {
            val slots = HubSlots.default(HubSlots.Ctx(upcoming, false, null, null, m, false, now))
            assertEquals(SlotAction.CHANNEL, slots[1].action)
            assertEquals(SlotLabel.CHANNELS, slots[1].label)
            assertFalse(slots[1].enabled)
            assertFalse("disabled: skipped by Left / Right and never entered", slots[1].usable)
        }
        val teams = MatchState.BeyondGuide(listOf(teamCand(Side.HOME), teamCand(Side.AWAY)), false)
        val slots = HubSlots.default(HubSlots.Ctx(upcoming, false, null, null, teams, false, now))
        assertEquals(SlotLabel.CHANNELS_N, slots[1].label)
        assertEquals(2, slots[1].count)
        assertTrue(slots[1].usable)
        assertEquals(teams.teams, HubPanelModel(upcoming, match = teams).candidates)
        assertEquals("Channels (2)", slotText(res, slots[1]))
    }

    // ------------------------------------------------------------------ fit (one line at Default and Largest)

    @Test fun everyHubLineFitsItsSlot() {
        val late = upcoming.copy(startUtcMs = java.time.ZonedDateTime.of(2026, 11, 24, 22, 30, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val states = listOf(
            late to MatchState.Done(nine(teamCand(Side.AWAY))),
            live to MatchState.Done(nine(teamCand(Side.AWAY))),
            upcoming to MatchState.BeyondGuide(listOf(teamCand(Side.HOME), teamCand(Side.AWAY)), false),
            upcoming to MatchState.BeyondGuide(emptyList(), false),
            upcoming to MatchState.BeyondGuide(emptyList(), true),
        )
        for (f in listOf(1.0f, 1.2f)) for ((g, m) in states) {
            val l = line(g, m, f, imminent = g.phase == GamePhase.LIVE)
            val full = l.full(" · ")
            assertTrue("chars at $f: $full", full.length <= HubPanelContents.lineBudget(f))
            assertTrue("px at $f: $full", width(full, f) <= HubPanelPlan.INNER_W)
        }
        // A long channel name ellipsizes; the tail alone always fits.
        assertTrue(width(" · " + res.getQuantityString(R.plurals.sports_hub_more, 99, 99), 1.2f) < HubPanelPlan.INNER_W / 4)
    }

    /** U-E-B1, E10: the sheet's reason line is one line of about 770 px; the time comes before the title. */
    @Test fun everySheetReasonFitsOrKeepsItsTime() {
        val sheetW = 770f
        for (f in listOf(1.0f, 1.2f)) {
            for (t in listOf(
                res.getString(R.string.sports_hub_reason_team, "Golden Knights"),
                res.getString(R.string.sports_hub_reason_network_unnamed),
                res.getString(R.string.sports_hub_also_in, "Sports | NHL Teams (2)"),
                res.getString(R.string.sports_hub_reason_event_untimed, "Vegas @ Edmonton"),
                // (Code review 2026-11-25, UI open item 2) The shown event reasons.
                res.getString(R.string.sports_hub_reason_event_rest, "10:30 PM", "Golden Knights Broadcast"),
                res.getString(R.string.sports_hub_reason_event_rest, "10:30 PM", "NHL on TNT"),
                res.getString(R.string.sports_hub_reason_event_bare, "10:30 PM"),
                res.getString(R.string.sports_hub_reason_event_untimed_rest, "Golden Knights Broadcast"),
                res.getString(R.string.sports_hub_reason_event_untimed_bare),
                res.getString(R.string.sports_hub_beyond_guide_one, "Golden Knights"),
                res.getString(R.string.sports_hub_reason_another_feed),
                // (Integration) The three older reasons, with the longest network and country fills the NHL feed gives.
                res.getString(R.string.sports_hub_reason_network, "Prime Video", "United States"),
                res.getString(R.string.sports_hub_reason_network, "NHL Network", "United States"),
                res.getString(R.string.sports_hub_reason_network_any, "NHL Network"),
                res.getString(R.string.sports_hub_reason_sport_listing, "NHL Hockey", "10:30 PM"),
            )) assertTrue("$t at $f", width(t, f) <= sheetW)
            val prefix = res.getString(R.string.sports_hub_reason_event, "10:30 PM", "")
            assertTrue("the time and the title's start are never cut at $f", width(prefix + "Golden Knights", f) <= sheetW)
            val listing = res.getString(R.string.sports_hub_reason_sport_listing, "", "10:30 PM")
            assertTrue("a sport listing's time is never cut at $f", width(listing + "NHL Hockey", f) <= sheetW)
            // (Integration, emulator at Largest) A guide listing's time range comes first too.
            val guide = res.getString(R.string.sports_hub_reason_guide, "", "10:00 PM–12:30 AM")
            assertTrue("a guide listing's time range is never cut at $f", width(guide + "NHL Hockey", f) <= sheetW)
        }
        assertEquals(47, res.getString(R.string.sports_hub_reason_team, "Golden Knights").length)
    }

    // ------------------------------------------------------------------ the sheet's words

    @Test fun sheetReasonsAndLineThree() {
        val g = upcoming
        val event = other(1, "NHL 01", MatchReason.EventName("NHL on TNT: Golden Knights at Oilers", now, false, false))
        val time = SportsText.startTime(now, false)
        // (Code review 2026-11-25, UI open item 2) The matchup is the sheet's title: the time and the rest of the title.
        assertEquals("Channel name: $time · NHL on TNT", channelReason(res, event, g, false))
        assertEquals("the spoken reason keeps the whole title", "From the channel name: $time, NHL on TNT: Golden Knights at Oilers", channelReason(res, event, g, false, spoken = true))
        val broadcast = other(4, "NHL 12", MatchReason.EventName("Vegas Golden Knights vs. Edmonton Oilers (Oilers Broadcast)", now, false, false))
        assertEquals("Channel name: $time · Oilers Broadcast", channelReason(res, broadcast, g, false))
        assertEquals("Channel name lists this game at $time", channelReason(res, other(5, "NHL 13", MatchReason.EventName("Golden Knights vs. Oilers", now, false, false)), g, false))
        val assumed = other(2, "NHL GP 01", MatchReason.EventName("Golden Knights @ Oilers", now, false, zoneAssumed = true))
        assertEquals("Channel name lists this game", channelReason(res, assumed, g, false))
        assertEquals("From the channel name: Golden Knights @ Oilers", channelReason(res, assumed, g, false, spoken = true))
        assertEquals("Team channel: always shows Oilers games", channelReason(res, teamCand(Side.HOME), g, false))
        // (Owner ruling 2026-11-25) Another feed of the row above; spoken, the row keeps its own evidence.
        assertEquals("Same channel, another feed", channelReason(res, teamCand(Side.HOME).copy(anotherFeed = true), g, false))
        assertEquals("Team channel: always shows Oilers games", channelReason(res, teamCand(Side.HOME).copy(anotherFeed = true), g, false, spoken = true))
        assertEquals("TNT carries this game.", channelReason(res, other(3, "US: TNT", MatchReason.Broadcaster("TNT", Market.NATIONAL, null)), g, false))

        // "Also in <group>": a twin with the same name; the matcher put the twin's group in `group`.
        val twin = teamCand(Side.HOME).copy(variants = listOf(GuideFixtures.channel(5, name = "Edmonton Oilers")), group = "Sports | NHL Teams (2)")
        assertEquals("Also in Sports | NHL Teams (2)", thirdLine(res, twin, manyPlaylists = false, sharedName = false))
        val both = twin.copy(variants = twin.variants + GuideFixtures.channel(6, name = "Edmonton Oilers FHD"))
        assertEquals("Also: FHD · Also in Sports | NHL Teams (2)", thirdLine(res, both, false, false))
        // Two rows named "NHL 01" with different events: line 3 names each row's group.
        assertEquals("Sports | NHL (2)", thirdLine(res, event.copy(group = "Sports | NHL (2)"), false, sharedName = true))
        assertEquals("Main · Sports | NHL (2)", thirdLine(res, event.copy(group = "Sports | NHL (2)"), true, sharedName = true))
        assertEquals("", thirdLine(res, event.copy(group = "Sports | NHL"), false, sharedName = false))
    }

    // ------------------------------------------------------------------ composed

    /** U-E-B4: the grey chip is never focused, so the panel's own description carries the line. */
    @Test fun thePanelDescribesTheBeyondGuideLine() {
        val m = model(upcoming, MatchState.BeyondGuide(emptyList(), false))
        val withSlots = m.copy(slots = HubSlots.default(HubSlots.Ctx(upcoming, false, null, null, m.match, false, now)))
        rule.setContent { BoxTheme { HubPanel(withSlots, false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = false) } }
        rule.waitForIdle()
        val desc = rule.onNodeWithTag(HUB_PANEL_TAG).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        assertEquals(listOf("Your guide doesn't cover this game yet"), desc)
    }

    /**
     * Code review 2026-11-25, UI M3: on a TV accessibility focus follows the D-pad, and the grey chip never takes focus,
     * so the slot that takes focus on entering the panel says why Channels is grey; the grey slot says it for touch.
     */
    @Test fun theFocusedSlotSaysWhyChannelsIsGrey() {
        val m = model(upcoming, MatchState.BeyondGuide(emptyList(), false))
        val slots = HubSlots.default(HubSlots.Ctx(upcoming, false, null, null, m.match, false, now))
        val withSlots = m.copy(slots = slots)
        val first = slots.indexOfFirst { it.usable }
        val grey = slots.indexOfFirst { it.action == SlotAction.CHANNEL }
        assertTrue(first >= 0 && first != grey)
        rule.setContent { BoxTheme { HubPanel(withSlots, false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = true) } }
        rule.waitForIdle()
        fun descOf(i: Int) = rule.onNodeWithTag(hubSlotTag(i)).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.single()
        val line = "Your guide doesn't cover this game yet"
        assertEquals("${slotText(res, slots[first])}. Channels unavailable: $line", descOf(first))
        assertEquals("Channels, unavailable. $line", descOf(grey))
        for (i in slots.indices) if (i != first && i != grey && slots[i].action != null) assertFalse(descOf(i).contains(line))
    }

    /** U-E-N5: at Largest a long channel name ellipsizes and " · +8 more" stays whole. */
    @Test fun theTailIsNeverCutAtLargest() {
        UiTextScale.running = TextSize.LARGEST
        val long = other(1, "CA| SPORTSNET ONTARIO HD WITH A VERY LONG PROVIDER NAME", MatchReason.Guide("NHL Hockey", now, now + 1, false))
        val m = model(live, MatchState.Done(nine(long)))
        rule.setContent { BoxTheme { HubPanel(m, false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = false) } }
        rule.waitForIdle()
        val more = rule.onNodeWithTag(HUB_CHANNEL_MORE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val lineNode = rule.onNodeWithTag(HUB_CHANNEL_LINE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the tail sits inside the line", more.boundsInRoot.right <= lineNode.boundsInRoot.right + 0.5f)
        val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        more.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("the tail is never ellipsized", layouts.single().hasVisualOverflow)
        assertEquals(
            listOf("On TV: CA| SPORTSNET ONTARIO HD WITH A VERY LONG PROVIDER NAME · in your guide · +8 more"),
            lineNode.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() },
        )
    }
}
