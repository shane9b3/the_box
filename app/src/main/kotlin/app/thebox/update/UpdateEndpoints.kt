package app.thebox.update

import app.thebox.core.update.UpdateEndpoint
import javax.inject.Inject

/**
 * The release build's update endpoint (docs/design/m5-setup-settings.md §3.12.1; reviewed 2026-10-16, S7: keys per
 * build type). §8 Q7 and Q8 were resolved by the owner on 2026-11-08:
 * - Q8: releases are published on the public GitHub repository [UPDATE_REPO]. The design names a `BuildConfig` field;
 *   a plain constant here does the same job with one source of truth (`tools/release/release.py` reads it to check that
 *   the manifest it publishes is the one this build fetches).
 * - Q7: [UPDATE_PUBLIC_KEYS_HEX] holds the manifest signing keys, current first, then the next key held for rotation.
 *   The private halves are kept offline on the release Mac (`~/.thebox-release/`, see `tools/release/README.md`) and
 *   are never in this repository. Releases are built and signed there by `tools/release/release.py`, not in CI.
 * Debug builds never read this object: `app/src/debug/.../DebugUpdateEndpoint` contributes an [UpdateEndpointOverride]
 * with the debug test key only, and release builds contain no override (the debug source set is not compiled into them).
 */
object ReleaseUpdateConfig {
    /** `{owner}/{repo}` on GitHub whose latest release carries `update.json` and `update.json.sig` (§8 Q8). */
    val UPDATE_REPO: String? = "shane9b3/the_box"

    /**
     * Ed25519 public keys (hex, 32 bytes each, §8 Q7): the key that signs today's manifests, then the next key. To
     * rotate, ship a release signed by the current key that lists the new pair, then start signing with the next key.
     */
    val UPDATE_PUBLIC_KEYS_HEX: List<String> = listOf(
        "dc0243f367bda3024ea97a4b82777d41abd9b0ea4a92fbc607be1009af5ad7ba", // current (update-current.pem)
        "4b056d8a1dc63b2fbdd9df8b282788bbd3a43f5a0b1dbcd9cd2a1cf4af0002d6", // next (update-next.pem), for rotation
    )

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
