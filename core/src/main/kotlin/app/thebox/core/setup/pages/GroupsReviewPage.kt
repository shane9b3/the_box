package app.thebox.core.setup.pages

import app.thebox.core.settings.Badge
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Tone
import app.thebox.core.settings.state.SetupDomain
import app.thebox.core.setup.MatchTotals
import app.thebox.core.setup.ReviewGroup
import app.thebox.core.setup.SetupConstants
import app.thebox.core.setup.SetupText
import app.thebox.core.setup.UnmatchedChannel
import kotlinx.coroutines.flow.first

/**
 * S6 groups review (§3.4.6); Done applies the choices and replaces the page with the matching report (§3.4.7,
 * `SettingsRoute.MatchingReport`, M5 integration amendment), unless the guide is still downloading.
 */
class GroupsReviewPage(
    private val r: SettingsRoute.GroupsReview,
    d: SetupPageDeps,
    private val tracker: ImportTracker?,
) : SetupPage(r, d) {
    private var groups: List<ReviewGroup> = emptyList()
    private var loaded = false
    private val hidden = HashSet<String>()
    /** Explicit lock decisions made on this page (key → locked). */
    private val lockEdits = HashMap<String, Boolean>()
    private var countries: List<Pair<String, Int>> = emptyList()
    private val selectedCountries = HashSet<String>()
    private var pinOffer = false
    private var uid: String? = null
    private var busy = false


    override fun start() {
        publish()
        launch {
            groups = d.groups.groups(r.playlistId)
            hidden.clear(); groups.filter { it.hidden }.mapTo(hidden) { it.key }
            countries = countriesOf(groups)
            uid = d.admin.summary(r.playlistId)?.uid
            loaded = true
            val key = uid
            if (groups.any { locked(it) } && !d.parental.value.pinSet) {
                pinOffer = true
                if (key != null) d.store.update(SetupDomain) { it.copy(pinOfferedFor = it.pinOfferedFor + key) }
            }
            publish()
        }
    }

    private fun locked(g: ReviewGroup): Boolean = lockEdits[g.key] ?: g.locked

    override fun build(): PageModel {
        val rows = ArrayList<SettingsRow>(groups.size + 6)
        if (countries.size >= 2) {
            val options = countries.map { (c, n) -> ChoiceOption(c, t(SetupText.GROUPS_COUNTRY_CHIP, c, num(n))) }
            val sel = options.indices.filter { options[it].id in selectedCountries }.toSet()
            rows += row(ROW_COUNTRIES, t(SetupText.GROUPS_COUNTRIES), RowKind.MultiChoice(options, sel), value = if (sel.isEmpty()) t(SetupText.GROUPS_ALL_COUNTRIES) else sel.joinToString(", ") { options[it].id })
        }
        for (g in groups) {
            val isHidden = g.key in hidden
            val isLocked = locked(g)
            val badges = buildSet {
                if (isHidden) add(Badge.HIDDEN)
                if (isLocked) add(Badge.LOCKED)
                if (g.adult) add(Badge.ADULT)
            }
            rows += row(
                groupRow(g.key), g.name,
                RowKind.Item(
                    primary = PRIMARY_TOGGLE_HIDDEN,
                    menu = listOf(
                        if (isHidden) ChoiceOption(MENU_SHOW, t(SetupText.GROUPS_SHOW)) else ChoiceOption(MENU_HIDE, t(SetupText.GROUPS_HIDE)),
                        if (isLocked) ChoiceOption(MENU_UNLOCK, t(SetupText.GROUPS_UNLOCK)) else ChoiceOption(MENU_LOCK, t(SetupText.GROUPS_LOCK)),
                    ),
                    badges = badges,
                ),
                value = t(if (isHidden) SetupText.GROUPS_VALUE_HIDDEN else SetupText.GROUPS_VALUE_SHOWN, num(g.channels)),
            )
        }
        if (pinOffer && groups.any { locked(it) } && !d.parental.value.pinSet) rows += pinOfferRows(groups.count { locked(it) })
        rows += info(ROW_HINT, t(SetupText.GROUPS_HINT), emptyList())
        rows += action(ROW_DONE, t(SetupText.GROUPS_DONE), enabled = loaded && !busy, disabledReason = t(SetupText.FORM_SAVING))
        return page(
            t(SetupText.GROUPS_TITLE), rows,
            subtitle = t(SetupText.GROUPS_SUBTITLE, num(groups.size)),
            placeholder = if (!loaded) t(SetupText.LOADING) else null,
        )
    }

    override fun onIntent(intent: RowIntent) {
        when (intent) {
            is RowIntent.Run -> when (val k = intent.row.key) {
                ROW_DONE -> done()
                ROW_PIN_SET -> { pinOffer = false; act(PageAction.CreatePin) }
                ROW_PIN_LATER -> pinOffer = false
                else -> keyOf(k)?.let { if (!hidden.remove(it)) hidden += it }
            }
            is RowIntent.Menu -> {
                val key = keyOf(intent.row.key) ?: return
                when (intent.optionId) {
                    MENU_HIDE -> hidden += key
                    MENU_SHOW -> hidden -= key
                    MENU_LOCK -> lockEdits[key] = true
                    MENU_UNLOCK -> lockEdits[key] = false
                }
            }
            is RowIntent.ChooseMany -> if (intent.row.key == ROW_COUNTRIES) applyCountries(intent.optionIds)
            else -> return
        }
        publish()
    }

    /** Back = Done (§3.4.6: the review is skippable, nothing is lost). */
    override fun onBack(): Boolean {
        done()
        return true
    }

    /** "Show only these countries": groups tagged with another country hide; untagged groups keep their state. */
    private fun applyCountries(ids: Set<String>) {
        selectedCountries.clear(); selectedCountries += ids
        for (g in groups) {
            val c = countryOf(g.name) ?: continue
            if (ids.isEmpty() || c in ids) hidden -= g.key else hidden += g.key
        }
    }

    private fun done() {
        if (busy || !loaded) return
        busy = true
        launch {
            d.setup.applyGroupsReview(
                r.playlistId,
                hidden = HashSet(hidden),
                locked = lockEdits.filterValues { it }.keys.toSet(),
                unlocked = lockEdits.filterValues { !it }.keys.toSet(),
            )
            busy = false
            // §3.4.7: never block on the guide; while it still downloads the report is skipped (the sync chip has it).
            if (tracker != null && !tracker.guideDownloaded) {
                act(PageAction.LeaveToGuide)
                return@launch
            }
            // (M5 integration) The report has its own route (amendment `SettingsRoute.MatchingReport`); Back on it = Later.
            act(PageAction.Replace(SettingsRoute.MatchingReport(r.playlistId, r.firstRun)))
        }
    }

    companion object {
        const val ROW_COUNTRIES = "setup.groups.countries"
        const val ROW_GROUP_PREFIX = "setup.groups.g."
        const val ROW_HINT = "setup.groups.hint"
        const val ROW_DONE = "setup.groups.done"
        const val ROW_REPORT_SUMMARY = "setup.report.summary"
        const val ROW_REPORT_CHANNEL = "setup.report.ch."
        const val ROW_REPORT_FIX = "setup.report.fix"
        const val ROW_REPORT_LATER = "setup.report.later"
        const val PRIMARY_TOGGLE_HIDDEN = "toggle_hidden"
        const val MENU_HIDE = "hide"
        const val MENU_SHOW = "show"
        const val MENU_LOCK = "lock"
        const val MENU_UNLOCK = "unlock"

        fun groupRow(key: String) = ROW_GROUP_PREFIX + key
        fun keyOf(rowKey: String): String? = if (rowKey.startsWith(ROW_GROUP_PREFIX)) rowKey.removePrefix(ROW_GROUP_PREFIX) else null

        private val COUNTRY_TAG = Regex("^\\s*\\[?([A-Z]{2,3})\\]?\\s*(\\||:|-|–|»|\\])\\s*")

        /** The leading country tag of a group name (`UK | Sports`, `US: News`, `[DE] Kino`), or null. */
        fun countryOf(name: String): String? = COUNTRY_TAG.find(name)?.groupValues?.get(1)

        /** Country chips with their group counts, most groups first (§3.4.6). */
        fun countriesOf(groups: List<ReviewGroup>): List<Pair<String, Int>> =
            groups.mapNotNull { countryOf(it.name) }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }
    }
}

