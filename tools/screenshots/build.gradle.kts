/*
 * Robolectric screenshot harness: runs the real activities on the JVM with native graphics and
 * writes PNGs. Build the app first (../../gradlew :app:assembleApk), then: gradle test
 *
 * androidx.test isn't on Maven Central, so src/stubs contains the handful of androidx.test
 * classes Robolectric references.
 */
plugins { java }
configurations.all { exclude(group = "androidx.test"); exclude(group = "androidx.test.espresso") }
val app = rootDir.resolve("../../app").canonicalFile
val androidAll = files(app.resolve("build/sdk/android-36.jar"))
dependencies {
    compileOnly(androidAll); testCompileOnly(androidAll); testRuntimeOnly(androidAll)
    testImplementation("org.robolectric:robolectric:4.16"); testImplementation("org.robolectric:nativeruntime-dist-compat:1.0.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(app.resolve("build/classes/kotlin/main"), app.resolve("build/classes/java/main")))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
sourceSets { main { java.srcDir("src/stubs/java") } }

val testConfig = tasks.register("testConfig") {
    val out = layout.buildDirectory.dir("generated/test-config/com/android/tools")
    outputs.dir(out)
    doLast {
        out.get().asFile.mkdirs()
        out.get().file("test_config.properties").asFile.writeText("""
            android_merged_manifest=${app.resolve("src/main/AndroidManifest.xml")}
            android_resource_apk=${app.resolve("build/apk/strata.apk")}
            android_merged_assets=${app.resolve("src/main/assets")}
            android_custom_package=app.strata.psd
        """.trimIndent())
    }
}
sourceSets { test { resources.srcDir(layout.buildDirectory.dir("generated/test-config")) } }
tasks.processTestResources { dependsOn(testConfig) }
tasks.test {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    systemProperty("shots.out", layout.buildDirectory.dir("shots").get().asFile.path)
    systemProperty("sample.psd", app.resolve("src/main/assets/sample.psd").path)
    maxHeapSize = "4g"
    outputs.upToDateWhen { false }
}
