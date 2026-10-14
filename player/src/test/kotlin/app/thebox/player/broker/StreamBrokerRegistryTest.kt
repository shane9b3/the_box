package app.thebox.player.broker

import app.thebox.core.playback.LeaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JUnit 4, no Robolectric: the registry touches no `android.*`. */
class StreamBrokerRegistryTest {
    private val registry = StreamBrokerRegistryImpl { 0L }

    @Test
    fun oneBrokerPerKey() {
        val a = registry.broker("p:1")
        assertSame(a, registry.broker("p:1"))
        assertNotSame(a, registry.broker("p:2"))
        assertNotSame(a, registry.broker("d:host"))
        assertEquals("p:1", a.key)
    }

    @Test
    fun configureClampsAndAppliesTheOverrideChain() {
        registry.configure("p:1", 0)
        assertEquals(1, registry.broker("p:1").snapshot().max)
        registry.configure("p:1", 80)
        assertEquals(50, registry.broker("p:1").snapshot().max)
        registry.configure("p:1", override = 2, accountMax = 4)
        assertEquals(2, registry.broker("p:1").snapshot().max)
        registry.configure("p:1", override = null, accountMax = 4)
        assertEquals(4, registry.broker("p:1").snapshot().max)
        registry.configure("p:1", override = null, accountMax = null)
        assertEquals(1, registry.broker("p:1").snapshot().max)
    }

    @Test
    fun reconfigureOnEveryTuneKeepsLeases() {
        registry.configure("p:1", 2)
        val lease = registry.broker("p:1").acquire(LeaseKind.PLAYBACK, 1, 0)
        registry.configure("p:1", 1)
        val s = registry.broker("p:1").snapshot()
        assertEquals(1, s.active)
        assertEquals(1, s.max)
        lease.release()
    }

    @Test
    fun concurrentLookupsShareOneBroker() {
        val pool = Executors.newFixedThreadPool(8)
        val seen = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()))
        repeat(200) { pool.execute { seen += registry.broker("p:9") } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, seen.size)
    }
}
