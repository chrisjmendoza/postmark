plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// versionCode/versionName derived from git commit count so every build is
// uniquely identified — Firebase App Distribution silently rejects re-uploads
// that share a versionCode with a prior release.
val gitSha: String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
} catch (_: Exception) { "unknown" }

val gitCount: Int = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
} catch (_: Exception) { 1 }

android {
    namespace = "com.plusorminustwo.postmark"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.plusorminustwo.postmark"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCount
        versionName = "1.0.$gitCount"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // ── Shared debug keystore ─────────────────────────────────────────────
        // Committed to the repo so all dev machines sign with the same key.
        // This lets you install an updated build without uninstalling first.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.incremental", "true")
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    sourceSets {
        // Expose Room schema JSON files as androidTest assets so MigrationTestHelper
        // can load them for schema-validation migration tests.
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager + Hilt
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coil — image loading for MMS attachments
    implementation(libs.coil.compose)

    // ExifInterface — read EXIF rotation before image compression
    implementation(libs.androidx.exifinterface)

    // Media3 ExoPlayer — video playback in thread view
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Media3 Transformer — video transcoding to fit MMS carrier size limits
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
