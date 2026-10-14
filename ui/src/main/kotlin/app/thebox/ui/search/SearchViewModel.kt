package app.thebox.ui.search

import androidx.lifecycle.ViewModel
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** WP-B stub (docs/design/m3-guide.md §1.3, §3.10.3): 300 ms debounce, 2-char minimum, `SearchClosed` on Back/tune. */
@HiltViewModel
class SearchViewModel @Inject constructor(private val search: GuideSearch) : ViewModel() {
    val results: StateFlow<SearchResults?> get() = TODO("WP-B")
    fun onQuery(text: String): Unit = TODO("WP-B")
}
