package app.thebox.data.sports

import app.thebox.core.sports.Fetch
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * (Update interval §3.2, review S11) The compressed body bytes of one sports call. The source tags each request with a
 * fresh holder; the client's [EventListener] writes `responseBodyEnd(byteCount)` into it. That count is what came off
 * the socket, before OkHttp's transparent gunzip, so it is the wire size. `responseBodyEnd` fires when the body is
 * exhausted or closed, i.e. inside `response.use { }` on OkHttp's callback thread, so the source reads the holder right
 * after `use` returns and before it resumes the caller.
 */
internal class WireBytes {
    @Volatile var bytes: Long? = null
}

internal object WireMeter {
    private val LISTENER = object : EventListener() {
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            call.request().tag(WireBytes::class.java)?.bytes = byteCount
        }
    }

    /**
     * [builder] (a `newBuilder()` of [from]) with the listener added after [from]'s own (BoxDns counts IPv6 connect
     * failures there); the shared client is untouched.
     */
    fun install(builder: OkHttpClient.Builder, from: OkHttpClient): OkHttpClient.Builder {
        val prev = from.eventListenerFactory
        return builder.eventListenerFactory { call -> prev.create(call) + LISTENER }
    }

    fun tag(builder: Request.Builder, holder: WireBytes): Request.Builder = builder.tag(WireBytes::class.java, holder)

    /** `Cache-Control`: `s-maxage` first, else `max-age`; null when absent or unreadable. */
    fun maxAgeS(res: Response): Int? {
        val cc = res.headers("Cache-Control").joinToString(",")
        if (cc.isEmpty()) return null
        fun find(re: Regex): Int? = re.find(cc)?.groupValues?.get(1)?.toIntOrNull()
        return find(S_MAXAGE) ?: find(MAX_AGE)
    }

    private val S_MAXAGE = Regex("""(?i)(?:^|[,\s])s-maxage\s*=\s*"?(\d{1,9})""")
    private val MAX_AGE = Regex("""(?i)(?:^|[,\s])max-age\s*=\s*"?(\d{1,9})""")

    /** [f] with the holder's byte count, when it is an `Ok` and the listener saw the body end. */
    fun <T> withWire(f: Fetch<T>, holder: WireBytes): Fetch<T> {
        val b = holder.bytes ?: return f
        return if (f is Fetch.Ok) f.copy(wireBytes = b) else f
    }
}
