import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stessa firma per sempre: ogni APK nuovo si installa sopra il precedente.
// Optional signing key, kept out of git (see README). Without it, debug uses the Android debug key and release is unsigned.
val signingFile = rootProject.file(".signing/keystore.properties")
val signingProps: Properties? = if (signingFile.exists()) Properties().apply { signingFile.inputStream().use { load(it) } } else null

android {
    namespace = "com.the151universe.grabber"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.the151universe.grabber"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.7.5"
    }

    signingConfigs {
        if (signingProps != null) create("grabber") {
            storeFile = rootProject.file(".signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug { if (signingProps != null) signingConfig = signingConfigs.getByName("grabber") }
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("grabber")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
}
