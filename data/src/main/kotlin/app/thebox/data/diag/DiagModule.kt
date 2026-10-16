package app.thebox.data.diag

import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.Diagnostics
import app.thebox.core.diag.LogRing
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.StreamSampler
import app.thebox.core.update.SignatureVerifier
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** WP-E's `:data` bindings (docs/design/m5-setup-settings.md §2.13). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagModule {
    @Binds @Singleton abstract fun diagnostics(impl: DiagnosticsImpl): Diagnostics
    @Binds abstract fun networkTests(impl: DiagnosticsImpl): NetworkTests
    @Binds abstract fun diagSources(impl: DiagSourcesImpl): DiagSources
    @Binds @Singleton abstract fun logRing(impl: LogRingImpl): LogRing

    /** `:ui`'s `DiagUiModule` binds the sampler (it sees the playback controller); a `:data`-only graph has none. */
    @BindsOptionalOf abstract fun streamSampler(): StreamSampler

    companion object {
        /** The updater's Ed25519 verifier (Tink is a `:data` dependency; F11). */
        @Provides fun signatureVerifier(): SignatureVerifier = TinkEd25519Verifier()
    }
}
