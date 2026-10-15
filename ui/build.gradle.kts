plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.android.compose)
    // M3 (docs/design/m3-guide.md §0.16, §6.2): GuideViewModel, InputModule, GuideGridModule. :ui never sees :data.
    alias(libs.plugins.thebox.hilt)
}

android {
    namespace = "app.thebox.ui"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    // VideoLayer (M2) and the M3/M4 screens observe the PlaybackController (docs/design/m2-playback-core.md §1.2).
    implementation(project(":player"))
    implementation(libs.media3.ui.compose)
    // M4 (docs/design/m4-player-ux.md §6.2): SubtitleView in an AndroidView (§3.6.3).
    implementation(libs.media3.ui)

    api(libs.tv.material)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // M3 (§6.2)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))   // guide fakes (docs/design/m3-guide.md §1.1)
    debugImplementation(libs.compose.ui.test.manifest)
}
