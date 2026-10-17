package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * C-B1 (M5 code review 2026-10-16, adopted from the core reviewer's reproductions): four or more pages on the open path
 * overflowed `PAGE_CACHE` and `onPage`'s eviction threw `NoSuchElementException` on main.
 */
class SettingsMachinePageCacheTest {

    /** C-B1: Playlists → Playlist → Edit source → Phone setup puts four pages on the path. */
    @Test
    fun fourPagesOnThePathDoNotCrash() {
        val w = SettingsWorld().open()
        w.enter(SectionId.PLAYLISTS)
        w.machine.push(SettingsRoute.Playlist(1)); w.pump()
        w.machine.push(SettingsRoute.PlaylistSource(1)); w.pump()
        w.machine.push(SettingsRoute.PhoneSetup(firstRun = false)); w.pump()
        assertEquals(SettingsRoute.PhoneSetup(firstRun = false), w.ui.route)
        assertEquals(SettingsRoute.PhoneSetup(firstRun = false), w.ui.page?.route)
    }

    /** C-B1: first run, mainstream: Welcome → form → (replace) import → review → report → Fix now → pick a channel. */
    @Test
    fun firstRunFixNowThenPickerDoesNotCrash() {
        val w = SettingsWorld().open(SettingsRoute.FirstRun)
        w.machine.push(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)); w.pump()
        w.machine.replace(SettingsRoute.ImportProgress(1, firstRun = true)); w.pump()
        w.machine.replace(SettingsRoute.GroupsReview(1, firstRun = true)); w.pump()
        w.machine.replace(SettingsRoute.MatchingReport(1, firstRun = true)); w.pump()
        w.machine.push(SettingsRoute.FixGuide(null)); w.pump()
        w.machine.push(SettingsRoute.FixGuide(w.fixtures.channel)); w.pump()
        assertEquals(SettingsRoute.FixGuide(w.fixtures.channel), w.ui.page?.route)
    }

    /** C-B1: first run: Welcome → chooser → Xtream form → result card "Phone setup" → PhoneSetup, then BACK. */
    @Test
    fun firstRunPhoneDetourDoesNotCrash() {
        val w = SettingsWorld().open(SettingsRoute.FirstRun)
        w.machine.push(SettingsRoute.AddSource(null, firstRun = true)); w.pump()
        w.machine.push(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true)); w.pump()
        w.machine.push(SettingsRoute.PhoneSetup(firstRun = true)); w.pump()
        assertEquals(SettingsRoute.PhoneSetup(firstRun = true), w.ui.page?.route)
        w.act(BoxAction.BACK)
        assertEquals(SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true), w.ui.page?.route)
    }
}
