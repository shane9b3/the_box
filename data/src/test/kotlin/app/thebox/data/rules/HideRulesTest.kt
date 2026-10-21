package app.thebox.data.rules

import app.thebox.core.text.RegexSafety

import app.thebox.core.refresh.EntryProducer
import app.thebox.core.refresh.MergeContext
import app.thebox.core.settings.tree.RuleDisabledReason
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RulePreview
import app.thebox.core.settings.tree.RuleProblem
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.HideRuleEntity
import app.thebox.data.repo.RoomExistingChannels
import app.thebox.data.repo.RoomMergeSink
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeDiffer
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.pc
import app.thebox.data.testutil.scalar
import app.thebox.data.repo.writeTransaction
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m5-setup-settings.md §3.7.3, §6.1 WP-D `HideRulesTest` (Robolectric DB). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HideRulesTest {
    private lateinit var db: LibraryDatabase
    private lateinit var rules: HideRulesImpl
    private var p = 0L

    @Before fun setUp() = runBlocking {
        db = TestDb.create()
        rules = HideRulesImpl(db, ImportMutex(), FakeClock())
        p = db.newPlaylist()
        Unit
    }

    @After fun tearDown() { db.close() }

    private suspend fun seed() {
        val sports = db.newGroup(p, "g:sports", "Sports HD")
        val news = db.newGroup(p, "g:news", "News")
        val locked = db.newGroup(p, "g:adult", "Locked group", adult = true)
        repeat(3) { db.newChannel(p, "s$it", "Sport $it", groupId = sports) }
        repeat(2) { db.newChannel(p, "n$it", "News $it", groupId = news) }
        db.newChannel(p, "n-xxx", "XXX News", groupId = news)
        db.newChannel(p, "a1", "Late", groupId = locked)
    }

    private suspend fun ruleHidden(key: String) = db.scalar("SELECT rule_hidden FROM channel WHERE stable_key = ?", key)
    private suspend fun groupRuleHidden(key: String) = db.scalar("SELECT rule_hidden FROM channel_group WHERE group_key = ?", key)

    @Test
    fun containsPreviewCountsGroupsAndTheirChannels() = runBlocking {
        seed()
        assertEquals(RulePreview.Counts(1, 3), rules.preview(p, RuleDraft(RuleTarget.GROUP_NAME, "sports")))
        assertEquals(RulePreview.Counts(0, 1), rules.preview(p, RuleDraft(RuleTarget.CHANNEL_NAME, "xxx")))
        assertEquals(RulePreview.Counts(0, 0), rules.preview(p, RuleDraft(RuleTarget.CHANNEL_NAME, "nothing")))
    }

    @Test
    fun regexPreview() = runBlocking {
        seed()
        assertEquals(RulePreview.Counts(0, 5), rules.preview(p, RuleDraft(RuleTarget.CHANNEL_NAME, "^(sport|news) \\d$", isRegex = true)))
    }

    @Test
    fun invalidDraftsSayWhy() = runBlocking {
        seed()
        assertEquals(RulePreview.Invalid(RuleProblem.EMPTY), rules.preview(p, RuleDraft(pattern = " ")))
        assertEquals(RulePreview.Invalid(RuleProblem.TOO_LONG), rules.preview(p, RuleDraft(pattern = "a".repeat(RULE_REGEX_MAX_CHARS + 1), isRegex = true)))
        assertEquals(RulePreview.Invalid(RuleProblem.BAD_REGEX), rules.preview(p, RuleDraft(pattern = "(unclosed", isRegex = true)))
        rules.add(p, RuleDraft(RuleTarget.GROUP_NAME, "news"))
        assertEquals(RulePreview.Invalid(RuleProblem.DUPLICATE), rules.preview(p, RuleDraft(RuleTarget.GROUP_NAME, "news")))
    }

    @Test
    fun addRecomputesRuleHiddenAndDeleteRestores() = runBlocking {
        seed()
        val ver0 = db.scalar("SELECT hide_rules_ver FROM playlist WHERE id = ?", p)
        rules.add(p, RuleDraft(RuleTarget.GROUP_NAME, "SPORTS"))
        rules.add(p, RuleDraft(RuleTarget.CHANNEL_NAME, "xxx"))
        assertEquals(1L, groupRuleHidden("g:sports"))
        assertEquals(0L, groupRuleHidden("g:news"))
        assertEquals(1L, ruleHidden("n-xxx"))
        assertEquals(0L, ruleHidden("n0"))
        assertTrue(db.scalar("SELECT hide_rules_ver FROM playlist WHERE id = ?", p) > ver0)
        assertEquals(listOf(3, 1), rules.rules(p).map { it.channels })
        // The hidden-channels page lists the rule-hidden channel.
        assertEquals(1, db.hiddenChannelsDao().count(p))

        rules.delete(rules.rules(p).first().id)
        assertEquals(0L, groupRuleHidden("g:sports"))
        assertEquals(1L, ruleHidden("n-xxx"))
        rules.setEnabled(rules.rules(p).single().id, false)
        assertEquals(0L, ruleHidden("n-xxx"))
    }

    @Test
    fun anUnchangedLibraryWritesNothing() = runBlocking {
        seed()
        rules.add(p, RuleDraft(RuleTarget.GROUP_NAME, "sports"))
        assertEquals(0, rules.apply(p).changedRows)
    }

    @Test
    fun unsupportedSavedRegexIsRefusedInPreviewAndSwitchedOffByThePass() = runBlocking {
        val g = db.newGroup(p, "g:x", "X")
        repeat(20) { db.newChannel(p, "c$it", "a".repeat(30) + "!", groupId = g) }
        val evil = """^(a+)+\1b""" // Backreferences are outside the supported regular-language subset.
        assertEquals(RulePreview.Invalid(RuleProblem.BAD_REGEX), rules.preview(p, RuleDraft(RuleTarget.CHANNEL_NAME, evil, isRegex = true)))
        // A rule that got in anyway (restored from a backup, or slow only on new channels) is switched off with a reason.
        val id = db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.CHANNEL_NAME.ordinal, pattern = evil, isRegex = true, createdAt = 1))
        db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = RuleTarget.GROUP_NAME.ordinal, pattern = "x", isRegex = false, createdAt = 1))
        val r = rules.apply(p)
        assertEquals(listOf(id), r.disabled)
        val row = rules.rules(p).first { it.id == id }
        assertFalse(row.enabled)
        assertEquals(RuleDisabledReason.BAD_REGEX, row.disabledReason)
        // The other rule still applied.
        assertEquals(1L, groupRuleHidden("g:x"))
    }

    @Test
    fun aBadRegexStoredInTheDatabaseIsSwitchedOff() = runBlocking {
        seed()
        val id = db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = 1, pattern = "(", isRegex = true, createdAt = 1))
        assertEquals(listOf(id), rules.apply(p).disabled)
        assertEquals(RuleDisabledReason.BAD_REGEX, rules.rules(p).single().disabledReason)
    }

    @Test
    fun ruleHiddenIsRecomputedAndPreservedAcrossARefreshMerge() = runBlocking {
        var seq = 0L
        fun merge(entries: List<ParsedChannel>) {
            seq++
            val sink = RoomMergeSink(db, p, 1_760_000_000_000L + seq, seq)
            FakeDiffer().merge(EntryProducer { c -> entries.forEach(c) }, RoomExistingChannels(db, p), sink, MergeContext(p, PlaylistType.M3U_URL, seq, 1L, previousLiveCount = 0))
        }
        merge(listOf(pc("Alpha XXX", position = 0, group = "Films"), pc("Beta", position = 1, group = "Sports Live")))
        rules.add(p, RuleDraft(RuleTarget.CHANNEL_NAME, "xxx"))
        rules.add(p, RuleDraft(RuleTarget.GROUP_NAME, "sports"))
        assertEquals(1L, db.scalar("SELECT rule_hidden FROM channel WHERE name = 'Alpha XXX'"))
        // Second refresh: Alpha moves (an update/touch), a new matching channel and a new matching group arrive.
        merge(
            listOf(
                pc("Beta", position = 0, group = "Sports Live"),
                pc("Alpha XXX", position = 1, group = "Films"),
                pc("Gamma XXX", position = 2, group = "Films"),
                pc("Delta", position = 3, group = "More Sports"),
            ),
        )
        assertEquals(1L, db.scalar("SELECT rule_hidden FROM channel WHERE name = 'Alpha XXX'"))
        assertEquals(1L, db.scalar("SELECT rule_hidden FROM channel WHERE name = 'Gamma XXX'"))
        assertEquals(0L, db.scalar("SELECT rule_hidden FROM channel WHERE name = 'Beta'"))
        assertEquals(2L, db.scalar("SELECT COUNT(*) FROM channel_group WHERE rule_hidden = 1"))
    }

    @Test
    fun aRegexOverItsPageBudgetIsSwitchedOffByThePass() = runBlocking {
        seed()
        val id = db.hideRuleDao().insert(HideRuleEntity(playlistId = p, target = 1, pattern = "^news \\d$", isRegex = true, createdAt = 1))
        // A clock that jumps 1 s per read: every regex match ends past its 50 ms budget.
        var t = 0L
        val r = db.writeTransaction { RuleHiddenPass.run(this, p, nanoTime = { t += 1_000_000_000; t }) }
        assertEquals(listOf(id), r.disabled)
        assertEquals(RuleDisabledReason.TOO_SLOW, rules.rules(p).single().disabledReason)
        assertEquals(0L, ruleHidden("n0"))
    }

    @Test
    fun regexSafetyBoundsCompilationAndAllowsLinearEngineShapes() {
        for (p in listOf("(a{1000}){1000}", "a{1000000000}", "x".repeat(201))) {
            assertTrue(p, RegexSafety.isRisky(p))
        }
        for (p in listOf("^(sport|news) \\d$", "xxx", "[a-z]+ hd$", "^(uk|us):", "(a+)?b", "(ab){2}", "\\(1\\)+", "^[(]+", "^(a+)+$", "^(a+)(a+)(a+)(a+)$")) {
            assertFalse(p, RegexSafety.isRisky(p))
        }
    }

    @Test
    fun timeBoxedSequenceThrowsAfterTheDeadline() {
        var t = 0L
        val s = TimeBoxedCharSequence("abc".repeat(1000), deadlineNs = 10, nanoTime = { t })
        repeat(255) { s[0] }
        t = 11
        var thrown = false
        try { repeat(300) { s[0] } } catch (e: RegexBudgetExceeded) { thrown = true }
        assertTrue(thrown)
    }
}
