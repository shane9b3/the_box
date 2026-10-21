plugins {
    alias(libs.plugins.thebox.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    // epg/testutil (WP-B) shared with :data tests (docs/design/m1-data-layer.md §6.2, review B2).
    `java-test-fixtures`
}

dependencies {
    api(libs.okio)                               // BufferedSource in public signatures
    api(libs.sqlite)                             // SQLiteDriver/SQLiteConnection in EpgStoreImpl's constructor
    api(libs.kotlinx.coroutines.core)            // StateFlow in EpgStore
    api(libs.kotlinx.serialization.json)         // @Serializable contract types
    implementation(libs.sqlite.bundled)          // KMP: JVM jar here, the Android AAR in :data/:app (per-consumer variant selection)
    implementation(libs.xz)
    implementation(libs.re2j)                    // User patterns must not execute through Android's backtracking ICU matcher.
    implementation(libs.androidx.collection)     // MutableLongIntMap etc. (KMP JVM variant)
    compileOnly(libs.kxml2)                      // org.xmlpull API; the platform provides it on Android
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.sqlite.bundled)
    testFixturesImplementation(libs.okio)
    testFixturesImplementation(libs.kxml2)       // EpgTestDb.openStore() builds a real EpgStoreImpl (amended 2026-10-13)
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("perf") } }

// M3 §1.7 step 2 / §2.13 item 20: the state machine's tests run again on WP-A's GuideNavigatorImpl (GuideWorld reads
// the property). Runs after `test`, so `:core:test` covers both navigators.
val stateOnRealNavigator by tasks.registering(Test::class) {
    description = "Guide state-machine tests on GuideNavigatorImpl (docs/design/m3-guide.md §2.13)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { excludeTags("perf") }
    filter { includeTestsMatching("app.thebox.core.guide.state.*") }
    systemProperty("thebox.realNavigator", "true")
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/stateOnRealNavigator"))
}
tasks.named("test") { finalizedBy(stateOnRealNavigator) }

// M1 performance budgets (§5.5). Not part of `check`; run explicitly or nightly.
val perfTest by tasks.registering(Test::class) {
    description = "M1 performance budgets (docs/design/m1-data-layer.md §5.5)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    maxHeapSize = "64m"
    jvmArgs("-XX:+UseSerialGC")
    // The task may legitimately contain zero tests until the WPs add their *PerfTest classes.
    failOnNoDiscoveredTests = false
    outputs.dir(layout.buildDirectory.dir("perf"))
}
