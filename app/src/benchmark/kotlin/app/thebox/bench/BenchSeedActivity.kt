package app.thebox.bench

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.thebox.MainActivity
import app.thebox.core.common.Outcome
import app.thebox.core.epg.EpgStore
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.source.PlaylistType
import app.thebox.data.di.ApplicationScope
import app.thebox.data.repo.EpgImporter
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.Trigger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Benchmark-build seeding (docs/design/m3-guide.md §1.5, §5.7; owner WP-D): the benchmark build type does not include
 * `src/debug`, so `GuideScrollBenchmark` seeds through this exported activity.
 *
 * Extras: `panel` (fake panel base URL, required), `user`/`pass` (default `test`), `perf=auto|high|low` (applied to the
 * in-memory guide settings every launch). Idempotent: when an Xtream playlist for the panel's host already exists and
 * the EPG has a generation, nothing is downloaded again. While it works it shows [SEEDING_TEXT]; when done it logs
 * [DONE_LOG] under [TAG], starts [MainActivity] and finishes (the benchmark waits for the text to disappear).
 */
@AndroidEntryPoint
class BenchSeedActivity : ComponentActivity() {
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var refresher: PlaylistRefresher
    @Inject lateinit var epgImporter: EpgImporter
    @Inject lateinit var epgStore: EpgStore
    @Inject lateinit var guideSettings: GuideSettingsSource
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            text = SEEDING_TEXT
        }
        setContentView(text)
        val panel = intent.getStringExtra("panel")?.trimEnd('/')
        applyPerf(intent.getStringExtra("perf"))
        if (panel.isNullOrBlank()) {
            Log.w(TAG, "no panel extra: opening the guide unseeded")
            open()
            return
        }
        val user = intent.getStringExtra("user") ?: "test"
        val pass = intent.getStringExtra("pass") ?: "test"
        appScope.launch {
            val error = mutex.withLock { withContext(Dispatchers.IO) { seed(panel, user, pass) } }
            withContext(Dispatchers.Main) {
                if (error == null) {
                    Log.i(TAG, DONE_LOG)
                    open()
                } else {
                    Log.e(TAG, "seed failed: $error")
                    text.text = "Seed failed: $error"
                }
            }
        }
    }

    private fun applyPerf(perf: String?) {
        val mode = when (perf?.lowercase()) {
            "auto" -> PerformanceMode.AUTO
            "high" -> PerformanceMode.HIGH
            "low" -> PerformanceMode.LOW
            else -> return
        }
        try {
            guideSettings.update { it.copy(performance = mode) }
            Log.i(TAG, "performance = $mode")
        } catch (t: Throwable) {
            Log.w(TAG, "guide settings not applied (WP-B stub?): $t")
        }
    }

    /** Null on success, else the reason. */
    private suspend fun seed(base: String, user: String, pass: String): String? = try {
        val host = PlaylistRepository.displayHost(base)
        val existing = playlists.all().firstOrNull { it.type == PlaylistType.XTREAM && it.displaySource == host }
        if (existing != null && epgStore.info.value != null) {
            Log.i(TAG, "playlist ${existing.id} for $host already seeded")
            null
        } else {
            val id = existing?.id ?: when (val r = playlists.addXtream("Bench $host", listOf(base), user, pass)) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> throw IllegalStateException("addXtream: ${r.error.code}")
            }
            val rr = refresher.refresh(id, Trigger.MANUAL)
            check(rr.ok) { "refresh: ${rr.error?.code}" }
            val er = epgImporter.build(Trigger.MANUAL, force = true)
            check(epgStore.info.value != null) { "epg: ${er.error?.code}" }
            Log.i(TAG, "seeded playlist $id, epg generation ${epgStore.info.value?.generation}")
            null
        }
    } catch (t: Throwable) {
        t.toString()
    }

    private fun open() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        const val TAG = "BenchSeed"
        const val SEEDING_TEXT = "Seeding the benchmark guide…"
        const val DONE_LOG = "bench-seed: done"
        private val mutex = Mutex()
    }
}
