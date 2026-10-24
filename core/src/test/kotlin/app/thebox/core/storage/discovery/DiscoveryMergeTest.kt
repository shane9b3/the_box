package app.thebox.core.storage.discovery

import app.thebox.core.storage.NameSource
import app.thebox.core.storage.SmbAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DiscoveryMergeTest {
    private fun sweep(a: String) = Sighting(a)
    private fun nsd(a: String, n: String, port: Int = 445) = Sighting(a, n, NameSource.NSD, port)
    private fun nb(a: String, n: String) = Sighting(a, n, NameSource.NETBIOS, port = null, hintOnly = true)
    private fun dns(a: String, n: String) = Sighting(a, n, NameSource.REVERSE_DNS, port = null, hintOnly = true)

    @Test fun mergesByAddress() {
        val m = DiscoveryMerge()
        assertTrue(m.add(sweep("192.168.1.20")))
        assertTrue(m.add(nsd("192.168.1.20", "NAS")))
        assertFalse(m.add(sweep("192.168.1.20")))
        assertEquals(1, m.size)
        val c = m.computers().single()
        assertEquals("NAS", c.name); assertEquals(NameSource.NSD, c.source); assertEquals(445, c.port)
    }

    @Test fun namePreferenceNsdThenNetbiosThenReverseDns() {
        val m = DiscoveryMerge()
        m.add(sweep("10.0.0.5"))
        m.add(dns("10.0.0.5", "desktop.lan"))
        assertEquals("desktop.lan", m.computers()[0].name)
        m.add(nb("10.0.0.5", "DESKTOP-7F3K2LQ"))
        assertEquals(NameSource.NETBIOS, m.computers()[0].source)
        m.add(dns("10.0.0.5", "other.lan"))                       // lower rank never replaces
        assertEquals("DESKTOP-7F3K2LQ", m.computers()[0].name)
        m.add(nsd("10.0.0.5", "Shawn's Mac"))
        assertEquals("Shawn's Mac", m.computers()[0].name)
        assertFalse(m.add(nb("10.0.0.5", "SOMETHING")))
        assertEquals(NameSource.NSD, m.computers()[0].source)
    }

    @Test fun sameRankKeepsTheFirstName() {
        val m = DiscoveryMerge()
        m.add(nsd("10.0.0.5", "First"))
        m.add(nsd("10.0.0.5", "Second"))
        assertEquals("First", m.computers()[0].name)
    }

    @Test fun hintsAloneNeverCreateAComputer() {
        val m = DiscoveryMerge()
        assertFalse(m.add(nb("10.0.0.9", "PRINTER")))
        assertFalse(m.add(Sighting("10.0.0.9", apple = true, port = null, hintOnly = true)))
        assertEquals(0, m.size)
        assertTrue(m.computers().isEmpty())
        assertFalse(m.has("10.0.0.9"))
        // ... but they apply once the address is a computer.
        assertTrue(m.add(sweep("10.0.0.9")))
        val c = m.computers().single()
        assertEquals("PRINTER", c.name); assertTrue(c.apple)
    }

    @Test fun appleHintMarksTheComputer() {
        val m = DiscoveryMerge()
        m.add(nsd("192.168.1.4", "iMac"))
        assertTrue(m.add(Sighting("192.168.1.4", port = null, apple = true, hintOnly = true)))
        assertTrue(m.computers()[0].apple)
    }

    @Test fun portPrefers445ElseAdvertised() {
        val m = DiscoveryMerge()
        m.add(nsd("192.168.1.30", "Samba", port = 4445))
        assertEquals(4445, m.computers()[0].port)
        m.add(sweep("192.168.1.30"))
        assertEquals(445, m.computers()[0].port)
        m.add(nsd("192.168.1.30", "Samba", port = 4445))
        assertEquals(445, m.computers()[0].port)
        val m2 = DiscoveryMerge()
        m2.add(Sighting("192.168.1.31", "x", NameSource.NSD, port = 0))
        assertEquals(445, m2.computers()[0].port)                   // invalid port → default
    }

    @Test fun nonIpv4AddressesAreIgnored() {
        val m = DiscoveryMerge()
        assertFalse(m.add(Sighting("fe80::1", "v6", NameSource.NSD)))
        assertFalse(m.add(Sighting("nas.local", "n", NameSource.NSD)))
        assertFalse(m.add(Sighting("192.168.1.256")))
        assertFalse(m.add(Sighting("192.168.01.2")))
        assertEquals(0, m.size)
    }

    @Test fun orderSavedThenNamedAzThenUnnamedByAddress() {
        val saved = SavedHosts(listOf(SmbAddress(host = "zeta", share = "TV"), SmbAddress(host = "nas.local", share = "TV", lastAddress = "192.168.1.200")))
        val m = DiscoveryMerge(saved)
        m.add(sweep("192.168.1.100"))
        m.add(sweep("192.168.1.9"))
        m.add(nsd("192.168.1.50", "beta"))
        m.add(nsd("192.168.1.51", "Alpha"))
        m.add(nsd("192.168.1.52", "ZETA"))                          // saved by name, case-insensitive
        m.add(sweep("192.168.1.200"))                               // saved by last address, unnamed
        m.add(nsd("192.168.1.60", "alpha"))                         // tie on name → by address
        val order = m.computers().map { it.address }
        assertEquals(listOf("192.168.1.52", "192.168.1.200", "192.168.1.51", "192.168.1.60", "192.168.1.50", "192.168.1.9", "192.168.1.100"), order)
        assertEquals(listOf(true, true, false, false, false, false, false), m.computers().map { it.saved })
    }

    @Test fun savedMatchesLocalSuffixAndAddress() {
        val s = SavedHosts(listOf(SmbAddress(host = "NAS", share = "a"), SmbAddress(host = "10.1.2.3", share = "b")))
        assertTrue(s.matches("1.1.1.1", "nas.local"))
        assertTrue(s.matches("1.1.1.1", "nas."))
        assertTrue(s.matches("10.1.2.3", null))
        assertFalse(s.matches("10.1.2.4", "other"))
        assertFalse(SavedHosts.NONE.matches("10.1.2.3", "NAS"))
    }

    @Test fun unnamedSortNumericallyNotLexically() {
        val m = DiscoveryMerge()
        listOf("10.0.0.100", "10.0.0.20", "10.0.0.3").forEach { m.add(sweep(it)) }
        assertEquals(listOf("10.0.0.3", "10.0.0.20", "10.0.0.100"), m.computers().map { it.address })
        assertTrue(m.computers().all { it.name == null && it.source == NameSource.NONE })
    }

    @Test fun wantsNameOnlyForUnnamedComputers() {
        val m = DiscoveryMerge()
        m.add(sweep("10.0.0.1")); m.add(nsd("10.0.0.2", "n"))
        assertTrue(m.wantsName("10.0.0.1"))
        assertFalse(m.wantsName("10.0.0.2"))
        assertFalse(m.wantsName("10.0.0.3"))
        m.add(nb("10.0.0.1", "PC"))
        assertFalse(m.wantsName("10.0.0.1"))
    }

    @Test fun cleanNameDropsAddressesAndJunk() {
        assertNull(DiscoveryMerge.cleanName("192.168.1.5", "192.168.1.5"))
        assertNull(DiscoveryMerge.cleanName("10.0.0.1", "192.168.1.5"))
        assertNull(DiscoveryMerge.cleanName("  ", "1.2.3.4"))
        assertNull(DiscoveryMerge.cleanName(null, "1.2.3.4"))
        assertEquals("nas.lan", DiscoveryMerge.cleanName(" nas.lan. ", "1.2.3.4"))
        assertEquals("ab", DiscoveryMerge.cleanName("a\u0000b\n", "1.2.3.4"))
        assertEquals(63, DiscoveryMerge.cleanName("x".repeat(100), "1.2.3.4")!!.length)
        val m = DiscoveryMerge()
        m.add(sweep("1.2.3.4")); m.add(dns("1.2.3.4", "1.2.3.4"))  // reverse DNS echoing the address
        assertNull(m.computers()[0].name)
        assertTrue(m.wantsName("1.2.3.4"))
    }

    @Test fun appleModels() {
        listOf("MacBookPro18,1", "Mac14,2", "iMac21,1", "Macmini9,1", "MacPro7,1@ECOLOR=226,226,224", "Xserve", "MacStudio1,1", "TimeCapsule8,119")
            .forEach { assertTrue(DiscoveryMerge.isAppleModel(it), it) }
        listOf("MacSamba", "macsamba", "Synology", "RackStation", "", "Linux", "PC").forEach { assertFalse(DiscoveryMerge.isAppleModel(it), it) }
        assertFalse(DiscoveryMerge.isAppleModel(null))
    }
}

