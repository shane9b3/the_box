package app.thebox.data.rules

import androidx.room.PooledConnection
import app.thebox.core.common.BoxLog
import app.thebox.core.events.EventNameParser
import app.thebox.core.settings.tree.RuleDisabledReason
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.data.db.dao.ChannelDao
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
 *
 * (Event channels WP-2, docs/design/m6-event-channels.md §5.5) One built-in matcher sits next to the user's channel
 * rules: while the playlist's `hide_empty_events` switch is on, a channel whose provider name is an empty event slot
 * (`EventNameParser.isEmptySlot`: "NO EVENT", "NHL 02:", "… | Offline") is hidden. It is plain string scanning, not a
 * regex, so it has no budget and is never switched off. A slot that gets an event name at a refresh is recomputed here
 * (from `finish`) and shows again; switching it off clears it (the `clearAll` path when no user rule is left). A
 * favourite empty slot is hidden too: hide rules apply to every lineup (re-review E2).
 */
object RuleHiddenPass {
    const val PAGE = 500
    private const val TAG = "RuleHiddenPass"

    data class Result(val hiddenGroups: Int, val hiddenChannels: Int, val changedRows: Int, val disabled: List<Long>)

    /** [group]: the provider group's name (channels only), for a bare empty form (code review 2026-11-25, data m1). */
    private class Row(val id: Long, val name: String, val ruleHidden: Boolean, val group: String? = null)

