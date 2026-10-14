package app.thebox.core.playback.buffer

import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.BufferProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BufferProfileFsmTest {
    private val fsm = BufferProfileFsmImpl()
    private val key = "p:1"
    private val min = 60_000L
    private val normal = BufferConstants.NORMAL_TARGET_BYTES

    private fun level(k: String = key) = fsm.profileFor(k, BufferMode.AUTO, lowRam = false).level

    @Test
    fun autoStartsAtL0() {
        assertEquals(BufferProfile(1_000, 3_000, 10_000, 20_000, normal, 0), fsm.profileFor(key, BufferMode.AUTO, false))
    }

    @Test
    fun firstRebufferAtL0GoesToL1AtOnce() {
        assertEquals(BufferProfile(1_500, 5_000, 15_000, 30_000, normal, 1), fsm.onRebuffer(key, 0, BufferMode.AUTO, false))
        assertEquals(1, level())
        assertEquals(0, level("p:2"), "per playlist")
    }

    @Test
    fun twoRebuffersWithinTenMinutesClimbOneLevel() {
        fsm.onRebuffer(key, 0, BufferMode.AUTO, false)
        assertNull(fsm.onRebuffer(key, 1 * min, BufferMode.AUTO, false))
        assertEquals(BufferProfile(2_500, 10_000, 30_000, 45_000, normal, 2), fsm.onRebuffer(key, 9 * min, BufferMode.AUTO, false))
        assertNull(fsm.onRebuffer(key, 10 * min, BufferMode.AUTO, false))
        assertEquals(BufferProfile(5_000, 20_000, 40_000, 60_000, normal, 3), fsm.onRebuffer(key, 11 * min, BufferMode.AUTO, false))
        assertNull(fsm.onRebuffer(key, 12 * min, BufferMode.AUTO, false), "L3 stays")
        assertNull(fsm.onRebuffer(key, 12 * min + 1, BufferMode.AUTO, false))
        assertEquals(3, level())
    }

    @Test
    fun oneRebufferPerWindowChangesNothing() {
        fsm.onRebuffer(key, 0, BufferMode.AUTO, false)
        for (i in 1..5) assertNull(fsm.onRebuffer(key, i * 10 * min, BufferMode.AUTO, false))
        assertEquals(1, level())
    }

    @Test
    fun decaysOneLevelPerTenStableMinutes() {
        fsm.onRebuffer(key, 0, BufferMode.AUTO, false)
        fsm.onRebuffer(key, 1 * min, BufferMode.AUTO, false)
        fsm.onRebuffer(key, 2 * min, BufferMode.AUTO, false) // L2, last rebuffer at 2 min
        assertNull(fsm.onStableTick(key, 11 * min, BufferMode.AUTO, false))
        assertEquals(1, fsm.onStableTick(key, 12 * min, BufferMode.AUTO, false)!!.level)
        assertNull(fsm.onStableTick(key, 13 * min, BufferMode.AUTO, false))
        assertNull(fsm.onStableTick(key, 21 * min, BufferMode.AUTO, false))
        assertEquals(0, fsm.onStableTick(key, 22 * min, BufferMode.AUTO, false)!!.level)
        assertNull(fsm.onStableTick(key, 60 * min, BufferMode.AUTO, false), "L0 does not decay")
        assertNull(fsm.onStableTick("unknown", 60 * min, BufferMode.AUTO, false))
    }

    @Test
    fun manualModesAreFixedAndLeaveTheFsmAlone() {
        val expect = mapOf(
            BufferMode.SMALL to BufferProfile(2_000, 2_000, 10_000, 20_000, normal, -1),
            BufferMode.MEDIUM to BufferProfile(5_000, 5_000, 15_000, 30_000, normal, -1),
            BufferMode.LARGE to BufferProfile(10_000, 10_000, 30_000, 45_000, normal, -1),
            BufferMode.EXTRA_LARGE to BufferProfile(20_000, 20_000, 40_000, 60_000, normal, -1),
        )
        for ((mode, p) in expect) {
            assertEquals(p, fsm.profileFor(key, mode, false))
            assertNull(fsm.onRebuffer(key, 0, mode, false))
            assertNull(fsm.onStableTick(key, 100 * min, mode, false))
        }
        assertEquals(0, level())
    }

    @Test
    fun lowRamUsesTheSmallByteCap() {
        assertEquals(24 * 1024 * 1024, fsm.profileFor(key, BufferMode.AUTO, lowRam = true).targetBytes)
        assertEquals(24 * 1024 * 1024, fsm.profileFor(key, BufferMode.LARGE, lowRam = true).targetBytes)
        assertEquals(48 * 1024 * 1024, fsm.profileFor(key, BufferMode.LARGE, lowRam = false).targetBytes)
        assertEquals(24 * 1024 * 1024, fsm.onRebuffer(key, 0, BufferMode.AUTO, lowRam = true)!!.targetBytes)
    }
}
