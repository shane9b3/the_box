package app.thebox.data.sports

import app.thebox.core.sports.FailKind
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.3; WP-2) Video lookups on the playback service (https only, `@ApiClient`,
 * 10 s, body cap `HUB_VIDEO_BODY_MAX`, `BrightcoveMapper`); [info] cached `HUB_CLIP_META_TTL_MS` (LRU 40), [stream]
 * always looked up again; signed tokens registered with `Redact.registerTransient`.
 *
 * Commit 0 stub: makes no request and answers `Failed(HTTP_OTHER)`. Bound in `SportsModule`; WP-2 changes the
 * constructor freely (Hilt injects it).
 */
@Singleton
class NhlClipResolver @Inject constructor() : ClipResolver {
    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> = ClipResult.Failed(FailKind.HTTP_OTHER)
    override suspend fun stream(ref: ClipRef): ClipResult<Pair<ClipInfo, ClipStream>> = ClipResult.Failed(FailKind.HTTP_OTHER)
}
