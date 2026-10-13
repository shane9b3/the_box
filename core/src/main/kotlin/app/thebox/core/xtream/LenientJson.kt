package app.thebox.core.xtream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient accessors over Xtream JSON (docs/design/m1-data-layer.md §3.7.2). Panels send numbers as strings,
 * booleans as "1"/"0", `""`/`null` for absent values and `[]` (an empty PHP array) for an empty object.
 * Every accessor returns null (or an empty collection) instead of throwing.
 */
object LenientJson {
    private val TRUE = setOf("1", "true", "yes", "y", "on")
    private val FALSE = setOf("0", "false", "no", "n", "off")
    private val EMPTY = JsonObject(emptyMap())

    /** Trimmed string; numbers and booleans as their text. `""`, `"null"`, JSON null, arrays and objects → null. */
    fun str(o: JsonObject?, k: String): String? = str(o?.get(k))

    fun str(e: JsonElement?): String? {
        if (e !is JsonPrimitive || e is JsonNull) return null
        val s = e.content.trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    /** Integral number, a float with an integral value, a numeric string (`"12"`, `"12.0"`), or a bool (1/0). */
    fun long(o: JsonObject?, k: String): Long? = long(o?.get(k))

    fun long(e: JsonElement?): Long? {
        if (e !is JsonPrimitive || e is JsonNull) return null
        val s = e.content.trim()
        if (s.isEmpty()) return null
        if (!e.isString) {
            if (s == "true") return 1
            if (s == "false") return 0
        }
        s.toLongOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) return null
        if (d >= Long.MAX_VALUE.toDouble() || d <= Long.MIN_VALUE.toDouble()) return null
        return d.toLong()
    }

    /** [long] within the Int range. */
    fun int(o: JsonObject?, k: String): Int? = int(o?.get(k))

    fun int(e: JsonElement?): Int? = long(e)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** `true`/`false`, numbers (≠ 0 → true), strings 1/true/yes/y/on and 0/false/no/n/off (any case). */
    fun bool(o: JsonObject?, k: String): Boolean? = bool(o?.get(k))

    fun bool(e: JsonElement?): Boolean? {
        if (e !is JsonPrimitive || e is JsonNull) return null
        val s = e.content.trim().lowercase()
        if (s.isEmpty()) return null
        if (s in TRUE) return true
        if (s in FALSE) return false
        if (e.isString) return null
        val d = s.toDoubleOrNull() ?: return null
        return d != 0.0
    }

    /** Number or numeric string; a decimal comma is accepted. */
    fun double(o: JsonObject?, k: String): Double? = double(o?.get(k))

    fun double(e: JsonElement?): Double? {
        if (e !is JsonPrimitive || e is JsonNull) return null
        val s = e.content.trim()
        if (s.isEmpty()) return null
        return s.replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() }
    }

    /**
     * Array → elements through [str]; a string that starts with `[` → parsed as a JSON array, otherwise split on
     * `,`/`;`; an object → its values. Anything else → empty.
     */
    fun strList(o: JsonObject?, k: String): List<String> = strList(o?.get(k))

    fun strList(e: JsonElement?): List<String> = when (e) {
        null, JsonNull -> emptyList()
        is JsonArray -> e.mapNotNull { str(it) }
        is JsonObject -> e.values.mapNotNull { str(it) }
        is JsonPrimitive -> {
            val s = str(e)
            when {
                s == null -> emptyList()
                s.startsWith("[") -> (runCatching { XtreamJson.json.parseToJsonElement(s) }.getOrNull() as? JsonArray)
                    ?.let { strList(it) } ?: splitList(s.trim('[', ']'))
                else -> splitList(s)
            }
        }
    }

    private fun splitList(s: String): List<String> =
        s.split(',', ';').map { it.trim().trim('"') }.filter { it.isNotEmpty() }

    /** Object; `[]` (an empty PHP array) → empty object. Anything else → null. */
    fun obj(o: JsonObject?, k: String): JsonObject? = obj(o?.get(k))

    fun obj(e: JsonElement?): JsonObject? = when (e) {
        is JsonObject -> e
        is JsonArray -> if (e.isEmpty()) EMPTY else null
        else -> null
    }

    /** Object → values; array → elements; anything else → empty. */
    fun objOrArrayValues(o: JsonObject?, k: String): List<JsonElement> = objOrArrayValues(o?.get(k))

    fun objOrArrayValues(e: JsonElement?): List<JsonElement> = when (e) {
        is JsonObject -> e.values.toList()
        is JsonArray -> e
        else -> emptyList()
    }
}
