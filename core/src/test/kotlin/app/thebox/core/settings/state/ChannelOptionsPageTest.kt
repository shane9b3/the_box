package app.thebox.core.settings.state

import app.thebox.core.playback.AspectMode
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.ChannelOptionsRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Channel options page builder (docs/design/m5-setup-settings.md §3.3.12): rows, values, the DST note, no key shown. */
class ChannelOptionsPageTest {
    private val route = SettingsRoute.Channel(SettingsWorld.ID)
    private val base = FixturePages().snapshot()

    @Test
    fun rowsInOrderWithTheirValues() {
        val p = ChannelOptionsPage.build(route, base, SettingsTexts.KEYS, 7)
        assertEquals(ChannelOptionsRows.rows.map { it.id }, p.rows.map { it.id })
        assertEquals("Channel 1.1", p.title)
        val fav = p.rows.first { it.id == ChannelOptionsRows.FAVOURITES }
        assertEquals(setOf(0), (fav.kind as RowKind.MultiChoice).selected)
        assertEquals("Favourites", fav.value)
        assertEquals("DEFAULT", p.rows.first { it.id == ChannelOptionsRows.ASPECT }.value)
        val audio = p.rows.first { it.id == ChannelOptionsRows.AUDIO_LANG }
        assertTrue(audio.enabled)
        val subs = p.rows.first { it.id == ChannelOptionsRows.SUBTITLES }
        assertFalse(subs.enabled, "nothing remembered: Forget is disabled with the reason")
        assertEquals("CH_NOT_REMEMBERED", subs.disabledReason)
        assertNotNull((p.rows.first { it.id == ChannelOptionsRows.EXTERNAL }.kind as RowKind.Action).confirm, "§3.3.4 asks before the hand-off")
        assertTrue(p.rows.first { it.id == ChannelOptionsRows.BUFFER }.description!!.contains("EFFECT_REPREPARE"))
    }

    @Test
    fun infoLinesNeverShowTheKeyAndCarryTheDstNote() {
        val lines = ChannelOptionsPage.infoLines(base, SettingsTexts.KEYS)
        assertTrue(lines.contains("INFO_IDENTITY(ID_PROVIDER)"))
        assertTrue(lines.contains("INFO_DST_NOTE"))
        assertTrue(lines.none { it.contains("xc:1-1") })
        assertFalse(ChannelOptionsPage.infoLines(base.copy(fixedOffsetZone = false), SettingsTexts.KEYS).contains("INFO_DST_NOTE"))
    }

    @Test
    fun rememberedChoicesAreSelected() {
        val p = ChannelOptionsPage.build(route, base.copy(aspect = AspectMode.ZOOM, shiftMin = 60), SettingsTexts.KEYS, 1)
        assertEquals("ASPECT_ZOOM", p.rows.first { it.id == ChannelOptionsRows.ASPECT }.value)
        assertEquals("SHIFT_VALUE(+1)", p.rows.first { it.id == ChannelOptionsRows.SHIFT }.value)
    }

    @Test
    fun aGoneChannelShowsTheReason() {
        val p = ChannelOptionsPage.build(route, null, SettingsTexts.KEYS, 1)
        assertEquals("CH_UNKNOWN", p.placeholder)
        assertTrue(p.rows.isEmpty())
    }
}
