plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.animetv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.animetv.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "2.8.3-lite"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = false
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.glide)
    implementation("androidx.webkit:webkit:1.12.1")

    // Background Catalog & HTML Parsing
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Native Borderless TV Video Player (Media3 / ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")

    testImplementation(libs.junit)
}
