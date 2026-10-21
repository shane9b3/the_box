package app.thebox.core.editor

/**
 * Every user string of the channel editor (docs/design/m6-channel-editor.md §5; `strings_editor.xml` in `:ui`,
 * `ResourceEditorTexts`). Keys in [EditorText.PLURAL] are quantity strings and go through [EditorTexts.count].
 * American English throughout ("Favorites", "program").
 */
enum class EditorText {
    TITLE, GROUPS_TITLE, GROUPS_COUNT, ALL_CHANNELS, FIND_RESULTS, CHANNELS_COUNT, SELECTED_COUNT, SAVING,
    DETAIL_MOVED, DETAIL_RENAMED, DETAIL_RULE, LOCKED_NOTE,
    TB_ACTIONS, TB_SORT, TB_FIND, TB_SHOW, TB_HIDDEN_GROUPS_ON, TB_HIDDEN_GROUPS_OFF,
    FILTER_ALL, FILTER_SHOWN, FILTER_HIDDEN,
    SORT_NAME_AZ, SORT_NAME_ZA, SORT_NUMBER, SORT_PROVIDER,
    MENU_TITLE_CHANNELS, MENU_TITLE_GROUPS,
    A_HIDE, A_SHOW, A_MOVE_TO_GROUP, A_REORDER, A_ADD_FAVORITE, A_REMOVE_FAVORITE, A_LOCK, A_UNLOCK,
    A_RENAME, A_SET_NUMBER, A_CLEAR_NUMBER, A_SELECT_ALL, A_FIND, A_SHOW_FILTER, A_SORT, A_SORT_GROUPS,
    A_EDIT_CHANNELS, A_DROP_RESULTS, A_RESET,
    PICKER_TITLE, PICKER_HERE, PICKER_HIDDEN_DETAIL, MOVE_HEADER,
    EDIT_RENAME_CHANNEL, EDIT_RENAME_GROUP, EDIT_NUMBER, EDIT_FIND,
    N_HIDDEN, N_SHOWN, N_GROUPS_HIDDEN, N_GROUPS_SHOWN, N_SKIPPED_RULE, N_MOVED, N_ORDER_SAVED, N_SORTED,
    N_RENAMED, N_NAME_RESET, N_NUMBER_SET, N_NUMBER_CLEARED, N_NUMBER_CLASH, N_FAVORITE_ADDED, N_FAVORITE_REMOVED,
    N_LOCKED, N_LOCKED_NO_PIN, N_UNLOCKED, N_RESET, N_GROUPS_RESET, N_UNSELECTED, N_QUEUE_FULL, N_NO_NUMBER,
    C_SORT_TITLE, C_SORT_BODY, C_PROVIDER_TITLE, C_PROVIDER_BODY, C_RESET_TITLE, C_RESET_BODY,
    C_RESET_GROUPS_TITLE, C_RESET_GROUPS_BODY, C_CONFIRM_SORT, C_CONFIRM_RESET, CANCEL,
    FOOT_BROWSE, FOOT_SELECT, FOOT_CHANNELS, FOOT_GROUPS, FOOT_ACTIONS, FOOT_DONE, FOOT_CLEAR, FOOT_MOVE, FOOT_DROP,
    FOOT_CANCEL, FOOT_GUIDE,
    // §5.2 empty, disabled and error states (reason + next step)
    E_NO_CHANNELS, E_PLAYLIST_GONE, E_EMPTY_HIDDEN, E_EMPTY_SHOWN, E_EMPTY_GROUP, E_FIND_NONE, E_LOCKED,
    E_PIN_UNLOCK, E_PIN_RESET, E_PIN_MOVE, E_GONE, E_LOAD, E_WRITE, E_WRITE_BUSY,
    D_REORDER_LIST, D_REORDER_FILTER, D_REORDER_ALL, D_SINGLE, D_RULE, D_LOCKED_BY_GROUP, D_NO_GROUPS, D_GROUP_TICK_FIRST,
    // Code review 2026-10-18 (S4, S7, S8, N1, N2; append-only)
    D_REORDER_HIDDEN_GROUPS, D_REORDER_LOCKED, C_SORT_GROUPS_TITLE, C_SORT_GROUPS_BODY, C_PROVIDER_GROUPS_BODY,
    GROUP_VALUE_HIDDEN, GROUPS_COUNT_NONE, CHANNELS_COUNT_NONE, E_SUMMARY,
    ;

    companion object {
        /** Quantity strings (§5.1 "(P)"): the first argument is the count. */
        val PLURAL: Set<EditorText> = setOf(
            GROUPS_COUNT, CHANNELS_COUNT, SELECTED_COUNT, MENU_TITLE_CHANNELS, MENU_TITLE_GROUPS, PICKER_TITLE,
            N_HIDDEN, N_SHOWN, N_GROUPS_HIDDEN, N_GROUPS_SHOWN, N_SKIPPED_RULE, N_MOVED, N_FAVORITE_ADDED, N_FAVORITE_REMOVED,
            N_LOCKED, N_UNLOCKED, N_RESET, N_GROUPS_RESET, N_UNSELECTED, C_RESET_TITLE, C_RESET_GROUPS_TITLE,
            GROUP_VALUE_HIDDEN, GROUPS_COUNT_NONE, CHANNELS_COUNT_NONE,
        )
    }
}

interface EditorTexts {
    fun get(key: EditorText, vararg args: Any): String
    /** A quantity string for [n] (also the first format argument). */
    fun count(key: EditorText, n: Int, vararg args: Any): String

    companion object {
        /** Key names, for JVM tests: `KEY`, `KEY(a,b)`; counts as `KEY[n](a,b)`. */
        val KEYS: EditorTexts = object : EditorTexts {
            override fun get(key: EditorText, vararg args: Any): String =
                if (args.isEmpty()) key.name else key.name + args.joinToString(",", "(", ")")
            override fun count(key: EditorText, n: Int, vararg args: Any): String =
                key.name + "[" + n + "]" + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")
        }
    }
}
