package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked.md §4.3, §8.1 (NFL Stacked commit 0): [NflScoreSpec.PILL] is bit-for-bit the pre-spec
 * timeline ([LegacyNflScoreTimeline], frozen at `412446e`) on every [NflScoreFrame] field, through the old overloads and
 * the spec ones, on a 0.25-ms grid from −1 to 9 700 plus NaN and +∞, for every kind, single and pair, animated and still.
 * PILL writes `rowFlashAlpha = 0` whatever the frame held. The STACKED differences are `NflScoreSpecTest`'s (WP-2).
 */
class NflScoreSpecLegacyTest {
    /** −1 .. 9 700 in 0.25-ms steps, then NaN and +∞ (38 806 instants). */
    private val instants: FloatArray = run {
        val n = ((9_700f - -1f) / 0.25f).toInt() + 1
        FloatArray(n + 2) { i -> if (i < n) -1f + i * 0.25f else if (i == n) Float.NaN else Float.POSITIVE_INFINITY }
    }

    private val kinds = NflScoreKind.entries
    /** The §5.7 pairs the machine emits (TOUCHDOWN then EXTRA POINT or TWO-POINT), plus every other second kind. */
    private val pairs = kinds.map { NflScoreKind.TOUCHDOWN to it }

    /** Every pre-spec field of [a] and [b] equal bitwise (floats by `toBits`), and [b]'s `rowFlashAlpha` 0. */
    private fun assertSameBits(a: NflScoreFrame, b: NflScoreFrame, what: () -> String) {
        fun f(name: String, x: Float, y: Float, i: Int = -1) {
            if (x.toBits() != y.toBits()) assertEquals(x.toBits(), y.toBits(), "${what()}: $name${if (i >= 0) "[$i]" else ""} legacy $x vs new $y")
        }
        fun z(name: String, x: Boolean, y: Boolean) { if (x != y) assertEquals(x, y, "${what()}: $name") }
        f("leadFrom", a.leadFrom, b.leadFrom); f("leadTo", a.leadTo, b.leadTo)
        f("panelFrom", a.panelFrom, b.panelFrom); f("panelTo", a.panelTo, b.panelTo)
        f("glowLowAlpha", a.glowLowAlpha, b.glowLowAlpha); f("glowPeakAlpha", a.glowPeakAlpha, b.glowPeakAlpha)
        z("streak1On", a.streak1On, b.streak1On); f("streak1X", a.streak1X, b.streak1X)
        z("streak2On", a.streak2On, b.streak2On); f("streak2X", a.streak2X, b.streak2X)
        z("runOn", a.runOn, b.runOn); z("arcOn", a.arcOn, b.arcOn)
        f("ballX", a.ballX, b.ballX); f("ballY", a.ballY, b.ballY); f("ballRotDeg", a.ballRotDeg, b.ballRotDeg)
        f("ballScale", a.ballScale, b.ballScale); f("ballAlpha", a.ballAlpha, b.ballAlpha)
        z("postsOn", a.postsOn, b.postsOn); f("postsAlpha", a.postsAlpha, b.postsAlpha); f("postsDy", a.postsDy, b.postsDy)
        f("flashAlpha", a.flashAlpha, b.flashAlpha)
        f("badgeAlpha", a.badgeAlpha, b.badgeAlpha); f("badgeDx", a.badgeDx, b.badgeDx)
        f("badgeScale", a.badgeScale, b.badgeScale); f("badgeRotDeg", a.badgeRotDeg, b.badgeRotDeg)
        if (a.letterCount != b.letterCount) assertEquals(a.letterCount, b.letterCount, "${what()}: letterCount")
        for (i in 0 until NflScoreFrame.LETTERS) {
            f("letterAlpha", a.letterAlpha[i], b.letterAlpha[i], i)
            f("letterDy", a.letterDy[i], b.letterDy[i], i)
            f("letterScale", a.letterScale[i], b.letterScale[i], i)
        }
        f("wordPulse", a.wordPulse, b.wordPulse)
        f("blockAlpha", a.blockAlpha, b.blockAlpha); f("blockDx", a.blockDx, b.blockDx); f("blockScale", a.blockScale, b.blockScale)
        f("tabPulse", a.tabPulse, b.tabPulse)
        z("popOn", a.popOn, b.popOn); f("popScale", a.popScale, b.popScale); f("popMix", a.popMix, b.popMix)
        if (a.segment != b.segment) assertEquals(a.segment, b.segment, "${what()}: segment")
        z("done", a.done, b.done)
        f("rowFlashAlpha", 0f, b.rowFlashAlpha)
    }

