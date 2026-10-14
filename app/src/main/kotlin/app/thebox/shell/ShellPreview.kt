package app.thebox.shell

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.thebox.player.api.PlaybackController
import app.thebox.ui.root.ShellHost
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * TEMPORARY, debug-only seam for WP-C's visual check before WP-A/B/D land (docs/design/m3-guide.md §1.7: WP-C builds
 * against fakes). Bound only in `src/debug` (`DebugShellPreviewModule`) and used only when MainActivity is started with
 * `--ez thebox.shell_preview true`; release and benchmark builds see `Optional.empty()`.
 * The integrator deletes this file, `app/src/debug/.../ShellPreviewImpl.kt` and the preview branch in MainActivity.
 */
interface ShellPreview {
    fun host(controller: PlaybackController, intent: Intent): ShellHost

    @Composable
    fun Grid(modifier: Modifier)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellPreviewModule {
    @BindsOptionalOf
    abstract fun shellPreview(): ShellPreview
}
