package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.Demand
import app.thebox.core.sports.EspnGameDto
import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FieldPos
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NflLastPlay
import app.thebox.core.sports.NflPlay
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflStackedColours
import app.thebox.core.sports.NflYardsFixtures
import app.thebox.core.sports.NflYardsPlanner
import app.thebox.core.sports.YardsResult
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.ui.sports.StackedNflRunFixtures as F
import app.thebox.ui.theme.BoxDesignScale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * NFL Stacked integration (2026-11-15): **everything real**, end to end. The real `ScoreBugMachine` (WP-1) reads the
 * eight live 2026-11-15 fixtures (mapped by the real `EspnMapper`) and scripted snapshots on a clock in lockstep with the
 * compose test clock; its views go through `FakeScoreRepository.bug` (a plain flow) into the real `StackedNflBug` runner
 * (WP-4) with `StackedNflHooks` at their production defaults (`StackedNflTargets.of`, `StackedNflTimeline`,
 * `NflStackedColours`, `NflScoreSpec.STACKED`; WP-2) and the real painters (WP-3), drawn through the probe's `draw` into
 * bitmaps. The only test seam is the probe (observation) and `background = EmptyCoroutineContext` (bakes on the test
 * thread).
 *
 * Asserted on the rendered pixels and the runner's state: the play bar, a possession change, the flag (8 s, then back),
 * a confirmed timeout (burn + bar, held, released on the next play), the fallback timeout, both orders of a type 21 and
 * its count drop (exactly one burn, on the caller's side), the touchdown, the +7 pair, the field goal, the safety, the
 * end of a quarter, halftime, final, stale and delayed, a score during a flag; all four corners at both sizes; zero
 * allocations per draw and no frame asked for while idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedNflEndToEndTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedNflProbe()
    private var placement by mutableStateOf(F.input())

    // ------------------------------------------------------------------ the machine, in lockstep with the test clock

    private var now = 0L
    private var wall0 = 0L
    private lateinit var m: ScoreBugMachine

    private fun push() { repo.bug.value = m.view() }

    private fun follow(first: GameSnapshot, delaySec: Int = 0) {
        wall0 = first.startUtcMs + 30 * 60_000L
        m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))
        m.follow(SportsFixtures.followedOf(first))
        if (delaySec > 0) m.prefs(delaySec)
        m.demand(Demand.SEEN)
        m.fetched(Fetch.Ok(first, now, wall0 + now, 0))
        push()
    }

    private fun start(first: GameSnapshot, delaySec: Int = 0) {
        follow(first, delaySec)
        rule.mainClock.autoAdvance = false
        val hooks = StackedNflHooks(background = EmptyCoroutineContext, probe = probe)
        rule.setContent {
            BoxDesignScale { StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        rule.waitForIdle()
        advance(2_000)
    }

    /** [ms] of time on both clocks, in [step]-ms ticks (the machine steps and its view is pushed each tick). */
    private fun advance(ms: Long, step: Long = 50) {
        var left = ms
        while (left > 0) {
            val n = minOf(step, left)
            now += n
            m.step()
            push()
            rule.mainClock.advanceTimeBy(n)
            rule.waitForIdle()
            left -= n
        }
    }

    /**
     * A poll [gapMs] after the last one, received and shown at once (SEEN, no delay). With [yards] the snapshot first goes
     * through [sourced], the yards step as `EspnScoreSource` runs it.
     */
    private fun poll(s: GameSnapshot, gapMs: Long = 20_000, yards: Pair<Int, Int>? = null) {
        advance(gapMs, 250)
        m.fetched(Fetch.Ok(if (yards != null) sourced(s, yards) else s, now, wall0 + now, 0))
        push()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
    }

    private fun applyPlacement(p: app.thebox.core.sports.PlacementInput) {
        placement = p
        Snapshot.sendApplyNotifications()
        advance(1_000)
    }

    // ------------------------------------------------------------------ the yards step (NFL YDS §1.6, §1.7)

    /** One follow's real planner, as `EspnScoreSource` keeps it. */
    private val planner = NflYardsPlanner()

    /**
     * (NFL YDS §1.3) `EspnScoreSource.withYards` without the HTTP: the real [NflYardsPlanner] decides whether [s] is a
     * stoppage that is due, the two statistics calls answer [yards] (away, home), and the snapshot is returned with the
     * usable yards attached exactly as the source hands it to the machine (none outside a stoppage).
     */
    private fun sourced(s: GameSnapshot, yards: Pair<Int, Int>): GameSnapshot {
        planner.due(s, now)?.let { planner.fetched(it, now, YardsResult.Ok(yards.first, yards.second)) }
        return planner.attach(s, now)
    }

    // ------------------------------------------------------------------ scripted KC @ BUF snapshots

    private var seq = 100L

    private fun lp(type: String = "24", penalty: Boolean = false, side: Side? = Side.AWAY, id: Long? = null) =
        NflLastPlay((id ?: ++seq).let { "401899001$it" }, type, "t$type", side, 0, penalty)

    @Suppress("LongParameterList")
    private fun g(
        a: Int? = 3,
        h: Int? = 3,
        lastPlay: NflLastPlay? = lp(),
        period: Int = 1,
        secs: Int = 600,
        away: Int = 0,
        home: Int = 0,
        poss: Side = Side.AWAY,
        phase: GamePhase = GamePhase.LIVE,
        breakKind: NflBreak? = null,
        play: NflPlay = NflPlay.Down(1, 10, goalToGo = false, field = FieldPos(poss, 35)),
        finalKind: PeriodKind? = null,
    ): GameSnapshot = SportsFixtures.nflGame(
        phase = phase,
        away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", away),
        home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", home),
        period = period,
        periodKind = if (period >= 5) PeriodKind.OT else PeriodKind.REG,
        otNumber = if (period >= 5) period - 4 else null,
        clock = GameClock(secs, running = false, inIntermission = phase == GamePhase.INTERMISSION),
        finalKind = finalKind,
        detail = SportsFixtures.nflDetail(play, poss, false, breakKind, lastPlay, a, h),
    )

    // ------------------------------------------------------------------ the live fixtures

    private val json = Json { ignoreUnknownKeys = true }
    private val larAtAri = GameId(League.NFL, "401873061")
    private val seaAtLv = GameId(League.NFL, "401873062")

    /** A 2026-11-15 fixture (core's test resources) mapped by the real `EspnMapper.game`. */
    private fun fx(name: String, id: GameId = larAtAri): GameSnapshot = mapped("$name-2026-11-15", id)

    /** (NFL YDS) A 2026-11-16 live2 board (`NflYardsFixtures`, LAC @ BAL) mapped by the real `EspnMapper.game`. */
    private fun fy(name: String): GameSnapshot = mapped(name, NflYardsFixtures.LAC_AT_BAL)

    private fun mapped(file: String, id: GameId): GameSnapshot {
        val f = listOf(File("../core/src/test/resources/sports/nfl/$file.json"), File("core/src/test/resources/sports/nfl/$file.json"))
            .first { it.isFile }
        val dto = json.decodeFromString(EspnGameDto.serializer(), f.readText())
        return requireNotNull(EspnMapper.game(dto, id, null, "https://a.espncdn.com/")) { file }
    }

    // ------------------------------------------------------------------ observation

    private fun args() = probe.args!!()
    private fun f() = args().frame!!
    private fun barKey() = probe.barKey!!()
    private fun painter() = probe.painter!!()
    private fun words(): String = painter().barText(probe.barContent!!())
    private fun moves() = probe.transitions.filter { it.kind != StackedNflTransition.STILL }
    private fun movesOf(track: StackedNflTrack) = moves().filter { it.track == track }
    private fun burns(side: Side) = movesOf(if (side == Side.AWAY) StackedNflTrack.PIPS_AWAY else StackedNflTrack.PIPS_HOME)
        .filter { it.kind == StackedNflTransition.BURN }
    private fun on() = probe.takeoverOn!!.value
    private fun t() = probe.driver!!.value
    private fun live() = rule.onNodeWithTag(SCORE_BUG_LIVE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun advanceTakeoverTo(ms: Float) {
        var guard = 0
        while (t() < ms) {
            now += 16; m.step(); push()
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            check(++guard < 2_000) { "the takeover driver stopped at ${t()}" }
        }
    }

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float, val k: Float)

    /** The bug's whole draw (the real painters, through the runner's own draw) into a footprint + 2 M bitmap. */
    private fun render(): Shot {
        val large = placement.size == ScoreBugSize.LARGE
        val k = if (large) K.SCORE_LARGE_SCALE else 1f
        val mg = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val bmp = Bitmap.createBitmap(Math.round(K.STACKED_NFL_W_MAX * k + 2 * mg), Math.round((K.STACKED_H + K.STACKED_BAR_H) * k + 2 * mg), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(20, 22, 28))
        val a = args()
        val ox = mg + if (a.mirrored) K.STACKED_CLOCK_W * k else 0f
        val oy = mg + if (a.up) K.STACKED_BAR_H * k else 0f
        c.translate(ox, oy)
        probe.draw!!(c)
        return Shot(bmp, ox, oy, k)
    }

    private fun save(name: String, s: Shot) {
        runCatching {
            val dir = File(StackedNflTestKit.SHEETS, "e2e").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { s.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The pixel at the bar's far end from its words (unmirrored the right end, mirrored the left), mid-height. */
    private fun barFarEnd(s: Shot): Int {
        val a = args()
        val w = f().boxW * s.k
        val l = if (a.mirrored) K.STACKED_ROWS_W * s.k - w else 0f
        val x = if (a.mirrored) l + 3f else l + w - 3f
        val y = if (a.up) -K.STACKED_BAR_H * s.k / 2f else K.STACKED_H * s.k + K.STACKED_BAR_H * s.k / 2f
        return s.bmp.getPixel((x + s.ox).toInt(), (y + s.oy).toInt())
    }

    /** The pixel in the middle of where the open clock column is (left of the rows mirrored, right of them otherwise). */
    private fun clockColumnPixel(s: Shot): Int {
        val a = args()
        val x = if (a.mirrored) -K.STACKED_CLOCK_W * s.k / 2f else (K.STACKED_ROWS_W + K.STACKED_CLOCK_W / 2f) * s.k
        // Between the period and the clock texts: on the rule's row is too thin, so take the slot's left quarter.
        val y = (K.STACKED_NFL_PERIOD_SLOT_H + 3f) * s.k
        return s.bmp.getPixel((x + s.ox + 30f * s.k).toInt(), (y + s.oy).toInt())
    }

    private fun near(a: Int, b: Int, tol: Int = 3) = StackedNflTestKit.near(a, b, tol)

    private fun hex(c: Int) = Integer.toHexString(c)

    /** Thread-allocated bytes over 300 of the runner's whole draws (args fill, sampling, the painters), after warm-ups. */
    private fun drawAllocations(): Long {
        val s = render()
        val c = Canvas(s.bmp)
        val draw = probe.draw!!
        repeat(900) { draw(c) } // the JIT warms up (as the painter test's 3 × 300)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        var least = Long.MAX_VALUE
        repeat(2) {
            val before = bean.getThreadAllocatedBytes(id)
            repeat(300) { draw(c) }
            least = minOf(least, bean.getThreadAllocatedBytes(id) - before)
        }
        return least
    }

    /** Snapshot state changes over [ms] with no new snapshot (0 = no frame asked for). */
    private fun appliesOver(ms: Long): Int {
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            advance(ms, 500)
        } finally {
            handle.dispose()
        }
        return applies
    }

    private val kc get() = NflStackedColours.pair("KC", "BUF").away
    private val buf get() = NflStackedColours.pair("KC", "BUF").home

    // ------------------------------------------------------------------ the scripted game

    @Test
    fun aScriptedGameThroughTheRealMachineRunnerAndPainters() {
        start(g(lastPlay = lp(id = 1)))
        // The play bar, at rest from the first view (rule 2: no animation in on follow).
        assertTrue(moves().isEmpty())
        assertEquals("1ST & 10 | KC 35", words())
        assertTrue("top right: mirrored", args().mirrored)
        var shot = render().also { save("01-play", it) }
        assertTrue("the play bar in KC's bar colour: ${hex(barFarEnd(shot))}", near(kc.barBg, barFarEnd(shot)))
        assertEquals(1f, f().ballAwayAlpha, 1e-4f)
        assertEquals("pips 3 / 3", listOf(1f, 1f, 1f, 1f, 1f, 1f), f().pipAway.toList() + f().pipHome.toList())
        assertTrue("the open clock column: ${hex(clockColumnPixel(shot))}", near(K.STACKED_COL_BG, clockColumnPixel(shot), 8))
        assertEquals("idle: no frame asked for", 0, appliesOver(10_000))
        val idleBytes = drawAllocations()
        assertTrue("idle draw: $idleBytes B per 300", idleBytes < LIMIT)

        // A possession change: the ball and the wide stripe move, the bar re-keys to BUF's colours.
        poll(g(poss = Side.HOME))
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.BALL_AWAY).last().kind)
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.BALL_HOME).last().kind)
        assertEquals(StackedNflTransition.REKEY, movesOf(StackedNflTrack.BAR).last().kind)
        advance(200)
        val rekeyBytes = drawAllocations()
        assertTrue("mid-REKEY draw: $rekeyBytes B per 300", rekeyBytes < LIMIT)
        advance(1_000)
        shot = render().also { save("02-possession", it) }
        assertTrue("BUF's bar: ${hex(barFarEnd(shot))}", near(buf.barBg, barFarEnd(shot)))
        assertEquals("1ST & 10 | BUF 35", words())
        assertEquals(1f, f().ballHomeAlpha, 1e-4f)
        assertEquals(0f, f().ballAwayAlpha, 1e-4f)

        // The flag: 8 s on the yellow bar with the shake and wave, then back to the play.
        poll(g(poss = Side.HOME, lastPlay = lp("8", penalty = true)))
        assertEquals(NflBarKind.FLAG, barKey()!!.kind)
        assertEquals("FLAG ON THE PLAY", words())
        assertFalse("animated", probe.flags.last().still)
        assertEquals("Flag on the play.", live())
        advance(300)
        assertTrue("the wave runs", f().waveDeg != 0f)
        val flagBytes = drawAllocations()
        assertTrue("flag draw: $flagBytes B per 300", flagBytes < LIMIT)
        shot = render().also { save("03-flag", it) }
        assertTrue("the yellow flag bar: ${hex(barFarEnd(shot))}", near(K.STACKED_NFL_FLAG_BG, barFarEnd(shot)))
        advance(K.NFL_FLAG_MARK_MS)
        assertEquals("back to the play after 8 s", NflBarKind.PLAY, barKey()!!.kind)
        advance(1_000)
        assertEquals(0f, f().waveDeg, 0f)
        assertEquals("idle after the flag hold", 0, appliesOver(10_000))

        // A confirmed timeout: the 21 and BUF's drop in one poll → the burn on BUF's side and the caller's bar, held while
        // the 21 is the lastPlay, released by the next play.
        val to21 = lp("21", side = Side.HOME)
        poll(g(h = 2, poss = Side.HOME, lastPlay = to21))
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 2), barKey())
        assertEquals("TIMEOUT BUF | 2 LEFT", words())
        assertEquals(1, burns(Side.HOME).size)
        assertEquals("the pip at index 2 burns", 3 to 2, burns(Side.HOME).single().let { it.fromLit to it.toLit })
        assertTrue(burns(Side.AWAY).isEmpty())
        assertEquals("Timeout, Bills.", live())
        advance(200)
        shot = render().also { save("04-timeout-burn", it) }
        assertTrue("the burnt pip flashes white", f().pipHomeBurn[2] > 0f)
        assertTrue("the caller's bar: ${hex(barFarEnd(shot))}", near(buf.barBg, barFarEnd(shot)))
        val barMoves = movesOf(StackedNflTrack.BAR).size
        poll(g(h = 2, poss = Side.HOME, lastPlay = to21)) // the held poll
        assertEquals("held: no REKEY", barMoves, movesOf(StackedNflTrack.BAR).size)
        assertEquals("held: no second burn", 1, burns(Side.HOME).size)
        assertEquals(NflBarKind.TIMEOUT, barKey()!!.kind)
        assertEquals(listOf(1f, 1f, 0f), f().pipHome.toList())
        poll(g(h = 2, poss = Side.HOME))
        assertEquals("released on the next play", NflBarKind.PLAY, barKey()!!.kind)
        assertEquals(1, burns(Side.HOME).size)

        // The fallback (a drop of 1 with no 21, a lost challenge): 8 s, KC's side, not announced.
        poll(g(a = 2, h = 2, poss = Side.HOME))
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.AWAY, left = 2), barKey())
        assertEquals(1, burns(Side.AWAY).size)
        assertEquals("", live())
        advance(K.NFL_TIMEOUT_FALLBACK_MS + 500)
        assertEquals(NflBarKind.PLAY, barKey()!!.kind)

        // The two orders (WP-4 assumption 11): exactly one burn, on the caller's side.
        // (a) the 21 one poll before BUF's count drops.
        val pending = lp("21", side = Side.HOME)
        poll(g(a = 2, h = 2, poss = Side.HOME, lastPlay = pending))
        assertEquals("no mark yet", NflBarKind.PLAY, barKey()!!.kind)
        assertEquals(1, burns(Side.HOME).size)
        poll(g(a = 2, h = 1, poss = Side.HOME, lastPlay = pending))
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1), barKey())
        assertEquals("one burn for the drop", 2, burns(Side.HOME).size)
        assertEquals(1, burns(Side.AWAY).size)
        poll(g(a = 2, h = 1, poss = Side.HOME))
        // (b) KC's count drops one poll (5 s) before its 21.
        poll(g(a = 1, h = 1, poss = Side.HOME))
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.AWAY, left = 1), barKey())
        assertEquals(2, burns(Side.AWAY).size)
        val movesB = movesOf(StackedNflTrack.BAR).size
        poll(g(a = 1, h = 1, poss = Side.HOME, lastPlay = lp("21", side = Side.AWAY)), gapMs = 5_000)
        assertEquals("the fallback upgraded in place", NflBarKey(NflBarKind.TIMEOUT, Side.AWAY, left = 1), barKey())
        assertEquals("no REKEY: the same key", movesB, movesOf(StackedNflTrack.BAR).size)
        assertEquals("still one burn for that drop", 2, burns(Side.AWAY).size)
        assertEquals(2, burns(Side.HOME).size)
        poll(g(a = 1, h = 1, poss = Side.HOME))

        // A touchdown (+6): the takeover, the score label at 216, the release at 3 600, done at 4 800.
        poll(g(a = 1, h = 1, away = 6, poss = Side.AWAY))
        assertTrue(on())
        advanceTakeoverTo(1_000f)
        val takeoverBytes = drawAllocations()
        assertTrue("takeover draw: $takeoverBytes B per 300", takeoverBytes < LIMIT)
        shot = render().also { save("05-touchdown-t1000", it) }
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6), barKey())
        assertEquals("TOUCHDOWN | KC +6", words())
        assertTrue("the takeover is handed to the painter", args().takeover != null && args().takeoverOn)
        val topMid = shot.bmp.getPixel((shot.ox + (K.STACKED_ROWS_W - f().boxW / 2f) * shot.k).toInt(), (shot.oy + 3f).toInt())
        assertFalse("the panel covers the rows: ${hex(topMid)}", near(K.STACKED_ROW_BG, topMid, 6))
        val edge = shot.bmp.getPixel((shot.ox + (K.STACKED_ROWS_W - f().boxW / 2f) * shot.k).toInt(), shot.oy.toInt())
        assertTrue("the border is on top of the takeover: ${hex(edge)}", near(K.STACKED_BORDER, edge, 40))
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 200f)
        assertTrue("takeoverOn holds for the pop and the row flash", args().takeoverOn && args().score!!.popOn)
        assertTrue(args().score!!.rowFlashAlpha > 0f)
        save("06-touchdown-pop", render())
        advanceTakeoverTo(4_700f)
        advance(300)
        assertFalse(on())
        assertNull(args().takeover)

        // The +7 pair: TD, "EXTRA POINT" between, the XP from 4 800, released at 8 400, done at 9 600.
        poll(g(a = 1, h = 1, away = 13, poss = Side.AWAY))
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 100f)
        assertEquals(NflBarKey(NflBarKind.PAT, Side.AWAY), barKey())
        assertEquals("EXTRA POINT", words())
        advanceTakeoverTo(5_100f)
        assertEquals(listOf(0, 1), probe.begins.takeLast(2).map { it.segment })
        assertEquals("EXTRA POINT | KC +1", words())
        save("07-pair-xp", render())
        advanceTakeoverTo(9_500f)
        advance(500)
        assertFalse(on())

        // A field goal (BUF +3) and a safety (KC +2, no try pending).
        poll(g(a = 1, h = 1, away = 13, home = 3, poss = Side.HOME))
        advanceTakeoverTo(400f)
        assertEquals("FIELD GOAL | BUF +3", words())
        save("08-field-goal", render())
        advanceTakeoverTo(4_700f); advance(500)
        poll(g(a = 1, h = 1, away = 15, home = 3, poss = Side.HOME))
        advanceTakeoverTo(400f)
        assertEquals("SAFETY | KC +2", words())
        advanceTakeoverTo(4_700f); advance(500)

        // A score during a flag: the takeover wins; the flag that ended in the pin never shows.
        poll(g(a = 1, h = 1, away = 15, home = 3, poss = Side.AWAY, lastPlay = lp("8", penalty = true)))
        assertEquals(NflBarKind.FLAG, barKey()!!.kind)
        val flags = probe.flags.size
        poll(g(a = 1, h = 1, away = 21, home = 3, poss = Side.AWAY), gapMs = 3_000)
        advanceTakeoverTo(400f)
        assertEquals(NflBarKind.SCORE, barKey()!!.kind)
        advanceTakeoverTo(4_700f); advance(500)
        assertEquals("the flag is gone after the release: the try's bar (a +6 leaves the try pending)", NflBarKind.PAT, barKey()!!.kind)
        assertEquals(flags, probe.flags.size)

        // The end of the quarter: the clock column folds to the 243 box, a neutral "END OF 1ST" bar, no ball.
        poll(g(a = 1, h = 1, away = 21, home = 3, phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD, secs = 0, lastPlay = lp("2")))
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.CLOCK).last().kind)
        advance(1_000)
        assertEquals("END OF 1ST", words())
        assertEquals(K.STACKED_ROWS_W, f().boxW, 1e-3f)
        assertEquals(0f, f().ballAwayAlpha, 1e-4f)
        shot = render().also { save("09-end-of-1st", it) }
        assertTrue("the neutral bar: ${hex(barFarEnd(shot))}", near(K.STACKED_BAR_NEUTRAL, barFarEnd(shot)))
        assertFalse("the column is folded away", near(K.STACKED_COL_BG, clockColumnPixel(shot), 2))

        // Halftime (period 2): the fold again after the second quarter's play.
        poll(g(a = 3, h = 3, away = 21, home = 3, period = 2, poss = Side.HOME))
        advance(1_000)
        assertEquals(K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        poll(g(a = 3, h = 3, away = 21, home = 3, period = 2, phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME, secs = 0, lastPlay = lp("65")))
        advance(1_000)
        assertEquals("HALFTIME", words())
        assertEquals(K.STACKED_ROWS_W, f().boxW, 1e-3f)
        save("10-halftime", render())

        // Stale in play: no poll for 61 s → "No update" in the clock slot, the bar closes; fresh again → it opens.
        poll(g(a = 3, h = 3, away = 21, home = 3, period = 3, poss = Side.AWAY))
        advance(1_000)
        advance(61_000, 500)
        assertEquals(CentreText.NoUpdate, repo.bug.value!!.top)
        assertNull("stale in play: the bar closes", barKey())
        advance(1_000)
        assertTrue(f().barClosed)
        assertEquals(K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        save("11-stale", render())
        poll(g(a = 3, h = 3, away = 21, home = 3, period = 3, poss = Side.AWAY), gapMs = 500)
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.BAR).last().kind)
        advance(1_000)
        assertEquals(K.STACKED_BAR_H, f().barH, 1e-3f)

        poll(g(a = 3, h = 3, away = 21, home = 3, period = 4, poss = Side.AWAY))
        advance(1_000)

        // Final.
        poll(g(a = 3, h = 3, away = 21, home = 3, period = 4, phase = GamePhase.FINAL, finalKind = PeriodKind.REG, secs = 0, lastPlay = lp("66")))
        advance(1_000)
        assertEquals("FINAL", words())
        assertEquals(K.STACKED_ROWS_W, f().boxW, 1e-3f)
        save("13-final", render())
    }

    /**
     * Review fix C-S1, through the real machine, runner and painters: a field goal on a dead-ball body. At the release
     * the bar re-keys to the held bar (§3.4 "the held bar": BUF's 4th & 3, no CLOSE), holds it through the re-sent
     * dead body, and re-keys to "BUF KICKOFF" on the kick-off body: never closed in between.
     */
    @Test
    fun aFieldGoalOnADeadBallHoldsTheBarUntilTheKickoff() {
        val fourth = NflPlay.Down(4, 3, goalToGo = false, field = FieldPos(Side.AWAY, 20))
        start(g(poss = Side.HOME, play = fourth, secs = 400, lastPlay = lp(id = 1)))
        assertEquals("4TH & 3 | KC 20", words())
        val held = barKey()
        val dead = g(poss = Side.HOME, home = 3, secs = 396, play = NflPlay.Dead(Side.HOME, 3), lastPlay = lp("59", side = Side.HOME))
        poll(dead)
        assertTrue(on())
        advanceTakeoverTo(400f)
        assertEquals("FIELD GOAL | BUF +3", words())
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 100f)
        assertEquals("released to the held bar", held, barKey())
        assertEquals("4TH & 3 | KC 20", words())
        advanceTakeoverTo(4_700f); advance(500)
        assertFalse(on())
        poll(dead)
        assertEquals("the re-sent dead body keeps it", held, barKey())
        assertFalse(f().barClosed)
        poll(g(poss = Side.HOME, home = 3, secs = 396, play = NflPlay.Kickoff(Side.HOME, FieldPos(Side.HOME, 35)), lastPlay = lp("74", side = Side.HOME)))
        assertEquals(NflBarKind.KICKOFF, barKey()!!.kind)
        assertTrue("never closed between the score and the kick-off", movesOf(StackedNflTrack.BAR).none { it.kind == StackedNflTransition.CLOSE })
    }

    /** Delayed (a 30-s delay, nothing old enough yet): "Delayed" in the clock slot, the bar closed; then fresh, at rest. */
    @Test
    fun delayedInPlayClosesTheBarThenTheFirstFreshViewSnapsOpen() {
        start(g(lastPlay = lp(id = 1)), delaySec = 30)
        assertEquals(CentreText.Delayed, repo.bug.value!!.top)
        assertNull("waiting in play: no bar", barKey())
        assertTrue(f().barClosed)
        assertEquals(K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        assertTrue("the column is dimmed", args().state!!.columnDim)
        save("12-delayed", render())
        advance(30_000, 250)
        assertEquals(CentreText.Period(app.thebox.core.sports.PeriodLabel(PeriodKind.REG, 1)), repo.bug.value!!.top)
        assertEquals("1ST & 10 | KC 35", words())
        assertEquals("the first fresh view snaps (rule 2)", K.STACKED_BAR_H, f().barH, 1e-3f)
        assertTrue("nothing animated in", moves().none { it.track == StackedNflTrack.BAR })
    }

    // ------------------------------------------------------------------ the eight live fixtures

    @Test
    fun theLiveFixturesThroughTheRealPipeline() {
        start(fx("scoreboard-live-clock-lag-a"))
        val first = words()
        poll(fx("scoreboard-live-clock-lag-b"))
        assertEquals("the clock-lag pair marks nothing", null, probe.flags.firstOrNull())
        assertTrue(first.isNotEmpty())
        poll(fx("scoreboard-live-penalty-type8"))
        assertEquals("FLAG ON THE PLAY", words())
        save("fx-01-penalty", render())
        advance(K.NFL_FLAG_MARK_MS + 500)
        assertTrue("the flag ends after 8 s", barKey()?.kind != NflBarKind.FLAG)
        poll(fx("scoreboard-live-team-timeout-type21"))
        assertEquals("TIMEOUT LAR | 2 LEFT", words())
        assertEquals("one burn on LAR's (away) side", 1, burns(Side.AWAY).size)
        assertTrue(burns(Side.HOME).isEmpty())
        save("fx-02-timeout", render())
        val moves = movesOf(StackedNflTrack.BAR).size
        poll(fx("scoreboard-live-team-timeout-held"), gapMs = 30_000)
        assertEquals("held across the poll", "TIMEOUT LAR | 2 LEFT", words())
        assertEquals(moves, movesOf(StackedNflTrack.BAR).size)
        assertEquals(1, burns(Side.AWAY).size)
        poll(fx("scoreboard-live-after-timeout"), gapMs = 5_000)
        assertEquals("the next snap ends it", NflBarKind.PLAY, barKey()!!.kind)
        assertEquals(1, burns(Side.AWAY).size)
        save("fx-03-after-timeout", render())
    }

    @Test
    fun theKickoffFixturesNeverReadRedZone() {
        start(fx("scoreboard-live-official-timeout-kickoff-redzone", seaAtLv))
        assertEquals(NflBarKind.KICKOFF, barKey()!!.kind)
        assertEquals("LV KICKOFF | LV 35", words())
        assertFalse("RC9", probe.barContent!!().redZone)
        save("fx-04-kickoff", render())
        poll(fx("scoreboard-live-kickoff-return-no-possession", seaAtLv))
        assertEquals(NflBarKind.PLAY, barKey()!!.kind)
        assertTrue(words(), words().startsWith("1ST & 10"))
        save("fx-05-return", render())
    }

    // ------------------------------------------------------------------ every corner, both sizes

    @Test
    fun allFourCornersAtBothSizes() {
        start(g(h = 2, lastPlay = lp(id = 1)))
        for (size in ScoreBugSize.entries) for (corner in ScoreCorner.entries) {
            applyPlacement(F.input(corner, size))
            val a = args()
            val right = corner == ScoreCorner.TOP_RIGHT || corner == ScoreCorner.BOTTOM_RIGHT
            val bottom = corner == ScoreCorner.BOTTOM_LEFT || corner == ScoreCorner.BOTTOM_RIGHT
            assertEquals("$corner $size mirrored", right, a.mirrored)
            assertEquals("$corner $size bar up", bottom, a.up)
            val shot = render().also { save("corner-${corner.name.lowercase()}-${size.name.lowercase()}", it) }
            assertTrue("$corner $size: KC's bar ${hex(barFarEnd(shot))}", near(kc.barBg, barFarEnd(shot)))
            assertTrue("$corner $size: the clock column", near(K.STACKED_COL_BG, clockColumnPixel(shot), 8))
            // From the screen edge inward: the stripe first (KC's stripe on the away row's outer edge).
            val k = shot.k
            val stripeX = if (a.mirrored) (K.STACKED_ROWS_W - 3f) * k else 3f * k
            val stripe = shot.bmp.getPixel((shot.ox + stripeX).toInt(), (shot.oy + K.STACKED_H * k / 4f).toInt())
            assertTrue("$corner $size: the stripe on the screen edge ${hex(stripe)}", near(kc.stripe, stripe, 12))
        }
        // A takeover in each corner (the right side mirrored), Standard.
        for (corner in ScoreCorner.entries) {
            applyPlacement(F.input(corner, ScoreBugSize.STANDARD))
            poll(g(h = 2, away = 6 * (corner.ordinal + 1)), gapMs = 1_000)
            advanceTakeoverTo(1_000f)
            save("corner-${corner.name.lowercase()}-touchdown-t1000", render())
            assertTrue(args().takeoverOn)
            advanceTakeoverTo(4_700f); advance(500)
        }
    }

    // ------------------------------------------------------------------ NFL YDS (docs/design/m6-nfl-stacked-yds.md §4.3)

    /**
     * (NFL YDS §4.3) The live type-21 fixture through the yards step ([sourced]: the real planner attaches both teams'
     * yards to its snapshot), the real machine (its `NflView` copies them), the runner and the painters: the 396 box with
     * the digits drawn between the rows and the clock (mirrored, top right); the next play (no stoppage, so the step
     * attaches nothing) closes it to 333 by 650 ms. Its layer is sized from `STACKED_NFL_FOOTPRINT_W` (N2; [render] stays
     * the v1 one).
     */
    @Test
    fun aTeamTimeoutWithYardsOpensThe396BoxAndTheNextPlayClosesIt() {
        val yards = 142 to -2
        start(fx("scoreboard-live-clock-lag-a"))
        assertEquals(K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        poll(fx("scoreboard-live-team-timeout-type21"), yards = yards)
        assertEquals("the machine's view carries the snapshot's yards", 142, repo.bug.value!!.nfl!!.awayYards)
        assertEquals("TIMEOUT LAR | 2 LEFT", words())
        assertTrue("the YDS track opened", movesOf(StackedNflTrack.YDS).any { it.kind == StackedNflTransition.OPEN })
        advance(700)
        assertEquals("rows + YDS + clock", K.STACKED_NFL_FOOTPRINT_W, f().boxW, 1e-3f)
        assertEquals("142" to "−2", args().state!!.awayYds to args().state!!.homeYds)
        val shot = renderFootprint()
        save("yds-timeout-type21", shot)
        assertTrue("mirrored", args().mirrored)
        // Mirrored: the column is just left of the rows (x −63..0), the digits white on the box fill.
        fun bright(x0: Float, x1: Float, y0: Float, y1: Float): Int {
            var n = 0
            for (y in (shot.oy + y0 * shot.k).toInt() until (shot.oy + y1 * shot.k).toInt()) for (x in (shot.ox + x0 * shot.k).toInt() until (shot.ox + x1 * shot.k).toInt()) {
                val p = shot.bmp.getPixel(x, y)
                if (Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200) n++
            }
            return n
        }
        assertTrue("the away row's digits", bright(-59f, -4f, 14f, 40f) > 20)
        assertTrue("the home row's digits", bright(-59f, -4f, 68f, 94f) > 20)
        assertTrue("the clock after the column", bright(-149f, -67f, K.STACKED_NFL_PERIOD_SLOT_H + 4f, K.STACKED_H - 4f) > 20)
        assertTrue(rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            .contains("Total yards"))
        poll(fx("scoreboard-live-after-timeout"), gapMs = 5_000, yards = yards)
        assertNull("the next play: the step attaches nothing", repo.bug.value!!.nfl!!.awayYards)
        assertEquals(NflBarKind.PLAY, barKey()!!.kind)
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.YDS).last().kind)
        advance(650)
        assertEquals("closed by 650 ms", K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        save("yds-after-timeout", renderFootprint())
    }

    /**
     * (NFL YDS §1.12, S1) The live2 change of ends (LAC @ BAL, 2026-11-16) through the real mapper, the yards step, the
     * machine, the runner and the painters: the official timeout (74) opens the column beside its unchanged play bar
     * (396); `STATUS_END_PERIOD` (which also carries LAC's later points: the takeover pins 396 first) folds the clock to the
     * END OF 1ST bar with the column (306); the change-of-ends body (P2
     * 15:00, `lastPlay` type 2) is the same `"E1"` stoppage, so the bar, the column and its digits stay with no move and no
     * second fetch; the first Q2 snap (type 5, the clock still 15:00) closes the column and opens the clock (333).
     */
    @Test
    fun theChangeOfEndsKeepsEndOf1stAndTheColumnUntilTheFirstPlay() {
        start(sourced(fy(NflYardsFixtures.OFFICIAL_TIMEOUT), 70 to -2))
        assertEquals(NflBarKind.PLAY, barKey()!!.kind)
        assertEquals("rows + YDS + clock", K.STACKED_NFL_FOOTPRINT_W, f().boxW, 1e-3f)
        assertEquals("70" to "−2", args().state!!.awayYds to args().state!!.homeYds)

        poll(fy(NflYardsFixtures.END_PERIOD), gapMs = 10_000, yards = 75 to -2)
        assertTrue("the machine's view: ${repo.bug.value!!.top}", repo.bug.value!!.top is CentreText.EndOf)
        assertEquals(75, repo.bug.value!!.nfl!!.awayYards)
        // LAC scored between the two live2 samples (0–10 at the end of the 1st): the takeover pins the pre-score layout
        // (396, the timeout's digits), and the break with its column comes at the release (§3.6).
        advance(1_000)
        assertTrue("the score's takeover", args().takeoverOn)
        assertEquals("pinned", K.STACKED_NFL_FOOTPRINT_W, f().boxW, 1e-3f)
        advance(6_000)
        assertFalse(args().takeoverOn)
        assertEquals(NflBarKind.END_PERIOD, barKey()!!.kind)
        assertEquals("END OF 1ST", words())
        assertEquals("rows + YDS", K.STACKED_ROWS_W + K.STACKED_NFL_YDS_W, f().boxW, 1e-3f)
        assertEquals("75" to "−2", args().state!!.awayYds to args().state!!.homeYds)
        save("yds-end-1st", renderFootprint())
        val ydsMoves = movesOf(StackedNflTrack.YDS).size
        val barMoves = movesOf(StackedNflTrack.BAR).size
        val clockMoves = movesOf(StackedNflTrack.CLOCK).size

        for (gap in listOf(10_000L, 10_000L)) {
            // The change-of-ends body, twice (20 s into the stoppage: the refresh is not due yet); the step answers other
            // digits, which must not be read: same key, no fetch.
            poll(fy(NflYardsFixtures.CHANGE_OF_ENDS), gapMs = gap, yards = 99 to 99)
            assertTrue("mapped to the end of the 1st (S1)", repo.bug.value!!.top is CentreText.EndOf)
            assertEquals(75, repo.bug.value!!.nfl!!.awayYards)
            assertEquals(NflBarKind.END_PERIOD, barKey()!!.kind)
            assertEquals("END OF 1ST", words())
            advance(1_000)
            assertEquals(K.STACKED_ROWS_W + K.STACKED_NFL_YDS_W, f().boxW, 1e-3f)
            assertEquals("75" to "−2", args().state!!.awayYds to args().state!!.homeYds)
            assertEquals("no YDS move", ydsMoves, movesOf(StackedNflTrack.YDS).size)
            assertEquals("no bar move", barMoves, movesOf(StackedNflTrack.BAR).size)
            assertEquals("no clock move", clockMoves, movesOf(StackedNflTrack.CLOCK).size)
        }
        save("yds-change-of-ends", renderFootprint())

        poll(fy(NflYardsFixtures.FIRST_PLAY_AFTER_CHANGE_OF_ENDS), gapMs = 10_000, yards = 80 to 1)
        assertNull("in play: no yards", repo.bug.value!!.nfl!!.awayYards)
        advance(1_000)
        assertEquals(NflBarKind.PLAY, barKey()!!.kind)
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.YDS).last().kind)
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.CLOCK).last().kind)
        assertEquals("rows + clock", K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        save("yds-first-play-after-change-of-ends", renderFootprint())
    }

    /** As [render], into a layer sized from the YDS footprint (`STACKED_NFL_FOOTPRINT_W` + 2 M). */
    private fun renderFootprint(): Shot {
        val large = placement.size == ScoreBugSize.LARGE
        val k = if (large) K.SCORE_LARGE_SCALE else 1f
        val mg = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val bmp = Bitmap.createBitmap(Math.round(K.STACKED_NFL_FOOTPRINT_W * k + 2 * mg), Math.round((K.STACKED_H + K.STACKED_BAR_H) * k + 2 * mg), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(20, 22, 28))
        val a = args()
        val ox = mg + if (a.mirrored) (K.STACKED_NFL_FOOTPRINT_W - K.STACKED_ROWS_W) * k else 0f
        val oy = mg + if (a.up) K.STACKED_BAR_H * k else 0f
        c.translate(ox, oy)
        probe.draw!!(c)
        return Shot(bmp, ox, oy, k)
    }

    private companion object {
        /** The draw's bound for 300 frames (GA §8.3), best effort under Robolectric. */
        const val LIMIT = 4_096L
    }
}
