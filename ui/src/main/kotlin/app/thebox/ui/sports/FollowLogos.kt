package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * (docs/design/m6-score-bug.md §3, A-S11) The two trimmed logos of the followed game, held for the life of the follow
 * (the `LogoCache` precedent: Coil's memory cache is off for these requests, so this holder is the cache). One holder per
 * follow: [release] drops both bitmaps when the follow changes or expires. Each [Slot] is snapshot state read only in the
 * bug's draw lambda, so an arriving logo redraws the node and never recomposes.
 */
class FollowLogos(private val fetch: LogoFetch?, private val scope: CoroutineScope) {
    class Slot {
        var image: ImageBitmap? by mutableStateOf(null)
            internal set
        /** The load failed (or there is nothing to load): the disc shows at once. */
        var failed: Boolean by mutableStateOf(false)
            internal set
    }

    val away = Slot()
    val home = Slot()
    private var jobs: List<Job> = emptyList()

    /** Starts both loads at the slot size in device px; a null URL means no logo (the disc). */
    fun start(awayUrl: String?, homeUrl: String?, slotW: Int, slotH: Int) {
        release()
        jobs = listOf(load(away, awayUrl, slotW, slotH), load(home, homeUrl, slotW, slotH))
    }

    private fun load(slot: Slot, url: String?, w: Int, h: Int): Job = scope.launch {
        val f = fetch
        if (url == null || f == null) { slot.failed = true; return@launch }
        val bmp: Bitmap? = try {
            f(url, w, h)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (bmp == null) slot.failed = true else slot.image = bmp.asImageBitmap()
    }

    fun release() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        away.image = null; away.failed = false
        home.image = null; home.failed = false
    }
}
