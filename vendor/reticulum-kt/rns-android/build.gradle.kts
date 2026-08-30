// Vendored: reticulum-kt v0.0.22, module rns-android. MPL-2.0 — see ../LICENSE.
// See vendor/PROVENANCE.md.
//
// Carries a local patch on top of v0.0.22 (BleGattServer early-central holding).
plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
}

android {
    namespace = "network.reticulum.android"
    compileSdk = 36

    defaultConfig {
        // minSdk 26 while the app is 24 — this is upstream's floor and is why
        // app/src/main/AndroidManifest.xml carries
        // `tools:overrideLibrary="network.reticulum.android"`. Kept as-is: lowering it
        // would silently expose API-26 calls in this module on API 24/25 devices.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    api(project(":vendor:reticulum-kt:rns-core"))
    api(project(":vendor:reticulum-kt:rns-interfaces"))

    implementation(libs.core.ktx)
    api(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime)
    implementation(libs.work.runtime)

    // No Google Play Services here. Upstream shipped AndroidNearbyDriver.kt, a Nearby
    // Connections implementation of rns-interfaces' NearbyDriver, which pulled
    // play-services-nearby (and coroutines-play-services for Task.await) into every
    // build. Nothing in this app ever constructed it. The file was deleted rather than
    // left dormant: a proprietary blob in the process that runs the mesh stack is not
    // worth carrying for a code path nobody calls. The GMS-free NearbyDriver /
    // NearbyInterface abstractions stay in rns-interfaces, so restoring Nearby means
    // supplying a driver — see vendor/PROVENANCE.md.
    implementation(libs.coroutines.android)

    implementation(libs.room)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
