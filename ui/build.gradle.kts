plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.android.compose)
}

android {
    namespace = "app.thebox.ui"
}

dependencies {
    implementation(project(":core"))

    api(libs.tv.material)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
