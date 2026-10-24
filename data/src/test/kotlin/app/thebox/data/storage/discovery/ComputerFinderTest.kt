package app.thebox.data.storage.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.FindState
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.discovery.LanSubnet
import app.thebox.core.storage.discovery.Sighting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A socket that never touches the network: [open] hosts accept, [hang] hosts block until closed, others refuse. */
internal class FakeLanSockets(
    private val open: Set<String> = emptySet(),
    private val hang: (String) -> Boolean = { false },
    private val connectDelayMs: Long = 0,
) : LanSockets {
    val targets: MutableList<String> = Collections.synchronizedList(ArrayList())
    val created = AtomicInteger()
    val closed = AtomicInteger()
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val ports: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    override fun stream(): Socket {
        created.incrementAndGet()
        return object : Socket() {
            private val closedLatch = CountDownLatch(1)
            @Volatile private var isClosedFlag = false

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val a = endpoint as InetSocketAddress
                val host = a.address.hostAddress!!
                targets += host
                ports += a.port
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { x, y -> maxOf(x, y) }
                try {
                    if (isClosedFlag) throw SocketException("closed")
                    if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
                    when {
                        hang(host) -> {
                            closedLatch.await(30, TimeUnit.SECONDS)
                            throw SocketException("Socket closed")
                        }
                        host in open -> Unit
                        else -> throw ConnectException("refused")
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }

            override fun close() {
                if (!isClosedFlag) { isClosedFlag = true; closed.incrementAndGet() }
                closedLatch.countDown()
            }

            override fun isClosed(): Boolean = isClosedFlag
        }
    }

    override fun datagram(): DatagramSocket = throw java.io.IOException("no UDP in tests")
}

/** The NsdManager seam, scripted by the test; callbacks are driven by hand. */
internal class FakeNsdApi(override val watchSupported: Boolean = false) : NsdApi {
    val discovered = ArrayList<Pair<String, NsdManager.DiscoveryListener>>()
    val stopped = ArrayList<NsdManager.DiscoveryListener>()
    val resolves = ArrayList<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
    val watches = ArrayList<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()
    val unwatched = ArrayList<Any>()
    val addressesOf = HashMap<String, List<InetAddress>>()
    var throwOnStopUnstarted = true
    var refuseDiscover = false

    override fun discover(type: String, listener: NsdManager.DiscoveryListener) {
        if (refuseDiscover) throw IllegalArgumentException("listener already in use")
        discovered += type to listener
    }

    override fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        stopped += listener
    }

    override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        resolves += info to listener
    }

    override fun watch(info: NsdServiceInfo, executor: Executor, onUpdated: (NsdServiceInfo) -> Unit): Any {
        val token = Any()
        watches += info to onUpdated
        return token
    }

    override fun unwatch(token: Any) { unwatched += token }

    override fun addresses(info: NsdServiceInfo): List<InetAddress> = addressesOf[info.serviceName].orEmpty()

    fun listener(type: String) = discovered.last { it.first == type }.second
}

private val direct = Executor { it.run() }

private fun info(name: String, type: String = NsdFinder.SMB_TYPE, port: Int = 445, model: String? = null) = NsdServiceInfo().apply {
    serviceName = name; serviceType = type; this.port = port
    if (model != null) setAttribute("model", model)
}

