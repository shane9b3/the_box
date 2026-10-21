package app.thebox.ui.editor

import app.thebox.core.editor.ChannelEditorMachineFactory
import app.thebox.core.editor.ChannelEditorMachineImpl
import app.thebox.core.editor.EditorTexts
import app.thebox.core.settings.state.SettingsPageSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * WP-C's `:ui` bindings of the channel editor (docs/design/m6-channel-editor.md §7.4): the editor texts, the machine
 * factory, the route's settings page source (`@IntoSet`) and the application-lifetime scope of the write FIFO.
 * `ChannelEditorRepository` is bound by `:data` (`EditorModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EditorUiModule {
    @Binds abstract fun editorTexts(impl: ResourceEditorTexts): EditorTexts

    @Binds @IntoSet abstract fun channelEditorPage(impl: ChannelEditorPageSource): SettingsPageSource

    companion object {
        @Provides
        fun channelEditorMachineFactory(): ChannelEditorMachineFactory = ChannelEditorMachineImpl.FACTORY

        /** Lives as long as the process (as `:data`'s `@ApplicationScope`): queued editor writes survive leaving the editor. */
        @Provides @Singleton @EditorWriteScope
        fun editorWriteScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
