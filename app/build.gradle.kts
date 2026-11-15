plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val stableKeystore = stableKeystorePath?.let { file(it) }

android {
    namespace = "com.chaman.calllogeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chaman.calllogeditor"
        minSdk = 26
        targetSdk = 28
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.2.0"
    }

    signingConfigs {
        if (stableKeystore != null && stableKeystore.exists()) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore != null && stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
            if (stableKeystore != null && stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
