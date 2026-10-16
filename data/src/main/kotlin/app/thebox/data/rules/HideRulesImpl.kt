package app.thebox.data.rules

import app.thebox.core.settings.tree.HideRuleRow
import app.thebox.core.settings.tree.RuleDisabledReason
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RulePreview
import app.thebox.core.settings.tree.RuleProblem
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.HideRuleEntity
import app.thebox.data.di.WallClock
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import app.thebox.data.work.ImportMutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hide rules (docs/design/m5-setup-settings.md §3.7.3): preview counts for a draft, add/delete/enable, and [apply], the
 * one paged `rule_hidden` pass under [ImportMutex] in one write transaction. `RoomMergeSink.finish` runs the same pass
 * inside its own transaction after every refresh merge. Main-safe (Room's dispatcher does the IO).
 */
@Singleton
class HideRulesImpl @Inject constructor(
    private val db: LibraryDatabase,
    private val importMutex: ImportMutex,
    private val clock: WallClock,
) {
    /** Counts for [draft], or why it cannot be saved (empty, too long, bad regex, too slow, duplicate). */
    suspend fun preview(playlistId: Long, draft: RuleDraft): RulePreview {
        val m = when (val r = RuleMatcher.compile(0, draft)) {
            is RuleMatcher.Result.Problem -> return RulePreview.Invalid(r.problem)
            is RuleMatcher.Result.Ok -> r.matcher
        }
        if (db.hideRuleDao().countIdentical(playlistId, draft.target.ordinal, draft.pattern, draft.isRegex) > 0) return RulePreview.Invalid(RuleProblem.DUPLICATE)
        val counts = db.read { RuleHiddenPass.preview(this, playlistId, m) } ?: return RulePreview.Invalid(RuleProblem.TOO_SLOW)
        return RulePreview.Counts(counts.first, counts.second)
    }

    /** Saves [draft] after a fresh preview and runs the pass. Returns the preview the decision was based on. */
    suspend fun add(playlistId: Long, draft: RuleDraft): RulePreview {
        val p = preview(playlistId, draft)
        if (p !is RulePreview.Counts) return p
        db.hideRuleDao().insert(HideRuleEntity(playlistId = playlistId, target = draft.target.ordinal, pattern = draft.pattern, isRegex = draft.isRegex, createdAt = clock.now()))
        db.hideRuleDao().bumpVersion(playlistId)
        apply(playlistId)
        return p
    }

    suspend fun delete(ruleId: Long) {
        val r = db.hideRuleDao().get(ruleId) ?: return
        db.hideRuleDao().delete(ruleId)
        db.hideRuleDao().bumpVersion(r.playlistId)
        apply(r.playlistId)
    }

    /** User switch; switching on clears an automatic reason (the pass may switch it off again if it is still too slow). */
    suspend fun setEnabled(ruleId: Long, enabled: Boolean) {
        val r = db.hideRuleDao().get(ruleId) ?: return
        db.hideRuleDao().setEnabled(ruleId, enabled, null)
        db.hideRuleDao().bumpVersion(r.playlistId)
        apply(r.playlistId)
    }

    /** The paged pass under [ImportMutex] (never concurrent with a refresh merge or an EPG build). */
    suspend fun apply(playlistId: Long): RuleHiddenPass.Result =
        importMutex.withLock { db.writeTransaction { RuleHiddenPass.run(this, playlistId) } }

    /** Every rule with the number of channels it hides now (each counted on its own). */
    suspend fun rules(playlistId: Long): List<HideRuleRow> = db.hideRuleDao().all(playlistId).map { e ->
        val target = RuleTarget.entries.getOrElse(e.target) { RuleTarget.GROUP_NAME }
        val reason = e.disabledReason?.let { r -> RuleDisabledReason.entries.firstOrNull { it.name == r } }
        val count = if (!e.enabled) 0 else when (val m = RuleMatcher.compile(e.id, RuleDraft(target, e.pattern, e.isRegex))) {
            is RuleMatcher.Result.Ok -> db.read { RuleHiddenPass.preview(this, playlistId, m.matcher) }?.second ?: 0
            is RuleMatcher.Result.Problem -> 0
        }
        HideRuleRow(e.id, target, e.pattern, e.isRegex, e.enabled, reason, count)
    }
}