    /** Fills [f] with values no sampler writes, so a field a sampler leaves alone shows up as a mismatch. */
    private fun dirty(f: NflScoreFrame) {
        f.leadFrom = 7f; f.leadTo = 7f; f.panelFrom = 7f; f.panelTo = 7f; f.glowLowAlpha = 7f; f.glowPeakAlpha = 7f
        f.streak1On = true; f.streak1X = 7f; f.streak2On = true; f.streak2X = 7f
        f.runOn = true; f.arcOn = true; f.ballX = 7f; f.ballY = 7f; f.ballRotDeg = 7f; f.ballScale = 7f; f.ballAlpha = 7f
        f.postsOn = true; f.postsAlpha = 7f; f.postsDy = 7f; f.flashAlpha = 7f
        f.badgeAlpha = 7f; f.badgeDx = 7f; f.badgeScale = 7f; f.badgeRotDeg = 7f
        f.letterCount = 77
        for (i in 0 until NflScoreFrame.LETTERS) { f.letterAlpha[i] = 7f; f.letterDy[i] = 7f; f.letterScale[i] = 7f }
        f.wordPulse = 7f; f.blockAlpha = 7f; f.blockDx = 7f; f.blockScale = 7f; f.tabPulse = 7f
        f.popOn = true; f.popScale = 7f; f.popMix = 7f; f.segment = 7; f.done = true
        f.rowFlashAlpha = 7f
    }

    @Test
    fun pillIsBitForBitTheLegacyTimelineForEveryKind() {
        val legacy = NflScoreFrame()
        val viaOld = NflScoreFrame()
        val viaSpec = NflScoreFrame()
        for (kind in kinds) for (t in instants) {
            LegacyNflScoreTimeline.sample(kind, t, legacy)
            dirty(viaOld); NflScoreTimeline.sample(kind, t, viaOld)
            dirty(viaSpec); NflScoreTimeline.sample(NflScoreSpec.PILL, kind, t, viaSpec)
            assertSameBits(legacy, viaOld, { "sample($kind, t=$t)" })
            assertSameBits(legacy, viaSpec, { "sample(PILL, $kind, t=$t)" })
        }
    }

    @Test
    fun pillPairIsBitForBitTheLegacyPair() {
        val legacy = NflScoreFrame()
        val viaOld = NflScoreFrame()
        val viaSpec = NflScoreFrame()
        for ((a, b) in pairs) for (t in instants) {
            LegacyNflScoreTimeline.samplePair(a, b, t, legacy)
            dirty(viaOld); NflScoreTimeline.samplePair(a, b, t, viaOld)
            dirty(viaSpec); NflScoreTimeline.samplePair(NflScoreSpec.PILL, a, b, t, viaSpec)
            assertSameBits(legacy, viaOld, { "samplePair($a, $b, t=$t)" })
            assertSameBits(legacy, viaSpec, { "samplePair(PILL, $a, $b, t=$t)" })
        }
    }

