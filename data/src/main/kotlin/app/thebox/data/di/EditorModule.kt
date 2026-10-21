package app.thebox.data.di

import app.thebox.core.editor.ChannelEditorRepository
import app.thebox.data.editor.ChannelEditorRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The channel editor's data side (docs/design/m6-channel-editor.md §3.4, §7.3). */
@Module
@InstallIn(SingletonComponent::class)
abstract class EditorModule {
    @Binds @Singleton abstract fun channelEditorRepository(impl: ChannelEditorRepositoryImpl): ChannelEditorRepository
}
