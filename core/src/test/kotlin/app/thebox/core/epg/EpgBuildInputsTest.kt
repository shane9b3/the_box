package app.thebox.core.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Review 2026-11-10 S1: the build-input fingerprint is stable, order-free over sources, and moves with every input. */
class EpgBuildInputsTest {
    private val a = EpgBuildInputs.Source(1, null)
    private val b = EpgBuildInputs.Source(2, "Europe/London")

    private fun fp(
        sources: List<EpgBuildInputs.Source> = listOf(a, b),
        past: Int = 2,
        future: Int = 7,
        desc: Boolean = true,
        max: Int = 2048,
        langs: List<String> = listOf("en"),
        matching: String = "m",
    ) = EpgBuildInputs.fingerprint(sources, past, future, desc, max, langs, matching)

    @Test
    fun stableAndIndependentOfSourceOrder() {
        assertEquals(fp(), fp())
        assertEquals(fp(listOf(a, b)), fp(listOf(b, a)))
    }

    @Test
    fun everyInputChangesIt() {
        val base = fp()
        val changed = listOf(
            fp(sources = listOf(a)),
            fp(sources = listOf(a, b.copy(assumeZone = null))),
            fp(sources = listOf(a, b, EpgBuildInputs.Source(3, null))),
            fp(past = 1),
            fp(future = 14),
            fp(desc = false),
            fp(max = 512),
            fp(langs = listOf("en", "fr")),
            fp(langs = emptyList()),
            fp(matching = "m2"),
        )
        for ((i, c) in changed.withIndex()) assertNotEquals(base, c, "input $i")
        assertEquals(changed.size, changed.toSet().size)
    }
}
