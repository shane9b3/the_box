package app.thebox.update

import app.thebox.core.update.UpdateEndpoint
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds' update endpoint (docs/design/m5-setup-settings.md §3.12.1, §6.5; reviewed 2026-10-16, S7). Only in the
 * debug source set, so release builds never contain the debug key, and debug builds never trust the release keys
 * ([UpdateEndpoints] takes this override first). The manifest URL comes from the debug harness
 * (`thebox-debug://guide?update_manifest=URL`, see `GuideDebugReceiver`); until then the updater is disabled.
 *
 * Keys trusted: [DEBUG_PUBLIC_KEY_HEX] (a throwaway pair generated for M5 WP-E; its private half lives only in the
 * gitignored `tools/fakepanel/update_test_key` on the machine that generated it) plus one key the harness registers at
 * run time (`update_key=HEX`): the fake panel signs with a fresh per-run key when that file is absent, and the
 * instrumented suite passes the panel's `/update/pubkey`. No private key is in this repository.
 */
@Singleton
class DebugUpdateEndpoint @Inject constructor() : UpdateEndpointOverride {
    @Volatile var manifestUrl: String? = null
    @Volatile var runtimeKeyHex: String? = null

    override fun endpoint(): UpdateEndpoint = UpdateEndpoint(
        manifestUrl = manifestUrl,
        publicKeys = listOfNotNull(DEBUG_PUBLIC_KEY_HEX, runtimeKeyHex).filter { it.length == 64 }.map(ReleaseUpdateConfig::hexToBytes),
        anyHost = true,
        label = "debug",
    )

    companion object {
        const val DEBUG_PUBLIC_KEY_HEX = "0cbdb295f6fd7d0464c7e9ffdbbf2d6269cc407ec81ac77a68eb2066109380d1"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugUpdateModule {
    @Binds @IntoSet abstract fun debugEndpoint(impl: DebugUpdateEndpoint): UpdateEndpointOverride
}