    /**
     * Applies the playlist's enabled rules, and the "Hide channels with no event" switch when it is on. [isEmptySlot]:
     * the parser seam (`EventNameParser.isEmptySlot`; a fake in tests).
     */
    suspend fun run(
        conn: PooledConnection,
        playlistId: Long,
        budgetMs: Long = RULE_REGEX_BUDGET_MS,
        nanoTime: () -> Long = System::nanoTime,
        isEmptySlot: (String) -> Boolean = EventNameParser::isEmptySlot,
    ): Result {
        val disabled = ArrayList<Long>()
        var matchers = loadMatchers(conn, playlistId, disabled)
        val builtIn = if (hidesEmptyEvents(conn, playlistId)) isEmptySlot else null
        while (true) {
            val slow = ArrayList<Long>()
            val groups = evaluate(conn, playlistId, GROUPS, matchers.filter { it.target == RuleTarget.GROUP_NAME }, budgetMs, nanoTime, slow)
            val channels = if (slow.isEmpty()) {
                evaluate(conn, playlistId, CHANNELS, matchers.filter { it.target == RuleTarget.CHANNEL_NAME }, budgetMs, nanoTime, slow, builtIn = builtIn)
            } else {
                null
            }
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

    /** `playlist.hide_empty_events` (§5.5). */
    suspend fun hidesEmptyEvents(conn: PooledConnection, playlistId: Long): Boolean =
        conn.queryLong("SELECT COALESCE((SELECT hide_empty_events FROM playlist WHERE id = ?), 0)") { bindLong(1, playlistId) } != 0L

    /**
     * (Re-review E1) A `rule_hidden` row counts as hidden by the switch, never by a user rule, when the playlist's switch
     * is on and its provider name is an empty slot.
     */
    fun hiddenByNoEvent(
        switchOn: Boolean,
        ruleHidden: Boolean,
        providerName: String,
        providerGroup: String?,
        isEmptySlot: (String) -> Boolean = { EventNameParser.isEmptySlot(it) },
    ): Boolean = switchOn && ruleHidden && emptySlot(isEmptySlot, providerName, providerGroup)

    /**
     * (Re-review E1) How many live channels of [playlistId] the switch hides and no group rule does: the Hide rules
     * summary subtracts them. 0 while the switch is off. Paged by id; reads only `rule_hidden` rows.
     */
    suspend fun countHiddenByNoEvent(conn: PooledConnection, playlistId: Long, isEmptySlot: (String) -> Boolean = EventNameParser::isEmptySlot): Int {
        if (!hidesEmptyEvents(conn, playlistId)) return 0
        // (Code review 2026-11-25, data m4) A row a user channel rule also hides is the rule's: not counted here, so the
        // Hide rules summary keeps it under the rule.
        val rules = channelRuleMatchers(conn, playlistId)
        var n = 0
        var after = Long.MIN_VALUE
        while (true) {
            var last = after
            var rows = 0
            conn.usePrepared(NO_EVENT_PAGE) { st ->
                st.bindLong(1, playlistId)
                st.bindLong(2, after)
                while (st.step()) {
                    rows++
                    last = st.getLong(0)
                    val name = st.getText(1)
                    if (emptySlot(isEmptySlot, name, if (st.isNull(2)) null else st.getText(2)) && !userRuleHides(rules, name)) n++
                }
            }
            if (rows < PAGE) return n
            after = last
        }
    }

    private const val NO_EVENT_PAGE = "SELECT c.id, c.name, g0.name FROM channel c " + ChannelDao.EFFECTIVE_GROUP_JOINS +
        " WHERE c.playlist_id = ? AND c.id > ? AND c.missing_since IS NULL AND c.rule_hidden = 1 AND COALESCE(g.rule_hidden, 0) = 0" +
        " ORDER BY c.id LIMIT $PAGE"

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
        /** (Event channels §5.5) The built-in empty-slot matcher, null while the switch is off. Checked first: no budget. */
        builtIn: ((String) -> Boolean)? = null,
    ): Eval {
        if (matchers.isEmpty() && builtIn == null && !forceAll) return Eval(0, emptyList(), clearAll = true)
        var hidden = 0
        val changes = ArrayList<Pair<Long, Boolean>>()
        scan(conn, playlistId, table) { page ->
            // One budget per rule per page (§3.7.3).
            val deadlines = LongArray(matchers.size) { nanoTime() + budgetMs * 1_000_000 }
            for (r in page) {
                var hit = builtIn != null && emptySlot(builtIn, r.name, r.group)
                if (!hit) for ((i, m) in matchers.withIndex()) {
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

    /**
     * The switch's test: [test] (the parser's `isEmptySlot`), and (code review 2026-11-25, data m1) a bare form ("BBC 1 |
     * Offline", "beIN Sports 3:") only in an event-like provider [group] (`EventGroups`).
     */
    private fun emptySlot(test: (String) -> Boolean, name: String, group: String?): Boolean = try {
        test(name) && (!EventNameParser.isBareSlot(name) || app.thebox.core.events.EventGroups.eventLike(group))
    } catch (e: RuntimeException) {
        false // the parser never throws by contract; a bug there must not hide or show the whole playlist
    }

    /**
     * (Code review 2026-11-25, data m4) The playlist's enabled channel-name rules, compiled (a rule that does not compile
     * is left out; the pass itself disables it). For telling a user rule's hide from the switch's.
     */
    suspend fun channelRuleMatchers(conn: PooledConnection, playlistId: Long): List<RuleMatcher> {
        val out = ArrayList<RuleMatcher>()
        conn.usePrepared("SELECT id, pattern, is_regex FROM hide_rule WHERE playlist_id = ? AND enabled = 1 AND target = ? ORDER BY id") { st ->
            st.bindLong(1, playlistId)
            st.bindLong(2, RuleTarget.CHANNEL_NAME.ordinal.toLong())
            while (st.step()) {
                val r = RuleMatcher.compile(st.getLong(0), RuleDraft(RuleTarget.CHANNEL_NAME, st.getText(1), st.getLong(2) != 0L))
                if (r is RuleMatcher.Result.Ok) out += r.matcher
            }
        }
        return out
    }

    /** True when one of [matchers] hides [name] (a budget overrun counts as no match). */
    fun userRuleHides(matchers: List<RuleMatcher>, name: String): Boolean = matchers.any { m ->
        try {
            m.matches(name, System.nanoTime() + USER_RULE_BUDGET_NS)
        } catch (e: RegexBudgetExceeded) {
            false
        }
    }

    private const val USER_RULE_BUDGET_NS = 50_000_000L

    /** Pages through [table]'s rows of the playlist by id; [onPage] returns false to stop. */
    private suspend fun scan(conn: PooledConnection, playlistId: Long, table: String, onPage: (List<Row>) -> Boolean) {
        var after = Long.MIN_VALUE
        while (true) {
            val sql = if (table == CHANNELS) {
                "SELECT c.id, c.name, c.rule_hidden, g0.name FROM channel c " + ChannelDao.PROVIDER_GROUP_JOIN +
                    "WHERE c.playlist_id = ? AND c.id > ? AND c.missing_since IS NULL ORDER BY c.id LIMIT $PAGE"
            } else {
                "SELECT id, name, rule_hidden, NULL FROM $table WHERE playlist_id = ? AND id > ? AND missing_since IS NULL ORDER BY id LIMIT $PAGE"
            }
            val page = conn.usePrepared(sql) { st ->
                st.bindLong(1, playlistId)
                st.bindLong(2, after)
                buildList { while (st.step()) add(Row(st.getLong(0), st.getText(1), st.getLong(2) != 0L, if (st.isNull(3)) null else st.getText(3))) }
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
