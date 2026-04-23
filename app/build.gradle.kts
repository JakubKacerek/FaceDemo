plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.facedemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.facedemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildToolsVersion = "36.1.0"

    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

// ML Kit
    implementation("com.google.mlkit:face-detection:16.1.5")


    // Security - encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Image processing
    implementation("androidx.graphics:graphics-core:1.0.0-alpha03")

    // TensorFlow Lite – banknote detection (float32 model)
    // LiteRT is the new name for TFLite on Android (avoids namespace conflict on compileSdk 36)
    implementation("com.google.ai.edge.litert:litert:1.0.1") {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation("com.google.ai.edge.litert:litert-support:1.0.1") {
        exclude(group = "com.google.ai.edge.litert", module = "litert-support-api")
    }
}