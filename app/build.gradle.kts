plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.msa.r2mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.msa.r2mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            // Poco F6 Pro is arm64-v8a. The bundled radare2 binary is built for this ABI only.
            abiFilters += listOf("arm64-v8a")
        }
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

    packaging {
        jniLibs {
            // Keep libr2.so extracted on disk so it can be executed from nativeLibraryDir.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
