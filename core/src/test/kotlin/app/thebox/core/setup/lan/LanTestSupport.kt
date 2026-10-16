package app.thebox.core.setup.lan

import app.thebox.core.settings.fakes.FakeLanTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [LanTransport] over commit 0's [FakeLanTransport] pairs: [connect] is one phone connection. */
class FakeTransportAdapter(private val fake: FakeLanTransport = FakeLanTransport()) : LanTransport {
    private val queue = LinkedBlockingQueue<Any>()
    private val poison = Any()
    var takenPorts: Set<Int> = emptySet()
    val boundPorts = ArrayList<Int>()

    override fun listen(address: String, port: Int): LanListener {
        if (port in takenPorts) throw IOException("port taken")
        boundPorts += port
        return object : LanListener {
            override val port: Int = port
            override fun accept(): LanConnection? {
                val e = queue.take()
                if (e === poison) return null
                val end = e as FakeLanTransport.End
                return object : LanConnection {
                    override val input = end.input
                    override val output = end.output
                    override val remoteAddress = end.remoteAddress
                    override fun setReadTimeout(ms: Int) = Unit
                    override fun close() = end.close()
                }
            }
            override fun close() { queue.put(poison) }
        }
    }

    fun connect(ip: String = PHONE_IP): FakeLanTransport.End {
        val pair = fake.connect(ip)
        queue.put(pair.server)
        return pair.client
    }

    companion object {
        const val PHONE_IP = "192.168.1.34"
    }
}

data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun cookie(): String? = headers["set-cookie"]?.substringBefore(';')?.substringAfter("tb=")
}

/** Sends [raw] (plus [body]) on one connection and reads the response until the server closes. */
fun FakeTransportAdapter.exchange(raw: String, body: ByteArray = ByteArray(0), ip: String = FakeTransportAdapter.PHONE_IP): RawResponse {
    val c = connect(ip)
    val writer = Thread {
        try {
            c.output.write(raw.toByteArray(Charsets.ISO_8859_1))
            c.output.write(body)
            c.output.flush()
        } catch (_: IOException) {
            // the server may answer and close before reading everything (413, 411)
        }
    }
    writer.start()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = try { c.input.read(buf) } catch (_: IOException) { -1 }
        if (n < 0) break
        out.write(buf, 0, n)
    }
    writer.join(2_000)
    return parseResponse(out.toByteArray())
}

fun parseResponse(bytes: ByteArray): RawResponse {
    val text = String(bytes, Charsets.ISO_8859_1)
    val end = text.indexOf("\r\n\r\n")
    require(end > 0) { "no response head in ${bytes.size} bytes" }
    val lines = text.substring(0, end).split("\r\n")
    val status = lines[0].split(' ')[1].toInt()
    val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
    return RawResponse(status, headers, String(bytes, end + 4, bytes.size - end - 4, Charsets.UTF_8))
}

fun get(path: String, host: String, cookie: String? = null): String =
    "GET $path HTTP/1.1\r\nHost: $host\r\n" + (cookie?.let { "Cookie: tb=$it\r\n" } ?: "") + "\r\n"

fun post(path: String, host: String, body: String, cookie: String? = null, xTheBox: Boolean = true, origin: String? = null): String =
    "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n" +
        (if (xTheBox) "X-TheBox: 1\r\n" else "") + (origin?.let { "Origin: $it\r\n" } ?: "") +
        (cookie?.let { "Cookie: tb=$it\r\n" } ?: "") + "\r\n" + body

fun <T> awaitValue(timeoutMs: Long = 3_000, probe: () -> T?): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        probe()?.let { return it }
        Thread.sleep(5)
    }
    error("condition not met within $timeoutMs ms")
}
