package app.thebox.player.engine

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-C. Replace the TODO bodies; keep the names.
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** `ForwardingAudioSink` that denies encoded formats per policy (§3.8.3). */
@OptIn(UnstableApi::class)
internal class PassthroughGateAudioSink(sink: AudioSink) : ForwardingAudioSink(sink)
