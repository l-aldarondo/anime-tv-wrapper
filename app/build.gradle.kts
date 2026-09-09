plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.animetv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.animetv"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "2.0.2"

        // GeckoView ships no 32-bit x86 .so files for this build.
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
        }
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

    // Default AAPT packaging silently drops any asset directory starting with "_"
    // (e.g. uBlock Origin's _locales/), which breaks its manifest.json i18n placeholders.
    androidResources {
        ignoreAssetsPattern = "!_locales:!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

// ignoreAssetsPattern above only affects the final aapt2 packaging step; the earlier
// mergeAssets task strips "_"-prefixed directories on its own and ignores that setting.
// Re-inject uBlock's _locales/ into the merged output so it survives into the APK.
// Captured as a plain File (not a live Project/script reference) and copied with the Kotlin
// stdlib rather than Gradle's copy{} DSL, both specifically so this stays compatible with the
// configuration cache (org.gradle.configuration-cache=true, see gradle.properties) - the DSL
// version failed to serialize with "cannot serialize Gradle script object references".
val ublockLocalesSrc = layout.projectDirectory.file("src/main/assets/ublock_origin/_locales").asFile
tasks.matching { it.name.matches(Regex("merge[A-Za-z]+Assets")) }.configureEach {
    doLast {
        outputs.files.files
            .map { File(it, "ublock_origin") }
            .filter { it.isDirectory }
            .forEach { ublockDir ->
                ublockLocalesSrc.copyRecursively(File(ublockDir, "_locales"), overwrite = true)
            }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)

  // The GeckoView Engine
  implementation("org.mozilla.geckoview:geckoview:153.0.20260715202819")

  testImplementation(libs.junit)
}
