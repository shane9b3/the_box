package app.thebox.core.refresh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemapPlannerTest {
    private val p = RemapPlannerImpl()
    private fun c(key: String, name: String, group: String? = null) = RemapCandidate(key, name, group)

    @Test fun `1 to 1 by normalised name`() {
        val plan = p.plan(listOf(c("xc:1", "BBC One HD", "UK"), c("xc:2", "ITV")), listOf(c("xc:9", "BBC ONE FHD", "UK"), c("xc:8", "itv")))
        assertEquals(listOf(KeyRemap("xc:1", "xc:9", true), KeyRemap("xc:2", "xc:8", true)), plan)
    }

    @Test fun `ambiguity gives no remap`() {
        assertTrue(p.plan(listOf(c("a", "News", "X")), listOf(c("b", "News", "Y"), c("c", "News", "Z"))).isEmpty())
    }

    @Test fun `group tie-break picks the candidate in the same group`() {
        val plan = p.plan(listOf(c("a", "News", "UK HD")), listOf(c("b", "News", "US"), c("c", "News", "uk hd")))
        assertEquals(listOf(KeyRemap("a", "c", true)), plan)
    }

    @Test fun `an added candidate is claimed once`() {
        val plan = p.plan(listOf(c("a", "Sport"), c("b", "Sport")), listOf(c("n", "Sport")))
        assertEquals(listOf(KeyRemap("a", "n", true)), plan)
    }

    @Test fun `different group with a single candidate still remaps, flagged`() {
        assertEquals(listOf(KeyRemap("a", "b", false)), p.plan(listOf(c("a", "Cine", "Movies")), listOf(c("b", "Cine", "Films"))))
    }

    @Test fun `no candidates, or the same key, give nothing`() {
        assertTrue(p.plan(listOf(c("a", "X")), listOf(c("b", "Y"))).isEmpty())
        assertTrue(p.plan(listOf(c("a", "X")), listOf(c("a", "X"))).isEmpty())
    }
}
