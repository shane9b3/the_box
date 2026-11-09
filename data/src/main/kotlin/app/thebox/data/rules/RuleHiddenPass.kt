package app.thebox.data.rules

import androidx.room.PooledConnection
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.tree.RuleDisabledReason
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.data.db.dao.GroupDao
import app.thebox.data.repo.exec
import app.thebox.data.repo.forEachBound
import app.thebox.data.repo.queryLong

/**
 * The `rule_hidden` pass (docs/design/m5-setup-settings.md §3.7.3): recomputes `channel_group.rule_hidden` (group-name
 * rules) and `channel.rule_hidden` (channel-name rules) for one playlist from its enabled rules, in pages of
 * [PAGE] rows. Only changed rows are written, so an unchanged library writes nothing and the lineup flows re-emit only
 * when a flag moved. Each regex gets [RULE_REGEX_BUDGET_MS] per page; a rule that exceeds it is switched off with
 * [RuleDisabledReason.TOO_SLOW] and the pass restarts without it. Callers hold `ImportMutex` and an open write
 * transaction ([HideRulesImpl.apply], or `RoomMergeSink.finish`, which already runs under the merge's lock).
 */
object RuleHiddenPass {
    const val PAGE = 500
    private const val TAG = "RuleHiddenPass"

    data class Result(val hiddenGroups: Int, val hiddenChannels: Int, val changedRows: Int, val disabled: List<Long>)

    private class Row(val id: Long, val name: String, val ruleHidden: Boolean)

    /** Applies the playlist's enabled rules. */
    suspend fun run(conn: PooledConnection, playlistId: Long, budgetMs: Long = RULE_REGEX_BUDGET_MS, nanoTime: () -> Long = System::nanoTime): Result {
        val disabled = ArrayList<Long>()
        var matchers = loadMatchers(conn, playlistId, disabled)
        while (true) {
            val slow = ArrayList<Long>()
            val groups = evaluate(conn, playlistId, GROUPS, matchers.filter { it.target == RuleTarget.GROUP_NAME }, budgetMs, nanoTime, slow)
            val channels = if (slow.isEmpty()) evaluate(conn, playlistId, CHANNELS, matchers.filter { it.target == RuleTarget.CHANNEL_NAME }, budgetMs, nanoTime, slow) else null
            if (slow.isNotEmpty()) {
                for (id in slow) disable(conn, id, RuleDisabledReason.TOO_SLOW)
                disabled += slow
                matchers = matchers.filter { it.id !in slow }
                continue
            }
            var changed = 0
            changed += if (groups!!.clearAll) clearAll(conn, playlistId, GROUPS) else write(conn, "UPDATE channel_group SET rule_hidden = ? WHERE id = ?", groups.changes)
            changed += if (channels!!.clearAll) clearAll(conn, playlistId, CHANNELS) else write(conn, "UPDATE channel SET rule_hidden = ? WHERE id = ?", channels.changes)
            if (disabled.isNotEmpty()) conn.exec("UPDATE playlist SET hide_rules_ver = hide_rules_ver + 1 WHERE id = ?") { bindLong(1, playlistId) }
            BoxLog.i(TAG) { "playlist=$playlistId groups=${groups.hidden} channels=${channels.hidden} changed=$changed disabled=${disabled.size}" }
            return Result(groups.hidden, channels.hidden, changed, disabled)
        }
    }

    /** Read-only counts for a draft (the live preview): groups and channels it would hide. */
    suspend fun preview(conn: PooledConnection, playlistId: Long, matcher: RuleMatcher, budgetMs: Long = RULE_REGEX_BUDGET_MS, nanoTime: () -> Long = System::nanoTime): Pair<Int, Int>? {
        val slow = ArrayList<Long>()
        return when (matcher.target) {
            RuleTarget.GROUP_NAME -> {
                val ids = ArrayList<Long>()
                scan(conn, playlistId, GROUPS) { page ->
                    val deadline = nanoTime() + budgetMs * 1_000_000
                    for (r in page) {
                        try {
                            if (matcher.matches(r.name, deadline, nanoTime)) ids += r.id
                        } catch (e: RegexBudgetExceeded) {
                            slow += matcher.id
                            return@scan false
                        }
                    }
                    true
                }
                if (slow.isNotEmpty()) return null
                // v5 (docs/design/m6-channel-editor.md §3.6 row 15): channels whose effective group is matched, from one
                // grouped count over the playlist (GroupDao.EFFECTIVE_COUNTS), summed for the matched groups.
                val matched = ids.toHashSet()
                var channels = 0
                if (matched.isNotEmpty()) {
                    conn.usePrepared(GroupDao.EFFECTIVE_COUNTS) { st ->
                        st.bindLong(1, playlistId)
                        while (st.step()) if (!st.isNull(0) && st.getLong(0) in matched) channels += st.getLong(1).toInt()
                    }
                }
                ids.size to channels
            }
            RuleTarget.CHANNEL_NAME -> {
                val e = evaluate(conn, playlistId, CHANNELS, listOf(matcher), budgetMs, nanoTime, slow, forceAll = true)
                if (slow.isNotEmpty()) null else 0 to e.hidden
            }
        }
    }

