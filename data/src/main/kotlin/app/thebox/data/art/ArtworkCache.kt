package app.thebox.data.art

import app.thebox.core.common.BoxLog
import app.thebox.data.di.WallClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException

/**
 * Title → poster path, on disk as one JSON object (`{"<key>": ["<path or empty>", <ms>]}`), an LRU of [max] entries.
 * A hit is answered without a request; "" (nothing matched) is kept [NEGATIVE_TTL_MS], a path [POSITIVE_TTL_MS].
 * Single-threaded by its owner (the lookup's mutex); the file is written whole after every new entry, which is one
 * write per unseen title.
 */
internal class ArtworkCache(private val file: File, private val clock: WallClock, private val max: Int = MAX_ENTRIES) {
    private class Entry(val path: String, val atMs: Long)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > max
    }
    private var loaded = false

    val size: Int get() { load(); return map.size }

    /** The cached path ("" = known to have none), or null when unknown or expired. */
    fun get(key: String): String? {
        load()
        val e = map[key] ?: return null
        val ttl = if (e.path.isEmpty()) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        if (clock.now() - e.atMs > ttl) {
            map.remove(key)
            return null
        }
        return e.path
    }

    fun put(key: String, path: String) {
        load()
        map[key] = Entry(path, clock.now())
        save()
    }

    fun clear() {
        load()
        map.clear()
        save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            for ((k, v) in root) {
                val a = v.jsonArray
                map[k] = Entry(a[0].jsonPrimitive.content, a[1].jsonPrimitive.long)
            }
        } catch (e: Exception) {
            BoxLog.w(TAG, "cache unreadable, starting over: ${e.javaClass.simpleName}")
            map.clear()
            file.delete()
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val obj = JsonObject(map.entries.associate { (k, e) -> k to JsonArray(listOf(JsonPrimitive(e.path), JsonPrimitive(e.atMs))) })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            BoxLog.w(TAG, "cache not written: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ArtworkCache"
        const val MAX_ENTRIES = 3000
        const val POSITIVE_TTL_MS = 30L * 24 * 3600 * 1000
        const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
