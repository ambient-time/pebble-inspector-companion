import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeCompiler)
}
val signalRelease = Properties().apply { rootProject.file("signalApp/release.properties").inputStream().use { load(it) } }
// The published APK is signed with Signal Station's own release key. The four
// values arrive from the environment only; nothing is read from disk.
val releaseStoreFile = providers.environmentVariable("SIGNAL_STATION_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("SIGNAL_STATION_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SIGNAL_STATION_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SIGNAL_STATION_RELEASE_KEY_PASSWORD").orNull
val releaseSigningAvailable = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }
val signalRevision = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
val identityDirectory = layout.buildDirectory.dir("generated/signalIdentity")
val generateSignalIdentity = tasks.register("generateSignalIdentity") {
    inputs.file("release.properties")
    inputs.property("revision", signalRevision)
    outputs.dir(identityDirectory)
    val revision = signalRevision
    val directory = identityDirectory
    val version = signalRelease.getProperty("versionName")
    val code = signalRelease.getProperty("versionCode")
    doLast {
        directory.get().file("signal-build.txt").asFile.apply {
            parentFile.mkdirs()
            writeText("$version ($code) · ${revision.get().take(8)} · development preview")
        }
    }
}
// The offline English wake model is downloaded into this project's own build
// directory and pinned by SHA-256 before packaging, so building the app runs no
// task from another project. The model's license and notice are checked in under
// src/main/assets/signal-station/.
val signalWakeAssets = layout.buildDirectory.dir("generated/signalWakeAssets")
val prepareSignalWakeModel by tasks.registering {
    val output = signalWakeAssets.map { it.file("signal-station/wake-model.zip") }
    outputs.file(output)
    doLast {
        val expected = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        val target = output.get().asFile
        fun digest(file: java.io.File) = MessageDigest.getInstance("SHA-256").let { hash ->
            file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (!target.isFile || digest(target) != expected) {
            target.parentFile.mkdirs()
            val temporary = target.resolveSibling("wake-model.download")
            try {
                URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL().openConnection().apply {
                    connectTimeout = 15000; readTimeout = 60000
                }.getInputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                check(digest(temporary) == expected) { "Offline speech model checksum mismatch" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
}
android {
    namespace = "com.lukesteuber.signalstation"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = signalRelease.getProperty("packageName")
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = signalRelease.getProperty("versionCode").toInt()
        versionName = signalRelease.getProperty("versionName")
        // -PsignalGarminTethered=true talks to the Connect IQ simulator over adb instead of Garmin Connect.
        buildConfigField("boolean", "GARMIN_TETHERED", (providers.gradleProperty("signalGarminTethered").orNull == "true").toString())
    }
    sourceSets.getByName("main").assets.srcDir(identityDirectory.get().asFile)
    signingConfigs {
        create("release") {
            if (releaseSigningAvailable) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(signalWakeAssets.get().asFile)
}
dependencies {
    implementation(project(":localmodels"))
    implementation(project(":signal"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.serialization)
    implementation("io.rebble.pebblekit2:client:1.1.0")
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
}
tasks.matching { it.name.contains("Assets") || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(prepareSignalWakeModel) }

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateSignalIdentity) }

// An unsigned release APK is easy to mistake for a publishable one. Published
// builds are release-signed and never debuggable.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    val signed = releaseSigningAvailable // a local copy keeps the configuration cache usable
    doFirst {
        check(signed) {
            "Release signing is not configured. Run scripts/build-signal-release.sh through the release toolkit's with-signing command."
        }
    }
}
