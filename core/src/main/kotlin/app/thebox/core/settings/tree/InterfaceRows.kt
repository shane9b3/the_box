package app.thebox.core.settings.tree

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.EffectTiming.LIVE
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.Visibility.ADVANCED
import app.thebox.core.settings.Visibility.SIMPLE

/** WP-A: the Interface section (docs/design/m5-setup-settings.md §3.3.6). Every row is LIVE. */
object InterfaceRows : RowSource {
    val CLOCK = RowId("interface.clock")
    val MINI_PLAYER = RowId("interface.mini_player")
    val PERFORMANCE = RowId("interface.performance")
    /** (owner request 2026-11-09) "Scroll long descriptions" in the guide panel; simple (the §3.3.13 budget is 36). */
    val SCROLL_DESCRIPTIONS = RowId("interface.scroll_descriptions")
    val LOGOS = RowId("interface.logos")
    val AMBIENT = RowId("interface.ambient")
    val NUMBERS = RowId("interface.numbers")
    val REMINDER_SWITCH = RowId("interface.reminder_switch")
    val REMINDER_LEAD = RowId("interface.reminder_lead")

    override val rows: List<RowSpec> = listOf(
        a(CLOCK, SIMPLE, LIVE),
        a(MINI_PLAYER, SIMPLE, LIVE),
        a(PERFORMANCE, SIMPLE, LIVE),
        a(SCROLL_DESCRIPTIONS, SIMPLE, LIVE),
        a(LOGOS, ADVANCED, LIVE),
        a(AMBIENT, ADVANCED, LIVE),
        a(NUMBERS, ADVANCED, LIVE),
        a(REMINDER_SWITCH, ADVANCED, LIVE),
        a(REMINDER_LEAD, ADVANCED, LIVE),
    )

    private fun a(id: RowId, v: Visibility, e: EffectTiming) = RowSpec(id, SectionId.INTERFACE, v, e, "A")
}
