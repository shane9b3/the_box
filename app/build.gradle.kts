import java.util.Properties

plugins {
    alias(libs.plugins.thebox.android.application)
    alias(libs.plugins.thebox.android.compose)
    alias(libs.plugins.thebox.hilt)
    alias(libs.plugins.baselineprofile)
}

// Release signing: keystore.properties (gitignored) with storeFile, storePassword, keyAlias,
// keyPassword. Without it, release/benchmark builds are signed with the debug key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}

android {
    namespace = "app.thebox"

    buildFeatures {
        buildConfig = true // TheBoxApplication reads BuildConfig.DEBUG (WorkManager log level)
    }

    defaultConfig {
        applicationId = "app.thebox"
        versionCode = 1
        versionName = "0.0.1-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    }
}

// Ship ARM only (master plan §3). Debug keeps x86/x86_64 native libs (from sqlite-bundled) so the
// universal debug APK still runs on an x86_64 emulator.
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType != "debug") {
            variant.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
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

    baselineProfile(project(":baselineprofile"))

    // M2 instrumented suite against the fake panel (docs/design/m2-playback-core.md §5.5, §6.5). Default
    // AndroidJUnitRunner: the tests use the real TheBoxApplication and a Hilt @EntryPoint.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
