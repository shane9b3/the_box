package app.thebox.core.setup.lan

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** One accepted connection (a real socket, or an in-memory pair in tests). */
interface LanConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    /** The client's IPv4, as text. */
    val remoteAddress: String
    /** Per-read timeout; a no-op where the transport has none (the server's watchdog still applies). */
    fun setReadTimeout(ms: Int)
}

/** A bound listening endpoint. */
interface LanListener : Closeable {
    val port: Int
    /** Blocks for the next connection; null once closed. */
    fun accept(): LanConnection?
}

/**
 * The seam between [LanHttpServerImpl] and the network (docs/design/m5-setup-settings.md §3.5.2): production binds a
 * [ServerSocket] to the one LAN address ([SocketLanTransport]); tests feed `FakeLanTransport` pairs.
 */
fun interface LanTransport {
    /** Binds [address]:[port]; throws [IOException] when the port is taken or the address is gone. */
    @Throws(IOException::class)
    fun listen(address: String, port: Int): LanListener
}

/** [ServerSocket] on exactly one IPv4 (never 0.0.0.0, §3.5.2). The address is a literal, so no DNS lookup happens. */
class SocketLanTransport(private val backlog: Int = 8) : LanTransport {
    override fun listen(address: String, port: Int): LanListener {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(address), port), backlog)
        } catch (e: IOException) {
            runCatching { server.close() }
            throw e
        }
        return object : LanListener {
            override val port: Int get() = server.localPort
            override fun accept(): LanConnection? = try {
                SocketConnection(server.accept())
            } catch (e: IOException) {
                if (server.isClosed) null else throw e
            }
            override fun close() = server.close()
        }
    }

    private class SocketConnection(private val socket: Socket) : LanConnection {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()
        override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "?"
        override fun setReadTimeout(ms: Int) { socket.soTimeout = ms }
        override fun close() = socket.close()
    }
}
