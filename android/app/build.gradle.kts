import java.util.Properties

plugins {
    alias(libs.plugins.android.application)   // AGP 9: Kotlin support is built in
}

// Lets a checkout install over an app that was first sideloaded under a different id, without
// that id living in the published source. Set `cinder.applicationId=...` in local.properties
// (gitignored); everyone else builds app.cinderlite.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.cinder"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = localProperties.getProperty("cinder.applicationId") ?: "app.cinderlite"
        minSdk = 30
        // Deliberately 28: apps targeting API 29+ cannot exec binaries out of their own
        // writable data directory, which is exactly what the sandbox needs to do. Termux
        // used the same approach. Sideload only — Play Store requires a current target.
        targetSdk = 28
        versionCode = 35
        versionName = "2.1.0-lite"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from local.properties (gitignored), never from the repo:
    //   cinder.storeFile=keystore/cinder-lite-release.jks   (relative to the project root)
    //   cinder.storePassword=…  cinder.keyAlias=…  cinder.keyPassword=…
    // Every update must be signed with the same key, or it won't install over the previous one.
    val releaseSigning = localProperties.getProperty("cinder.storeFile")?.let { store ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(store)
            storePassword = localProperties.getProperty("cinder.storePassword")
            keyAlias = localProperties.getProperty("cinder.keyAlias")
            keyPassword = localProperties.getProperty("cinder.keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = releaseSigning
            // R8 drops unused Kotlin/library code (~2 MB). The stock rules keep classes with native
            // methods by name, which is what libtermux's JNI entry points need. Keep each release's
            // build/outputs/mapping/release/mapping.txt to read its stack traces.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { aidl = true }   // IShellService, the Shizuku user service's interface

    // targetSdk 28 is deliberate (see above) and this app is never published to Play.
    lint { disable += "ExpiredTargetSdkVersion" }

    // Assets are binaries and an already-gzipped tarball. Leaving aapt to compress them makes
    // AssetManager refuse to stream anything over ~1 MB, which is what broke the rootfs unpack.
    androidResources {
        noCompress += listOf("tgz", "so", "2", "loader", "proot", "ttf")   // ttf: Font.Builder maps it in place
    }

    // libtermux, built from source so it's 16 KB page-aligned (see src/main/cpp/CMakeLists.txt).
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    // terminal-emulator's AAR ships its own (4 KB-aligned) libtermux.so; ours is packaged first.
    packaging {
        jniLibs { pickFirsts += "lib/*/libtermux.so" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.termux.terminal.view)
    // Optional adb-shell access through the Shizuku app (⋮ → Shizuku); see ShizukuBridge.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // hev-socks5-tunnel 2.19.0 (MIT), its release AAR as published (sha256 fbbf014c1c4c6d1e0922db88542abd9dc726600473bdfdc8039ed157b4ba02b0):
    // turns the VPN's packets back into connections for mitmproxy (⋮ → Watch network traffic; see TrafficVpn).
    implementation(files("libs/hev-socks5-tunnel-2.19.0.aar"))
}
