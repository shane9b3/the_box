package app.thebox.ffmpeg

import java.lang.reflect.Method

/**
 * Whether the Media3 FFmpeg audio extension is packaged and its native library loads.
 *
 * Without the prebuilt AAR in `ffmpeg/libs/` (local builds) this is always false. Looked up
 * reflectively so this module compiles with or without the AAR.
 */
object FfmpegAvailability {
    private const val FFMPEG_LIBRARY = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    val isAvailable: Boolean by lazy {
        try {
            Class.forName(FFMPEG_LIBRARY).getMethod("isAvailable").invoke(null) as Boolean
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** `FfmpegLibrary.supportsFormat(String)` (public static in the 1.11.1 AAR), or null when absent. */
    private val supportsFormat: Method? by lazy {
        try {
            Class.forName(FFMPEG_LIBRARY).getMethod("supportsFormat", String::class.java)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    /**
     * True when the extension is available and FFmpeg has a decoder for [mimeType] (e.g. `audio/mpeg-L2`, `audio/ac3`).
     * Used by `BoxMediaCodecSelector` to hide platform decoders for a MIME type (docs/design/m2-playback-core.md §3.8.1).
     * False whenever the AAR is absent or its natives do not load.
     */
    fun supports(mimeType: String): Boolean {
        if (!isAvailable) return false
        val method = supportsFormat ?: return false
        return try {
            method.invoke(null, mimeType) as Boolean
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}
