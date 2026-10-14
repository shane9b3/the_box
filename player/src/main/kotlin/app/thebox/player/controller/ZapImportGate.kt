package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import app.thebox.core.common.ImportGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImportGate] implementation (§3.5.5): holds importers while a zap is in progress. Public so `:app` can install it in
 * `ImportMutex.gate` (§6.5).
 */
@Singleton
class ZapImportGate @Inject constructor() : ImportGate {
    override fun awaitClear(): Unit = TODO("WP-D")
}
