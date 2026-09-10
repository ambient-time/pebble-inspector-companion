import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeCompiler)
}
val signalRelease = Properties().apply { rootProject.file("signalApp/release.properties").inputStream().use { load(it) } }
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
    }
    sourceSets.getByName("main").assets.srcDir(identityDirectory.get().asFile)
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.layout.projectDirectory.dir("androidApp/build/generated/signalWakeAssets"))
}
dependencies {
    implementation(project(":signal"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.serialization)
    implementation("io.rebble.pebblekit2:client:1.1.0")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
tasks.matching { it.name.contains("Assets") || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(":androidApp:prepareSignalWakeModel") }

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateSignalIdentity) }
