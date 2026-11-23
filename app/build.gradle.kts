import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Strata is built WITHOUT the Android Gradle Plugin.
 *
 * It only depends on the Android framework + Kotlin stdlib, so the whole APK can be produced from
 * Maven Central artifacts and a handful of standalone Android build tools:
 *
 *   aapt (R.java + resource packaging) -> kotlinc/javac -> ProGuard (shrink + lambda backport)
 *   -> dx/d8 (dex) -> aapt (package) -> zipalign -> apksigner
 *
 * Tool locations can be overridden with Gradle properties (-Pname=value) or local.properties:
 *   aapt, dx, zipalign, apksigner        executables (defaults: Debian/Ubuntu packages on PATH)
 *   aaptPlatformJar                      android.jar used to link resources (any API >= 23)
 *
 * Signing: set STRATA_KEYSTORE, STRATA_KEYSTORE_PASSWORD, STRATA_KEY_ALIAS and STRATA_KEY_PASSWORD
 * (environment variables or Gradle properties) to sign with a release key; otherwise the debug key
 * in config/debug.keystore is used.
 */

plugins {
    kotlin("jvm") version "2.4.21"
}

val appId = "app.strata.psd"
// Release builds pass these from the git tag: -PversionName=1.2.0 -PversionCode=10200
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val minSdk = 29
val targetSdk = 36

fun prop(name: String, default: String): String {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val p = Properties().apply { local.inputStream().use { load(it) } }
        p.getProperty(name)?.let { return it }
    }
    return (findProperty(name) as String?) ?: default
}

val aapt = prop("aapt", "aapt")
val dx = prop("dx", "dalvik-exchange")
val zipalign = prop("zipalign", "zipalign")
val apksigner = prop("apksigner", "apksigner")
val aaptPlatformJar = prop("aaptPlatformJar", "/usr/lib/android-sdk/platforms/android-23/android.jar")

// Full Android 16 (API 36) framework classes, published by Robolectric on Maven Central.
// Used as compile-only "android.jar" (and as ProGuard library jar).
val androidFramework: Configuration by configurations.creating { isTransitive = false }
val proguardTool: Configuration by configurations.creating

dependencies {
    androidFramework("org.robolectric:android-all:16-robolectric-13921718-2")
    proguardTool("com.guardsquare:proguard-base:7.10.0")
}

val gen = layout.buildDirectory.dir("generated/r")
val out = layout.buildDirectory.dir("apk")
val manifest = file("src/main/AndroidManifest.xml")
val resDir = file("src/main/res")
val assetsDir = file("src/main/assets")

// Strip the 200 MB robolectric jar down to framework classes only.
val androidJar = tasks.register<Jar>("androidJar") {
    archiveFileName.set("android-36.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sdk"))
    from({ zipTree(androidFramework.singleFile) }) {
        include("android/**/*.class", "com/android/**/*.class", "dalvik/**/*.class", "libcore/**/*.class",
            "org/xmlpull/**/*.class", "org/json/**/*.class", "org/w3c/**/*.class", "org/xml/**/*.class")
    }
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val generateR = tasks.register<Exec>("generateR") {
    inputs.file(manifest); inputs.dir(resDir)
    outputs.dir(gen)
    doFirst { gen.get().asFile.mkdirs() }
    commandLine(aapt, "package", "-f", "-m", "--auto-add-overlay",
        "-J", gen.get().asFile.absolutePath,
        "-M", manifest.absolutePath, "-S", resDir.absolutePath, "-I", aaptPlatformJar)
}

sourceSets.main {
    java.srcDir(gen)
}

dependencies {
    compileOnly(files(androidJar))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-Xlambdas=class", "-Xsam-conversions=class",
            "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions",
        )
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateR)
    options.compilerArgs.add("-Xlint:-options")
}
tasks.named("compileKotlin") { dependsOn(generateR) }

val proguard = tasks.register<JavaExec>("proguard") {
    val classes = sourceSets.main.get().output.classesDirs
    val runtime = configurations.runtimeClasspath.get()
    val outJar = out.map { it.file("app-min.jar") }
    dependsOn("classes", androidJar)
    inputs.files(classes, runtime, androidJar)
    inputs.file("proguard-rules.pro")
    outputs.file(outJar)
    classpath = proguardTool
    mainClass.set("proguard.ProGuard")
    doFirst {
        val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile
        args = listOf(
            "@" + file("proguard-rules.pro").absolutePath,
            "-injars", (classes.files + runtime.files).joinToString(File.pathSeparator) { it.absolutePath + "(!META-INF/versions/**,!**/module-info.class)" },
            "-outjars", outJar.get().asFile.absolutePath,
            "-libraryjars", androidJar.get().archiveFile.get().asFile.absolutePath,
            "-libraryjars", "$jdk/jmods/java.base.jmod(!**.jar;!module-info.class)",
            "-printmapping", out.get().file("mapping.txt").asFile.absolutePath,
        )
    }
}

