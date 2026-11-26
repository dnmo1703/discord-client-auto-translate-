plugins {
    id("com.android.application")
}

android {
    namespace = "com.denimo.dcclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.denimo.dcclient"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("dcclient.keystore")
            storePassword = "dcclient"
            keyAlias = "dcclient"
            keyPassword = "dcclient"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // geo-базы из libv2ray.aar не нужны (маршрутизации по странам нет) — экономим ~27 МБ
    androidResources {
        ignoreAssetsPattern = "!geoip.dat:!geosite.dat:!geoip-only-cn-private.dat"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation(files("libs/libv2ray.aar"))
}
