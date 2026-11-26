plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Versions come from the release tag (v1.2.3 → name "1.2.3", code 10203): CI passes it in
// LOOKEE_VERSION, local builds read the latest git tag. Per-ABI APKs: code ×10, plus 1
// (armeabi-v7a) or 2 (arm64-v8a).
val versionNameFromTag: String = System.getenv("LOOKEE_VERSION")
    ?: runCatching {
        // Local builds: the latest tag, so they install over the release the box already has.
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().removePrefix("v")
    }.getOrNull()?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
    ?: "0.1.0"
val versionCodeFromTag: Int = versionNameFromTag.split('.').mapNotNull { it.toIntOrNull() }
    .let { p -> if (p.size == 3) p[0] * 10_000 + p[1] * 100 + p[2] else 1 }

// Release signing: the keystore and its password from the environment, never the repo
// (docs/RELEASING.md). Without them, release builds fall back to the debug key.
val releaseKeystore: String? = System.getenv("LOOKEE_KEYSTORE")
val releaseKeystorePassword: String? = System.getenv("LOOKEE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = System.getenv("LOOKEE_KEY_ALIAS") ?: "androiddebugkey"
val canSignRelease = releaseKeystore != null && releaseKeystorePassword != null && file(releaseKeystore).isFile

android {
    namespace = "com.lookee.tv"
    compileSdk = 35
    // Lets AGP strip the Go library's debug symbols with the NDK's llvm-strip.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.lookee.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeFromTag
        versionName = versionNameFromTag
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = System.getenv("LOOKEE_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    // One APK per ARM architecture (the Mi Box takes armeabi-v7a): half the download. Debug
    // builds keep one APK with x86 for the emulator.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    // Native libraries stay uncompressed and are read straight from the APK, so Android keeps
    // no extracted copy. (They used to be compressed to save flash, but compressed libraries are
    // extracted at install: the box held both, ~95 MB, against ~66 MB this way.)
    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    buildTypes {
        debug {
            // One APK with every ABI the libraries carry: x86 runs the Android 9 TV emulator.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release key (the same key the box's install already carries, docs/RELEASING.md);
            // without it, the debug key so local builds still install.
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // gomobile bind output (make bind). Git-ignored; built from core/.
    implementation(files("libs/lookeecore.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    // QR code for the phone setup URL (pure Java, no network).
    implementation(libs.zxing.core)
    // Playback (Phase 5): hardware decoding through MediaCodec; PlayerView only for its surface and subtitle view.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Discs with menus and the audio formats MediaCodec lacks (ROADMAP Decision 14: hybrid).
    implementation(libs.libvlc)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// BridgeContractTest reads the Go core's golden JSON: rerun it whenever those files change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("core/testdata/golden"))
        .withPropertyName("goldenJson")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Per-ABI version codes: arm64 above armeabi-v7a, both above any earlier single APK.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val offset = when (abi) { "armeabi-v7a" -> 1; "arm64-v8a" -> 2; else -> 0 }
            output.versionCode.set(versionCodeFromTag * 10 + offset)
        }
    }
}
