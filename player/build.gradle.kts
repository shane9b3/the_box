plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
}

android {
    namespace = "app.thebox.player"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ffmpeg"))

    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    api(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
