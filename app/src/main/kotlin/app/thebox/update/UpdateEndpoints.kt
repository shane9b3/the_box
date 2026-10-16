package app.thebox.update

import app.thebox.core.update.UpdateEndpoint
import javax.inject.Inject

/**
 * The release build's update endpoint (docs/design/m5-setup-settings.md §3.12.1; reviewed 2026-10-16, S7: keys per
 * build type). **Owner decisions still open (§8 Q7, Q8):**
 * - TODO(owner, Q8): the GitHub `owner/repo` that publishes releases. Until it is set, [UPDATE_REPO] is null and the
 *   updater is disabled in release builds (no request ever leaves the device; the About row says so).
 * - TODO(owner, Q7): the manifest signing public keys (current and next, hex, 32 bytes each), generated and kept offline
 *   by the owner. The private keys are never in this repository.
 * Debug builds never read this object: `app/src/debug/.../DebugUpdateEndpoint` contributes an [UpdateEndpointOverride]
 * with the debug test key only, and release builds contain no override (the debug source set is not compiled into them).
 */
object ReleaseUpdateConfig {
    /** `{owner}/{repo}`; null until the owner names it (§8 Q8). */
    val UPDATE_REPO: String? = null

    /** Ed25519 public keys (hex). Empty until the owner generates them (§8 Q7). */
    val UPDATE_PUBLIC_KEYS_HEX: List<String> = emptyList()

    fun manifestUrl(repo: String): String = "https://github.com/$repo/releases/latest/download/update.json"

    val endpoint: UpdateEndpoint
        get() = UpdateEndpoint(
            manifestUrl = UPDATE_REPO?.let(::manifestUrl),
            publicKeys = UPDATE_PUBLIC_KEYS_HEX.map(::hexToBytes),
            anyHost = false,
            label = "release",
        )

    fun hexToBytes(s: String): ByteArray = s.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** A build-type endpoint that replaces the release one (debug builds contribute exactly one). */
fun interface UpdateEndpointOverride {
    fun endpoint(): UpdateEndpoint
}

/** Picks the endpoint of the running build: a debug override when one is compiled in, else [ReleaseUpdateConfig]. */
class UpdateEndpoints @Inject constructor(private val overrides: Set<@JvmSuppressWildcards UpdateEndpointOverride>) {
    fun current(): UpdateEndpoint = overrides.firstOrNull()?.endpoint() ?: ReleaseUpdateConfig.endpoint
}
