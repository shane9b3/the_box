package app.thebox.core.settings.fakes

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * In-memory socket pairs for WP-D's server tests (docs/design/m5-setup-settings.md §1.9 item 8): what the client writes
 * the server reads and vice versa, with no network. WP-D's `LanHttpServerImpl` takes its accepted connections from a
 * transport seam it defines; this class provides the pairs ([connect] = one phone connection).
 */
class FakeLanTransport(private val bufferBytes: Int = 64 * 1024) {
    /** One end of a connection. [close] closes both directions of this end. */
    class End(val input: InputStream, val output: OutputStream, val remoteAddress: String) : AutoCloseable {
        override fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    class Pair(val client: End, val server: End)

    val accepted = ArrayList<End>()

    /** A new connection from [clientAddress]; its server end is appended to [accepted]. */
    fun connect(clientAddress: String = "192.168.1.30"): Pair {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, bufferBytes)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, bufferBytes)
        val server = End(serverIn, toClient, clientAddress)
        accepted += server
        return Pair(End(clientIn, toServer, "tv"), server)
    }
}
