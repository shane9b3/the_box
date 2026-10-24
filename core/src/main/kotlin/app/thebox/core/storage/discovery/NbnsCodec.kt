package app.thebox.core.storage.discovery

/**
 * NetBIOS name service, node status only (RFC 1002 §4.2.17 NODE STATUS REQUEST, §4.2.18 NODE STATUS RESPONSE), for the
 * names on `FindComputers` (docs/design/m6-storage-locations.md §7.1). Pure JVM; never throws on a malformed packet.
 *
 * The request is the 50-byte `NBSTAT *` query that `nmblookup -A` and Windows `nbtstat -A` send: a 12-byte header
 * (OPCODE QUERY, no NM_FLAGS, QDCOUNT 1), the wildcard name `*` + 15 NULs in first-level encoding (RFC 1001 §14.1,
 * `CK` + 30 × `A`), QUESTION_TYPE NBSTAT (0x0021), QUESTION_CLASS IN (0x0001).
 */
object NbnsCodec {
    const val REQUEST_BYTES = 50
    const val TYPE_NBSTAT = 0x0021
    const val CLASS_IN = 0x0001
    const val NAME_BYTES = 16
    private const val ENCODED_BYTES = 32
    private const val ENTRY_BYTES = 18

    const val FLAG_GROUP = 0x8000
    const val FLAG_DEREGISTERING = 0x1000
    const val FLAG_CONFLICT = 0x0800
    const val FLAG_ACTIVE = 0x0400
    const val FLAG_PERMANENT = 0x0200

    /** One NODE_NAME entry: [name] is the 15-byte name with trailing spaces/NULs removed, [suffix] the 16th byte. */
    data class NodeName(val name: String, val suffix: Int, val flags: Int) {
        val group: Boolean get() = flags and FLAG_GROUP != 0
        val usable: Boolean get() = flags and (FLAG_CONFLICT or FLAG_DEREGISTERING) == 0
    }

    /** A parsed NODE STATUS RESPONSE. [unitId] is the 6-byte UNIT_ID (usually the MAC), null when absent. */
    data class NodeStatus(val transactionId: Int, val names: List<NodeName>, val unitId: ByteArray?) {
        /** The computer name: the first unique, non-conflicting `<00>` name (§7.1), else null. */
        fun computerName(): String? = names.firstOrNull { it.suffix == 0x00 && !it.group && it.usable && it.name.isNotEmpty() }?.name

        override fun equals(other: Any?) = other is NodeStatus && other.transactionId == transactionId && other.names == names &&
            (other.unitId?.contentEquals(unitId) ?: (unitId == null))
        override fun hashCode() = 31 * (31 * transactionId + names.hashCode()) + (unitId?.contentHashCode() ?: 0)
    }

    /** First-level encoding (RFC 1001 §14.1) of a 16-byte NetBIOS name, without the length byte. */
    fun encodeName(raw16: ByteArray): ByteArray {
        require(raw16.size == NAME_BYTES) { "a NetBIOS name is 16 bytes" }
        val out = ByteArray(ENCODED_BYTES)
        for (i in 0 until NAME_BYTES) {
            val b = raw16[i].toInt() and 0xFF
            out[2 * i] = ('A'.code + (b shr 4)).toByte()
            out[2 * i + 1] = ('A'.code + (b and 0x0F)).toByte()
        }
        return out
    }

    /** [name] padded with spaces to 15 bytes, plus [suffix] (RFC 1001 §14.1's example "FRED" uses suffix 0x20). */
    fun rawName(name: String, suffix: Int): ByteArray {
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        require(bytes.size <= 15) { "a NetBIOS name is at most 15 bytes" }
        val raw = ByteArray(NAME_BYTES) { 0x20 }
        bytes.copyInto(raw)
        raw[15] = suffix.toByte()
        return raw
    }

    /** The wildcard name: `*` followed by 15 NULs (RFC 1002 §4.2.18). */
    fun wildcardName(): ByteArray = ByteArray(NAME_BYTES).also { it[0] = '*'.code.toByte() }

