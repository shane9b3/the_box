package app.thebox.data.guide

import android.content.Context
import android.content.SharedPreferences
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last source and first-run flags (docs/design/m3-guide.md §3.8.4), SharedPreferences `guide_session`. A source is
 * persisted by its fields (list id, playlist id, group key), never by `channel_group.id`.
 */
@Singleton
class GuideSessionStoreImpl internal constructor(private val prefs: SharedPreferences) : GuideSessionStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override fun lastSource(): GuideSource? = decode(prefs.getString(KEY_SOURCE, null))

    override fun setLastSource(source: GuideSource) {
        prefs.edit().putString(KEY_SOURCE, encode(source)).apply()
    }

    override fun flag(name: String): Boolean = prefs.getBoolean(FLAG_PREFIX + name, false)

    override fun setFlag(name: String) {
        prefs.edit().putBoolean(FLAG_PREFIX + name, true).apply()
    }

    companion object {
        const val FILE = "guide_session"
        private const val KEY_SOURCE = "last_source"
        private const val FLAG_PREFIX = "flag."
        private const val SEP = '\u001F'

        fun encode(s: GuideSource): String = when (s) {
            is GuideSource.Favourites -> "F$SEP${s.listId}"
            GuideSource.Recent -> "R"
            is GuideSource.All -> "A$SEP${s.playlistId}"
            is GuideSource.Group -> "G$SEP${s.playlistId}$SEP${s.groupKey}"
        }

        fun decode(v: String?): GuideSource? {
            if (v.isNullOrEmpty()) return null
            val parts = v.split(SEP, limit = 3)
            return when (parts[0]) {
                "F" -> parts.getOrNull(1)?.toLongOrNull()?.let { GuideSource.Favourites(it) }
                "R" -> GuideSource.Recent
                "A" -> parts.getOrNull(1)?.toLongOrNull()?.let { GuideSource.All(it) }
                "G" -> {
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    val key = parts.getOrNull(2)
                    if (id != null && key != null) GuideSource.Group(id, key) else null
                }
                else -> null
            }
        }
    }
}
