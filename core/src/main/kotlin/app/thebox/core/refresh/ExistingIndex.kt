package app.thebox.core.refresh

import androidx.collection.MutableIntIntMap
import androidx.collection.MutableIntLongMap
import androidx.collection.MutableLongIntMap
import app.thebox.core.source.EntryKind
import java.util.BitSet

/**
 * Primitive index over the existing channel rows (docs/design/m1-data-layer.md §3.3.2). No `String` is
 * retained per row: a 64-bit key hash maps to a slot, and each slot keeps primitives in parallel arrays.
 *
 * Collisions: each slot also stores an independent 32-bit check hash (`String.hashCode`). The first key
 * of a 64-bit hash owns the main map entry; a later key with the same 64-bit hash but a different check
 * hash goes into a small `HashMap<String, Int>` side table. Lookups verify the check hash, so a new key
 * that collides with an existing one is never matched to the wrong row.
 *
 * Per row: map entry (~15 B) + id, content hash, url hash (8 B each) + name hash, sort index, check hash
 * (4 B each) + kind (1 B) ≈ 52 B. `missing_since`/`missing_count` live in sparse maps (only missing rows have them).
 *
 * [capacityHint] is the entry count of pass 1 (the existing row count is usually close to it). Storage is
 * allocated at that size on the first [add], so a first import allocates nothing and a refresh does not
 * regrow (and garbage-collect) its arrays and map in the importer's small heap.
 */
internal class ExistingIndex(private val keyHash: (String) -> Long, private val capacityHint: Int = 1024) {
    private var map = MutableLongIntMap(0)
    private var side: HashMap<String, Int>? = null
    private var ids = LongArray(0)
    private var content = LongArray(0)
    private var urls = LongArray(0)
    private var names = IntArray(0)
    private var sorts = IntArray(0)
    private var checks = IntArray(0)
    private var kinds = ByteArray(0)
    private val missingSince = MutableIntLongMap()
    private val missingCount = MutableIntIntMap()
    private var sortedUrls: LongArray? = null

    val seen = BitSet()
    var size = 0
        private set
    var collisions = 0
        private set

    fun add(e: ExistingChannel) {
        val slot = size
        if (slot == 0 && ids.isEmpty()) {
            val cap = maxOf(16, capacityHint + capacityHint / 16)
            map = MutableLongIntMap(cap)
            ids = LongArray(cap)
            content = LongArray(cap)
            urls = LongArray(cap)
            names = IntArray(cap)
            sorts = IntArray(cap)
            checks = IntArray(cap)
            kinds = ByteArray(cap)
        }
        ensure(slot + 1)
        ids[slot] = e.id
        content[slot] = e.contentHash
        urls[slot] = e.urlHash
        names[slot] = e.nameHash.toInt()
        sorts[slot] = e.sortIndex
        checks[slot] = e.stableKey.hashCode()
        kinds[slot] = e.kind.ordinal.toByte()
        if (e.missingSince != null) missingSince[slot] = e.missingSince
        if (e.missingCount != 0) missingCount[slot] = e.missingCount
        val h = keyHash(e.stableKey)
        val owner = map.getOrDefault(h, -1)
        if (owner < 0) {
            map[h] = slot
        } else {
            collisions++
            (side ?: HashMap<String, Int>().also { side = it })[e.stableKey] = slot
        }
        size++
        sortedUrls = null
    }

    /** Slot of [key], or -1. */
    fun lookup(key: String): Int {
        val slot = map.getOrDefault(keyHash(key), -1)
        if (slot < 0) return -1
        if (checks[slot] == key.hashCode() && side?.containsKey(key) != true) return slot
        return side?.get(key) ?: -1
    }

    fun id(slot: Int) = ids[slot]
    fun contentHash(slot: Int) = content[slot]
    fun urlHash(slot: Int) = urls[slot]
    fun nameHash32(slot: Int) = names[slot]
    fun sortIndex(slot: Int) = sorts[slot]
    fun missingSince(slot: Int): Long? = if (missingSince.containsKey(slot)) missingSince[slot] else null
    fun missingCount(slot: Int): Int = missingCount.getOrDefault(slot, 0)
    fun kind(slot: Int): EntryKind = EntryKind.entries[kinds[slot].toInt()]

    /** Whether any existing row has [urlHash] (S7 auto-remap candidates). Built lazily on first use. */
    fun hasUrl(urlHash: Long): Boolean {
        if (size == 0) return false
        val s = sortedUrls ?: urls.copyOf(size).also { it.sort(); sortedUrls = it }
        return java.util.Arrays.binarySearch(s, urlHash) >= 0
    }

    private fun ensure(n: Int) {
        if (n <= ids.size) return
        val cap = maxOf(n, ids.size * 2)
        ids = ids.copyOf(cap)
        content = content.copyOf(cap)
        urls = urls.copyOf(cap)
        names = names.copyOf(cap)
        sorts = sorts.copyOf(cap)
        checks = checks.copyOf(cap)
        kinds = kinds.copyOf(cap)
    }
}

/**
 * The set of final keys assigned in this refresh that matched no existing row (the "seen-key occurrence
 * map" of §3.2.1/§4.5). Keys that matched a row are tracked by [ExistingIndex.seen] instead.
 * Same collision scheme as [ExistingIndex]: 64-bit hash → 32-bit check hash, side set for colliders.
 */
internal class NewKeySet(private val keyHash: (String) -> Long, initialCapacity: Int = 1024) {
    private val map = MutableLongIntMap(initialCapacity)
    private var side: HashSet<String>? = null

    fun contains(key: String): Boolean {
        val h = keyHash(key)
        if (map.containsKey(h) && map[h] == key.hashCode()) return true
        return side?.contains(key) == true
    }

    fun add(key: String) {
        val h = keyHash(key)
        if (!map.containsKey(h)) map[h] = key.hashCode()
        else (side ?: HashSet<String>().also { side = it }).add(key)
    }
}
