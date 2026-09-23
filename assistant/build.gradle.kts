plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Sandboxed on-device assistant built on SmolChat's :smollm llama.cpp binding.
// This package holds no INTERNET permission: inference runs in an
// isolatedProcess service, and web search is delegated to the separate
// :websearch package over a signature-protected Binder interface.
android {
    namespace = "io.shubham0204.smollmandroid.assistant"
    compileSdk = 35
    // same NDK as :smollm, so stripDebugDebugSymbols finds llvm-strip
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.shubham0204.smollmandroid.assistant"
        // llama.cpp calls posix_madvise (bionic API 23); 25 admits Android 7.1
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sandboxed"
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":smollm"))
    implementation(project(":assistant-ipc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation(libs.junit)
}
