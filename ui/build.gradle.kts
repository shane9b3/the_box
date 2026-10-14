plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.android.compose)
}

android {
    namespace = "app.thebox.ui"
}

dependencies {
    implementation(project(":core"))
    // VideoLayer (M2) and the M3/M4 screens observe the PlaybackController (docs/design/m2-playback-core.md §1.2).
    implementation(project(":player"))
    implementation(libs.media3.ui.compose)

    api(libs.tv.material)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
