plugins {
    alias(libs.plugins.android.library)
}

// Binder interfaces shared by the assistant app, its isolated inference
// service, and the separate web-search package.
android {
    namespace = "io.shubham0204.smollmandroid.assistant.ipc"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
    }
    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
