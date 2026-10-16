plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "hu.photosizer.photo_sizer"
    // Pinned to the SDK that is installed on this machine (API 33 / build-tools 33.0.2).
    compileSdk = 33
    buildToolsVersion = "33.0.2"
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "hu.photosizer.photo_sizer"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 26
        targetSdk = 33
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Flutter's AndroidX dependencies declare compileSdk 34+, but this project is deliberately kept on the
// SDK that is installed (API 33). The libraries are precompiled and the target devices (Android 16)
// provide every API they use, so the metadata check is switched off instead of updating the SDK.
tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) enabled = false
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
