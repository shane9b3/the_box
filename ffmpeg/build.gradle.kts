plugins {
    alias(libs.plugins.thebox.android.library)
}

android {
    namespace = "app.thebox.ffmpeg"
}

dependencies {
    // CI drops the prebuilt Media3 decoder_ffmpeg AAR (audio-only, LGPL) into ffmpeg/libs/.
    // Empty locally, in which case FfmpegAvailability reports false. See ffmpeg/README.md.
    api(fileTree("libs") { include("*.aar", "*.jar") })
    // The file dependency carries no POM, so declare what decoder_ffmpeg itself depends on.
    api(libs.media3.decoder)
    api(libs.media3.exoplayer)
}