    fun loadMatcher(id: Long, target: Int, pattern: String, isRegex: Boolean): RuleMatcher.Result =
        RuleMatcher.compile(id, RuleDraft(RuleTarget.entries.getOrElse(target) { RuleTarget.GROUP_NAME }, pattern, isRegex))

    private suspend fun loadMatchers(conn: PooledConnection, playlistId: Long, disabled: MutableList<Long>): List<RuleMatcher> {
        val out = ArrayList<RuleMatcher>()
        val bad = ArrayList<Pair<Long, RuleDisabledReason>>()
        conn.usePrepared("SELECT id, target, pattern, is_regex FROM hide_rule WHERE playlist_id = ? AND enabled = 1 ORDER BY id") { st ->
            st.bindLong(1, playlistId)
            while (st.step()) {
                when (val r = loadMatcher(st.getLong(0), st.getLong(1).toInt(), st.getText(2), st.getLong(3) != 0L)) {
                    is RuleMatcher.Result.Ok -> out += r.matcher
                    is RuleMatcher.Result.Problem -> bad += st.getLong(0) to
                        if (r.problem == app.thebox.core.settings.tree.RuleProblem.TOO_SLOW) RuleDisabledReason.TOO_SLOW else RuleDisabledReason.BAD_REGEX
                }
            }
        }
        for ((id, reason) in bad) disable(conn, id, reason)
        disabled += bad.map { it.first }
        return out
    }

    private suspend fun disable(conn: PooledConnection, id: Long, reason: RuleDisabledReason) {
        conn.exec("UPDATE hide_rule SET enabled = 0, disabled_reason = ? WHERE id = ?") { bindText(1, reason.name); bindLong(2, id) }
    }

    /** [clearAll]: no rule applies, so every flag is cleared by one guarded UPDATE instead of a scan (performance review L6). */
    private class Eval(val hidden: Int, val changes: List<Pair<Long, Boolean>>, val clearAll: Boolean = false)

    private suspend fun clearAll(conn: PooledConnection, playlistId: Long, table: String): Int {
        conn.exec("UPDATE $table SET rule_hidden = 0 WHERE playlist_id = ? AND rule_hidden = 1 AND missing_since IS NULL") { bindLong(1, playlistId) }
        return conn.queryLong("SELECT changes()").toInt()
    }

    private suspend fun evaluate(
        conn: PooledConnection,
        playlistId: Long,
        table: String,
        matchers: List<RuleMatcher>,
        budgetMs: Long,
        nanoTime: () -> Long,
        slow: MutableList<Long>,
        forceAll: Boolean = false,
    ): Eval {
        if (matchers.isEmpty() && !forceAll) return Eval(0, emptyList(), clearAll = true)
        var hidden = 0
        val changes = ArrayList<Pair<Long, Boolean>>()
        scan(conn, playlistId, table) { page ->
            // One budget per rule per page (§3.7.3).
            val deadlines = LongArray(matchers.size) { nanoTime() + budgetMs * 1_000_000 }
            for (r in page) {
                var hit = false
                for ((i, m) in matchers.withIndex()) {
                    try {
                        if (m.matches(r.name, deadlines[i], nanoTime)) { hit = true; if (!forceAll) break }
                    } catch (e: RegexBudgetExceeded) {
                        slow += m.id
                        return@scan false
                    }
                }
                if (hit) hidden++
                if (hit != r.ruleHidden) changes += r.id to hit
            }
            true
        }
        return Eval(hidden, changes)
    }

    /** Pages through [table]'s rows of the playlist by id; [onPage] returns false to stop. */
    private suspend fun scan(conn: PooledConnection, playlistId: Long, table: String, onPage: (List<Row>) -> Boolean) {
        var after = Long.MIN_VALUE
        while (true) {
            val page = conn.usePrepared(
                "SELECT id, name, rule_hidden FROM $table WHERE playlist_id = ? AND id > ? AND missing_since IS NULL ORDER BY id LIMIT $PAGE",
            ) { st ->
                st.bindLong(1, playlistId)
                st.bindLong(2, after)
                buildList { while (st.step()) add(Row(st.getLong(0), st.getText(1), st.getLong(2) != 0L)) }
            }
            if (page.isEmpty()) return
            if (!onPage(page)) return
            if (page.size < PAGE) return
            after = page.last().id
        }
    }

    private suspend fun write(conn: PooledConnection, sql: String, changes: List<Pair<Long, Boolean>>): Int =
        if (changes.isEmpty()) 0 else conn.forEachBound(sql, changes) { (id, on) -> bindLong(1, if (on) 1 else 0); bindLong(2, id) }

    private const val GROUPS = "channel_group"
    private const val CHANNELS = "channel"
}
