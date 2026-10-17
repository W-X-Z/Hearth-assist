import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hearthassist.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hearthassist.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("HA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HA_VERSION_NAME") ?: "0.1.0"
        ndk {
            // ML Kit ships native OCR libraries; phones only need the ARM ones.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed key keeps sideloaded updates installable over each other.
        // Override with HA_KEYSTORE_* environment variables for your own key.
        create("shared") {
            storeFile = file(System.getenv("HA_KEYSTORE_PATH") ?: "signing/hearthassist.jks")
            storePassword = System.getenv("HA_KEYSTORE_PASSWORD") ?: "hearthassist"
            keyAlias = System.getenv("HA_KEY_ALIAS") ?: "hearthassist"
            keyPassword = System.getenv("HA_KEY_PASSWORD") ?: "hearthassist"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // Shizuku instantiates FileUserService by reflection; keep shrinking off.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
