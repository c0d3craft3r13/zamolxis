// Vendored: LXST-kt v0.0.8, module lxst. MPL-2.0 — see ../LICENSE.
// See vendor/PROVENANCE.md — in particular the note on the two prebuilt .so files
// under src/main/jniLibs, which are the one part of this module that is NOT built
// from source in this repo.
plugins {
    id("com.android.library")
}

android {
    namespace = "tech.torlando.lxst"
    compileSdk = 36

    // Pinned rather than left to AGP's default so a build uses the NDK that is
    // installed instead of silently downloading another one. Bump together with the
    // NDK on the build machines.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        // Oboe ships its C++ headers/libs as a Prefab package inside its AAR.
        prefab = true
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Only these two: src/main/jniLibs carries prebuilt libopus/libcodec2 for
            // arm64-v8a and armeabi-v7a only, and CMakeLists imports them by path — an
            // x86_64 variant would fail to link. The app's abiFilters list x86_64 for
            // its other native deps; LXST simply contributes nothing there, exactly as
            // the published AAR did.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Pinned for the same reason as ndkVersion: CMakeLists.txt asks for >= 3.22,
            // and letting AGP pick means a different build host can silently compile the
            // audio path with a different toolchain. 3.22.1 is what the Android SDK
            // installs by default, so this is a pin, not an extra prerequisite.
            version = "3.22.1"
        }
    }

    compileOptions {
        // Upstream targeted Java 21, which is why settings.gradle.kts provisions a
        // JDK 21 toolchain: Hilt-generated Java in the pythonBackend flavor had to
        // read this module's bytecode. Built from source it follows the rest of the
        // repo at 17, so that constraint no longer binds.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Native audio — Oboe C++ consumed through Prefab, see buildFeatures above.
    implementation(libs.oboe)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