/**
 * The matching report (§3.4.7) on its own route (M5 integration amendment `SettingsRoute.MatchingReport`): matched
 * channels with guide data, the top unmatched, "Fix now" and "Later". Back = Later (to the guide).
 */
class MatchingReportPage(
    private val r: SettingsRoute.MatchingReport,
    d: SetupPageDeps,
    private val tracker: ImportTracker?,
) : SetupPage(r, d) {
    private var totals: MatchTotals? = null
    private var topUnmatched: List<UnmatchedChannel> = emptyList()

    override fun start() {
        publish()
        launch {
            totals = d.matching.totals(r.playlistId)
            topUnmatched = d.matching.unmatched(d.parental.value.access).first()
                .filter { it.identity.playlistId == r.playlistId }
                .sortedBy { it.name.lowercase(d.locale()) }
                .take(SetupConstants.MATCHING_REPORT_TOP)
            publish()
        }
    }

    override fun build(): PageModel {
        val rows = ArrayList<SettingsRow>(14)
        val tt = totals
        val lines = ArrayList<String>(2)
        if (tracker?.finished != true) lines += t(SetupText.REPORT_CONTINUES)
        rows += info(
            GroupsReviewPage.ROW_REPORT_SUMMARY,
            if (tt != null) t(SetupText.REPORT_SUMMARY, num(tt.matchedWithData), num(tt.live), percent(tt.matchedWithData, tt.live)) else t(SetupText.LOADING),
            lines, if (tt != null && tt.live > 0 && tt.matchedWithData * 10 >= tt.live * 8) Tone.GOOD else Tone.NORMAL,
        )
        topUnmatched.forEachIndexed { i, c -> rows += info("${GroupsReviewPage.ROW_REPORT_CHANNEL}$i", c.name, listOfNotNull(c.groupName)) }
        rows += link(GroupsReviewPage.ROW_REPORT_FIX, t(SetupText.REPORT_FIX_NOW), SettingsRoute.FixGuide(null))
        rows += action(GroupsReviewPage.ROW_REPORT_LATER, t(SetupText.REPORT_LATER))
        return page(t(SetupText.REPORT_TITLE), rows)
    }

    override fun onIntent(intent: RowIntent) {
        if (intent is RowIntent.Run && intent.row.key == GroupsReviewPage.ROW_REPORT_LATER) act(PageAction.LeaveToGuide)
    }

    override fun onBack(): Boolean {
        act(PageAction.LeaveToGuide)
        return true
    }

    private fun percent(a: Int, b: Int): String = if (b <= 0) "0" else ((a * 100L + b / 2) / b).toString()
}
