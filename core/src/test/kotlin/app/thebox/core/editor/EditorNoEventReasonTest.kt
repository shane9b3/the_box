package app.thebox.core.editor

import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.BackupText
import app.thebox.core.settings.tree.BackupTexts
import app.thebox.core.settings.tree.ChannelsPages
import app.thebox.core.settings.tree.HiddenChannelRow
import app.thebox.core.setup.pages.PlaylistPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/design/m6-event-channels.md §5.5, re-review E1: a channel hidden by the "Hide channels with no event" switch says
 * so in the channel editor's menu ([EditorText.D_NO_EVENT]) and on the Hidden channels page ([BackupText.HIDDEN_NO_EVENT],
 * linking to the playlist's settings), never "change it in Hide rules".
 */
class EditorNoEventReasonTest {
    private fun row(noEvent: Boolean, pattern: String? = null) = EditorChannelRow(
        stableKey = "k", name = "Sportsnet+ 11", providerName = "Sportsnet+ 11: NO EVENT", number = 1, customNumber = null, logoUrl = null,
        hidden = false, ruleHidden = true, rulePattern = pattern, locked = false, lockedByGroup = null, favorite = false,
        movedFrom = null, renamed = false, noEvent = noEvent,
    )

    private fun showLine(single: EditorChannelRow?, s: SelectionSummary): MenuLine =
        EditorMenus.channelLines(
            EditorMenus.ChannelContext(EditorList.All, EditorFilter.ALL, Selection.None, single, 20, 3), s,
        ).first { it.action == EditorAction.SHOW }

    @Test
    fun oneSwitchHiddenTargetSaysNoEvent() {
        val r = row(noEvent = true)
        val line = showLine(r, EditorMenus.summaryOf(r))
        assertEquals(false, line.enabled)
        assertEquals(EditorText.D_NO_EVENT, line.reason)
    }

    @Test
    fun aUserRuleKeepsItsReason() {
        val r = row(noEvent = false, pattern = "Oilers")
        assertEquals(EditorText.D_RULE, showLine(r, EditorMenus.summaryOf(r)).reason)
        // Several targets: the summary does not split the causes.
        assertEquals(EditorText.D_RULE, showLine(null, SelectionSummary(2, 0, 2, 0, 0, 0, 0)).reason)
    }

    @Test
    fun theHiddenChannelsPageRow() {
        val t = BackupTexts { k, args -> if (args.isEmpty()) k.name else k.name + args.joinToString(",", "(", ")") }
        val sw = ChannelsPages.hiddenItem(7, HiddenChannelRow("k", "Sportsnet+ 11", "Sports", byUser = false, ruleId = 0, rulePattern = "x", noEvent = true), t)
        assertEquals(BackupText.HIDDEN_NO_EVENT.name, sw.description)
        assertEquals(SettingsRoute.Playlist(7, ChannelsPages.NO_EVENT_SWITCH), (sw.kind as RowKind.Item).route)
        assertEquals(PlaylistPage.ROW_HIDE_EMPTY_EVENTS, ChannelsPages.NO_EVENT_SWITCH.key)
        val rule = ChannelsPages.hiddenItem(7, HiddenChannelRow("k", "Plain", null, byUser = false, ruleId = 0, rulePattern = "x"), t)
        assertEquals("HIDDEN_BY_RULE(x)", rule.description)
        assertEquals(SettingsRoute.HideRules(7), (rule.kind as RowKind.Item).route)
        val user = ChannelsPages.hiddenItem(7, HiddenChannelRow("k", "Plain", null, byUser = true, ruleId = 0, rulePattern = "x", noEvent = true), t)
        assertEquals(BackupText.HIDDEN_BY_YOU.name, user.description, "the user's own hide wins")
    }
}
