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
        versionCode = 1
        versionName = "1.0.0-lite"
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
    implementation(libs.glide)
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation(libs.junit)
}
