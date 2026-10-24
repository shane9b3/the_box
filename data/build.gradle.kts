import java.util.Properties

plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

// The app's own TMDB key for programme posters (owner ruling 2026-10-18, docs/decisions/2026-10-18-tmdb-programme-posters.md):
// `thebox.tmdbKey=…` in the root local.properties (gitignored) or the THEBOX_TMDB_KEY environment variable (CI). Empty =
// the build has no key and posters need the user's own.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val tmdbKey = (localProperties.getProperty("thebox.tmdbKey") ?: System.getenv("THEBOX_TMDB_KEY") ?: "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "app.thebox.data"
    buildFeatures {
        buildConfig = true // BuildConfig.TMDB_KEY
    }
    defaultConfig {
        // M0 L7. The runner class lands with WP-D's androidTest sources.
        testInstrumentationRunner = "app.thebox.data.HiltTestRunner"
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
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
            // Exported Room schemas as assets for the Robolectric MigrationTestHelper (m2 §2.10, day-0 check 7). The Room
            // Gradle plugin only wires them into androidTest.
            hostTest.sources.assets?.addStaticSourceDirectory("schemas")
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

    // Storage locations (docs/design/m6-storage-locations.md §3.3, §5.3, §7.5, §12.4). smbj's slf4j stays unbound (no
    // binding = no-op logging in every build, §8: smbj messages carry host, share and path). bcprov is declared to pin the
    // version smbj's BCSecurityProvider uses. lifecycle-process: the keeper's ON_START trigger (A-S13).
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(testFixtures(project(":core")))   // XmltvGenerator, EpgTestDb (WP-B)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sqlite.framework)            // AndroidSQLiteDriver for Robolectric
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(testFixtures(project(":core")))   // XmltvDump for I-8
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
}
