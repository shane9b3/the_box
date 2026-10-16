package app.thebox.player.probe

import app.thebox.core.setup.ProbeResult
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.StreamProbe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The onboarding UA probe through the playlist's broker (docs/design/m5-setup-settings.md §2.4, §3.4.5).
 * **Commit-0 stub (WP-B replaces the body):** opens nothing and answers OTHER.
 */
@Singleton
class StreamProbeImpl @Inject constructor() : StreamProbe {
    override suspend fun probe(playlistId: Long, url: String, headers: Map<String, String>, userAgent: String?): ProbeResult =
        ProbeResult(ProbeVerdict.OTHER, httpStatus = null, bytes = 0, ms = 0)
}
