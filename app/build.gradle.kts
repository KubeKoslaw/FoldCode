import java.util.Properties

plugins {
    id("com.android.application")
}

// Where the app downloads its environment from, and the SHA-256 it must match.
// Written by scripts/publish-packages.sh; empty values make first start report a clear error.
val bootstrap = Properties().apply {
    val f = file("bootstrap.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.fold"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "BOOTSTRAP_URL", "\"${bootstrap.getProperty("bootstrap.url", "")}\"")
        buildConfigField("String", "BOOTSTRAP_SHA256", "\"${bootstrap.getProperty("bootstrap.sha256", "")}\"")
        buildConfigField("long", "BOOTSTRAP_SIZE", "${bootstrap.getProperty("bootstrap.size", "0")}L")
        buildConfigField("String", "REPO_URL", "\"${bootstrap.getProperty("repo.url", "")}\"")

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
