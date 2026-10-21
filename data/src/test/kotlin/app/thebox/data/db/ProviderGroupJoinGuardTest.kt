package app.thebox.data.db

import app.thebox.core.editor.EditorList
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.backup.BackupExporter
import app.thebox.data.db.dao.ChannelDao
import app.thebox.data.db.dao.EditorDao
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.editor.EditorQueries
import app.thebox.data.guide.GuideGraph
import app.thebox.data.guide.GuideQueries
import app.thebox.data.parental.LockedChannelIndex
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.TestGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * docs/design/m6-channel-editor.md §3.6 "Guard test": every reader of a channel's group goes through the effective-group
 * constants. A provider-group join (`= c.group_id`, `c.group_id =`, `group_id IN`) is allowed only in
 * `ChannelDao.PROVIDER_GROUP_JOIN`, the group lineup's first half and the provider-on-purpose readers (rows 16–20); a
 * `channel_group g` join only in `ChannelDao.GROUP_TAIL`; `GROUP_TAIL` alone only after `PROVIDER_GROUP_JOIN` in a query
 * that drives from `channel_prefs cp` (never after a second `cp` join).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderGroupJoinGuardTest {
    private val root: File = listOf(File("src/main/kotlin"), File("data/src/main/kotlin")).first { it.isDirectory }

    /** Provider on purpose, any line (§3.6 rows 16–20). */
    private val providerFiles = setOf(
        "guide/DisplayNames.kt", "setup/PlaylistSettingsRepository.kt", "repo/RoomExistingChannels.kt", "repo/RoomMergeSink.kt",
    )

    /** (file, a token the line must contain): every provider-group reader, allowlisted line by line. */
    private val allowedLines = listOf(
        "db/dao/ChannelDao.kt" to "PROVIDER_GROUP_JOIN = ",
        "guide/GuideQueries.kt" to "CROSS JOIN channel c ON c.group_id = g0.id",
        "guide/DisplayNames.kt" to "FROM channel c LEFT JOIN channel_group g ON g.id = c.group_id",
        "setup/PlaylistSettingsRepository.kt" to "FROM channel c WHERE c.group_id = g.id AND c.missing_since IS NULL",
        "repo/RoomExistingChannels.kt" to "LEFT JOIN channel_group g ON g.id = c.group_id WHERE c.playlist_id",
        "repo/RoomMergeSink.kt" to "SELECT 1 FROM channel c WHERE c.group_id = channel_group.id",
    )

    private fun sources(): List<Pair<String, List<String>>> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.substringAfter("app/thebox/data/") to it.readLines() }.toList()

    @Test
    fun providerGroupJoinsOnlyInTheAllowlist() {
        // Alias-agnostic (data review 2026-10-18): any `<alias>.group_id`, `group_id IN`, `USING (group_id)`.
        val pattern = Regex("""\b\w+\.group_id\b|\bgroup_id\s+IN\b|USING\s*\(\s*group_id""", RegexOption.IGNORE_CASE)
        val offenders = ArrayList<String>()
        var allowedSeen = 0
        for ((path, lines) in sources()) {
            if (path.startsWith("db/entity/")) continue
            lines.forEachIndexed { i, line ->
                if (!pattern.containsMatchIn(line)) return@forEachIndexed
                when {
                    allowedLines.any { (f, token) -> f == path && token in line } -> allowedSeen++
                    else -> offenders += "$path:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals("provider-group joins outside the allowlist", emptyList<String>(), offenders)
        assertEquals("every allowlisted line exists (RoomMergeSink has two)", allowedLines.size + 1, allowedSeen)
    }

    @Test
    fun theEffectiveGroupIsJoinedOnlyThroughGroupTail() {
        val join = Regex("""JOIN\s+channel_group\s+(AS\s+)?g\s+ON""", RegexOption.IGNORE_CASE)
        val offenders = ArrayList<String>()
        for ((path, lines) in sources()) {
            if (path in providerFiles) continue
            lines.forEachIndexed { i, line ->
                if (join.containsMatchIn(line) && !(path == "db/dao/ChannelDao.kt" && "COALESCE(gt.id, g0.id)" in line)) offenders += "$path:${i + 1}: ${line.trim()}"
                // GROUP_TAIL alone (not EFFECTIVE_GROUP_TAIL) must follow PROVIDER_GROUP_JOIN on the same line.
                for (m in Regex("""(?<![A-Z_])GROUP_TAIL\b""").findAll(line)) {
                    if (path == "db/dao/ChannelDao.kt" && line.trimStart().startsWith("const val")) continue
                    if (line.trimStart().startsWith("*") || line.trimStart().startsWith("//")) continue
                    if (!line.substring(0, m.range.first).replace("ChannelDao.", "").trimEnd().endsWith("PROVIDER_GROUP_JOIN +")) offenders += "$path:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** Each SELECT (each half of a compound one) declares `cp`, `g` and `g0` at most once. */
    @Test
    fun noQueryJoinsAnAliasTwice() {
        val db = TestDb.create()
        try {
            val gq = GuideGraph(TestGraph(db)).queries
            val statements = buildList {
                for (s in listOf(GuideSource.All(1), GuideSource.Group(1, "g:x"), GuideSource.Favourites(1), GuideSource.Recent)) add(gq.lineupSql(s))
                add(ChannelDao.SELECT_ROW); add(ChannelDao.UNMATCHED_HEAD); add(LockedChannelIndex.SQL); add(GroupDao.EFFECTIVE_COUNTS)
                add(EditorDao.CLEAR_CHANNEL_ORDER)
                for (l in listOf(EditorList.All, EditorList.Find("bbc"), EditorList.Group("g:x"))) {
                    add(EditorQueries.listSql(1, l, ParentalAccess.LOCKED_AUTO, listOf(EditorQueries.COL_ID, EditorQueries.COL_HIDDEN, EditorQueries.COL_LOCKED_OUT)).sql)
                }
            }
            for (sql in statements) {
                for (part in sql.split("UNION ALL")) {
                    val inner = part.substringAfterLast("SELECT c.stable_key FROM channel c", part) // CLEAR_CHANNEL_ORDER's subquery
                    for (alias in listOf("channel_prefs cp ", "channel_group g ", "channel_group g0 ", "channel_group gt ")) {
                        val n = Regex(Regex.escape(alias.trim()) + """\s""").findAll(inner).count()
                        assertTrue("$alias $n× in: $inner", n <= 1)
                    }
                }
            }
            assertTrue(BackupExporter.USER_PREF_PREDICATE.contains("custom_group_key"))
        } finally {
            db.close()
        }
    }
}
