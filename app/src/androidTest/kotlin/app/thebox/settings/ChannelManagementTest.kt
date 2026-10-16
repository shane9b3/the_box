package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.guide.GuideSource
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.TextHint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channels management and guide matching on the synthetic lineup (`--synthetic 2000`; docs/design/m5-setup-settings.md
 * §3.6.3, §3.7.3, §6.3): I-M5-23 hide rules (**awaits WP-D**: rule editor, `rule_hidden` pass) and I-M5-24 fix unmatched
 * (**awaits WP-B**: FixGuide pages, `EpgChannelSearchDao`). Both also need WP-A's shell.
 */
class ChannelManagementTest : SettingsTestBase() {

    private fun synthetic(): Long {
        assertTrue("the panel must run with --synthetic 2000", panel.statsJson().optJSONObject("synthetic") != null)
        return seedXtream(timeoutMs = 600_000)
    }

    private fun allCount(pid: Long): Int = runBlocking {
        withTimeoutOrNull(10_000) { probe.library.lineup(GuideSource.All(pid), ParentalAccess.OPEN).first() }!!.rowIds.size
    }

    /** I-M5-23 */
    @Test
    fun hideRuleByGroupName() {
        val pid = synthetic()
        launch()
        val before = allCount(pid)
        openSettings("rules:$pid")
        activate(rowWhere("Add rule") { it.kind is RowKind.Action }.id.key)
        editText(rowWhere("rule text", p = { r -> (r.kind as? RowKind.Text)?.hint == TextHint.PATTERN }), "Sports")
        val preview = awaitText("preview count", 15_000) { Regex("""\d[\d,]* channels""").containsMatchIn(it) }
        val previewCount = Regex("""(\d[\d,]*) channels""").find(preview)!!.groupValues[1].replace(",", "").toInt()
        activate(rowWhere("Save rule") { it.kind is RowKind.Action && it.label.contains("Save", true) }.id.key)
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (allCount(pid) == before && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
        assertEquals("the guide lineup excludes exactly the previewed channels", before - previewCount, allCount(pid))
        // Delete restores.
        val rule = rowWhere("the rule") { it.kind is RowKind.Item }
        moveTo(rule.id.key)
        press(KeyEvent.KEYCODE_MENU)
        awaitSettings("rule menu") { it.menu != null }
        val delete = sui().menu!!.options.indexOfFirst { it.label.contains("Delete", true) || it.id.contains("delete") }
        repeat(delete) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val d2 = android.os.SystemClock.elapsedRealtime() + 30_000
        while (allCount(pid) != before && android.os.SystemClock.elapsedRealtime() < d2) Thread.sleep(500)
        assertEquals("delete restores the lineup", before, allCount(pid))
    }

    /** I-M5-24 */
    @Test
    fun fixUnmatchedChannel() {
        val pid = synthetic()
        launch()
        openSettings("fixguide")
        // Synthetic channel 9 (id % 20 == 9) has no EPG id.
        val unmatched = rowWhere("unmatched channel 9", 20_000) { it.label.endsWith(" 9") }
        // (M5 integration) The list is paged: compare the page's list count, not the rows loaded so far.
        val countBefore = sui().page?.list?.count ?: rows().count { it.kind is RowKind.Item }
        activate(unmatched.id.key)
        awaitSettings("the picker") { it.route is SettingsRoute.FixGuide && (it.route as SettingsRoute.FixGuide).channel != null }
        editText(rowWhere("search field", p = { r -> (r.kind as? RowKind.Text) != null }), "News")
        val candidate = rowWhere("a guide channel", 15_000) { it.kind is RowKind.Item }
        activate(candidate.id.key)
        if (sui().confirm != null) {
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        awaitSettings("back on the list without the row", 30_000) { s -> s.route == SettingsRoute.FixGuide() && rows(s).none { it.id == unmatched.id } }
        assertTrue(awaitSettings("one fewer unmatched") { s -> (s.page?.list?.count ?: Int.MAX_VALUE) < countBefore }.page != null)
        assertTrue(pid > 0)
    }
}
