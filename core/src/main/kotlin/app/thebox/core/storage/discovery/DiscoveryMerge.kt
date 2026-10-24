package app.thebox.core.storage.discovery

import app.thebox.core.setup.lan.LanAddressPolicy
import app.thebox.core.storage.FoundComputer
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants

/** Strict dotted-quad IPv4 (no leading zeros beyond "0", no spaces); the int is unsigned-ordered by [compareIpv4]. */
object Ipv4 {
    fun parse(s: String): Int? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            val o = p.toInt()
            if (o > 255) return null
            v = (v shl 8) or o
        }
        return v
    }

    fun format(v: Int): String = "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    fun compareIpv4(a: String, b: String): Int {
        val x = parse(a); val y = parse(b)
        return when {
            x != null && y != null -> Integer.compareUnsigned(x, y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b)
        }
    }
}

/**
 * The /24 the 445 sweep may touch (§7.1, owner ruling 2): the one that contains our Wi-Fi/Ethernet address, only for an
 * RFC 1918 address ([LanAddressPolicy]). Nothing outside it is ever a target.
 */
class LanSubnet private constructor(private val base: Int, val own: String) {
    /** "192.168.1.x" (U-N2). */
    val label: String get() = Ipv4.format(base).substringBeforeLast('.') + ".x"

    operator fun contains(ipv4: String): Boolean {
        val v = Ipv4.parse(ipv4) ?: return false
        return v and MASK == base
    }

    /** .1 … .254 in order, without [own], at most [max] (SWEEP_MAX_HOSTS). Never .0 or .255. */
    fun sweepTargets(max: Int = StorageConstants.SWEEP_MAX_HOSTS): List<String> {
        val ownV = Ipv4.parse(own)
        return (1..254).asSequence().map { base or it }.filter { it != ownV }.take(max.coerceAtLeast(0)).map(Ipv4::format).toList()
    }

    companion object {
        private const val MASK = 0xFFFFFF00.toInt()

        /** Null when [ownIpv4] is not a usable private IPv4 (no sweep then). */
        fun of(ownIpv4: String?): LanSubnet? {
            if (ownIpv4 == null || !LanAddressPolicy.isUsable(ownIpv4)) return null
            val v = Ipv4.parse(ownIpv4) ?: return null
            return LanSubnet(v and MASK, Ipv4.format(v))
        }
    }
}

/**
 * One thing a finder saw (§7.1). [port] is the SMB port it saw (the sweep: 445; NSD: the advertised port), null for a
 * [hintOnly] sighting, which never creates a computer: it only adds [apple] (from `_device-info._tcp`) to one that exists.
 */
data class Sighting(
    val address: String,
    val name: String? = null,
    val source: NameSource = NameSource.NONE,
    val port: Int? = StorageConstants.SMB_DEFAULT_PORT,
    val apple: Boolean = false,
    val hintOnly: Boolean = false,
)

/** Which found computers are already saved locations (U-S9, `Badge.SAVED`): by address, last address or host name. */
class SavedHosts(addresses: List<SmbAddress>) {
    private val ips = HashSet<String>()
    private val names = HashSet<String>()

    init {
        for (a in addresses) {
            for (h in listOfNotNull(a.host, a.lastAddress)) {
                val t = h.trim()
                if (Ipv4.parse(t) != null) ips += t else if (t.isNotEmpty()) names += norm(t)
            }
        }
    }

    fun matches(address: String, name: String?): Boolean = address in ips || (name != null && norm(name) in names)

    private fun norm(n: String) = n.trim().trimEnd('.').lowercase().removeSuffix(".local")

    companion object { val NONE = SavedHosts(emptyList()) }
}

/**
 * Merges sightings by IPv4 address (§7.1). Name preference: NSD instance, then NetBIOS, then reverse DNS. Port: 445 when
 * the sweep (or NSD) saw it, else the first advertised port. Order (U-S9): saved first; then named A–Z (case-insensitive,
 * ties by address); then unnamed by address. Not thread-safe: the caller serialises.
 */
