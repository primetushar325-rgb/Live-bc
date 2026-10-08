plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videolive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videolive.app"
        minSdk = 24
        // Kept at 34 on purpose: Android 15 (target 35) imposes a 6-hour limit on
        // dataSync foreground services, which would kill long live streams.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The FFmpegKit AARs ship arm64-v8a and x86_64 native libraries.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Encrypted local storage for the stream key (Android Keystore backed).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Real in-process FFmpeg (FFmpegKit). The maintained continuation publishes under
    // dev.ffmpegkit-maintained (the original com.arthenica artifacts were removed from
    // Maven Central in April 2025). Same com.arthenica.ffmpegkit API.
    // "https-gpl" = TLS (RTMPS to YouTube) + libx264 H.264 encoder (GPL variant).
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-https-gpl:6.0.3")
}
