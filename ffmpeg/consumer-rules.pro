# FfmpegAvailability and DeviceCapabilities (the diagnostics' ffmpeg.version) look the Media3 FFmpeg extension up reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary {
    public static boolean isAvailable();
    public static boolean supportsFormat(java.lang.String);
    public static java.lang.String getVersion();
}
-dontwarn androidx.media3.decoder.ffmpeg.**
