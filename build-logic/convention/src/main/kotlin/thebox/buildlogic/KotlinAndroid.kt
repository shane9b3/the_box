package thebox.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/** Bytecode level for every module. The build itself may run on JDK 17 or newer. */
internal val TARGET_JAVA = JavaVersion.VERSION_17
internal val TARGET_JVM = JvmTarget.JVM_17

/**
 * Shared Android settings: SDK levels, Java 17 bytecode and core-library desugaring
 * (java.time on API 25). Kotlin is AGP 9's built-in Kotlin support (no kotlin-android plugin).
 */
internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = libs.intVersion("compileSdk")
        defaultConfig.minSdk = libs.intVersion("minSdk")
        compileOptions.sourceCompatibility = TARGET_JAVA
        compileOptions.targetCompatibility = TARGET_JAVA
        compileOptions.isCoreLibraryDesugaringEnabled = true
        configureLint()
    }
    configureKotlinCompilerOptions()
    dependencies {
        add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
    }
}

internal fun Project.configureKotlinCompilerOptions() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        val options = compilerOptions
        if (options is KotlinJvmCompilerOptions) {
            options.jvmTarget.set(TARGET_JVM)
        }
    }
}

/**
 * Lint gate (`./gradlew lintAll`). Correctness checks that have shipped bugs are errors; the rest
 * stays at default severity. Existing warnings live in each module's `lint-baseline.xml`.
 */
private fun CommonExtension.configureLint() {
    lint.apply {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false // lintAll lints every module on its own
        baseline = java.io.File("lint-baseline.xml") // resolved against the module directory
        error += listOf(
            "NewApi", "WrongConstant", "Range", "ResourceType", "MissingPermission",
            "UnsafeOptInUsageError",
        )
        // InlinedApi stays at its default (warning): constants are inlined at compile time.
        disable += listOf(
            "GradleDependency", // versions are pinned deliberately (docs/m0-toolchain.md)
            "NewerVersionAvailable", // same: pinned toolchain
            "AndroidGradlePluginVersion", // same: AGP is pinned
            "OldTargetApi", // targetSdk is pinned in the catalog
        )
    }
}
