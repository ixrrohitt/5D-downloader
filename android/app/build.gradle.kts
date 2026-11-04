plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The web UI (../www) + version.json are bundled into the APK as the offline fallback.
val wwwOut = layout.buildDirectory.dir("generated/wwwAssets")
val syncWww by tasks.registering(Copy::class) {
    from(rootProject.file("../www")) { into("www") }
    from(rootProject.file("../version.json")) { into("www") }
    into(wwwOut)
    doFirst {
        check(rootProject.file("../www/index.html").exists()) { "www/index.html not found at ${rootProject.file("../www")}" }
    }
}
// Always run before any build step so the assets dir exists when AGP merges assets.
tasks.named("preBuild") { dependsOn(syncWww) }

android {
    namespace = "com.ixrrohitt.fived"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ixrrohitt.fived"
        minSdk = 29
        targetSdk = 34
        versionCode = 2          // keep in sync with "apk_version" in ../version.json
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Fixed key (committed) so every build can be installed over the previous one.
    signingConfigs {
        create("fived") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fived")
        }
        debug { signingConfig = signingConfigs.getByName("fived") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }   // yt-dlp / python / ffmpeg are shipped as native libs
    sourceSets.getByName("main").assets.srcDir(wwwOut)
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
