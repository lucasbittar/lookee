plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lookee.tv"
    compileSdk = 35
    // Lets AGP strip the Go library's debug symbols with the NDK's llvm-strip.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.lookee.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // x86 runs the Android 9 TV emulator; the Mi Box itself is armeabi-v7a.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    packaging {
        // Compressed native libs: the Go core is large and the Mi Box has ~700 MB of free flash.
        jniLibs { useLegacyPackaging = true }
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
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// BridgeContractTest reads the Go core's golden JSON: rerun it whenever those files change.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("core/testdata/golden"))
        .withPropertyName("goldenJson")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
