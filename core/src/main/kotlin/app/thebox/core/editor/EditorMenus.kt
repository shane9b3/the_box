package app.thebox.core.editor

/**
 * The action menus' lines (docs/design/m6-channel-editor.md §2.3 E3, §2.4 "Menus (ruled order, no wrap)", §2.6, §5.2).
 * Pure: the machine passes what it knows; the view maps each line to its label with [labelOf].
 *
 * [SelectionSummary] semantics (the view model's `summarize` must count the same way; [summaryOf] is the one-row form):
 * - `total`: targets; `hidden`: targets the user hid; `ruleHidden`: targets hidden by a rule **and not** by the user
 *   (so `total − hidden − ruleHidden` are shown);
 * - `locked`: targets locked by any means (their own lock or their group's); `lockedByGroup`: targets whose group is
 *   locked (an Unlock cannot help them); `favorite`: targets in the default Favorites list;
 * - `fromLockedGroup`: targets that sit in a locked group now (moving them out needs the PIN while the session is
 *   locked).
 */
object EditorMenus {
    /** What the channel menu acts on and where. [single] is the one target's row when there is exactly one. */
    data class ChannelContext(
        val list: EditorList,
        val filter: EditorFilter,
        val selection: Selection,
        val single: EditorChannelRow?,
        /** Rows in the list (Select all's count). */
        val listCount: Int,
        /** Real groups in the playlist (Move needs a second one). */
        val groupCount: Int,
        /** > 0: locked channels are left out of this list (the order there is partial: no Reorder). */
        val lockedOut: Int = 0,
    )

    fun summaryOf(row: EditorChannelRow): SelectionSummary {
        val byGroup = row.lockedByGroup != null
        return SelectionSummary(
            total = 1,
            hidden = if (row.hidden) 1 else 0,
            ruleHidden = if (row.ruleHidden && !row.hidden) 1 else 0,
            locked = if (row.locked || byGroup) 1 else 0,
            lockedByGroup = if (byGroup) 1 else 0,
            favorite = if (row.favorite) 1 else 0,
            fromLockedGroup = if (byGroup) 1 else 0,
        )
    }

    /**
     * The channel menu, in the ruled order: Hide or Show · Move to group… · Reorder · (the second of Hide/Show when mixed)
     * · Add to / Remove from Favorites · Lock or Unlock · Rename… · Set number… · Use the provider's number · Select all
     * in this list · Find… · Show… · Sort… · Reset to provider settings…. Rename, Set number and Use the provider's number
     * appear only with exactly one target (the last only when a custom number is set).
     */
    fun channelLines(c: ChannelContext, s: SelectionSummary): List<MenuLine> {
        val out = ArrayList<MenuLine>(14)
        val shown = (s.total - s.hidden - s.ruleHidden).coerceAtLeast(0)
        val hiddenAny = s.hidden + s.ruleHidden
        val showLine = if (s.hidden > 0) MenuLine(EditorAction.SHOW, s.hidden, true, null)
        else MenuLine(EditorAction.SHOW, null, false, EditorText.D_RULE)
        val mixed = shown > 0 && hiddenAny > 0
        out += if (shown > 0) MenuLine(EditorAction.HIDE, shown, true, null) else showLine
        out += if (c.groupCount >= 2) MenuLine(EditorAction.MOVE_TO_GROUP, null, true, null)
        else MenuLine(EditorAction.MOVE_TO_GROUP, null, false, EditorText.D_NO_GROUPS)
        out += reorderLine(c.list, c.filter, c.selection, c.lockedOut)
        if (mixed) out += showLine
        out += if (s.favorite < s.total) MenuLine(EditorAction.ADD_FAVORITE, null, true, null)
        else MenuLine(EditorAction.REMOVE_FAVORITE, null, true, null)
        out += when {
            s.locked < s.total -> MenuLine(EditorAction.LOCK, null, true, null)
            s.locked > s.lockedByGroup -> MenuLine(EditorAction.UNLOCK, null, true, null)
            else -> MenuLine(EditorAction.UNLOCK, null, false, EditorText.D_LOCKED_BY_GROUP)
        }
        val one = c.single
        if (one != null && s.total == 1) {
            out += MenuLine(EditorAction.RENAME, null, true, null)
            out += MenuLine(EditorAction.SET_NUMBER, null, true, null)
            if (one.customNumber != null) out += MenuLine(EditorAction.CLEAR_NUMBER, null, true, null)
        }
        out += tail(c.list, c.listCount)
        out += MenuLine(EditorAction.RESET, s.total, true, null)
        return out
    }

    /** Lines that need no target (an empty list, or the note row focused): Find…, Show…, Sort…. */
    fun noTargetLines(list: EditorList, listCount: Int): List<MenuLine> =
        tail(list, listCount).filter { it.action != EditorAction.SELECT_ALL || listCount > 0 }

    private fun tail(list: EditorList, listCount: Int): List<MenuLine> = listOf(
        MenuLine(EditorAction.SELECT_ALL, listCount, listCount > 0, null),
        MenuLine(EditorAction.FIND, null, true, null),
        MenuLine(EditorAction.FILTER, null, true, null),
        if (list is EditorList.Group) MenuLine(EditorAction.SORT, null, true, null)
        else MenuLine(EditorAction.SORT, null, false, EditorText.D_REORDER_LIST),
    )

