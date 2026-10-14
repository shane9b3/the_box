package app.thebox.player.engine

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.annotation.WorkerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import app.thebox.core.common.BoxLog
import app.thebox.ffmpeg.FfmpegAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device facts for the engine and the S16 diagnostics dump (docs/design/m2-playback-core.md §3.15).
 *
 * [isLowRam] is cheap and read when the player is built (buffer byte cap, §3.6.1). [dump] is expensive (codec list,
 * display, audio capabilities): call it off the main thread; it is cached until [invalidate].
 */
@OptIn(UnstableApi::class)
@Singleton
internal class DeviceCapabilities @Inject constructor(@ApplicationContext private val context: Context) {

    private val activityManager: ActivityManager? get() = context.getSystemService(ActivityManager::class.java)

    /** `isLowRamDevice || memoryClass ≤ 192 MB || totalMem ≤ 2 GiB` (architecture review C14). */
    val isLowRam: Boolean by lazy {
        val am = activityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        isLowRamClass(am?.isLowRamDevice ?: false, am?.memoryClass ?: 0, memInfo.totalMem)
    }

    /** The media buffer byte cap for this device class (§3.6.1). */
    val targetBufferBytes: Int get() = if (isLowRam) AdaptiveLoadControl.LOW_RAM_TARGET_BYTES else AdaptiveLoadControl.NORMAL_TARGET_BYTES

    @Volatile private var cached: Map<String, String>? = null

    /** Capability dump for diagnostics (decoders, HDR, display modes, audio encodings, FFmpeg). Not on the main thread. */
    @WorkerThread
    fun dump(): Map<String, String> = cached ?: compute().also { cached = it }

    /** Drops the cached dump (audio route or display change). */
    fun invalidate() {
        cached = null
    }

    /**
     * Calls [onChanged] (main thread) whenever the audio capabilities of the current route change (HDMI plug, AVR on/off).
     * Used to refresh the dump and to clear learned per-route passthrough denials (§3.8.3). Returns the unregister action.
     * Playback itself does not need this: `DefaultAudioSink` tracks route capabilities on its own.
     */
    fun observeAudioRoute(onChanged: () -> Unit): () -> Unit {
        val receiver = AudioCapabilitiesReceiver(context, {
            invalidate()
            onChanged()
        }, AudioAttributes.DEFAULT, null)
        receiver.register()
        return { receiver.unregister() }
    }

    private fun compute(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["device"] = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        val am = activityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        out["ram.lowRamClass"] = isLowRam.toString()
        out["ram.isLowRamDevice"] = (am?.isLowRamDevice ?: false).toString()
        out["ram.memoryClassMb"] = (am?.memoryClass ?: 0).toString()
        out["ram.totalMemMb"] = (memInfo.totalMem / (1024 * 1024)).toString()
        out["buffer.targetBytes"] = targetBufferBytes.toString()

        for (mime in DUMP_MIMES) out["decoder.$mime"] = decoders(mime)
        out["mp2.platformDecoder"] = hasPlatformDecoder(MimeTypes.AUDIO_MPEG_L2).toString()

        out["ffmpeg.available"] = FfmpegAvailability.isAvailable.toString()
        out["ffmpeg.version"] = ffmpegVersion() ?: "-"
        if (FfmpegAvailability.isAvailable) {
            out["ffmpeg.mimes"] = BoxMediaCodecSelector.FFMPEG_CANDIDATE_MIMES.filter { FfmpegAvailability.supports(it) }.joinToString(",")
        }

        try {
            val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null, emptyList())
            out["audio.passthrough"] = PASSTHROUGH_ENCODINGS.filter { caps.supportsEncoding(it.first) }.joinToString(",") { it.second }.ifEmpty { "none" }
            out["audio.maxChannels"] = caps.maxChannelCount.toString()
        } catch (e: RuntimeException) {
            out["audio.passthrough"] = "error ${e.javaClass.simpleName}"
        }

        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val mode = display.mode
            out["display.mode"] = "${mode.modeId} ${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}"
            out["display.modes"] = display.supportedModes.joinToString(" ") { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
            out["display.hdr"] = display.hdrCapabilities?.supportedHdrTypes?.joinToString(",") { hdrTypeName(it) }?.ifEmpty { "none" } ?: "none"
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val pref = context.getSystemService(DisplayManager::class.java)?.matchContentFrameRateUserPreference
            out["afr.matchContentPref"] = when (pref) {
                DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> "never"
                DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY -> "seamless_only"
                DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS -> "always"
                else -> "unknown"
            }
        }
        return out
    }

    private fun decoders(mime: String): String = try {
        MediaCodecUtil.getDecoderInfos(mime, false, false).joinToString(",") { info ->
            info.name + when {
                info.hardwareAccelerated -> "(HW)"
                info.softwareOnly -> "(SW)"
                else -> "(?)"
            }
        }.ifEmpty { "none" }
    } catch (e: MediaCodecUtil.DecoderQueryException) {
        BoxLog.w(TAG, "decoder query failed for $mime", e)
        "error"
    }

    private fun hasPlatformDecoder(mime: String): Boolean = try {
        MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
    } catch (_: MediaCodecUtil.DecoderQueryException) {
        false
    }

    private fun ffmpegVersion(): String? = try {
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary").getMethod("getVersion").invoke(null) as String?
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }

    companion object {
        private const val TAG = "PlayerFactory"

        /** `LOW_RAM_MEMORY_CLASS_MB` / `LOW_RAM_TOTAL_MEM_BYTES` (App. B). */
        const val LOW_RAM_MEMORY_CLASS_MB = 192
        const val LOW_RAM_TOTAL_MEM_BYTES = 2L * 1024 * 1024 * 1024

        /** Pure rule, exposed for tests. A memory class of 0 means unknown and does not count. */
        fun isLowRamClass(isLowRamDevice: Boolean, memoryClassMb: Int, totalMemBytes: Long): Boolean =
            isLowRamDevice || (memoryClassMb in 1..LOW_RAM_MEMORY_CLASS_MB) || (totalMemBytes in 1..LOW_RAM_TOTAL_MEM_BYTES)

        private val DUMP_MIMES = listOf(
            MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1,
            MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3,
        )

        private val PASSTHROUGH_ENCODINGS = listOf(
            C.ENCODING_AC3 to "AC-3", C.ENCODING_E_AC3 to "E-AC-3", C.ENCODING_E_AC3_JOC to "E-AC-3-JOC", C.ENCODING_AC4 to "AC-4",
            C.ENCODING_DTS to "DTS", C.ENCODING_DTS_HD to "DTS-HD", C.ENCODING_DOLBY_TRUEHD to "TrueHD",
        )

        private fun hdrTypeName(type: Int): String = when (type) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DV"
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            4 -> "HDR10+" // HDR_TYPE_HDR10_PLUS (API 29)
            else -> type.toString()
        }
    }
}