private fun v4(s: String): InetAddress = InetAddress.getByName(s)   // literal: no DNS

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NsdFinderQuirksTest {
    private fun session(api: FakeNsdApi, out: MutableList<Sighting> = ArrayList()) = NsdFinder.Session(api, direct) { out += it }

    @Test fun newListenerPerSearch() {
        val api = FakeNsdApi()
        session(api).start()
        session(api).start()
        val smbListeners = api.discovered.filter { it.first == NsdFinder.SMB_TYPE }.map { it.second }
        assertEquals(2, smbListeners.size)
        assertTrue(smbListeners[0] !== smbListeners[1])
        assertEquals(setOf(NsdFinder.SMB_TYPE, NsdFinder.DEVICE_INFO_TYPE), api.discovered.map { it.first }.toSet())
    }

    @Test fun noStopBeforeStartedAndLateStartIsStoppedOnArrival() {
        val api = FakeNsdApi()
        val s = session(api)
        s.start()
        s.stop()                                                   // before onDiscoveryStarted
        assertTrue(api.stopped.isEmpty())
        api.listener(NsdFinder.SMB_TYPE).onDiscoveryStarted(NsdFinder.SMB_TYPE)
        assertEquals(listOf(api.listener(NsdFinder.SMB_TYPE)), api.stopped)
        api.listener(NsdFinder.SMB_TYPE).onDiscoveryStarted(NsdFinder.SMB_TYPE)   // a duplicate never stops twice
        assertEquals(1, api.stopped.size)
    }

    @Test fun startedThenStopStops() {
        val api = FakeNsdApi()
        val s = session(api)
        s.start()
        api.listener(NsdFinder.SMB_TYPE).onDiscoveryStarted(NsdFinder.SMB_TYPE)
        api.listener(NsdFinder.DEVICE_INFO_TYPE).onDiscoveryStarted(NsdFinder.DEVICE_INFO_TYPE)
        s.stop()
        assertEquals(2, api.stopped.size)
        s.stop()                                                   // idempotent
        assertEquals(2, api.stopped.size)
    }

    @Test fun failedStartIsNeverStopped() {
        val api = FakeNsdApi()
        val s = session(api)
        s.start()
        api.listener(NsdFinder.SMB_TYPE).onStartDiscoveryFailed(NsdFinder.SMB_TYPE, NsdManager.FAILURE_MAX_LIMIT)
        api.listener(NsdFinder.DEVICE_INFO_TYPE).onDiscoveryStarted(NsdFinder.DEVICE_INFO_TYPE)
        s.stop()
        assertEquals(listOf(api.listener(NsdFinder.DEVICE_INFO_TYPE)), api.stopped)
        assertEquals(NsdFinder.DiscoveryState.FAILED, s.smb.state)
    }

    @Test fun refusedDiscoverIsFailedAndNotStopped() {
        val api = FakeNsdApi().apply { refuseDiscover = true }
        val s = session(api)
        s.start()
        s.stop()
        assertTrue(api.stopped.isEmpty())
        assertEquals(NsdFinder.DiscoveryState.FAILED, s.smb.state)
    }

    @Test fun resolvesOneAtATimeBelowApi34SmbFirst() {
        val api = FakeNsdApi(watchSupported = false)
        val out = ArrayList<Sighting>()
        val s = session(api, out)
        s.start()
        val smb = api.listener(NsdFinder.SMB_TYPE)
        val dev = api.listener(NsdFinder.DEVICE_INFO_TYPE)
        api.addressesOf["NAS"] = listOf(v4("192.168.1.20"), InetAddress.getByName("fe80::1"))
        api.addressesOf["Mac"] = listOf(v4("192.168.1.21"))
        dev.onServiceFound(info("Mac", NsdFinder.DEVICE_INFO_TYPE, 0, "MacBookPro18,1"))
        smb.onServiceFound(info("NAS", port = 4445))
        smb.onServiceFound(info("Mac"))
        smb.onServiceFound(info("NAS", port = 4445))               // duplicate: ignored
        assertEquals(1, api.resolves.size)                         // the device-info one went first (queue was empty)
        assertEquals("Mac", api.resolves[0].first.serviceName)
        api.resolves[0].second.onServiceResolved(info("Mac", NsdFinder.DEVICE_INFO_TYPE, 0, "MacBookPro18,1"))
        assertEquals(2, api.resolves.size)
        assertEquals("NAS", api.resolves[1].first.serviceName)     // SMB queue before device-info queue
        api.resolves[1].second.onResolveFailed(info("NAS"), NsdManager.FAILURE_ALREADY_ACTIVE)
        assertEquals(3, api.resolves.size)
        assertEquals("Mac", api.resolves[2].first.serviceName)
        api.resolves[2].second.onServiceResolved(info("Mac"))
        assertEquals("NAS", api.resolves[3].first.serviceName)     // the retry
        api.resolves[3].second.onServiceResolved(info("NAS", port = 4445))
        assertEquals(4, api.resolves.size)
        assertEquals(
            listOf(
                Sighting("192.168.1.21", port = null, apple = true, hintOnly = true),
                Sighting("192.168.1.21", "Mac", NameSource.NSD, 445),
                Sighting("192.168.1.20", "NAS", NameSource.NSD, 4445),
            ),
            out,
        )
    }

    @Test fun alreadyActiveRetriesAreBounded() {
        val api = FakeNsdApi()
        val s = session(api)
        s.start()
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("NAS"))
        repeat(5) { api.resolves.last().second.onResolveFailed(info("NAS"), NsdManager.FAILURE_ALREADY_ACTIVE) }
        assertEquals(3, api.resolves.size)                         // first try + 2 retries
    }

    @Test fun lateResolveAfterStopIsDropped() {
        val api = FakeNsdApi()
        val out = ArrayList<Sighting>()
        val s = session(api, out)
        s.start()
        api.addressesOf["NAS"] = listOf(v4("10.0.0.2"))
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("NAS"))
        s.stop()
        api.resolves[0].second.onServiceResolved(info("NAS"))
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("Other"))
        assertTrue(out.isEmpty())
        assertEquals(1, api.resolves.size)
    }

    @Test fun api34UsesServiceInfoCallbacksAndUnregistersOnStop() {
        val api = FakeNsdApi(watchSupported = true)
        val out = ArrayList<Sighting>()
        val s = session(api, out)
        s.start()
        api.addressesOf["A"] = listOf(v4("10.0.0.5"))
        api.addressesOf["B"] = listOf(v4("10.0.0.6"))
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("A"))
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("B"))
        assertEquals(2, api.watches.size)                          // no serial queue on 34+
        assertTrue(api.resolves.isEmpty())
        api.watches[1].second(info("B"))
        api.watches[0].second(info("A"))
        assertEquals(listOf("10.0.0.6", "10.0.0.5"), out.map { it.address })
        s.stop()
        assertEquals(2, api.unwatched.size)
        api.watches[0].second(info("A"))                           // after stop: dropped
        assertEquals(2, out.size)
    }

    @Test fun nonAppleDeviceInfoAndNoIpv4AreIgnored() {
        val api = FakeNsdApi(watchSupported = true)
        val out = ArrayList<Sighting>()
        val s = session(api, out)
        s.start()
        api.addressesOf["Samba"] = listOf(v4("10.0.0.7"))
        api.addressesOf["V6"] = listOf(InetAddress.getByName("fe80::2"))
        api.listener(NsdFinder.DEVICE_INFO_TYPE).onServiceFound(info("Samba", NsdFinder.DEVICE_INFO_TYPE, 0))
        api.watches[0].second(info("Samba", NsdFinder.DEVICE_INFO_TYPE, 0, "MacSamba"))
        api.listener(NsdFinder.SMB_TYPE).onServiceFound(info("V6"))
        api.watches[1].second(info("V6"))
        assertTrue(out.isEmpty())
    }

    @Test fun flowStopsDiscoveryWhenCancelled() = runBlocking {
        val api = FakeNsdApi()
        val finder = NsdFinder(api) { direct }
        val job = launch(Dispatchers.Unconfined) { finder.sightings().collect { } }
        api.listener(NsdFinder.SMB_TYPE).onDiscoveryStarted(NsdFinder.SMB_TYPE)
        job.cancel()
        job.join()
        assertEquals(listOf(api.listener(NsdFinder.SMB_TYPE)), api.stopped)  // device-info never started: not stopped
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Port445SweepTest {
    @Test fun probesEveryAddressOfTheSlash24OnceOnPort445AndSkipsOwn() = runBlocking {
        val sockets = FakeLanSockets(open = setOf("192.168.1.20", "192.168.1.254"))
        val found = Collections.synchronizedList(ArrayList<String>())
        val stats = Port445Sweep(sockets, Dispatchers.IO.limitedParallelism(32)).sweep(LanSubnet.of("192.168.1.37")!!) { found += it }
        assertEquals(253, stats.probed)
        assertEquals(2, stats.open)
        assertEquals(setOf("192.168.1.20", "192.168.1.254"), found.toSet())
        assertEquals(253, sockets.targets.size)
        assertEquals(253, sockets.targets.toSet().size)
        assertFalse("192.168.1.37" in sockets.targets)
        assertTrue(sockets.targets.all { it.startsWith("192.168.1.") && it != "192.168.1.0" && it != "192.168.1.255" })
        assertEquals(setOf(445), sockets.ports)
        assertEquals(sockets.created.get(), sockets.closed.get())    // every socket closed, open or not
    }

    @Test fun neverMoreThanSweepParallelAtOnce() = runBlocking {
        val sockets = FakeLanSockets(connectDelayMs = 20)
        Port445Sweep(sockets, Dispatchers.IO.limitedParallelism(64), parallel = 32).sweep(LanSubnet.of("10.0.0.1")!!) { }
        assertTrue("max in flight ${sockets.maxInFlight.get()}", sockets.maxInFlight.get() <= 32)
        assertTrue(sockets.maxInFlight.get() >= 2)
    }

    @Test fun cancelClosesEveryPendingSocketAtOnce() = runBlocking {
        val sockets = FakeLanSockets(hang = { true })
        val sweep = Port445Sweep(sockets, Dispatchers.IO.limitedParallelism(32), connectTimeoutMs = 30_000)
        val job = launch(Dispatchers.Default) { sweep.sweep(LanSubnet.of("10.0.0.1")!!) { } }
        withTimeout(5_000) { while (sockets.targets.size < 32) kotlinx.coroutines.delay(5) }
        assertEquals(32, sweep.pendingCount)
        val t0 = System.nanoTime()
        job.cancel()
        job.join()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stop took $ms ms", ms < 500)
        assertEquals(0, sweep.pendingCount)
        assertEquals(32, sockets.targets.size)                      // nothing new opened after the stop
        assertEquals(sockets.created.get(), sockets.closed.get())
    }

    @Test fun stopBeforeSweepProbesNothing() = runBlocking {
        val sockets = FakeLanSockets()
        val sweep = Port445Sweep(sockets, Dispatchers.IO)
        sweep.stop()
        val stats = sweep.sweep(LanSubnet.of("10.0.0.1")!!) { }
        assertEquals(0, stats.open)
        assertTrue(sockets.targets.isEmpty())
    }
}

/** A scripted platform for [ComputerFinderImpl]. */
internal class FakeFinderEnvironment(
    var lan: String? = "192.168.1.37",
    var vpn: Boolean = false,
    var network: Boolean = true,
    val sockets: FakeLanSockets = FakeLanSockets(),
    val nsdFlow: Flow<Sighting> = emptyFlow(),
    /** C-S5: false = the platform has no bindable non-VPN network for our address (`sockets` answers null). */
    var bindable: Boolean = true,
) : FinderEnvironment {
    val lockAcquired = AtomicInteger()
    val lockReleased = AtomicInteger()
    val socketsFor = ArrayList<String>()
    override fun lanAddress() = lan
    override fun vpnUp() = vpn
    override fun networkUp() = network
    override fun acquireMulticastLock(): AutoCloseable { lockAcquired.incrementAndGet(); return AutoCloseable { lockReleased.incrementAndGet() } }
    override fun sockets(lanAddress: String): LanSockets? { socketsFor += lanAddress; return if (bindable) sockets else null }
    override fun nsd(): Flow<Sighting> = nsdFlow
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComputerFinderTest {
    private fun finder(env: FakeFinderEnvironment, saved: List<SmbAddress> = emptyList(), names: NameLookup = NameLookup { null }, totalMs: Long = 2_000, sweepsMade: MutableList<Port445Sweep>? = null) =
        ComputerFinderImpl(env, { saved }, sweeps = { s -> Port445Sweep(s, Dispatchers.IO.limitedParallelism(32)).also { sweepsMade?.add(it) } }, names = { names }, totalMs = totalMs)

    /** Collects into a channel; the job is the page's lifetime. */
    private class Page(val states: Channel<FindState>, val job: kotlinx.coroutines.Job)

    private val pages = ArrayList<Page>()

    private fun kotlinx.coroutines.CoroutineScope.open(f: ComputerFinderImpl): Page {
        val ch = Channel<FindState>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { f.search().collect { ch.send(it) } }
        return Page(ch, job).also { pages += it }
    }

    /** runBlocking that closes every page even when an assertion fails (the flow never completes on its own). */
    private fun pageTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        try { block() } finally { pages.forEach { it.job.cancel() } }
    }

    private suspend fun Page.until(p: (FindState) -> Boolean): FindState = withTimeout(10_000) { states.receiveAsFlow().first(p) }

    @Test fun lockIsHeldForTheWholeCollectionNotJustTheSearch() = pageTest {
        val env = FakeFinderEnvironment()
        val page = open(finder(env, totalMs = 300))
        page.until { !it.searching }
        kotlinx.coroutines.delay(600)                               // finders are done (300 ms) …
        assertEquals(1, env.lockAcquired.get())
        assertEquals(0, env.lockReleased.get())                     // … the page is open: still held
        assertTrue(page.job.isActive)                               // the flow does not complete by itself
        page.job.cancel(); page.job.join()
        assertEquals(1, env.lockReleased.get())
    }

    @Test fun sweepUsesTheLanAddressAndItsSlash24() = pageTest {
        val env = FakeFinderEnvironment(lan = "10.20.30.40", sockets = FakeLanSockets(open = setOf("10.20.30.5")))
        val page = open(finder(env))
        val s = page.until { !it.searching }
        assertEquals(listOf("10.20.30.40"), env.socketsFor)
        assertEquals("10.20.30.x", s.scannedNetwork)
        assertEquals(listOf("10.20.30.5"), s.computers.map { it.address })
        assertTrue(env.sockets.targets.all { it.startsWith("10.20.30.") })
        assertFalse("10.20.30.40" in env.sockets.targets)
        page.job.cancel()
    }

    @Test fun noLanAddressMeansNoSweepButVpnRow() = pageTest {
        // LanAddressFinder never returns a VPN address; with only a VPN it returns null.
        val env = FakeFinderEnvironment(lan = null, vpn = true, network = true)
        val sweeps = ArrayList<Port445Sweep>()
        val page = open(finder(env, totalMs = 300, sweepsMade = sweeps))
        val first = page.until { true }
        assertTrue(first.vpnUp)
        assertNull(first.scannedNetwork)
        assertNull(first.error)
        page.until { !it.searching }
        assertTrue(sweeps.isEmpty())
        assertTrue(env.socketsFor.isEmpty())
        assertEquals(0, env.sockets.created.get())
        page.job.cancel()
    }

    /** C-S5: with a VPN up and no bindable LAN network, no SYN or NBNS packet may go out (it would ride the tunnel). */
    @Test fun withAVpnAndNoBindableNetworkTheSweepDoesNotRun() = pageTest {
        val env = FakeFinderEnvironment(lan = "192.168.1.37", vpn = true, bindable = false, sockets = FakeLanSockets(open = setOf("192.168.1.20")))
        val sweeps = ArrayList<Port445Sweep>()
        val page = open(finder(env, totalMs = 300, sweepsMade = sweeps))
        val s = page.until { !it.searching }
        assertTrue(s.vpnUp)
        assertTrue(sweeps.isEmpty())
        assertEquals(0, env.sockets.created.get())
        assertTrue(s.computers.isEmpty())
        page.job.cancel()
    }

    @Test fun publicAddressIsNeverSwept() = pageTest {
        val env = FakeFinderEnvironment(lan = "8.8.8.8")
        val page = open(finder(env, totalMs = 300))
        page.until { !it.searching }
        assertEquals(0, env.sockets.created.get())
        page.job.cancel()
    }

    @Test fun noNetworkIsAnError() = pageTest {
        val env = FakeFinderEnvironment(lan = null, network = false)
        val page = open(finder(env))
        val s = page.until { true }
        assertEquals(BrowseErrorKind.NO_NETWORK, s.error?.kind)
        assertFalse(s.searching)
        assertEquals(1, env.lockAcquired.get())
        page.job.cancel(); page.job.join()
        assertEquals(1, env.lockReleased.get())
    }

    @Test fun mergesNsdSweepAndNamesAndMarksSaved() = pageTest {
        val nsd = MutableSharedFlow<Sighting>(replay = 8)
        nsd.emit(Sighting("192.168.1.20", "NAS", NameSource.NSD, 445))
        val env = FakeFinderEnvironment(sockets = FakeLanSockets(open = setOf("192.168.1.20", "192.168.1.30", "192.168.1.40")), nsdFlow = nsd)
        // NetBIOS answers for .20 too; whichever arrives first, the NSD name wins.
        val names = NameLookup { a ->
            when (a) {
                "192.168.1.30" -> "DESKTOP-7F3K2LQ" to NameSource.NETBIOS
                "192.168.1.20" -> "NAS-NETBIOS" to NameSource.NETBIOS
                else -> null
            }
        }
        val f = finder(env, saved = listOf(SmbAddress(host = "192.168.1.40", share = "TV")), names = names)
        val page = open(f)
        val s = page.until { st -> st.computers.size == 3 && st.computers.any { it.name == "DESKTOP-7F3K2LQ" } && st.computers.any { it.name == "NAS" } && !st.searching }
        assertEquals(listOf("192.168.1.40", "192.168.1.30", "192.168.1.20"), s.computers.map { it.address })
        assertTrue(s.computers[0].saved)
        assertEquals(listOf(false, false), s.computers.drop(1).map { it.saved })
        assertEquals(NameSource.NONE, s.computers[0].source)
        assertNull(s.computers[0].name)
        assertEquals(NameSource.NSD, s.computers[2].source)
        assertEquals("192.168.1.20", f.recentAddress("nas.local"))
        assertNull(f.recentAddress("desktop-7f3k2lq"))              // only NSD names are remembered
        page.job.cancel()
    }

    @Test fun searchingEndsWhenTheSweepEndsAndFinalStateAtTotalTime() = pageTest {
        val nsd = Channel<Sighting>(Channel.UNLIMITED)
        val env = FakeFinderEnvironment(nsdFlow = nsd.receiveAsFlow())
        val page = open(finder(env, totalMs = 1_000))
        page.until { !it.searching }                                // sweep of refusing hosts is quick
        nsd.send(Sighting("192.168.1.99", "Late", NameSource.NSD))  // NSD keeps running until total time
        page.until { st -> st.computers.any { it.name == "Late" } }
        kotlinx.coroutines.delay(1_200)
        nsd.send(Sighting("192.168.1.98", "TooLate", NameSource.NSD))
        kotlinx.coroutines.delay(200)
        var last: FindState? = null
        while (true) last = page.states.tryReceive().getOrNull() ?: break
        assertTrue(last == null || last.computers.none { it.name == "TooLate" })
        page.job.cancel()
    }

    @Test fun cancellingThePageStopsAHangingSweepAtOnce() = pageTest {
        val env = FakeFinderEnvironment(lan = "10.0.0.1", sockets = FakeLanSockets(hang = { true }))
        val sweeps = ArrayList<Port445Sweep>()
        val f = ComputerFinderImpl(env, { emptyList() }, sweeps = { s -> Port445Sweep(s, Dispatchers.IO.limitedParallelism(32), connectTimeoutMs = 30_000).also { sweeps += it } }, names = { NameLookup { null } })
        val page = open(f)
        withTimeout(5_000) { while (env.sockets.targets.size < 32) kotlinx.coroutines.delay(5) }
        val t0 = System.nanoTime()
        page.job.cancel(); page.job.join()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stop took $ms ms", ms < 500)
        assertEquals(0, sweeps.single().pendingCount)
        assertEquals(env.sockets.created.get(), env.sockets.closed.get())
        assertEquals(1, env.lockReleased.get())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkNameLookupTest {
    /** A loopback "NBNS server" answering with [answer] (or nothing). */
    private fun server(answer: (ByteArray) -> ByteArray?): Pair<DatagramSocket, Thread> {
        val s = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val t = Thread {
            try {
                val buf = ByteArray(512)
                while (true) {
                    val p = java.net.DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val req = p.data.copyOf(p.length)
                    answer(req)?.let { s.send(java.net.DatagramPacket(it, it.size, p.socketAddress)) }
                }
            } catch (e: Exception) { /* closed */ }
        }.apply { isDaemon = true; start() }
        return s to t
    }

    private val udp = object : LanSockets {
        override fun stream(): Socket = throw java.io.IOException()
        override fun datagram(): DatagramSocket = DatagramSocket()
    }

    private fun response(tid0: Byte, tid1: Byte): ByteArray {
        val name = "NAS".toByteArray().copyOf(15).also { for (i in 3 until 15) it[i] = 0x20 }
        val rr = ByteArray(1) { 1 } + name + byteArrayOf(0, 0x04, 0) + ByteArray(46)
        val enc = app.thebox.core.storage.discovery.NbnsCodec.encodeName(app.thebox.core.storage.discovery.NbnsCodec.wildcardName())
        return byteArrayOf(tid0, tid1, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 32) + enc + byteArrayOf(0, 0, 0x21, 0, 1, 0, 0, 0, 0, 0, rr.size.toByte()) + rr
    }

    @Test fun netbiosAnswerIsUsed() = runBlocking {
        val (srv, _) = server { req -> response(req[0], req[1]) }
        try {
            val l = NetworkNameLookup(udp, nbnsPort = srv.localPort, reverse = { "nope" })
            assertEquals("NAS" to NameSource.NETBIOS, l.lookup("127.0.0.1"))
        } finally { srv.close() }
    }

    @Test fun wrongTransactionIdIsIgnoredThenReverseDns() = runBlocking {
        val (srv, _) = server { req -> response((req[0] + 1).toByte(), req[1]) }
        try {
            val l = NetworkNameLookup(udp, nbnsPort = srv.localPort, nbnsTimeoutMs = 200, reverse = { "nas.lan." })
            assertEquals("nas.lan" to NameSource.REVERSE_DNS, l.lookup("127.0.0.1"))
        } finally { srv.close() }
    }

    @Test fun reverseDnsIsBoundedAndEchoesAreNoName() = runBlocking {
        val (srv, _) = server { null }
        try {
            val slow = NetworkNameLookup(udp, nbnsPort = srv.localPort, nbnsTimeoutMs = 100, dnsTimeoutMs = 100, reverse = { Thread.sleep(2_000); "late" })
            val t0 = System.nanoTime()
            assertNull(slow.lookup("127.0.0.1"))
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
            val echo = NetworkNameLookup(udp, nbnsPort = srv.localPort, nbnsTimeoutMs = 100, reverse = { "127.0.0.1" })
            assertNull(echo.lookup("127.0.0.1"))
        } finally { srv.close() }
    }

    @Test fun cancelClosesTheNbnsSocketAtOnce() = runBlocking {
        val (srv, _) = server { null }
        try {
            val l = NetworkNameLookup(udp, nbnsPort = srv.localPort, nbnsTimeoutMs = 30_000)
            val job = launch(Dispatchers.Default) { l.netbios("127.0.0.1") }
            kotlinx.coroutines.delay(200)
            val t0 = System.nanoTime()
            job.cancel(); job.join()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
        } finally { srv.close() }
    }

    @Test fun nonIpv4IsNoName() = runBlocking {
        assertNull(NetworkNameLookup(udp).lookup("nas.local"))
    }
}
