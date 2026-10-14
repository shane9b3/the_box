package app.thebox.data.guide

import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.SearchResults
import javax.inject.Inject
import javax.inject.Singleton

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.10). */
@Singleton
class GuideSearchImpl @Inject constructor() : GuideSearch {
    override suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults = TODO("WP-B")
}
