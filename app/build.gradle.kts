plugins {
    id("com.android.application")
}

android {
    namespace = "io.fold"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.fold"
        minSdk = 30
        // 28 on purpose: Android 10+ blocks exec() of files in the app's data dir for targetSdk >= 29,
        // and the whole runtime (node, bash, php, clang...) lives there. See plan.md, "Distribution".
        targetSdk = 28
        versionCode = 1
        versionName = "0.0.1"
        ndk {
            abiFilters += "arm64-v8a"
            // x86_64 only so the probe also runs on the emulator.
            abiFilters += "x86_64"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 28 is intentional (sideload only, never Google Play).
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.window:window-java:1.5.1")
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation("junit:junit:4.13.2")
}