class DiscoveryMerge(private val saved: SavedHosts = SavedHosts.NONE) {
    private class Entry(val address: String) {
        var name: String? = null
        var source: NameSource = NameSource.NONE
        var port: Int? = null
        var apple = false
        var real = false
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** True when the visible list changed. Non-IPv4 addresses are ignored. */
    fun add(s: Sighting): Boolean {
        val address = s.address.trim()
        if (Ipv4.parse(address) == null) return false
        val e = entries.getOrPut(address) { Entry(address) }
        val before = snapshot(e)
        if (!s.hintOnly) {
            e.real = true
            val p = s.port
            if (p != null && p in 1..65535 && (e.port == null || p == StorageConstants.SMB_DEFAULT_PORT)) e.port = p
        }
        if (s.apple) e.apple = true
        val clean = cleanName(s.name, address)
        if (clean != null && s.source != NameSource.NONE && rank(s.source) < rank(e.source)) {
            e.name = clean; e.source = s.source
        }
        return e.real && snapshot(e) != before
    }

    /** True when [address] is a computer with no name yet (the name lookup runs NBNS, then reverse DNS). */
    fun wantsName(address: String): Boolean = entries[address]?.let { it.real && it.source == NameSource.NONE } ?: false

    fun has(address: String): Boolean = entries[address]?.real == true

    val size: Int get() = entries.values.count { it.real }

    fun computers(): List<FoundComputer> = entries.values.filter { it.real }.map { e ->
        FoundComputer(e.name, e.address, e.source, saved = saved.matches(e.address, e.name), apple = e.apple,
            port = e.port ?: StorageConstants.SMB_DEFAULT_PORT)
    }.sortedWith(ORDER)

    private fun snapshot(e: Entry) = listOf(e.name, e.source, e.port, e.apple, e.real)

    companion object {
        private fun rank(s: NameSource) = when (s) {
            NameSource.NSD -> 0
            NameSource.NETBIOS -> 1
            NameSource.REVERSE_DNS -> 2
            NameSource.NONE -> 3
        }

        /** Saved first; named A–Z; unnamed by address (U-S9). */
        val ORDER: Comparator<FoundComputer> = Comparator { a, b ->
            when {
                a.saved != b.saved -> if (a.saved) -1 else 1
                (a.name == null) != (b.name == null) -> if (a.name != null) -1 else 1
                a.name != null && b.name != null -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name).takeIf { it != 0 }
                    ?: Ipv4.compareIpv4(a.address, b.address)
                else -> Ipv4.compareIpv4(a.address, b.address)
            }
        }

        /** Trimmed, trailing dot removed, at most 63 chars; null when empty or just an address (reverse DNS echoes it). */
        fun cleanName(raw: String?, address: String): String? {
            val t = raw?.trim()?.trimEnd('.')?.filter { it >= ' ' && it != '\u007F' }?.trim() ?: return null
            if (t.isEmpty() || t == address || Ipv4.parse(t) != null) return null
            return t.take(63)
        }

        /**
         * `_device-info._tcp` TXT `model=` of an Apple computer (the §7.7 Mac hint). Samba's `vfs_fruit` default
         * "MacSamba" is not a Mac. A NAS set to pose as a Mac model (TrueNAS "MacPro7,1@ECOLOR=…") counts as one: what it
         * gets is one extra sentence, not a wrong next step.
         */
        fun isAppleModel(model: String?): Boolean {
            val m = model?.trim() ?: return false
            if (m.startsWith("MacSamba", ignoreCase = true)) return false
            return APPLE_MODEL.matches(m)
        }

        private val APPLE_MODEL = Regex("^(Mac|iMac|Xserve|MacBook|MacPro|Macmini|MacStudio|RackMac|PowerMac|PowerBook|AirPort|TimeCapsule)[A-Za-z]*\\d*(,\\d+)?(@.*)?$")
    }
}
