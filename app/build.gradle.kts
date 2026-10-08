import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// FFmpegKit AAR (community rebuild of the official full-gpl 6.0-2 package,
// verified: com.arthenica.ffmpegkit classes, all 4 ABIs incl. armeabi-v7a).
// The original artifacts were removed from Maven Central in April 2025, so the
// AAR is fetched from a pinned GitHub release and verified by SHA-256.
// ---------------------------------------------------------------------------
val ffmpegKitAarUrl =
    "https://github.com/NooruddinLakhani/ffmpeg-kit-full-gpl/releases/download/v1.0.0/ffmpeg-kit-full-gpl.aar"
val ffmpegKitAarSha256 =
    "87e37384ef5f8755d816212890775ba94d493a70d2eff4615a8d59780ac1fc5e"
val ffmpegKitAar = layout.projectDirectory.file("libs/ffmpeg-kit-full-gpl.aar").asFile

val downloadFfmpegKitAar = tasks.register("downloadFfmpegKitAar") {
    outputs.file(ffmpegKitAar)
    onlyIf { !ffmpegKitAar.exists() }
    doLast {
        ffmpegKitAar.parentFile.mkdirs()
        logger.lifecycle("Downloading FFmpegKit AAR (56 MB)...")
        URI(ffmpegKitAarUrl).toURL().openStream().use { input ->
            ffmpegKitAar.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val sha = ffmpegKitAar.inputStream().use { stream ->
            digest.digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
        }
        if (sha != ffmpegKitAarSha256) {
            ffmpegKitAar.delete()
            throw GradleException(
                "SHA-256 mismatch for ffmpeg-kit-full-gpl.aar (got $sha). " +
                    "Refusing to build with an unverified binary."
            )
        }
        logger.lifecycle("FFmpegKit AAR verified: $sha")
    }
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
        versionCode = 14
        versionName = "2.3"
        // No abiFilters on purpose: the FFmpegKit AAR ships armeabi-v7a,
        // arm64-v8a, x86 and x86_64, so one universal APK runs on every device.
    }

    signingConfigs {
        // Release signing material is provided by CI via environment variables.
        if (System.getenv("RELEASE_KEYSTORE_FILE") != null) {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_FILE"))
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
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
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        // Extract native libraries at install time (extractNativeLibs=true).
        // Maximum compatibility with older ROMs that cannot load unpacked .so
        // files straight from the APK.
        jniLibs.useLegacyPackaging = true
    }
}

afterEvaluate {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(downloadFfmpegKitAar)
    }
}

dependencies {
    // Local, SHA-256-pinned FFmpegKit AAR (all ABIs).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Encrypted local storage for the stream key (Android Keystore backed).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // FFmpegKit's exception helper. The engine is bundled as a LOCAL AAR, so its
    // transitive Maven dependencies are NOT pulled in automatically — FFmpegKit
    // references com.arthenica.smartexception.java.Exceptions at runtime and dies
    // with NoClassDefFoundError without these two artifacts.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("com.arthenica:smart-exception-common:0.2.1")
}