    /** §2.6: a real group, the filter All, explicit ticks (or none), and every channel listed. */
    fun reorderLine(list: EditorList, filter: EditorFilter, selection: Selection, lockedOut: Int = 0): MenuLine = when {
        list !is EditorList.Group -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_LIST)
        selection is Selection.AllExcept -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_ALL)
        filter != EditorFilter.ALL -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_FILTER)
        lockedOut > 0 -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_LOCKED)
        else -> MenuLine(EditorAction.REORDER, null, true, null)
    }

    /**
     * The group menu: Hide or Show · Reorder · Lock or Unlock · Rename… · Edit channels · Sort groups… · Select all ·
     * Reset…. [targets] are the rows acted on (known for explicit ticks and the focused group); null under Select all,
     * where the flags are not known and both forms are offered (Hide · Reorder · Show · Lock · Unlock · …).
     * Rename appears only with exactly one target.
     */
    fun groupLines(targets: List<EditorGroupRow>?, total: Int, allExcept: Boolean, showHidden: Boolean, groupCount: Int): List<MenuLine> {
        val out = ArrayList<MenuLine>(10)
        val reorder = when {
            allExcept -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_ALL)
            !showHidden -> MenuLine(EditorAction.REORDER, null, false, EditorText.D_REORDER_HIDDEN_GROUPS)
            else -> MenuLine(EditorAction.REORDER, null, true, null)
        }
        if (targets == null) {
            out += MenuLine(EditorAction.HIDE, total, true, null)
            out += reorder
            out += MenuLine(EditorAction.SHOW, null, true, null)
            out += MenuLine(EditorAction.LOCK, null, true, null)
            out += MenuLine(EditorAction.UNLOCK, null, true, null)
        } else {
            val shown = targets.count { !it.hidden && !it.ruleHidden }
            val userHidden = targets.count { it.hidden }
            val hiddenAny = targets.size - shown
            val showLine = if (userHidden > 0) MenuLine(EditorAction.SHOW, userHidden, true, null)
            else MenuLine(EditorAction.SHOW, null, false, EditorText.D_RULE)
            out += if (shown > 0) MenuLine(EditorAction.HIDE, shown, true, null) else showLine
            out += reorder
            if (shown > 0 && hiddenAny > 0) out += showLine
            out += if (targets.all { it.locked }) MenuLine(EditorAction.UNLOCK, null, true, null)
            else MenuLine(EditorAction.LOCK, null, true, null)
            if (targets.size == 1) out += MenuLine(EditorAction.RENAME, null, true, null)
        }
        out += MenuLine(EditorAction.EDIT_CHANNELS, null, true, null)
        out += MenuLine(EditorAction.SORT, null, true, null)
        out += MenuLine(EditorAction.SELECT_ALL, groupCount, groupCount > 0, null)
        out += MenuLine(EditorAction.RESET, total, true, null)
        return out
    }

    /** The short menu of the fixed rows (§2.3): Edit channels · Find… · (Results only) Drop results. */
    fun headLines(entry: EditorList): List<MenuLine> = buildList {
        add(MenuLine(EditorAction.EDIT_CHANNELS, null, true, null))
        add(MenuLine(EditorAction.FIND, null, true, null))
        if (entry is EditorList.Find) add(MenuLine(EditorAction.DROP_RESULTS, null, true, null))
    }

    /** The label key of a line ([pane]: Sort is "Sort groups…" in the Groups pane). */
    fun labelOf(line: MenuLine, pane: EditorPane): EditorText = when (line.action) {
        EditorAction.HIDE -> EditorText.A_HIDE
        EditorAction.SHOW -> EditorText.A_SHOW
        EditorAction.MOVE_TO_GROUP -> EditorText.A_MOVE_TO_GROUP
        EditorAction.REORDER -> EditorText.A_REORDER
        EditorAction.ADD_FAVORITE -> EditorText.A_ADD_FAVORITE
        EditorAction.REMOVE_FAVORITE -> EditorText.A_REMOVE_FAVORITE
        EditorAction.LOCK -> EditorText.A_LOCK
        EditorAction.UNLOCK -> EditorText.A_UNLOCK
        EditorAction.RENAME -> EditorText.A_RENAME
        EditorAction.SET_NUMBER -> EditorText.A_SET_NUMBER
        EditorAction.CLEAR_NUMBER -> EditorText.A_CLEAR_NUMBER
        EditorAction.SELECT_ALL -> EditorText.A_SELECT_ALL
        EditorAction.FIND -> EditorText.A_FIND
        EditorAction.FILTER -> EditorText.A_SHOW_FILTER
        EditorAction.SORT -> if (pane == EditorPane.GROUPS) EditorText.A_SORT_GROUPS else EditorText.A_SORT
        EditorAction.RESET -> EditorText.A_RESET
        EditorAction.EDIT_CHANNELS -> EditorText.A_EDIT_CHANNELS
        EditorAction.DROP_RESULTS -> EditorText.A_DROP_RESULTS
    }

    /** Ticked rows: explicit keys, or the list minus the exceptions under Select all. */
    fun selectedCount(selection: Selection, listCount: Int): Int = when (selection) {
        Selection.None -> 0
        is Selection.Keys -> selection.keys.size
        is Selection.AllExcept -> (listCount - selection.except.size).coerceAtLeast(0)
    }

    fun isTicked(selection: Selection, key: String): Boolean = when (selection) {
        Selection.None -> false
        is Selection.Keys -> key in selection.keys
        is Selection.AllExcept -> key !in selection.except
    }
}
