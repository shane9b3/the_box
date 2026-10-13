// Puts every plugin on the root build classpath once, at the catalog version, so the
// convention plugins in build-logic (compileOnly) and module scripts share one classloader.
// Kotlin 2.4.21 here also overrides the Kotlin Gradle plugin that AGP 9 uses for built-in Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.baselineprofile) apply false
}
