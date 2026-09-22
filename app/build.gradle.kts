plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "monster.kawa.webappcapsule"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "ir.steven750mc.mumarope"
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }

    // CRITICAL for the WebViewAssetLoader-served game files under
    // assets/gamefiles/: by default AAPT compresses assets whose extension
    // it recognizes (and re-compresses/repackages the rest depending on
    // size), which can make WebViewAssetLoader serve them incorrectly or
    // intermittently fail to serve them at all - exactly the "fetch
    // sometimes fails and the game hangs on Loading" symptom. Marking every
    // extension the game actually ships as noCompress makes AAPT store them
    // as-is, so what's read back is byte-for-byte what's on disk.
    androidResources {
        noCompress += ""
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