class LanSubnetTest {
    @Test fun onlyPrivateAddressesGetASubnet() {
        assertNull(LanSubnet.of(null))
        assertNull(LanSubnet.of("8.8.8.8"))
        assertNull(LanSubnet.of("100.64.1.2"))       // CGNAT
        assertNull(LanSubnet.of("127.0.0.1"))
        assertNull(LanSubnet.of("169.254.3.4"))
        assertNull(LanSubnet.of("172.32.0.1"))
        assertNull(LanSubnet.of("192.168.1"))
        assertEquals("172.16.5.x", LanSubnet.of("172.16.5.9")!!.label)
        assertEquals("10.20.30.x", LanSubnet.of("10.20.30.40")!!.label)
    }

    @Test fun targetsStayInsideTheSlash24AndSkipOwn() {
        val s = LanSubnet.of("192.168.1.37")!!
        assertEquals("192.168.1.x", s.label)
        val t = s.sweepTargets()
        assertEquals(253, t.size)
        assertFalse("192.168.1.37" in t)
        assertFalse("192.168.1.0" in t); assertFalse("192.168.1.255" in t)
        assertEquals("192.168.1.1", t.first()); assertEquals("192.168.1.254", t.last())
        assertTrue(t.all { it.startsWith("192.168.1.") && it in s })
        assertEquals(t.size, t.toSet().size)
    }

    @Test fun targetsAreCappedBySweepMaxHosts() {
        val s = LanSubnet.of("10.0.0.254")!!
        assertEquals(253, s.sweepTargets(1_000).size)               // never more than the /24 minus our own address
        assertEquals(253, s.sweepTargets().size)
        assertEquals(10, s.sweepTargets(10).size)
        assertEquals(0, s.sweepTargets(-1).size)
    }

    @Test fun containsIsTheSameSlash24Only() {
        val s = LanSubnet.of("10.1.2.3")!!
        assertTrue("10.1.2.200" in s)
        assertFalse("10.1.3.200" in s)
        assertFalse("11.1.2.3" in s)
        assertFalse("nas" in s)
    }

    @Test fun ipv4Strict() {
        assertEquals(0xC0A80101.toInt(), Ipv4.parse("192.168.1.1"))
        assertEquals("255.255.255.255", Ipv4.format(Ipv4.parse("255.255.255.255")!!))
        listOf("", "1.2.3", "1.2.3.4.5", "1.2.3.-4", "01.2.3.4", "1.2.3.4 ", "256.1.1.1", "a.b.c.d").forEach { assertNull(Ipv4.parse(it), it) }
        assertTrue(Ipv4.compareIpv4("200.0.0.1", "10.0.0.1") > 0)   // unsigned order
    }
}
