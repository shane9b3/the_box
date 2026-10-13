plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.thebox.data"
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
}
