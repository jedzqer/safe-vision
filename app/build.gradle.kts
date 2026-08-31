plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safe.vision"
    compileSdk = 36

    val storeFilePath = project.findProperty("STORE_FILE") as String?
    val storePasswordProp = project.findProperty("STORE_PASSWORD") as String?
    val keyAliasProp = project.findProperty("KEY_ALIAS") as String?
    val keyPasswordProp = project.findProperty("KEY_PASSWORD") as String?
    val hasSigning = !storeFilePath.isNullOrBlank() &&
        !storePasswordProp.isNullOrBlank() &&
        !keyAliasProp.isNullOrBlank() &&
        !keyPasswordProp.isNullOrBlank()

    defaultConfig {
        applicationId = "com.safe.vision2"
        minSdk = 24
        targetSdk = 36
        versionCode = 60
        versionName = "2.1.4"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val releaseSigning = if (hasSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(storeFilePath!!)
            storePassword = storePasswordProp
            keyAlias = keyAliasProp
            keyPassword = keyPasswordProp
        }
    } else {
        null
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDirs("src/main/assets", "../assets")

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
