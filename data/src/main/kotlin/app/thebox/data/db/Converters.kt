package app.thebox.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters. JSON map columns use kotlinx-serialization; an empty map is stored as NULL.
 * Enums (playlist.type) use Room's built-in by-name conversion.
 */
class Converters {
    @TypeConverter
    fun mapToJson(map: Map<String, String>?): String? = encodeMap(map)

    @TypeConverter
    fun jsonToMap(s: String?): Map<String, String>? = decodeMap(s)

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        private val MAP = MapSerializer(String.serializer(), String.serializer())

        fun encodeMap(map: Map<String, String>?): String? = if (map.isNullOrEmpty()) null else json.encodeToString(MAP, map)

        fun decodeMap(s: String?): Map<String, String>? =
            if (s.isNullOrEmpty()) null else runCatching { json.decodeFromString(MAP, s) }.getOrNull()
    }
}