    /** The NBSTAT `*` request with [transactionId] (low 16 bits). */
    fun nodeStatusRequest(transactionId: Int): ByteArray {
        val p = ByteArray(REQUEST_BYTES)
        p[0] = (transactionId shr 8).toByte(); p[1] = transactionId.toByte()
        // flags 0x0000: R = 0, OPCODE = QUERY, NM_FLAGS = 0 (unicast, no recursion: a node status goes to the host itself)
        p[5] = 1                                   // QDCOUNT = 1; ANCOUNT, NSCOUNT, ARCOUNT = 0
        p[12] = ENCODED_BYTES.toByte()
        encodeName(wildcardName()).copyInto(p, 13)
        p[45] = 0                                  // root label
        p[46] = (TYPE_NBSTAT shr 8).toByte(); p[47] = TYPE_NBSTAT.toByte()
        p[48] = (CLASS_IN shr 8).toByte(); p[49] = CLASS_IN.toByte()
        return p
    }

    /**
     * Parses a NODE STATUS RESPONSE. Null when the packet is not a positive response to an NBSTAT query with
     * [expectedTransactionId] (when given): too short, R bit clear, OPCODE ≠ QUERY, RCODE ≠ 0, ANCOUNT = 0, wrong type or
     * class, or a NODE_NAME array that runs past the end. Entries whose name has control characters are skipped.
     */
    fun parseNodeStatus(packet: ByteArray, length: Int = packet.size, expectedTransactionId: Int? = null): NodeStatus? {
        val len = length.coerceIn(0, packet.size)
        if (len < 12) return null
        fun u8(i: Int) = packet[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        val tid = u16(0)
        if (expectedTransactionId != null && tid != (expectedTransactionId and 0xFFFF)) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null                 // not a response
        if ((flags shr 11) and 0x0F != 0) return null           // OPCODE must be QUERY
        if (flags and 0x000F != 0) return null                  // RCODE: negative response
        val qd = u16(4); val an = u16(6)
        if (an < 1) return null
        var pos = 12
        repeat(qd) {                                            // not sent by Windows or Samba; skipped if present
            pos = skipName(packet, len, pos) ?: return null
            pos += 4
        }
        pos = skipName(packet, len, pos) ?: return null         // RR_NAME
        if (pos + 10 > len) return null
        if (u16(pos) != TYPE_NBSTAT || u16(pos + 2) != CLASS_IN) return null
        val rdLength = u16(pos + 8)
        pos += 10
        val rdEnd = minOf(len, pos + rdLength)
        if (pos + 1 > rdEnd) return null
        val count = u8(pos)
        pos += 1
        if (pos + count * ENTRY_BYTES > rdEnd) return null
        val names = ArrayList<NodeName>(count)
        for (n in 0 until count) {
            val e = pos + n * ENTRY_BYTES
            val name = decodeName(packet, e) ?: continue
            names += NodeName(name, u8(e + 15), u16(e + 16))
        }
        pos += count * ENTRY_BYTES
        val unitId = if (pos + 6 <= rdEnd) packet.copyOfRange(pos, pos + 6) else null
        return NodeStatus(tid, names, unitId)
    }

    /** Skips a domain name (labels or a compression pointer); null when it runs past [len] or loops. */
    private fun skipName(p: ByteArray, len: Int, start: Int): Int? {
        var pos = start
        var labels = 0
        while (true) {
            if (pos >= len || ++labels > 64) return null
            val l = p[pos].toInt() and 0xFF
            when {
                l == 0 -> return pos + 1
                l and 0xC0 == 0xC0 -> return if (pos + 2 <= len) pos + 2 else null
                l and 0xC0 != 0 -> return null
                else -> pos += 1 + l
            }
        }
    }

    private fun decodeName(p: ByteArray, at: Int): String? {
        val sb = StringBuilder(15)
        for (i in 0 until 15) {
            val c = p[at + i].toInt() and 0xFF
            if (c == 0) break                                   // NUL-padded names (the wildcard style)
            if (c < 0x20 || c == 0x7F) return null
            sb.append(c.toChar())                               // OEM bytes read as ISO-8859-1: ASCII names are exact
        }
        return sb.toString().trimEnd(' ')
    }
}
