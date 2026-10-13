package app.thebox.core.model

import java.time.Instant

/**
 * One EPG entry. Referenced elsewhere by `(stableKey, startMin, titleHash)`, never by row id.
 */
data class Programme(
    val epgChannelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val iconUrl: String? = null,
) {
    init {
        require(!stop.isBefore(start)) { "Programme stop before start: $start..$stop" }
    }

    /** Start in epoch minutes; the `programme` table key column. */
    val startMin: Long get() = start.epochSecond / 60

    val durationMinutes: Long get() = (stop.epochSecond - start.epochSecond) / 60

    fun isAiringAt(instant: Instant): Boolean = !instant.isBefore(start) && instant.isBefore(stop)
}
