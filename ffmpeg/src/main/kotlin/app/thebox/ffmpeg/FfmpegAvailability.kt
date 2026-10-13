package app.thebox.ffmpeg

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
}
