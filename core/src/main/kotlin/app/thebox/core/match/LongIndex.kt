package app.thebox.core.match

import androidx.collection.MutableLongIntMap
import app.thebox.core.common.Hash64

/**
 * String key → subject indices, keyed by the 64-bit [Hash64] of the key so no key string is retained
 * (§3.6.4 memory budget). Single-subject keys (the common case) cost one primitive map entry; keys shared
 * by several subjects spill into an overflow list. A 64-bit collision between two different keys is
 * astronomically unlikely at 20k keys; id stages also re-verify the subject's id on a hit.
 */
internal class LongIndex(expected: Int) {
    private val map = MutableLongIntMap(expected)
    private val overflow = ArrayList<IntArray>()
    private val overflowSize = ArrayList<Int>()

    fun add(key: String, subject: Int) {
        if (key.isEmpty()) return
        val h = Hash64.of(key)
        val cur = map.getOrDefault(h, MISSING)
        when {
            cur == MISSING -> map[h] = subject
            cur >= 0 -> {
                if (cur == subject) return
                overflow += intArrayOf(cur, subject, 0, 0)
                overflowSize += 2
                map[h] = -overflow.size
            }
            else -> {
                val slot = -cur - 1
                var arr = overflow[slot]
                val size = overflowSize[slot]
                if (arr[size - 1] == subject) return
                if (size == arr.size) { arr = arr.copyOf(size * 2); overflow[slot] = arr }
                arr[size] = subject
                overflowSize[slot] = size + 1
            }
        }
    }

    /** Calls [block] for every subject indexed under [key]. */
    inline fun forEach(key: String, block: (Int) -> Unit) {
        if (key.isEmpty()) return
        val v = lookup(Hash64.of(key))
        when {
            v == MISSING -> return
            v >= 0 -> block(v)
            else -> {
                val slot = -v - 1
                val arr = overflowArray(slot)
                for (i in 0 until overflowCount(slot)) block(arr[i])
            }
        }
    }

    fun lookup(hash: Long): Int = map.getOrDefault(hash, MISSING)
    fun overflowArray(slot: Int): IntArray = overflow[slot]
    fun overflowCount(slot: Int): Int = overflowSize[slot]

    companion object {
        const val MISSING = Int.MIN_VALUE
    }
}
