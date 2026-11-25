package app.thebox.data.sports

import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.2, A-S4, A-N6; WP-2) The playback key, read at run time from the public
 * player script: memory only, once per process on first need, `Redact.registerSecret` at once, a hold of
 * `HUB_VIDEO_KEY_REREAD_MS` after any failed read, one re-read after a 401 / 403. Never logged, stored or written down.
 *
 * Commit 0 stub: has no key (null) and makes no request. WP-2 changes the constructor freely (Hilt injects it).
 */
@Singleton
class NhlVideoKeySource @Inject constructor() {
    /** The key, reading the script if needed; null when it cannot be read (or inside the hold). */
    suspend fun key(): String? = null

    /** Drop the key after a refusal, so the next [key] re-reads the script (once). */
    fun invalidate() = Unit
}
