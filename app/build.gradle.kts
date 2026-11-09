import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.thebox.android.application)
    alias(libs.plugins.thebox.android.compose)
    alias(libs.plugins.thebox.hilt)
    alias(libs.plugins.baselineprofile)
}

// Release signing: keystore.properties (gitignored) with storeFile, storePassword, keyAlias,
// keyPassword. Production packaging requires it; benchmark remains explicitly debug-signed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}
val releaseSigningReady = keystorePropertiesFile.exists() &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProperties.getProperty(it).isNullOrBlank() } &&
    rootProject.file(keystoreProperties.getProperty("storeFile", "")).isFile

// App version: version.properties at the repo root (versionCode, versionName), bumped and committed by
// tools/release/release.py. `-Pthebox.versionCode=` / `-Pthebox.versionName=` override it for one build without touching
// the file (the release script's --dry-run uses them).
val versionProperties = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val appVersionCode: Int = (providers.gradleProperty("thebox.versionCode").orNull ?: versionProperties.getProperty("versionCode")).trim().toInt()
val appVersionName: String = (providers.gradleProperty("thebox.versionName").orNull ?: versionProperties.getProperty("versionName")).trim()

android {
    namespace = "app.thebox"

    buildFeatures {
        buildConfig = true // TheBoxApplication reads BuildConfig.DEBUG (WorkManager log level)
    }

    defaultConfig {
        applicationId = "app.thebox"
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release-like build used by :benchmark (macrobenchmarks need a non-debuggable app).
        create("benchmark") {
            initWith(getByName("release"))
            // Macrobenchmark needs <profileable android:shell="true"/> on API 29+ for a
            // non-debuggable target; AGP adds it to this variant's manifest only.
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Storage locations (docs/design/m6-storage-locations.md §12.4; smbj issue #865): bcprov's multi-release OSGi
        // manifests collide with jspecify's under Hilt.
        resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
    }
}

// Check only production packaging: debug, sync and profile-generation builds need no production key.
abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val signingReady: Property<Boolean>

    @TaskAction
    fun validate() {
        check(signingReady.get()) {
            "Release packaging requires keystore.properties with storeFile, storePassword, keyAlias and keyPassword, and an existing keystore."
        }
    }
}

val validateReleaseSigning = tasks.register<ValidateReleaseSigningTask>("validateReleaseSigning") {
    group = "verification"
    description = "Require an explicit production signing configuration before release packaging."
    signingReady.set(releaseSigningReady)
}
tasks.configureEach {
    if (name == "packageRelease" || name == "packageReleaseBundle") dependsOn(validateReleaseSigning)
}

// Ship ARM only (master plan §3). The non-shipping build types keep x86/x86_64 native libs (from sqlite-bundled
// and the FFmpeg AAR) so their universal APK runs on the x86 TV emulator: debug, benchmark (:benchmark's
// macrobenchmarks) and nonMinifiedRelease (the baseline-profile generator) (docs/design/m3-guide.md §2.13 item 19).
// release and benchmarkRelease stay ARM only.
val emulatorBuildTypes = setOf("debug", "benchmark", "nonMinifiedRelease")
// BenchSeedActivity (deep-link-free seeding for GuideScrollBenchmark and BaselineProfileGenerator) is compiled into
// the benchmark and nonMinifiedRelease build types only, never into release (§2.13 item 19).
val benchSeedBuildTypes = setOf("benchmark", "nonMinifiedRelease")
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType !in emulatorBuildTypes) {
            variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
        }
        if (variant.buildType in benchSeedBuildTypes) {
            variant.sources.kotlin?.addStaticSourceDirectory("src/benchShared/kotlin")
            variant.sources.manifests.addStaticManifestFile("src/benchShared/AndroidManifest.xml")
        }
    }
}

baselineProfile {
    // Profiles are generated on a device by :baselineprofile and checked in to src/main.
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":ui"))
    implementation(project(":ffmpeg"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.acra.core)

    // Configuration.Provider + HiltWorkerFactory (docs/design/m1-data-layer.md §3.10, §6.4).
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // M3 ImageModule: the app's Coil ImageLoader (docs/design/m3-guide.md §6.2).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Score bug (docs/design/m6-score-bug.md §3, A-N8): the NHL team logos are SVG; SvgDecoder is registered in ImageModule.
    implementation(libs.coil.svg)

    baselineProfile(project(":baselineprofile"))

    // M3 ReminderSchedulerTest / CatchupLauncherImpl tests (JUnit 4 + coroutines-test, §5.4).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))

    // M2 instrumented suite against the fake panel (docs/design/m2-playback-core.md §5.5, §6.5). Default
    // AndroidJUnitRunner: the tests use the real TheBoxApplication and a Hilt @EntryPoint.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