val dex = tasks.register<Exec>("dex") {
    dependsOn(proguard)
    val dexFile = out.map { it.file("classes.dex") }
    inputs.file(out.map { it.file("app-min.jar") })
    outputs.file(dexFile)
    commandLine(dx, "--dex", "--min-sdk-version=$minSdk",
        "--output=${dexFile.get().asFile.absolutePath}", out.get().file("app-min.jar").asFile.absolutePath)
}

val debugKeystore = rootProject.file("config/debug.keystore")
val keystore = tasks.register<Exec>("debugKeystore") {
    onlyIf { !debugKeystore.exists() }
    outputs.file(debugKeystore)
    commandLine("keytool", "-genkeypair", "-keystore", debugKeystore.absolutePath, "-storepass", "android",
        "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")
}

val assembleApk = tasks.register("assembleApk") {
    group = "build"
    description = "Builds a signed, zip-aligned APK into app/build/apk/strata.apk"
    dependsOn(dex, keystore)
    inputs.file(manifest); inputs.dir(resDir); inputs.file(out.map { it.file("classes.dex") })
    if (assetsDir.isDirectory) inputs.dir(assetsDir)
    inputs.property("version", "$appVersionName/$appVersionCode")
    inputs.property("signingKey", System.getenv("STRATA_KEYSTORE") ?: (findProperty("STRATA_KEYSTORE") as String?) ?: "debug")
    val apk = out.map { it.file("strata.apk") }
    outputs.file(apk)
    doLast {
        val dir = out.get().asFile
        val unsigned = File(dir, "unsigned.apk")
        val aligned = File(dir, "aligned.apk")
        unsigned.delete(); aligned.delete()
        fun run(vararg cmd: String, workDir: File = dir) {
            val p = ProcessBuilder(*cmd).directory(workDir).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("Command failed: ${cmd.joinToString(" ")}\n$text")
        }
        run(aapt, "package", "-f", "--auto-add-overlay", "-0", "arsc", "-0", "psd", "-0", "psb",
            "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk",
            "--version-code", "$appVersionCode", "--version-name", appVersionName,
            "--rename-manifest-package", appId,
            "-M", manifest.absolutePath, "-S", resDir.absolutePath, "-I", aaptPlatformJar,
            *(if (assetsDir.isDirectory) arrayOf("-A", assetsDir.absolutePath) else emptyArray()),
            "-F", unsigned.absolutePath)
        run(aapt, "add", unsigned.absolutePath, "classes.dex")
        run(zipalign, "-f", "-p", "4", unsigned.absolutePath, aligned.absolutePath)
        fun secret(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: (findProperty(name) as String?)
        val releaseKs = secret("STRATA_KEYSTORE")?.let { File(it) }?.takeIf { it.isFile }
        val signArgs = if (releaseKs != null) {
            logger.lifecycle("Signing with release key ${releaseKs.name}")
            arrayOf("--ks", releaseKs.absolutePath, "--ks-pass", "env:STRATA_KEYSTORE_PASSWORD",
                "--ks-key-alias", secret("STRATA_KEY_ALIAS") ?: throw GradleException("STRATA_KEY_ALIAS not set"),
                "--key-pass", if (System.getenv("STRATA_KEY_PASSWORD").isNullOrBlank()) "env:STRATA_KEYSTORE_PASSWORD" else "env:STRATA_KEY_PASSWORD")
        } else {
            logger.lifecycle("Signing with the DEBUG key (set STRATA_KEYSTORE for release signing)")
            arrayOf("--ks", debugKeystore.absolutePath, "--ks-pass", "pass:android", "--key-pass", "pass:android")
        }
        run(apksigner, "sign", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", *signArgs,
            "--min-sdk-version", "$minSdk", "--out", apk.get().asFile.absolutePath, aligned.absolutePath)
        unsigned.delete(); aligned.delete()
        File(dir, "strata.apk.idsig").delete()
        logger.lifecycle("APK: ${apk.get().asFile}")
    }
}

// Renders a directory of PSDs on the JVM with the same engine the app uses (for verification).
tasks.register<JavaExec>("renderPsds") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.strata.psd.engine.RenderToolKt")
    args = listOfNotNull(findProperty("psdDir") as String?, findProperty("quiet") as String?)
    maxHeapSize = "4g"
}
tasks.register<JavaExec>("bench") { dependsOn("testClasses"); classpath = sourceSets.test.get().runtimeClasspath; mainClass.set("app.strata.psd.engine.BenchKt"); args = listOfNotNull(findProperty("psd") as String?); maxHeapSize = "512m" }
