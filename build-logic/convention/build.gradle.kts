import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "thebox.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the actual plugin versions are put on the build classpath by the
    // root build.gradle.kts (`apply false`), so they resolve from the version catalog.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.thebox.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.thebox.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.thebox.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidTest") {
            id = libs.plugins.thebox.android.test.get().pluginId
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.thebox.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.thebox.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
    }
}
