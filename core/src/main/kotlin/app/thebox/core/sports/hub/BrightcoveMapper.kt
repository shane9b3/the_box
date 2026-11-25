package app.thebox.core.sports.hub

import kotlinx.serialization.json.JsonObject

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.3; WP-2) Maps one playback-service video answer: `name`,
 * `long_description` (else `description`), `duration`, `poster`, `thumbnail`; `sources[]` in the §6.3 order (https HLS
 * without `key_systems`, https MP4 ≤ 720p, then the http variants; DASH and DRM ignored); English captions. Pure.
 *
 * Commit 0 stub: maps nothing (null). Only WP-2's `NhlClipResolver` calls it, so WP-2 may refine the signatures.
 */
object BrightcoveMapper {
    /** The metadata of [ref] from [body]; null = unmappable. */
    @Suppress("UNUSED_PARAMETER")
    fun info(ref: ClipRef, body: JsonObject): ClipInfo? = null

    /** The best playable source of [body] (and its captions), or null when nothing is playable. */
    @Suppress("UNUSED_PARAMETER")
    fun stream(body: JsonObject): ClipStream? = null
}
