package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live/segment `OkHttpClient` variants derived from the injected base client (`:data`'s unqualified
 * `HttpModule.baseClient()`, bound in `:app`'s graph), and the per-playlist "ignore TLS errors" variant.
 */
@Singleton
internal class PlaybackHttpClients @Inject constructor(private val base: OkHttpClient) {
    fun live(ignoreTlsErrors: Boolean): OkHttpClient = TODO("WP-B")
    fun segments(ignoreTlsErrors: Boolean): OkHttpClient = TODO("WP-B")
}
