package app.thebox.ui.editor

import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.setup.PlaylistAdmin
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * The settings page of `SettingsRoute.ChannelEditor` (docs/design/m6-channel-editor.md §3.1): no rows (the editor
 * screen draws the page), titled "Channel editor · {playlist}" so the settings header's breadcrumb reads
 * "Settings › Channels and groups › Channel editor · MyProvider". It keeps the shell's header, cursor memory and open
 * trace working.
 */
class ChannelEditorPageSource internal constructor(
    private val texts: EditorTexts,
    private val playlistName: suspend (Long) -> String?,
) : SettingsPageSource {
    @Inject constructor(texts: EditorTexts, admin: PlaylistAdmin) : this(texts, { id -> admin.summary(id)?.name })

    override fun handles(route: SettingsRoute): Boolean = route is SettingsRoute.ChannelEditor

    override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel {
        val pid = (route as SettingsRoute.ChannelEditor).playlistId
        val name = try {
            playlistName(pid)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            null
        }
        val title = texts.get(EditorText.TITLE)
        return PageModel(route, if (name.isNullOrBlank()) title else title + SEP + name, rows = emptyList(), version = 1L)
    }
}
