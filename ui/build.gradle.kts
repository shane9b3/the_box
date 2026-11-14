import java.security.MessageDigest

plugins {
    alias(libs.plugins.thebox.android.library)
    alias(libs.plugins.thebox.android.compose)
    // M3 (docs/design/m3-guide.md §0.16, §6.2): GuideViewModel, InputModule, GuideGridModule. :ui never sees :data.
    alias(libs.plugins.thebox.hilt)
}

android {
    namespace = "app.thebox.ui"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    // VideoLayer (M2) and the M3/M4 screens observe the PlaybackController (docs/design/m2-playback-core.md §1.2).
    implementation(project(":player"))
    implementation(libs.media3.ui.compose)
    // M4 (docs/design/m4-player-ux.md §6.2): SubtitleView in an AndroidView (§3.6.3).
    implementation(libs.media3.ui)

    api(libs.tv.material)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // M3 (§6.2)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    // M5 (docs/design/m5-setup-settings.md §6.6): QR code of the phone-setup URL (WP-D's QrImage).
    implementation(libs.qrcodegen)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))   // guide fakes (docs/design/m3-guide.md §1.1)
    debugImplementation(libs.compose.ui.test.manifest)
}

// (CHL design §7.4, review S9; integration) The sports screenshot hash gate: `./gradlew :ui:sportsHashGate`.
// 1. `cleanSportsShots` deletes the six NHL / NFL / Stacked output folders, so a stale PNG from a removed case never passes;
// 2. `testDebugUnitTest` then runs (forced to rerun, never from the build cache, whenever the gate is requested), which
//    writes every PNG; the gate reads them only after the whole test task has finished, so test order does not matter;
// 3. `sportsHashGate` compares each SHA-256 with `src/test/resources/golden/sports-hashes.txt`.
// The bytes depend on the host (OS, CPU, JDK): the list's `# host:` line records the one it was taken on. On any other
// host the gate is SKIPPED with a message (not failed); regenerate the list there from the commit the header names.
val sportsShotDirs = listOf("score-bug", "score-bug-stacked", "nfl-screenshots", "power-play", "goal-animation", "sports-tab")
val cleanSportsShots = tasks.register<Delete>("cleanSportsShots") {
    description = "Deletes the sports screenshot folders the hash gate checks."
    delete(sportsShotDirs.map { layout.buildDirectory.dir(it) })
}
val sportsHashGateRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "sportsHashGate" }
tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
    mustRunAfter(cleanSportsShots)
    if (sportsHashGateRequested) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
tasks.register("sportsHashGate") {
    group = "verification"
    description = "Runs the :ui unit tests with fresh sports screenshots and checks the NHL/NFL/Stacked PNG hashes."
    dependsOn(cleanSportsShots, "testDebugUnitTest")
    val list = layout.projectDirectory.file("src/test/resources/golden/sports-hashes.txt").asFile
    val root = layout.buildDirectory.get().asFile
    val dirs = sportsShotDirs.toList() // a local copy: the action must not capture the build script (configuration cache)
    inputs.file(list)
    outputs.upToDateWhen { false }
    doLast {
        val here = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} jdk ${System.getProperty("java.version")}"
        val lines = list.readLines()
        val recorded = lines.firstOrNull { it.startsWith("# host:") }?.removePrefix("# host:")?.trim()
            ?: throw GradleException("sports-hashes.txt has no '# host:' line")
        if (recorded != here) {
            logger.warn("sportsHashGate SKIPPED: the list was recorded on '$recorded', this host is '$here'. The PNG bytes depend on the host; regenerate the list here from the commit its header names to gate on this host.")
            return@doLast
        }
        val md = MessageDigest.getInstance("SHA-256")
        val bad = ArrayList<String>()
        var n = 0
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val hash = line.substringBefore(' ')
            val path = line.substringAfter(' ').trim()
            n++
            val f = File(root, path)
            if (!f.isFile) { bad += "missing  $path"; continue }
            val got = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            if (got != hash) bad += "changed  $path"
        }
        val listed = lines.filter { !it.startsWith("#") && it.isNotBlank() }.map { it.substringAfter(' ').trim() }.toSet()
        val extra = dirs.flatMap { d -> File(root, d).walk().filter { it.isFile && it.name.endsWith(".png") }.map { it.relativeTo(root).invariantSeparatorsPath }.toList() }
            .filter { it !in listed }
        if (extra.isNotEmpty()) logger.warn("sportsHashGate: ${extra.size} PNG(s) not in the list (new cases? review by eye, then add): ${extra.take(10)}")
        if (bad.isNotEmpty()) throw GradleException("sportsHashGate: ${bad.size} of $n screenshots differ from sports-hashes.txt:\n" + bad.take(40).joinToString("\n"))
        logger.lifecycle("sportsHashGate: all $n NHL / NFL / Stacked screenshots hash equal on '$here'.")
    }
}
