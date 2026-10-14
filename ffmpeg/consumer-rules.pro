# FfmpegAvailability looks the Media3 FFmpeg extension up reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary {
    public static boolean isAvailable();
    public static boolean supportsFormat(java.lang.String);
}
-dontwarn androidx.media3.decoder.ffmpeg.**
