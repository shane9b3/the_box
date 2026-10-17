package app.thebox.data.rules

import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.parental.ParentalSession
import app.thebox.core.settings.Domains
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.ChannelsPages
import app.thebox.core.settings.tree.ChannelsRows
import app.thebox.core.settings.tree.ChannelsSummary
import app.thebox.core.settings.tree.FavouriteEntryRow
import app.thebox.core.settings.tree.FavouriteListRow
import app.thebox.core.settings.tree.GroupAdminRow
import app.thebox.core.settings.tree.HiddenChannelRow
import app.thebox.core.settings.tree.PageAction
import app.thebox.core.settings.tree.PlaylistChoice
import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RulePreview
import app.thebox.core.settings.tree.RuleTarget
import app.thebox.core.settings.tree.SectionPages
import app.thebox.core.source.EntryKind
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.FavouriteListEntity
import app.thebox.data.di.WallClock
import app.thebox.data.repo.forEachBound
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Channels and groups pages (docs/design/m5-setup-settings.md §3.3.5, §3.7) over the library: data for
 * [ChannelsPages], and the intents of every row. Writes are single statements or one transaction (a move drop).
 */
@Singleton
class ChannelsPagesImpl @Inject constructor(
    private val db: LibraryDatabase,
    private val rules: HideRulesImpl,
    private val texts: BackupTexts,
    private val store: SettingsStore,
    private val parental: ParentalSession,
    private val clock: WallClock,
    /** (M5 integration, WP-C §1.6a) Group locks go through the one parental write path. */
    private val controls: app.thebox.core.parental.ParentalControls = app.thebox.core.parental.ParentalControls.NONE,
) : SectionPages {
    private val version = AtomicLong()
    private val _invalidations = MutableSharedFlow<SettingsRoute>(extraBufferCapacity = 8)
    override val invalidations: Flow<SettingsRoute> = _invalidations.asSharedFlow()

    @Volatile private var selected: Long? = null
    private val drafts = HashMap<Long, RuleDraft>()
    private val previews = HashMap<Long, RulePreview?>()

    override fun handles(route: SettingsRoute): Boolean = when (route) {
        is SettingsRoute.Section -> route.id == SectionId.CHANNELS
        is SettingsRoute.Groups, is SettingsRoute.HiddenChannels, is SettingsRoute.HideRules, SettingsRoute.FavouriteLists, is SettingsRoute.FavouriteList -> true
        else -> false
    }

    private fun v() = version.incrementAndGet()

    override suspend fun page(route: SettingsRoute, advanced: Boolean): PageModel = when (route) {
        is SettingsRoute.Section -> section(advanced)
        is SettingsRoute.Groups -> {
            val count = db.groupAdminDao().count(route.playlistId, LIVE)
            ChannelsPages.groups(route, texts, v(), count, mapOf(0 to items(route, 0, PagedList.PAGE_SIZE)))
        }
        is SettingsRoute.HiddenChannels -> {
            val count = db.hiddenChannelsDao().count(route.playlistId)
            ChannelsPages.hidden(route, texts, v(), count, mapOf(0 to items(route, 0, PagedList.PAGE_SIZE)))
        }
        is SettingsRoute.HideRules -> {
            val d = draft(route.playlistId)
            ChannelsPages.rules(route, texts, v(), d, synchronized(previews) { previews[route.playlistId] }, rules.rules(route.playlistId))
        }
        SettingsRoute.FavouriteLists -> ChannelsPages.favouriteLists(texts, v(), favouriteLists())
        is SettingsRoute.FavouriteList -> {
            val lists = favouriteLists()
            val list = lists.firstOrNull { it.id == route.listId }
            ChannelsPages.favouriteList(route, texts, v(), list, if (list == null) emptyList() else entries(route.listId), onlyList = lists.size <= 1)
        }
        else -> error("not a Channels route: $route")
    }

    override suspend fun items(route: SettingsRoute, from: Int, to: Int): List<SettingsRow> {
        val limit = (to - from).coerceAtLeast(0)
        return when (route) {
            is SettingsRoute.Groups -> {
                val pinSet = parental.state.value.pinSet
                db.groupAdminDao().entries(route.playlistId, LIVE, from, limit).map {
                    ChannelsPages.groupItem(GroupAdminRow(it.kind, it.groupKey, it.name, it.channels, it.hidden, it.locked, it.isAdult, it.ruleHidden), texts, pinSet)
                }
            }
            is SettingsRoute.HiddenChannels -> {
                val pattern = firstChannelRulePattern(route.playlistId)
                db.hiddenChannelsDao().entries(route.playlistId, from, limit).map {
                    ChannelsPages.hiddenItem(route.playlistId, HiddenChannelRow(it.stableKey, it.name, it.groupName, it.byUser, if (it.ruleHidden) 0L else null, pattern), texts)
                }
            }
            else -> emptyList()
        }
    }

    private suspend fun firstChannelRulePattern(pid: Long): String? =
        db.hideRuleDao().enabledRules(pid).firstOrNull { it.target == RuleTarget.CHANNEL_NAME.ordinal }?.pattern

    private suspend fun section(advanced: Boolean): PageModel {
        val playlists = db.playlistDao().all().map { PlaylistChoice(it.id, it.name) }
        val sel = selected?.takeIf { s -> playlists.any { it.id == s } } ?: playlists.firstOrNull()?.id
        selected = sel
        val summary = sel?.let { summary(it) } ?: ChannelsSummary()
        return ChannelsPages.section(texts, advanced, v(), playlists, sel, summary, store.flow(GUIDE).value.openGuideOn)
    }

    private suspend fun summary(pid: Long): ChannelsSummary {
        val ruleList = rules.rules(pid)
        return ChannelsSummary(
            groups = db.groupAdminDao().count(pid, LIVE),
            hiddenGroups = db.groupAdminDao().hiddenCount(pid),
            lockedGroups = db.groupAdminDao().lockedCount(pid),
            hiddenChannels = db.hiddenChannelsDao().count(pid),
            rules = ruleList.size,
            ruleHiddenChannels = db.read { usePrepared("SELECT COUNT(*) FROM channel c LEFT JOIN channel_group g ON g.id = c.group_id WHERE c.playlist_id = ? AND c.missing_since IS NULL AND (c.rule_hidden = 1 OR COALESCE(g.rule_hidden, 0) = 1)") { st -> st.bindLong(1, pid); st.step(); st.getLong(0).toInt() } },
            favouriteLists = db.favouriteDao().lists().size,
        )
    }

    private suspend fun favouriteLists(): List<FavouriteListRow> = db.favouriteDao().lists().map { l ->
        FavouriteListRow(l.id, l.name, db.read { usePrepared("SELECT COUNT(*) FROM favourite WHERE list_id = ?") { st -> st.bindLong(1, l.id); st.step(); st.getLong(0).toInt() } })
    }

    private suspend fun entries(listId: Long): List<FavouriteEntryRow> = db.read {
        usePrepared(
            "SELECT f.playlist_id, f.stable_key, COALESCE(cp.custom_name, c.display_name, c.name, f.stable_key) FROM favourite f " +
                "LEFT JOIN channel c ON c.playlist_id = f.playlist_id AND c.stable_key = f.stable_key " +
                "LEFT JOIN channel_prefs cp ON cp.playlist_id = f.playlist_id AND cp.stable_key = f.stable_key WHERE f.list_id = ? ORDER BY f.sort_index",
        ) { st ->
            st.bindLong(1, listId)
            buildList { while (st.step()) add(FavouriteEntryRow(st.getLong(0), st.getText(1), st.getText(2))) }
        }
    }

    private fun draft(pid: Long) = synchronized(drafts) { drafts.getOrPut(pid) { RuleDraft() } }

    override suspend fun onIntent(route: SettingsRoute, intent: RowIntent): PageAction {
        val now = clock.now()
        val key = intent.row.key
        return when (route) {
            is SettingsRoute.Section -> when {
                intent.row == ChannelsRows.PLAYLIST && intent is RowIntent.Choose -> { selected = intent.optionId.toLongOrNull(); PageAction() }
                intent.row == ChannelsRows.OPEN_GUIDE_ON && intent is RowIntent.Choose -> {
                    val v = OpenGuideOn.entries.firstOrNull { it.name == intent.optionId } ?: return PageAction.NONE
                    store.update(GUIDE) { it.copy(openGuideOn = v) }
                    PageAction()
                }
                else -> PageAction.NONE
            }
            is SettingsRoute.Groups -> groupIntent(route.playlistId, intent, key, now)
            is SettingsRoute.HiddenChannels -> when {
                intent.row == ChannelsRows.UNHIDE_ALL && intent is RowIntent.Run -> {
                    db.hiddenChannelsDao().unhideAll(route.playlistId, now)
                    PageAction(notice = texts.get(BackupText.UNHIDDEN))
                }
                key.startsWith(ChannelsRows.HIDDEN_PREFIX) && (intent is RowIntent.Run || intent is RowIntent.Menu) -> {
                    db.channelPrefsDao().upsertHidden(route.playlistId, key.removePrefix(ChannelsRows.HIDDEN_PREFIX), false, now)
                    PageAction(notice = texts.get(BackupText.UNHIDDEN))
                }
                else -> PageAction.NONE
            }
            is SettingsRoute.HideRules -> ruleIntent(route.playlistId, intent, key)
            SettingsRoute.FavouriteLists -> when {
                intent.row == ChannelsRows.FAV_NEW && intent is RowIntent.Edit -> {
                    val name = intent.text.trim()
                    if (name.isEmpty()) return PageAction(notice = texts.get(BackupText.FAV_NAME_EMPTY), reload = false)
                    val order = (db.favouriteDao().lists().maxOfOrNull { it.sortIndex } ?: -1) + 1
                    db.favouriteDao().insertList(FavouriteListEntity(name = name, sortIndex = order, createdAt = now))
                    PageAction()
                }
                key.startsWith(ChannelsRows.FAV_LIST_PREFIX) && intent is RowIntent.Move -> {
                    val ids = db.favouriteDao().lists().map { it.id }.toMutableList()
                    val id = key.removePrefix(ChannelsRows.FAV_LIST_PREFIX).toLongOrNull() ?: return PageAction.NONE
                    reorder(ids, id, intent.toIndex)
                    db.writeTransaction {
                        forEachBound("UPDATE favourite_list SET sort_index = ? WHERE id = ?", ids.withIndex()) { (i, lid) -> bindLong(1, i.toLong()); bindLong(2, lid) }
                    }
                    PageAction()
                }
                else -> PageAction.NONE
            }
            is SettingsRoute.FavouriteList -> favIntent(route.listId, intent, key, now)
            else -> PageAction.NONE
        }
    }

    /** C-B2: a PIN is set and the session is not unlocked (Unlock and Reset refuse then, §3.9.2). */
    private fun lockedSession(): Boolean = parental.state.value.let { it.pinSet && it.access != app.thebox.core.parental.ParentalAccess.OPEN }

    /** Live groups (kind 0) lock through `ParentalControls.setGroupLocked` (WP-C); other kinds keep the direct write. */
    private suspend fun lockGroup(pid: Long, kind: Int, groupKey: String, locked: Boolean, now: Long) {
        if (kind == 0 && controls !== app.thebox.core.parental.ParentalControls.NONE) {
            controls.setGroupLocked(app.thebox.core.guide.GuideSource.Group(pid, groupKey), locked)
        } else {
            db.groupAdminDao().upsertLocked(pid, kind, groupKey, locked, now)
        }
    }

    private suspend fun groupIntent(pid: Long, intent: RowIntent, key: String, now: Long): PageAction {
        if (intent.row == ChannelsRows.SHOW_ALL && intent is RowIntent.Run) {
            db.groupAdminDao().showAll(pid, now)
            return PageAction(notice = texts.get(BackupText.GROUPS_SHOWN_ALL))
        }
        if (!key.startsWith(ChannelsRows.GROUP_PREFIX)) return PageAction.NONE
        val rest = key.removePrefix(ChannelsRows.GROUP_PREFIX)
        val kind = rest.substringBefore('/').toIntOrNull() ?: return PageAction.NONE
        val groupKey = rest.substringAfter('/')
        val entry = db.groupAdminDao().entries(pid, kind, 0, Int.MAX_VALUE).firstOrNull { it.groupKey == groupKey } ?: return PageAction.NONE
        val dao = db.groupDao()
        return when (intent) {
            is RowIntent.Run -> { dao.upsertGroupHidden(pid, kind, groupKey, !entry.hidden, now); PageAction() }
            is RowIntent.Menu -> when (intent.optionId) {
                ChannelsRows.M_HIDE -> { dao.upsertGroupHidden(pid, kind, groupKey, true, now); PageAction() }
                ChannelsRows.M_SHOW -> { dao.upsertGroupHidden(pid, kind, groupKey, false, now); PageAction() }
                ChannelsRows.M_LOCK -> { lockGroup(pid, kind, groupKey, true, now); PageAction() }
                // C-B2 (defence in depth): the menu asks for the PIN first; a locked session never lowers protection here.
                ChannelsRows.M_UNLOCK -> if (lockedSession()) PageAction.NONE else { lockGroup(pid, kind, groupKey, false, now); PageAction() }
                ChannelsRows.M_RESET -> if (lockedSession()) PageAction.NONE else { dao.deletePrefs(pid, kind, groupKey); PageAction() }
                else -> PageAction.NONE // M_MOVE: the machine enters move mode itself (§3.2.2)
            }
            is RowIntent.Move -> {
                val keys = db.groupAdminDao().orderedKeys(pid, kind).toMutableList()
                reorder(keys, groupKey, intent.toIndex)
                dao.setGroupOrder(pid, kind, keys, now) // one transaction per drop
                PageAction()
            }
            else -> PageAction.NONE
        }
    }

    private suspend fun ruleIntent(pid: Long, intent: RowIntent, key: String): PageAction {
        fun setDraft(d: RuleDraft) = synchronized(drafts) { drafts[pid] = d }
        suspend fun refreshPreview() {
            val d = draft(pid)
            val p = if (d.pattern.isEmpty()) null else rules.preview(pid, d)
            synchronized(previews) { previews[pid] = p }
        }
        return when {
            intent.row == ChannelsRows.RULE_TARGET && intent is RowIntent.Choose -> {
                setDraft(draft(pid).copy(target = RuleTarget.entries.firstOrNull { it.name == intent.optionId } ?: RuleTarget.GROUP_NAME)); refreshPreview(); PageAction()
            }
            intent.row == ChannelsRows.RULE_KIND && intent is RowIntent.Choose -> {
                setDraft(draft(pid).copy(isRegex = intent.optionId == "REGEX")); refreshPreview(); PageAction()
            }
            intent.row == ChannelsRows.RULE_PATTERN && intent is RowIntent.Edit -> {
                setDraft(draft(pid).copy(pattern = intent.text)); refreshPreview(); PageAction()
            }
            intent.row == ChannelsRows.RULE_SAVE && intent is RowIntent.Run -> {
                when (val p = rules.add(pid, draft(pid))) {
                    is RulePreview.Counts -> {
                        setDraft(RuleDraft())
                        synchronized(previews) { previews.remove(pid) }
                        emitChanged(pid)
                        PageAction(notice = texts.get(BackupText.RULE_SAVED))
                    }
                    is RulePreview.Invalid -> {
                        synchronized(previews) { previews[pid] = p }
                        PageAction(notice = ChannelsPages.problemText(p.reason, texts))
                    }
                }
            }
            key.startsWith(ChannelsRows.RULE_PREFIX) && intent is RowIntent.Menu -> {
                val id = key.removePrefix(ChannelsRows.RULE_PREFIX).toLongOrNull() ?: return PageAction.NONE
                when (intent.optionId) {
                    ChannelsRows.M_DELETE -> { rules.delete(id); emitChanged(pid); PageAction(notice = texts.get(BackupText.RULE_DELETED)) }
                    ChannelsRows.M_DISABLE -> { rules.setEnabled(id, false); emitChanged(pid); PageAction() }
                    ChannelsRows.M_ENABLE -> { rules.setEnabled(id, true); emitChanged(pid); PageAction() }
                    else -> PageAction.NONE
                }
            }
            else -> PageAction.NONE
        }
    }

    private fun emitChanged(pid: Long) {
        _invalidations.tryEmit(SettingsRoute.HiddenChannels(pid))
        _invalidations.tryEmit(SettingsRoute.Section(SectionId.CHANNELS))
    }

    private suspend fun favIntent(listId: Long, intent: RowIntent, key: String, now: Long): PageAction {
        val dao = db.favouriteDao()
        return when {
            intent.row == ChannelsRows.FAV_LIST_NAME && intent is RowIntent.Edit -> {
                val name = intent.text.trim()
                if (name.isEmpty()) return PageAction(notice = texts.get(BackupText.FAV_NAME_EMPTY), reload = false)
                dao.renameList(listId, name)
                PageAction()
            }
            intent.row == ChannelsRows.FAV_LIST_DELETE && intent is RowIntent.Run -> {
                if (dao.lists().size <= 1) return PageAction(notice = texts.get(BackupText.FAV_LIST_ONLY_ONE), reload = false)
                dao.deleteList(listId)
                PageAction(open = SettingsRoute.FavouriteLists)
            }
            key.startsWith(ChannelsRows.FAV_ENTRY_PREFIX) -> {
                val rest = key.removePrefix(ChannelsRows.FAV_ENTRY_PREFIX)
                val pid = rest.substringBefore('/').toLongOrNull() ?: return PageAction.NONE
                val sk = rest.substringAfter('/')
                when {
                    intent is RowIntent.Menu && intent.optionId == ChannelsRows.M_REMOVE -> { dao.delete(listId, pid, sk); PageAction() }
                    intent is RowIntent.Move -> {
                        val order = entries(listId).map { it.playlistId to it.stableKey }.toMutableList()
                        reorder(order, pid to sk, intent.toIndex)
                        db.writeTransaction {
                            forEachBound("UPDATE favourite SET sort_index = ? WHERE list_id = ? AND playlist_id = ? AND stable_key = ?", order.withIndex()) { (i, e) ->
                                bindLong(1, i.toLong()); bindLong(2, listId); bindLong(3, e.first); bindText(4, e.second)
                            }
                        }
                        PageAction()
                    }
                    else -> PageAction.NONE
                }
            }
            else -> PageAction.NONE
        }
    }

    companion object {
        private val LIVE = EntryKind.LIVE.ordinal

        /** The guide domain: WP-A's object (M5 integration). */
        val GUIDE: SettingsDomain<GuidePrefs> get() = app.thebox.core.settings.state.GuideDomain

        fun <T> reorder(list: MutableList<T>, item: T, toIndex: Int) {
            val from = list.indexOf(item)
            if (from < 0) return
            list.removeAt(from)
            list.add(toIndex.coerceIn(0, list.size), item)
        }
    }
}
