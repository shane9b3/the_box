package app.thebox.data.settings

import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.Domains
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.state.NetworkDomain
import app.thebox.core.settings.state.SetupDomain
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.AppSettingEntity
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `RoomSettingsStore` on Robolectric's SQLite (docs/design/m5-setup-settings.md §2.2, §6.1): defaults,
 * `encodeDefaults = false`, unknown keys, the schema migrate hook, debounce and flush, the ready timeout, and the S1 rows
 * "slow read beyond the timeout, then update → stored document plus the change" and "restore during a pending write
 * keeps the restored value".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSettingsStoreTest {
    private lateinit var db: LibraryDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() { db = TestDb.create() }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun store(debounceMs: Long = 20, timeoutMs: Long = 1_000, beforeRead: suspend () -> Unit = {}) =
        RoomSettingsStore(db, db.appSettingDao(), scope, Dispatchers.IO, FakeClock()).also {
            it.debounceMs = debounceMs
            it.loadTimeoutMs = timeoutMs
            it.beforeRead = beforeRead
        }

    private fun row(domain: String) = runBlocking { db.appSettingDao().get(domain) }

    private fun waitFor(ms: Long = 3_000, cond: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) delay(10)
        assertTrue("condition not met in $ms ms", cond())
    }

    @Test fun emptyTableGivesDefaultsAndReady() = runBlocking {
        val s = store()
        s.load()
        s.awaitReady()
        assertEquals(GuidePrefs(), s.flow(GuideDomain).value)
        assertNull(row(Domains.GUIDE))
    }

    @Test fun onlyNonDefaultFieldsAreStored() = runBlocking {
        val s = store()
        s.load(); s.awaitReady()
        s.update(GuideDomain) { it.copy(clock = ClockMode.H12) }
        s.flush()
        assertEquals("""{"clock":"H12"}""", row(Domains.GUIDE)!!.json)
        s.update(GuideDomain) { it.copy(clock = ClockMode.SYSTEM) }
        s.flush()
        assertEquals("{}", row(Domains.GUIDE)!!.json)
    }

    @Test fun unknownKeysAreIgnoredAndStoredValuesLoad() = runBlocking {
        db.appSettingDao().upsert(AppSettingEntity(Domains.GUIDE, """{"clock":"H24","fromTheFuture":true,"exitConfirmation":"ASK"}""", 1, 1))
        val s = store()
        s.load(); s.awaitReady()
        assertEquals(ClockMode.H24, s.flow(GuideDomain).value.clock)
        assertEquals(ExitConfirmation.ASK, s.flow(GuideDomain).value.exitConfirmation)
    }

    @Serializable data class V2(val name: String = "", val size: Int = 0)

    @Test fun olderSchemaGoesThroughMigrate() = runBlocking {
        val v2 = SettingsDomain("test_v2", V2.serializer(), V2(), schema = 2, migrate = { from, json ->
            if (from < 2) buildJsonObject { put("name", json.jsonObject["title"]!!); put("size", 7) } else json
        })
        db.appSettingDao().upsert(AppSettingEntity("test_v2", """{"title":"old"}""", 1, 1))
        val s = store()
        s.load(); s.awaitReady()
        assertEquals(V2("old", 7), s.flow(v2).value)
        s.update(v2) { it.copy(size = 8) }
        s.flush()
        assertEquals(2, row("test_v2")!!.schemaVer)
    }

    @Test fun writesAreCoalescedAndFlushWritesAtOnce() = runBlocking {
        val s = store(debounceMs = 10_000)
        s.load(); s.awaitReady()
        s.update(NetworkDomain) { it.copy(connectTimeoutSec = 5) }
        s.update(NetworkDomain) { it.copy(readTimeoutSec = 40) }
        delay(100)
        assertNull("nothing written inside the debounce", row(Domains.NETWORK))
        s.flush()
        assertEquals("""{"connectTimeoutSec":5,"readTimeoutSec":40}""", row(Domains.NETWORK)!!.json)
    }

    @Test fun debouncedWriteLands() {
        val s = store(debounceMs = 30)
        s.load()
        runBlocking { s.awaitReady() }
        s.update(GuideDomain) { it.copy(showLogos = false) }
        waitFor { row(Domains.GUIDE)?.json == """{"showLogos":false}""" }
    }

    @Test fun readyTimeoutUnblocksWithDefaults() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val s = store(timeoutMs = 50, beforeRead = { gate.await() })
        s.load()
        s.awaitReady()
        assertEquals(GuidePrefs(), s.flow(GuideDomain).value)
        gate.complete(Unit)
        Unit
    }

    @Test fun slowReadBeyondTheTimeoutThenUpdateGivesTheStoredDocumentPlusTheChange() {
        runBlocking { db.appSettingDao().upsert(AppSettingEntity(Domains.GUIDE, """{"clock":"H12","showNumbers":false}""", 1, 1)) }
        val gate = CompletableDeferred<Unit>()
        val s = store(timeoutMs = 50, beforeRead = { gate.await() })
        s.load()
        runBlocking { s.awaitReady() } // timed out: defaults on screen
        s.update(GuideDomain) { it.copy(showLogos = false) }
        assertFalse("in memory at once", s.flow(GuideDomain).value.showLogos)
        runBlocking { delay(150) }
        assertEquals("S1: nothing written before the read", """{"clock":"H12","showNumbers":false}""", row(Domains.GUIDE)!!.json)
        gate.complete(Unit)
        waitFor { s.flow(GuideDomain).value.clock == ClockMode.H12 }
        val v = s.flow(GuideDomain).value
        assertFalse(v.showLogos); assertFalse(v.showNumbers)
        waitFor { row(Domains.GUIDE)?.json?.let { Json.parseToJsonElement(it) } == Json.parseToJsonElement("""{"clock":"H12","showLogos":false,"showNumbers":false}""") }
    }

    @Test fun restoreDuringAPendingWriteKeepsTheRestoredValue() {
        val s = store(debounceMs = 200)
        s.load()
        runBlocking { s.awaitReady() }
        s.update(GuideDomain) { it.copy(clock = ClockMode.H24) }
        runBlocking {
            s.importInTransaction(mapOf(Domains.GUIDE to JsonObject(mapOf("clock" to JsonPrimitive("H12")))), replace = false)
            s.commitImport() // D-S4: published after the (caller's) commit
            delay(500)
        }
        assertEquals(ClockMode.H12, s.flow(GuideDomain).value.clock)
        assertEquals("""{"clock":"H12"}""", row(Domains.GUIDE)!!.json)
    }

    @Test fun exportCarriesBackedUpDomainsOnlyAndReplaceResetsTheRest() = runBlocking {
        db.appSettingDao().upsert(AppSettingEntity(Domains.REMOTE, """{"entries":[]}""", 1, 1))
        val s = store()
        s.load(); s.awaitReady()
        s.update(GuideDomain) { it.copy(clock = ClockMode.H12) }
        s.update(NetworkDomain) { it.copy(readTimeoutSec = 40) }
        s.update(SetupDomain) { it.copy(completed = true) }
        val out = s.export()
        assertTrue(Domains.GUIDE in out); assertTrue(Domains.NETWORK in out)
        assertTrue("an unregistered backed-up domain is exported as stored", Domains.REMOTE in out)
        assertFalse("setup is device-bound", Domains.SETUP in out)
        s.importInTransaction(mapOf(Domains.GUIDE to JsonObject(mapOf("clock" to JsonPrimitive("H24"))), "nonsense" to JsonObject(emptyMap())), replace = true)
        s.commitImport() // D-S4: published after the (caller's) commit
        assertEquals(ClockMode.H24, s.flow(GuideDomain).value.clock)
        assertEquals("replace resets backed-up domains not in the backup", 30, s.flow(NetworkDomain).value.readTimeoutSec)
        assertTrue("setup is not touched by a restore", s.flow(SetupDomain).value.completed)
        assertNull(row(Domains.NETWORK)); assertNull(row(Domains.REMOTE)); assertNull(row("nonsense"))
    }
}
