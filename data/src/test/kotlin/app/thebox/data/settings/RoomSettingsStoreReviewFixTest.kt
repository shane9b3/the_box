package app.thebox.data.settings

import app.thebox.core.parental.ParentalDomain
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.repo.writeTransaction
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D-S4 (M5 code review 2026-10-16, §3.10.4 step 5): the store publishes restored documents only after the commit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSettingsStoreReviewFixTest {
    private lateinit var db: LibraryDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val doc: JsonElement = Json.parseToJsonElement("""{"autoLockAdult":false}""")

    @Before fun setUp() { db = TestDb.create() }

    @After fun tearDown() { scope.cancel(); db.close() }

    private suspend fun store() = RoomSettingsStore(db, db.appSettingDao(), scope, Dispatchers.IO, FakeClock()).also { it.load(); it.awaitReady() }

    /** D-S4 (adopted from the data reviewer): a rolled-back restore leaves nothing of the backup in memory. */
    @Test
    fun aRolledBackRestoreLeavesNoSettingsInMemory() = runBlocking {
        val store = store()
        assertTrue(store.flow(ParentalDomain).value.autoLockAdult)
        runCatching {
            db.writeTransaction {
                store.importInTransaction(mapOf("parental" to doc), replace = false)
                throw IllegalStateException("a later restore step failed")
            }
        }
        store.commitImport(committed = false)
        assertEquals(null, db.appSettingDao().get("parental"))
        assertTrue("rolled back in the database but published in memory", store.flow(ParentalDomain).value.autoLockAdult)
    }

    /** D-S4: a committed restore is published once commitImport says so. */
    @Test
    fun aCommittedRestoreIsPublishedAfterTheCommit() = runBlocking {
        val store = store()
        db.writeTransaction { store.importInTransaction(mapOf("parental" to doc), replace = false) }
        assertTrue("not before commitImport", store.flow(ParentalDomain).value.autoLockAdult)
        store.commitImport()
        assertFalse(store.flow(ParentalDomain).value.autoLockAdult)
    }
}
