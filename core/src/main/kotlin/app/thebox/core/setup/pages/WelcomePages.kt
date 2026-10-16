package app.thebox.core.setup.pages

import app.thebox.core.settings.PageModel
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import app.thebox.core.setup.SetupText

/**
 * Welcome (S1, docs/design/m5-setup-settings.md §3.4.2): four cards as Link rows in card order (← / → move between
 * them; `WelcomeScreen` draws them as cards), then the footer note. Back exits the app (the shell's FirstRun rule).
 * Restore backup opens WP-D's Backup page (its restore sources: phone, file, Downloads; §3.4.1).
 */
class WelcomePage(d: SetupPageDeps) : SetupPage(SettingsRoute.FirstRun, d) {
    override fun start() = publish()

    override fun build(): PageModel = page(
        title = t(SetupText.WELCOME_TITLE),
        subtitle = t(SetupText.WELCOME_BODY),
        rows = listOf(
            link(ROW_PHONE, t(SetupText.WELCOME_PHONE), SettingsRoute.PhoneSetup(firstRun = true), description = t(SetupText.WELCOME_PHONE_BODY)),
            link(ROW_XTREAM, t(SetupText.WELCOME_XTREAM), SettingsRoute.AddSource(SourceKind.XTREAM, firstRun = true), description = t(SetupText.WELCOME_XTREAM_BODY)),
            link(ROW_M3U, t(SetupText.WELCOME_M3U), SettingsRoute.AddSource(SourceKind.M3U, firstRun = true), description = t(SetupText.WELCOME_M3U_BODY)),
            link(ROW_RESTORE, t(SetupText.WELCOME_RESTORE), SettingsRoute.Backup, description = t(SetupText.WELCOME_RESTORE_BODY)),
            info(ROW_NOTE, t(SetupText.WELCOME_NOTE), emptyList()),
        ),
    )

    companion object {
        const val ROW_PHONE = "setup.welcome.phone"
        const val ROW_XTREAM = "setup.welcome.xtream"
        const val ROW_M3U = "setup.welcome.m3u"
        const val ROW_RESTORE = "setup.welcome.restore"
        const val ROW_NOTE = "setup.welcome.note"

        /** Default card (§3.4.2): Set up from phone, unless a physical keyboard is attached, then Xtream login. */
        fun defaultCard(hasPhysicalKeyboard: Boolean): Int = if (hasPhysicalKeyboard) 1 else 0
    }
}

/** AddSource() with no kind (Settings → Playlists → Add playlist): the three ways in. */
class AddSourceChooserPage(route: SettingsRoute.AddSource, d: SetupPageDeps) : SetupPage(route, d) {
    private val firstRun = route.firstRun

    override fun start() = publish()

    override fun build(): PageModel = page(
        title = t(SetupText.ADD_TITLE),
        subtitle = t(SetupText.ADD_SUBTITLE),
        rows = listOf(
            link("setup.add.xtream", t(SetupText.WELCOME_XTREAM), SettingsRoute.AddSource(SourceKind.XTREAM, firstRun), description = t(SetupText.WELCOME_XTREAM_BODY)),
            link("setup.add.m3u", t(SetupText.WELCOME_M3U), SettingsRoute.AddSource(SourceKind.M3U, firstRun), description = t(SetupText.WELCOME_M3U_BODY)),
            link("setup.add.phone", t(SetupText.WELCOME_PHONE), SettingsRoute.PhoneSetup(firstRun), description = t(SetupText.WELCOME_PHONE_BODY)),
        ),
    )
}
