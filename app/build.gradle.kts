plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from CI secrets; without them the release APK stays unsigned.
val keystore = System.getenv("KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    // never change this id later: Android would treat it as a new app
    namespace = "com.claudeborne.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudeborne.camera"
        minSdk = 34
        targetSdk = 35
        // CI passes -PversionCode=<100 + run number> so every build is newer than the last; local builds keep this number
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // no keystore (local builds) falls back to the debug key so the APK still installs
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
            // phone is arm64; -Pemulator also keeps x86_64 so a release build can be tried on the emulator
            ndk { abiFilters += if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
        }
        debug {
            // installs next to the release app as "Camera Dev"; no abiFilters so it runs on phone and emulator
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Camera Dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; resValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
