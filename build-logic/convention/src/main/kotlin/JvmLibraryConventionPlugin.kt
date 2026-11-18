import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import thebox.buildlogic.TARGET_JAVA
import thebox.buildlogic.configureKotlinCompilerOptions
import thebox.buildlogic.lib
import thebox.buildlogic.libs

/** Pure Kotlin/JVM module (no Android) with kotlin-test on JUnit 5. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = TARGET_JAVA
            targetCompatibility = TARGET_JAVA
        }
        configureKotlinCompilerOptions()
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // The suites still exercise pause and rewind, which is switched off in the app (TimeshiftFeature, 2026-11-18).
            systemProperty("thebox.timeshift.tests", "1")
        }
        dependencies {
            add("testImplementation", libs.lib("kotlin-test-junit5"))
            add("testImplementation", platform(libs.lib("junit-bom")))
            add("testImplementation", libs.lib("junit-jupiter"))
            add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        }
    }
}
