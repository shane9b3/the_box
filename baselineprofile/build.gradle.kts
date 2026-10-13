plugins {
    alias(libs.plugins.thebox.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.thebox.baselineprofile"
}

baselineProfile {
    // Run on a connected device/emulator (API 28+); no Gradle-managed devices in M0.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
