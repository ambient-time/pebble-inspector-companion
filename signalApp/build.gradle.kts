plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeCompiler)
}
android {
    namespace = "com.lukesteuber.signalstation"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.lukesteuber.signalstation"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0-separation-dev"
    }
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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
tasks.matching { it.name.contains("Assets") || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(":androidApp:prepareSignalWakeModel") }
