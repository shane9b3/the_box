package app.thebox.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Hash64 is persisted (channel.content_hash, epg_text.hash, reminder.title_hash): these values must never change. */
class Hash64Test {
    @Test
    fun offsetBasisIsFnv1a64() {
        assertEquals(0xcbf29ce484222325uL.toLong(), Hash64.OFFSET)
        assertEquals(0x100000001b3L, Hash64.PRIME)
    }

    @Test
    fun goldenValuesAreStable() {
        // Computed by an independent implementation (Python) of the documented algorithm.
        assertEquals(-5808391946409677970L, Hash64.of(""))
        assertEquals(-1937671896890010297L, Hash64.of("BBC One"))
        assertEquals(3410412597404831074L, Hash64.of("Télé-Québec"))
        assertEquals(-5808390846898049759L, Hash64.of(null))
        assertEquals(1899228443037480213L, Hash64.str(Hash64.str(Hash64.OFFSET, "ab"), "c"))
        assertEquals(-55488592825689361L, Hash64.long(Hash64.OFFSET, 42))
        assertEquals(-8289690350564177859L, Hash64.long(Hash64.OFFSET, -1))
    }

    @Test
    fun fieldSeparatorsPreventConcatenationCollisions() {
        val ab_c = Hash64.str(Hash64.str(Hash64.OFFSET, "ab"), "c")
        val a_bc = Hash64.str(Hash64.str(Hash64.OFFSET, "a"), "bc")
        assertNotEquals(ab_c, a_bc)
        assertNotEquals(Hash64.of(null), Hash64.of(""))
    }

    @Test
    fun helpersAreConsistent() {
        assertEquals(Hash64.str(Hash64.OFFSET, null), Hash64.int(Hash64.OFFSET, null))
        assertEquals(Hash64.long(Hash64.OFFSET, 7), Hash64.int(Hash64.OFFSET, 7))
        assertEquals(Hash64.long(Hash64.OFFSET, 1), Hash64.bool(Hash64.OFFSET, true))
        assertEquals(Hash64.long(Hash64.OFFSET, 1.5.toRawBits()), Hash64.double(Hash64.OFFSET, 1.5))
        assertNotEquals(Hash64.bool(Hash64.OFFSET, true), Hash64.bool(Hash64.OFFSET, false))
    }

    @Test
    fun mapHashIsOrderIndependent() {
        val a = linkedMapOf("User-Agent" to "x", "Referer" to "y")
        val b = linkedMapOf("Referer" to "y", "User-Agent" to "x")
        assertEquals(Hash64.map(Hash64.OFFSET, a), Hash64.map(Hash64.OFFSET, b))
        assertNotEquals(Hash64.map(Hash64.OFFSET, a), Hash64.map(Hash64.OFFSET, mapOf("User-Agent" to "x")))
        assertNotEquals(Hash64.map(Hash64.OFFSET, mapOf("k" to "v1")), Hash64.map(Hash64.OFFSET, mapOf("k" to "v2")))
    }
}
