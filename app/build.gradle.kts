plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "cn.yojigen.sharehelper"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.yojigen.sharehelper"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    signingConfigs {
        create("release") {
            val releaseStoreFile = System.getenv("SHAREHELPER_RELEASE_STORE_FILE")
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("SHAREHELPER_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("SHAREHELPER_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("SHAREHELPER_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
  implementation(libs.androidx.exifinterface)
}
