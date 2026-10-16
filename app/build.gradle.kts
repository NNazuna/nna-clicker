plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nnazuna.nnaclicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nnazuna.nnaclicker"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "2.1.0-beta.1"
    }

    // Kunci tanda tangan tetap, supaya APK versi baru bisa langsung menimpa versi lama.
    signingConfigs {
        create("stable") {
            storeFile = file("signing/oprecorder.keystore")
            storePassword = "u3NCvmRw3AURfb4QBCFb"
            keyAlias = "oprecorder"
            keyPassword = "u3NCvmRw3AURfb4QBCFb"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        aidl = true
        buildConfig = true
    }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
}
