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
        versionCode = 17
        versionName = "2.2.1"
    }

    // Release key comes from the environment (GitHub Actions secrets), never from the repo.
    // Without it (local builds, forks) the default debug key is used.
    val signingStore = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (signingStore != null) {
            create("stable") {
                storeFile = file(signingStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingStore != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (signingStore != null) signingConfig = signingConfigs.getByName("stable")
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

    testImplementation("junit:junit:4.13.2")
}
