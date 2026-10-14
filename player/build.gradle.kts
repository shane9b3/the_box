plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
}

android {
    namespace = "app.thebox.player"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// TS/HTTP fixtures live in :core (docs/design/m2-playback-core.md §5.2, §6.2). AGP 9's `android.sourceSets["test"]`
// accessor fails with a class-cast error, so this uses the variant API (as :data does).
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { hostTest ->
            hostTest.sources.resources?.addStaticSourceDirectory("../core/src/test/resources")
        }
    }
}

dependencies {
    // Public API types (ChannelIdentity, PlaybackError, ArchiveRequest, …) come from :core.
    api(project(":core"))
    implementation(project(":ffmpeg"))

    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    api(libs.media3.session)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)          // mockwebserver3 5.5.0
    testImplementation(libs.kotlinx.coroutines.test)
    // Both pull com.squareup.okhttp3:mockwebserver 4.12.0, which would run against OkHttp 5 classes (§5.4).
    testImplementation(libs.media3.test.utils) { exclude(group = "com.squareup.okhttp3", module = "mockwebserver") }
    testImplementation(libs.media3.test.utils.robolectric) { exclude(group = "com.squareup.okhttp3", module = "mockwebserver") }
}
