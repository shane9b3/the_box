package app.thebox.data.player

import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.resolve.AutoTsRetry
import app.thebox.core.playback.resolve.LearnedOutput
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.data.db.dao.PlaylistDao
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.di.WallClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `playlist.xtream_output_learned` with the Auto TS-retry back-off (docs/decisions/2026-11-25-auto-format-retries-ts.md).
 *
 * The retry's facts (when HLS was learned, the back-off step, a hold-off) ride in the same column behind the output name
 * ([LearnedOutput]), so they share its life exactly: Room keeps them across restarts, deleting the playlist deletes
 * them, the user's Stream format choice clears them ([PlaylistDao.setXtreamOutput]), and a backup never carries them
 * (`MergePlanner.PLAYLIST_COLUMNS` leaves the column out). Writes go through the one-column
 * [PlaylistDao.setXtreamOutputLearned], serialised here so a refusal and the flip's save cannot interleave.
 */
@Singleton
class LearnedOutputStore @Inject constructor(private val dao: PlaylistDao, private val clock: WallClock) {
    private val lock = Mutex()

    /** The flip reached first media ([AutoTsRetry.afterLearn]: an HLS already stored keeps its back-off). */
    suspend fun learn(playlistId: Long, output: XtreamOutput?) = lock.withLock {
        val previous = LearnedOutput.parse(dao.get(playlistId)?.xtreamOutputLearned)
        dao.setXtreamOutputLearned(playlistId, AutoTsRetry.afterLearn(previous, output, clock.now())?.encode())
    }

    /** A TS retry's result; returns the value stored after it (null = plain Auto). */
    suspend fun record(playlistId: Long, result: AutoTsRetry.Result): LearnedOutput? = lock.withLock {
        val row = dao.get(playlistId) ?: return@withLock null
        val previous = LearnedOutput.parse(row.xtreamOutputLearned)
        // The user picked another Stream format meanwhile: their choice cleared the learned value; keep it that way.
        if (!row.xtreamOutput.equals(XtreamOutput.AUTO.name, ignoreCase = true)) return@withLock previous
        val next = AutoTsRetry.afterResult(previous, result, clock.now())
        dao.setXtreamOutputLearned(playlistId, next?.encode())
        next
    }

    /** An Xtream playlist on Auto that learned HLS may try TS at this user tune ([AutoTsRetry.due]). */
    fun retryDue(p: PlaylistEntity, account: XtreamAccountInfo?): Boolean =
        p.type == PlaylistType.XTREAM &&
            AutoTsRetry.due(output(p.xtreamOutput), LearnedOutput.parse(p.xtreamOutputLearned), account, clock.now())

    private fun output(name: String): XtreamOutput = XtreamOutput.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: XtreamOutput.AUTO
}
