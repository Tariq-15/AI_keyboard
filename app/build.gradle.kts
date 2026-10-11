plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chalkboard.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chalkboard.keyboard"
        // 26 is the floor for adaptive launcher icons and the java.time APIs the
        // personal-learning decay pass will need; it covers effectively all live devices.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        // The about screen states the version it is actually running, rather than a
        // number kept in sync by hand.
        buildConfig = true
    }

    androidResources {
        // The n-gram dictionary is memory-mapped straight out of the APK, which is only
        // possible if it is stored uncompressed. See NgramDictionary.
        noCompress += "bin"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // ExploreByTouchHelper: exposes the canvas-drawn keys to TalkBack (section 1.6).
    implementation(libs.androidx.customview)
    // The emoji panel: categories, recents and skin tones, kept current by androidx.
    implementation(libs.androidx.emoji2.emojipicker)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
