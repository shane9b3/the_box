package app.thebox.data.guide

import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSource
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.8.4): SharedPreferences `guide_session`. */
@Singleton
class GuideSessionStoreImpl @Inject constructor() : GuideSessionStore {
    override fun lastSource(): GuideSource? = TODO("WP-B")
    override fun setLastSource(source: GuideSource): Unit = TODO("WP-B")
    override fun flag(name: String): Boolean = TODO("WP-B")
    override fun setFlag(name: String): Unit = TODO("WP-B")
}
