plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val aiBackendUrl = providers.gradleProperty("claudgameAiBackendUrl").orElse("").get()

android {
    namespace = "com.lucasmoraes.claudgame"
    buildFeatures { buildConfig = true }

    signingConfigs {
        create("claudGameDebug") {
            storeFile = file("../claudgame-debug.keystore")
            storePassword = "claudgame123"
            keyAlias = "claudgame"
            keyPassword = "claudgame123"
        }
    }
    compileSdk = 35
    defaultConfig {
        buildConfigField("String", "AI_BACKEND_URL", "\"$aiBackendUrl\"")
        applicationId = "com.lucasmoraes.claudgame"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("claudGameDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
