plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.thebox.data"
    defaultConfig {
        // M0 L7. The runner class lands with WP-D's androidTest sources.
        testInstrumentationRunner = "app.thebox.data.HiltTestRunner"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Shared parser fixtures (m3u/, xmltv/, xtream/, …) are read by :data integration tests (§6.3). AGP 9's
// `android.sourceSets["test"]` accessor fails with a class-cast error, so this uses the variant API.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { hostTest ->
            hostTest.sources.resources?.addStaticSourceDirectory("../core/src/test/resources")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)
    implementation(libs.sqlite.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)

    testImplementation(testFixtures(project(":core")))   // XmltvGenerator, EpgTestDb (WP-B)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sqlite.framework)            // AndroidSQLiteDriver for Robolectric
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
}
