plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.framesteps.speedsim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.framesteps.speedsim"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Распознавание активности ОС (IN_VEHICLE / ON_FOOT / WALKING / RUNNING / STILL ...)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
