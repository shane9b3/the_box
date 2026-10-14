package app.thebox.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import app.thebox.core.common.Redact
import app.thebox.core.model.StableKey
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.data.repo.ChannelPrefsRepository
import app.thebox.data.repo.ChannelRepository
import app.thebox.data.repo.PlaylistRefresher
import app.thebox.data.repo.PlaylistRepository
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.ZapList
import app.thebox.player.engine.WindowDisplayModeBinder
import app.thebox.ui.playback.VideoLayer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only playback harness (§3.16): `thebox-debug://play?url=…` / `?playlist=<id>&key=<stableKey>`, and
 * `thebox-debug://stop`. Hosts `VideoLayer` exactly as M3 will, with the state/stats/event overlay. Debug source set
 * only: release builds do not contain it and do not register the scheme.
 *
 * Parameters (URL-encoded): `url` (+ `ua`, `max`, `format=ts|hls`) → [TuneTarget.DirectUrl]; `playlist` + `key` →
 * [TuneTarget.Channel]; `zap=k1,k2,…` → a [ZapList] over those keys of the same playlist; `stats=1`;
 * `viewport=mini` (the M3 mini-player rect: left 64, top 56, 720×405 design px, 1 design px = width / 1920).
 *
 * Keys: CH± / DPAD_UP/DOWN → zap, DPAD_CENTER → retry, MENU or S → stats, BACK → stop and finish.
 */
@AndroidEntryPoint
class PlaybackDebugActivity : ComponentActivity() {
    @Inject lateinit var controller: PlaybackController

    private var mini by mutableStateOf(false)
    private var statsOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            controller.keepScreenOn.collect {
                if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        WindowDisplayModeBinder(controller).bind(this)
        setContent { Harness(controller, mini, statsOn) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // No setIntent(): ActivityScenario matches lifecycle events on the launch intent, and a replaced intent made
        // scenario.close() time out after a second deep link (device integration pass, 2026-10-13).
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        controller.setViewport(if (mini) Viewport.MINI else Viewport.FULL)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != SCHEME) return
        when (uri.host) {
            "stop" -> controller.stop(StopReason.USER)
            "play" -> play(uri)
        }
    }

    private fun play(uri: Uri) {
        mini = uri.getQueryParameter("viewport") == "mini"
        controller.setViewport(if (mini) Viewport.MINI else Viewport.FULL)
        statsOn = uri.getQueryParameter("stats") == "1"
        controller.setStatsEnabled(statsOn)
        val url = uri.getQueryParameter("url")
        val playlist = uri.getQueryParameter("playlist")?.toLongOrNull()
        val key = uri.getQueryParameter("key")
        val zap = uri.getQueryParameter("zap")?.split(',')?.filter { it.isNotBlank() }
        when {
            url != null -> {
                val headers = uri.getQueryParameter("ua")?.let { mapOf("User-Agent" to it) } ?: emptyMap()
                val format = when (uri.getQueryParameter("format")) {
                    "hls" -> StreamFormat.HLS
                    "ts" -> StreamFormat.TS
                    else -> StreamFormat.UNKNOWN
                }
                val max = uri.getQueryParameter("max")?.toIntOrNull() ?: 1
                controller.tune(TuneTarget.DirectUrl(url, headers, max, format))
            }
            playlist != null && key != null -> {
                val list = zap?.let { keys -> KeyZapList(keys.map { ChannelIdentity(playlist, StableKey(it)) }) }
                controller.tune(TuneTarget.Channel(ChannelIdentity(playlist, StableKey(key))), list)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_DOWN -> controller.zap(+1, repeat)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_UP -> controller.zap(-1, repeat)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> controller.retry()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_S -> {
                statsOn = !statsOn
                controller.setStatsEnabled(statsOn)
            }
            KeyEvent.KEYCODE_BACK -> {
                controller.stop(StopReason.USER)
                finish()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** CH± over a fixed list of keys, wrapping. */
    private class KeyZapList(private val items: List<ChannelIdentity>) : ZapList {
        override val id: String = "debug"
        override suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity? {
            if (items.isEmpty()) return null
            val i = items.indexOf(from).coerceAtLeast(0)
            return items[Math.floorMod(i + delta, items.size)]
        }
    }

    /** What the instrumented suite (§5.5) reaches through `EntryPointAccessors.fromApplication`. Debug builds only. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DebugPlaybackEntryPoint {
        fun controller(): PlaybackController
        fun settings(): PlaybackSettingsSource
        fun playlists(): PlaylistRepository
        fun refresher(): PlaylistRefresher
        fun channels(): ChannelRepository
        fun channelPrefs(): ChannelPrefsRepository
        fun channelMemory(): ChannelMemory
        fun sourceProvider(): PlaybackSourceProvider
    }

    companion object {
        const val SCHEME = "thebox-debug"
    }
}

/** Surface rule: the root Box has no graphicsLayer/alpha/clip; VideoLayer is its first child. */
@Composable
private fun Harness(controller: PlaybackController, mini: Boolean, statsOn: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = if (constraints.maxWidth == Constraints.Infinity) 1920 else constraints.maxWidth
        val h = if (constraints.maxHeight == Constraints.Infinity) 1080 else constraints.maxHeight
        val rect = if (mini) {
            val px = w / 1920f
            RectPx((64 * px).toInt(), (56 * px).toInt(), ((64 + 720) * px).toInt(), ((56 + 405) * px).toInt())
        } else {
            RectPx(0, 0, w, h)
        }
        VideoLayer(controller.surfacePlayer, rect)
        Overlay(controller, statsOn, Modifier.align(Alignment.BottomStart).fillMaxWidth())
    }
}

@Composable
private fun Overlay(controller: PlaybackController, statsOn: Boolean, modifier: Modifier) {
    val state by controller.state.collectAsState()
    val stats by controller.stats.collectAsState()
    var events by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(controller) {
        while (true) {
            events = try {
                controller.diagnostics.events().takeLast(8).map { "${it.tuneId} ${it.type} ${it.code} ${it.detail}" }
            } catch (e: Exception) {
                listOf("event log unavailable: ${e.javaClass.simpleName}")
            }
            delay(500)
        }
    }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
    Column(modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp)) {
        Text(Redact.text(state.toString()).take(300), style = style)
        if (statsOn) {
            Text(
                "v=${stats.videoMime} ${stats.width}x${stats.height} ${stats.videoDecoder} a=${stats.audioMime} ${stats.audioDecoder} " +
                    "host=${stats.host} http=${stats.httpStatus} buf=${stats.bufferMs} rc=${stats.reconnects} drop=${stats.droppedFrames} " +
                    "br=${stats.streamBitrateBps} broker=${stats.broker?.active}/${stats.broker?.max}",
                style = style,
            )
        }
        events.forEach { Text(it.take(160), style = style) }
    }
}