    @Test
    fun pillStillIsBitForBitTheLegacyStill() {
        val legacy = NflScoreFrame()
        val viaOld = NflScoreFrame()
        val viaSpec = NflScoreFrame()
        val cases: List<Pair<NflScoreKind, NflScoreKind?>> = kinds.map { it to null } + pairs
        for ((a, b) in cases) for (t in instants) {
            LegacyNflScoreTimeline.sampleStill(a, b, t, legacy)
            dirty(viaOld); NflScoreTimeline.sampleStill(a, b, t, viaOld)
            dirty(viaSpec); NflScoreTimeline.sampleStill(NflScoreSpec.PILL, a, b, t, viaSpec)
            assertSameBits(legacy, viaOld, { "sampleStill($a, $b, t=$t)" })
            assertSameBits(legacy, viaSpec, { "sampleStill(PILL, $a, $b, t=$t)" })
        }
    }

    @Test
    fun pillHoldsTodaysConstants() {
        val p = NflScoreSpec.PILL
        val k = SportsConstants
        assertEquals(LegacyNflScoreTimeline.END, p.endMs)
        assertEquals(4_500f, p.endMs)
        assertEquals(4_500f, p.pairSecondMs)
        assertEquals(9_000f, p.pairEndMs)
        assertEquals(900f, p.stillFrameBMs)
        assertEquals(p.endMs - k.NFL_TAKEOVER_MS, p.stillFrameBMs, "frame B ends at endMs")
        assertEquals(0f, p.rowFlashMs)
        assertEquals(8, p.tabPulseCycles)
        assertEquals(0.6f, p.streak1Alpha); assertEquals(0.35f, p.streak2Alpha)
        assertEquals(-85f, p.streakFrom); assertEquals(616f, p.streakTo)
        assertEquals(-42f, p.runFrom); assertEquals(562f, p.runTo); assertEquals(18f, p.runY); assertEquals(34f, p.runPx)
        assertEquals(0.4f, p.runAlpha); assertEquals(0.55f, p.arcAlpha)
        assertEquals(listOf(33f, 44f, 202f, -7f, 366f, 12f), listOf(p.arcP0X, p.arcP0Y, p.arcP1X, p.arcP1Y, p.arcP2X, p.arcP2Y))
        assertEquals(listOf(1f, 0.75f, 0.45f), listOf(p.arcS0, p.arcS1, p.arcS2))
        assertEquals(listOf(0f, 360f, 720f), listOf(p.arcR0, p.arcR1, p.arcR2))
        assertEquals(listOf(355f, 9f, 43f, 52f, 8f), listOf(p.postsX, p.postsY, p.postsW, p.postsH, p.postsDy))
        assertEquals(0.42f, p.flashAlpha)
        assertEquals(-32f, p.badgeDx); assertEquals(2f, p.badgeMidDx)
        assertEquals(22f, p.letterDrop); assertEquals(-2f, p.letterOvershoot)
        assertEquals(19f, p.pointsDx); assertEquals(-1.6f, p.pointsMidDx)
    }

    @Test
    fun pillWritesZeroRowFlashOverAnyValue() {
        val f = NflScoreFrame()
        for (t in listOf(0f, 3_600f, 3_700f, 4_499f, 4_800f)) {
            f.rowFlashAlpha = 0.5f; NflScoreTimeline.sample(NflScoreSpec.PILL, NflScoreKind.TOUCHDOWN, t, f)
            assertEquals(0f, f.rowFlashAlpha, "sample t=$t")
            f.rowFlashAlpha = 0.5f; NflScoreTimeline.sampleStill(NflScoreSpec.PILL, NflScoreKind.FIELD_GOAL, null, t, f)
            assertEquals(0f, f.rowFlashAlpha, "still t=$t")
        }
    }

    @Test
    fun copyFromCarriesTheRowFlash() {
        val a = NflScoreFrame().apply { rowFlashAlpha = 0.3f }
        val b = NflScoreFrame()
        b.copyFrom(a)
        assertEquals(0.3f, b.rowFlashAlpha)
        assertTrue(instants.size > 38_000)
    }
}
