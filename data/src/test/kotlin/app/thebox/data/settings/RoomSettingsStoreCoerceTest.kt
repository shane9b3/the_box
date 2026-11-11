package app.thebox.data.settings

import app.thebox.core.parental.LockedInGuide
import app.thebox.core.parental.ParentalDomain
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.StayUnlocked
import app.thebox.core.settings.Domains
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.AppSettingEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Stacked commit 0, docs/design/m6-score-bug-stacked-theme.md §1.3, S7) The store's `coerceInputValues`: an enum name
 * this build does not know (a newer build's `nhlStyle`, a corner that does not exist) reads as **that field's default**
 * and every other field of the document survives, on both paths that used to lose the whole document:
 * - `decode` (a stored row, and an import into a slot not registered yet, which stores the raw JSON and decodes later);
 * - `importInTransaction` into a **registered** slot (`importSlotLocked`, which used to skip the document as "kept").
 * The limits are pinned too: a `null` for a non-null field coerces; an unknown map key still fails the document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSettingsStoreCoerceTest {
    private lateinit var db: LibraryDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() { db = TestDb.create() }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun store() = RoomSettingsStore(db, db.appSettingDao(), scope, Dispatchers.IO, FakeClock()).also { it.debounceMs = 20 }

    /** A newer build's style; every other field is set away from its default, so a reset would show. */
    private val futureStyle = """{"enabled":false,"corner":"BOTTOM_LEFT","delaySec":30,"size":"LARGE","league":"NFL","nhlStyle":"FUTURE"}"""
    private val expectFutureStyle = SportsPrefs(
        enabled = false, corner = ScoreCorner.BOTTOM_LEFT, delaySec = 30, size = ScoreBugSize.LARGE, league = League.NFL,
        nhlStyle = NhlBugStyle.PILL,
    )

    /** A corner this build does not know, next to a real (non-default) style. */
    private val unknownCorner = """{"corner":"MIDDLE","delaySec":60,"size":"LARGE","nhlStyle":"STACKED"}"""
    private val expectUnknownCorner = SportsPrefs(
        corner = ScoreCorner.TOP_RIGHT, delaySec = 60, size = ScoreBugSize.LARGE, nhlStyle = NhlBugStyle.STACKED,
    )

    private val cases = listOf(futureStyle to expectFutureStyle, unknownCorner to expectUnknownCorner)

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    // ---------------------------------------------------------------- decode (a stored row)

    @Test fun decodeKeepsTheOtherFieldsOfAStoredDocument() {
        for ((doc, expected) in cases) {
            val s = store()
            runBlocking {
                db.appSettingDao().upsert(AppSettingEntity(Domains.SPORTS, doc, 1, 1))
                s.load(); s.awaitReady()
            }
            assertEquals(doc, expected, s.flow(SportsDomain).value)
        }
    }

    // ---------------------------------------------------------------- import into a registered slot

    @Test fun importIntoARegisteredSlotKeepsTheOtherFields() {
        for ((doc, expected) in cases) {
            val s = store()
            runBlocking { s.load(); s.awaitReady() }
            s.update(SportsDomain) { it.copy(delaySec = 90) } // registered, and not what the backup says
            runBlocking {
                s.importInTransaction(mapOf(Domains.SPORTS to json(doc)), replace = false)
                s.commitImport()
            }
            assertEquals(doc, expected, s.flow(SportsDomain).value)
        }
    }

    // ---------------------------------------------------------------- import into a slot not registered yet

    @Test fun importIntoAnUnregisteredSlotKeepsTheOtherFieldsWhenItRegisters() {
        for ((doc, expected) in cases) {
            val s = store()
            runBlocking {
                s.load(); s.awaitReady()
                // `sports` is not among the domains registered at start: the raw JSON is stored and decoded on first use.
                s.importInTransaction(mapOf(Domains.SPORTS to json(doc)), replace = false)
                s.commitImport()
            }
            assertEquals(doc, expected, s.flow(SportsDomain).value)
        }
    }

    // ---------------------------------------------------------------- the parental policy is not coerced (review C N9)

    /** What the device holds: stricter than every default. */
    private val deviceParental = ParentalPolicy(
        autoLockAdult = true, lockedInGuide = LockedInGuide.HIDE, lockSettings = true, stayUnlocked = StayUnlocked.ASK_EVERY_TIME,
    )
    private val deviceParentalJson = """{"lockedInGuide":"HIDE","lockSettings":true,"stayUnlocked":"ASK_EVERY_TIME"}"""
    /** A newer build's backup: a value this build does not know. Coerced, it would read SHOW_WITH_LOCK / UNTIL_APP_CLOSES. */
    private val futureParental = listOf(
        """{"lockedInGuide":"BLUR","lockSettings":true,"stayUnlocked":"ASK_EVERY_TIME"}""",
        """{"lockedInGuide":"HIDE","lockSettings":true,"stayUnlocked":"TEN_MINUTES"}""",
    )

    private fun storeWithDeviceParental(): RoomSettingsStore = runBlocking {
        db.appSettingDao().upsert(AppSettingEntity(Domains.PARENTAL, deviceParentalJson, 1, 1))
        store().also { it.load(); it.awaitReady() }
    }

    private fun storedParental(): String? = runBlocking { db.appSettingDao().all().firstOrNull { it.domain == Domains.PARENTAL }?.json }

    @Test fun aParentalDocumentWithAnUnknownValueIsKeptWholeOnARestoreIntoARegisteredSlot() {
        for (doc in futureParental) for (replace in listOf(false, true)) {
            val s = storeWithDeviceParental()
            assertEquals(deviceParental, s.flow(ParentalDomain).value) // registered
            runBlocking {
                s.importInTransaction(mapOf(Domains.PARENTAL to json(doc)), replace = replace)
                s.commitImport()
            }
            assertEquals("$doc replace=$replace", deviceParental, s.flow(ParentalDomain).value)
            assertEquals("$doc replace=$replace: the row", deviceParentalJson, storedParental())
            db.close(); db = TestDb.create()
        }
    }

    @Test fun aParentalDocumentWithAnUnknownValueIsKeptWholeOnARestoreBeforeItsSlotRegisters() {
        for (doc in futureParental) for (replace in listOf(false, true)) {
            val s = storeWithDeviceParental()
            runBlocking {
                // `parental` is not among the domains registered at start: this is the raw-JSON path.
                s.importInTransaction(mapOf(Domains.PARENTAL to json(doc)), replace = replace)
                s.commitImport()
            }
            assertEquals("$doc replace=$replace: the row", deviceParentalJson, storedParental())
            assertEquals("$doc replace=$replace", deviceParental, s.flow(ParentalDomain).value)
            db.close(); db = TestDb.create()
        }
    }

    @Test fun aReadableParentalDocumentStillRestoresOnBothPaths() {
        val doc = """{"autoLockAdult":false,"lockedInGuide":"HIDE"}"""
        val expected = ParentalPolicy(autoLockAdult = false, lockedInGuide = LockedInGuide.HIDE)
        for (registered in listOf(true, false)) {
            val s = storeWithDeviceParental()
            if (registered) s.flow(ParentalDomain)
            runBlocking {
                s.importInTransaction(mapOf(Domains.PARENTAL to json(doc)), replace = false)
                s.commitImport()
            }
            assertEquals("registered=$registered", expected, s.flow(ParentalDomain).value)
            db.close(); db = TestDb.create()
        }
    }

    // ---------------------------------------------------------------- the stated limits

    @Test fun aNullForANonNullFieldReadsAsItsDefault() = runBlocking {
        db.appSettingDao().upsert(AppSettingEntity(Domains.SPORTS, """{"delaySec":null,"corner":"BOTTOM_RIGHT"}""", 1, 1))
        val s = store()
        s.load(); s.awaitReady()
        assertEquals(SportsPrefs(corner = ScoreCorner.BOTTOM_RIGHT), s.flow(SportsDomain).value)
    }

    @Test fun anUnknownMapKeyStillFailsTheWholeDocument() = runBlocking {
        // `SportsFollowDoc.meters` is keyed by League; coercion does not reach map keys (stated limit, §1.3).
        db.appSettingDao().upsert(
            AppSettingEntity(Domains.SPORTS_FOLLOW, """{"firstFollowShown":true,"meters":{"MLB":{"endpoint":"x"}}}""", 1, 1),
        )
        val s = store()
        s.load(); s.awaitReady()
        assertEquals(SportsFollowDoc(), s.flow(SportsFollowDomain).value)
    }
}
