package app.thebox.core.settings

import app.thebox.core.backup.BACKUP_KDF_ITERATIONS
import app.thebox.core.backup.BackupHeader
import app.thebox.core.backup.BackupRecord
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.input.BoxAction
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyOverride
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.parental.LockedInGuide
import app.thebox.core.parental.PIN_KDF_BUDGET_MS
import app.thebox.core.parental.PIN_KDF_ITERATIONS
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPurpose
import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.parental.legacyUnlocked
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.model.StableKey
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.fakes.SETTINGS_JSON
import app.thebox.core.settings.fakes.SettingsFixtures
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.setup.lan.PhoneSubmission
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5 commit 0 (docs/design/m5-setup-settings.md §2): the pure rules the contracts state, so a WP cannot drift from them
 * (like M4's PlayerContractsTest).
 */
class SettingsContractsTest {

    // ---- §2.6 ParentalState.access (B3/S8): the full truth table

    @Test
    fun accessTruthTable() {
        for (pinSet in listOf(false, true)) for (unlocked in listOf(false, true)) for (auto in listOf(false, true)) for (lost in listOf(false, true)) {
            val s = ParentalState(pinSet, unlocked, ParentalPolicy(autoLockAdult = auto), pinLostToKeyReset = lost)
            val expected = when {
                pinSet && unlocked -> ParentalAccess.OPEN
                pinSet && auto -> ParentalAccess.LOCKED_AUTO
                pinSet -> ParentalAccess.LOCKED_MANUAL_ONLY
                auto || lost -> ParentalAccess.FILTERED_NO_PIN
                else -> ParentalAccess.OPEN
            }
            assertEquals(expected, s.access, "pin=$pinSet unlocked=$unlocked auto=$auto lost=$lost")
            assertEquals(expected == ParentalAccess.FILTERED_NO_PIN, s.unprotected)
        }
    }

    @Test
    fun defaultsAreFilteredWithoutAPinAndAKeyResetOverridesTheToggle() {
        assertEquals(ParentalAccess.FILTERED_NO_PIN, ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy()).access)
        val lost = ParentalState(pinSet = false, unlocked = true, policy = ParentalPolicy(autoLockAdult = false), pinLostToKeyReset = true)
        assertEquals(ParentalAccess.FILTERED_NO_PIN, lost.access, "S8: content stays filtered after a Keystore reset whatever auto-lock says")
    }

    /** §4.3: ordinals are bound into SQL; append only. */
    @Test
    fun accessOrdinalsAreTheSqlValues() {
        assertEquals(listOf("OPEN", "LOCKED_AUTO", "LOCKED_MANUAL_ONLY", "FILTERED_NO_PIN"), ParentalAccess.entries.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), ParentalAccess.entries.map { it.ordinal })
    }

    @Test
    fun queryKeyIsDistinctPerAccessAndHideMode() {
        val keys = HashSet<Int>()
        for (pinSet in listOf(false, true)) for (unlocked in listOf(false, true)) for (auto in listOf(false, true)) for (hide in LockedInGuide.entries) {
            val s = ParentalState(pinSet, unlocked, ParentalPolicy(autoLockAdult = auto, lockedInGuide = hide))
            assertEquals(s.access.ordinal * 2 + if (hide == LockedInGuide.HIDE) 1 else 0, s.queryKey)
            keys += s.queryKey
        }
        assertEquals(8, keys.size, "four access values x two hide modes")
    }

    /** S10, commit 0: the call sites keep M3's behaviour (debug switch → OPEN, else filtered like M3's locked lists). */
    @Test
    fun legacyAccessKeepsM3Behaviour() {
        assertEquals(ParentalAccess.OPEN, legacyParentalAccess(true))
        assertEquals(ParentalAccess.LOCKED_AUTO, legacyParentalAccess(false))
        assertEquals(listOf(true, false, false, false), ParentalAccess.entries.map { it.legacyUnlocked })
    }

    @Test
    fun pinPurposesRouteToTheirOwners() {
        assertEquals(PinOwner.GUIDE, PinPurpose.EnterSettings(SettingsRoute.Root).owner)
        assertEquals(PinOwner.SETTINGS, PinPurpose.SettingsAction(7).owner)
        val ch = app.thebox.core.guide.fakes.GuideFixtures.channel(0)
        assertEquals(PinOwner.PLAYER, PinPurpose.Tune(ch, PinOwner.PLAYER).owner)
    }

    @Test
    fun tuneGuardAllowAllAllows() {
        val id = ChannelIdentity(1, StableKey("xc:1"))
        assertEquals(TuneVerdict.ALLOWED, TuneGuard.ALLOW_ALL.check(id))
    }

    // ---- §2.1 EffectTiming and the tree

    @Test
    fun effectTimingsAreTheDesignList() {
        assertEquals(
            listOf("LIVE", "NEXT_TUNE", "REPREPARE", "NEXT_REFRESH", "NEXT_EPG_IMPORT", "NEXT_LAUNCH", "RESTART", "NONE"),
            EffectTiming.entries.map { it.name },
        )
        assertEquals(EffectTiming.LIVE, SettingsRow(RowId("x"), "X", kind = RowKind.Info(emptyList())).effect, "rows default to LIVE")
    }

    @Test
    fun everyRouteHasItsSection() {
        assertNull(SettingsTree.sectionOf(SettingsRoute.Root))
        assertNull(SettingsTree.sectionOf(SettingsRoute.FirstRun))
        assertEquals(SectionId.NETWORK, SettingsTree.sectionOf(SettingsRoute.Section(SectionId.NETWORK)))
        assertEquals(SectionId.PLAYLISTS, SettingsTree.sectionOf(SettingsRoute.AddSource(firstRun = true)))
        assertEquals(SectionId.GUIDE, SettingsTree.sectionOf(SettingsRoute.FixGuide())) // C-L1: a TV guide row (§3.3.2)
        assertEquals(SectionId.CHANNELS, SettingsTree.sectionOf(SettingsRoute.HideRules(1)))
        assertEquals(SectionId.REMOTE, SettingsTree.sectionOf(SettingsRoute.ActionBindings(BoxAction.AUDIO)))
        assertEquals(SectionId.GUIDE, SettingsTree.sectionOf(SettingsRoute.SyncStatus)) // C-L1: parent TV guide
        assertEquals(SectionId.BACKUP, SettingsTree.sectionOf(SettingsRoute.RestorePreview(3)))
    }

    @Test
    fun registryListsEverySourceOnceAndNoRowTwice() {
        // (Storage locations commit 0) + PauseRewindRows, the Pause and rewind sub-page (m6-storage-locations §9.5).
        assertEquals(15, SettingsTree.sources.size, "one source per *Rows.kt of §1.3a")
        assertEquals(SettingsTree.sources.size, SettingsTree.sources.toSet().size)
        assertEquals(SettingsTree.all.size, SettingsTree.all.map { it.id }.toSet().size)
    }

    @Test
    fun pagedListDefaultPageSize() = assertEquals(32, PagedList(count = 0, loaded = emptyMap()).pageSize)

    // ---- §2.2 store and domains

    @Test
    fun domainNamesAreUnique() {
        val names = listOf(Domains.GUIDE, Domains.PLAYER, Domains.PLAYBACK, Domains.DATA, Domains.NETWORK, Domains.PARENTAL, Domains.REMOTE, Domains.BACKUP, Domains.UPDATE, Domains.SETUP)
        assertEquals(names.size, names.toSet().size)
    }

    /** encodeDefaults = false: a value equal to its default is not stored, so a later default change reaches the user. */
    @Test
    fun defaultsAreNotEncoded() {
        assertEquals("{}", SETTINGS_JSON.encodeToString(GuidePrefs.serializer(), GuidePrefs()))
        assertEquals("{}", SETTINGS_JSON.encodeToString(PlaybackPrefs.serializer(), PlaybackPrefs()))
        assertEquals("{}", SETTINGS_JSON.encodeToString(DataPrefs.serializer(), DataPrefs()))
        assertEquals("{}", SETTINGS_JSON.encodeToString(ParentalPolicy.serializer(), ParentalPolicy()))
        val g = GuidePrefs(clock = ClockMode.H24, advancedSettings = true)
        assertEquals(g, SETTINGS_JSON.decodeFromString(GuidePrefs.serializer(), SETTINGS_JSON.encodeToString(GuidePrefs.serializer(), g)))
        assertEquals(GuidePrefs(), SETTINGS_JSON.decodeFromString(GuidePrefs.serializer(), """{"fromANewerVersion":1}"""), "unknown keys ignored")
    }

    /** §2.12 item 4: PlayerSettings is @Serializable and stored as is. */
    @Test
    fun playerSettingsRoundTrip() {
        val p = PlayerSettings(infoBarMs = 8_000, upDown = UpDownInPlayer.ZAP, invertChannelDirection = true)
        assertEquals(p, SETTINGS_JSON.decodeFromString(PlayerSettings.serializer(), SETTINGS_JSON.encodeToString(PlayerSettings.serializer(), p)))
    }

    @Test
    fun matchLevelIsTheExistingEnum() = assertEquals(app.thebox.core.match.MatchLevel.SUGGEST_FUZZY, DataPrefs().matchLevel)

    @Test
    fun fakeStoreExportsBackedUpDomainsOnly() = runTest {
        val store = FakeSettingsStore()
        val guide = SettingsDomain(Domains.GUIDE, GuidePrefs.serializer(), GuidePrefs())
        val backup = SettingsDomain(Domains.BACKUP, BackupPrefs.serializer(), BackupPrefs(), backedUp = false)
        store.update(guide) { it.copy(showLogos = false) }
        store.register(backup)
        val docs = store.export()
        assertEquals(setOf(Domains.GUIDE), docs.keys)
        store.importInTransaction(mapOf(Domains.GUIDE to JsonObject(emptyMap())), replace = true)
        assertEquals(GuidePrefs(), store.flow(guide).value)
        assertEquals(1_000L, SettingsTimings.SETTINGS_LOAD_TIMEOUT_MS)
    }

    // ---- §2.7 key bindings, §2.12 item 1

    @Test
    fun newActionsAreAppendedAndRemappable() {
        val ids = BoxAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids unique")
        assertEquals(listOf(BoxAction.OPEN_SEARCH, BoxAction.OPEN_SETTINGS), BoxAction.entries.takeLast(2))
        assertTrue(BoxAction.OPEN_SEARCH.remappable && BoxAction.OPEN_SETTINGS.remappable)
    }

    /** F2 values (android-35 stubs, API notes §5.1). */
    @Test
    fun reservedKeyCodes() {
        assertEquals(listOf(176, 24, 25, 164, 91, 3, 26, 177, 223, 224),
            listOf(Keys.SETTINGS, Keys.VOLUME_UP, Keys.VOLUME_DOWN, Keys.VOLUME_MUTE, Keys.MUTE, Keys.HOME, Keys.POWER, Keys.TV_POWER, Keys.SLEEP, Keys.WAKEUP))
    }

    @Test
    fun keyOverridesRoundTripAndKeepUnknownActionIds() {
        val o = KeyBindingOverrides(entries = listOf(KeyOverride(KeyContext.PLAYER, Keys.PROG_RED, Press.LONG, "from_a_newer_build", "Remote", 5), KeyOverride(KeyContext.GUIDE, 186, Press.SHORT, null, null, 6)))
        assertEquals(o, Json.decodeFromString(KeyBindingOverrides.serializer(), Json.encodeToString(KeyBindingOverrides.serializer(), o)))
    }

    // ---- §2.8 backup, §2.5 LAN, KDF constants (S15)

    @Test
    fun backupRecordsUseTheirSerialNames() {
        val json = Json { classDiscriminator = "t" }
        val line = json.encodeToString(BackupRecord.serializer(), BackupRecord.End(3))
        assertEquals("""{"t":"end","records":3}""", line)
        assertEquals(BackupRecord.Settings("guide", JsonObject(emptyMap())), json.decodeFromString(BackupRecord.serializer(), """{"t":"settings","domain":"guide","json":{}}"""))
        assertEquals("thebox-backup", BackupHeader.FORMAT)
    }

    @Test
    fun passphraseNeverPrints() = assertEquals("Passphrase(****)", PhoneSubmission.Passphrase("hunter22".toCharArray()).toString())

    @Test
    fun kdfIterationsAreTheBenchmarkedCounts() {
        assertEquals(10_000, PIN_KDF_ITERATIONS)
        assertEquals(50_000, BACKUP_KDF_ITERATIONS)
        assertEquals(150L, PIN_KDF_BUDGET_MS)
    }

    // ---- §2.12 item 7

    @Test
    fun forcePassthroughIsAppended() = assertEquals(AudioOutputMode.FORCE_PASSTHROUGH, AudioOutputMode.entries.last())

    // ---- fixtures (§1.9 item 8)

    @Test
    fun fixtureLibraryHasTheDesignedShape() {
        assertEquals(2, SettingsFixtures.playlists.size)
        assertEquals(40, SettingsFixtures.groups.size)
        assertEquals(2, SettingsFixtures.groups.count { it.isAdult })
        assertEquals(2_000, SettingsFixtures.channels.size)
        assertEquals(30, SettingsFixtures.hidden.size)
        assertEquals(3, SettingsFixtures.favouriteLists.size)
        assertEquals(SettingsFixtures.playlists.map { it.id }, SettingsFixtures.reminders.map { it.identity.playlistId })
        assertEquals(SettingsFixtures.channels.size, SettingsFixtures.channels.map { it.identity }.toSet().size)
        assertFalse(SettingsFixtures.favouriteLists.flatMap { it.members }.any { id -> SettingsFixtures.channels.first { it.identity == id }.isAdult })
    }
}
