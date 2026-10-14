package app.thebox.player.datasource

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-B. Replace the TODO bodies; keep the names.
import app.thebox.core.playback.CoreLease
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `Call.Factory` that tags requests, attaches every `Call` to the session lease (a per-call `EventListener` via
 * `Call.addEventListener`; `callEnd`/`callFailed` detach and release) and records status/redirects/final host (§1.5, S2).
 */
internal class BrokeredCallFactory(
    private val client: OkHttpClient,
    private val lease: CoreLease,
) : Call.Factory {
    override fun newCall(request: Request): Call = TODO("WP-B")
}
