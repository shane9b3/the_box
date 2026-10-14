package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import app.thebox.player.internal.TsCompat

/** TS-only extractor list with the session's compat level, wrapped in the dead-track guard (§3.8.5, S5). */
@OptIn(UnstableApi::class)
internal class TsExtractorsFactory(private val compat: TsCompat) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = TODO("WP-C")
}
