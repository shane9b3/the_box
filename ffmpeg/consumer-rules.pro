# FfmpegAvailability looks the Media3 FFmpeg extension up reflectively.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { public static boolean isAvailable(); }
-dontwarn androidx.media3.decoder.ffmpeg.**
