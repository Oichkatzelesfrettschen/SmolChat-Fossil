plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Separate package, separate UID: the only package of the assistant that
// declares INTERNET. It fetches from one configured search endpoint and
// returns stripped text to the signature-matched assistant app.
android {
    namespace = "io.shubham0204.smollmandroid.websearch"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.shubham0204.smollmandroid.websearch"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sandboxed"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":assistant-ipc"))
}
