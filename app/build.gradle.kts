plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spikked27.hyperhdrcalibrator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spikked27.hyperhdrcalibrator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-beta.1"
        testInstrumentationRunner = "android.app.InstrumentationTestRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation(kotlin("test"))
}
