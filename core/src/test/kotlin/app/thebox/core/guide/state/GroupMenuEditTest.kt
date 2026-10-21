package app.thebox.core.guide.state

import app.thebox.core.guide.GroupMenuItem
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction.CONTEXT_MENU
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.GROUPS_PANEL
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.SettingsRoute
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** docs/design/m6-channel-editor.md §2.1, §3.1: the group menu's "Edit channels…" is last and opens the gated route. */
class GroupMenuEditTest {
    private fun GuideWorld.openGroupMenuOn(index: Int): List<GroupMenuItem> {
        press(GROUPS_PANEL)
        val o = ui.overlay as Overlay.Groups
        val target = o.shortcuts.size + index
        repeat(target - o.focused) { press(DOWN) }
        press(CONTEXT_MENU)
        return (ui.overlay as Overlay.Groups).menu!!
    }

    private fun GuideWorld.selectLast(menu: List<GroupMenuItem>) {
        repeat(menu.size - 1) { press(DOWN) }
        press(SELECT)
    }

    @Test fun editIsTheLastItem() {
        val w = GuideWorld()
        val i = w.entries.indexOfFirst { it.source == w.groupSource }
        val menu = w.openGroupMenuOn(i)
        assertEquals(GroupMenuItem.EDIT, menu.last())
        assertEquals(listOf(GroupMenuItem.HIDE, GroupMenuItem.LOCK, GroupMenuItem.MOVE_UP, GroupMenuItem.MOVE_DOWN, GroupMenuItem.EDIT), menu)
    }

    @Test fun editOpensTheEditorOnThatGroup() {
        val w = GuideWorld()
        val i = w.entries.indexOfFirst { it.source == w.group2Source }
        w.selectLast(w.openGroupMenuOn(i))
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertEquals(SettingsRoute.ChannelEditor(1, "sport"), w.calls.last("openSettingsRoute")!!.args[0])
    }

    @Test fun editAsksForThePinWhenSettingsAreLocked() {
        val w = GuideWorld()
        w.send(GuideEvent.Parental(ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy(lockSettings = true))))
        val i = w.entries.indexOfFirst { it.source == w.groupSource }
        w.selectLast(w.openGroupMenuOn(i))
        assertEquals(ScreenMode.GUIDE, w.ui.mode, "not yet")
        assertEquals(PinPurpose.EnterSettings(SettingsRoute.ChannelEditor(1, "news")), w.ui.pin!!.purpose)
        assertNull(w.calls.last("openSettingsRoute"))
    }
}
