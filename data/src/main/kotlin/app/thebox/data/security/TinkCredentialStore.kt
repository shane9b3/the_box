package app.thebox.data.security

import android.content.Context
import app.thebox.core.common.Outcome
import app.thebox.data.contracts.CredentialStore
import app.thebox.data.contracts.PlaylistSecret
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tink AEAD + Keystore credential store (docs/design/m1-data-layer.md §3.9). Commit-0 stub; WP-D owns this file. */
@Suppress("unused")
@Singleton
class TinkCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CredentialStore {
    override fun newRef(): String = TODO("WP-D")
    override suspend fun put(ref: String, secret: PlaylistSecret): Outcome<Unit> = TODO("WP-D")
    override suspend fun get(ref: String): Outcome<PlaylistSecret> = TODO("WP-D")
    override suspend fun delete(ref: String): Unit = TODO("WP-D")
}
