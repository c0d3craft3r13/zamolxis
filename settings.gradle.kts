pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK toolchain pinned in build.gradle.kts (JDK 21) so the
    // pythonBackend flavor builds regardless of the developer's default JDK — its
    // Hilt-generated Java makes javac read LXST-kt's Java 21 bytecode, which a
    // JDK <21 javac can't load. CI uses JDK 25; 21 is the local floor.
    //
    // 1.0.0 is the floor for Gradle 9: earlier versions (0.10.0 and below) reference
    // the removed `JvmVendorSpec.IBM_SEMERU`, so provisioning dies at configuration
    // time with `NoSuchFieldError` instead of downloading the toolchain. CI never
    // saw it because its runners ship a JDK 21 that auto-detection finds first.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // libs/ is a plain Maven layout holding artifacts that are not published to
        // Maven Central. It used to hold the Reticulum stack as prebuilt jars/aars;
        // that stack is now built from source under vendor/, and the only thing left
        // here is usb-serial-for-android, which its author publishes through JitPack
        // alone. The .aar is byte-identical to the sha256 pinned in
        // gradle/verification-metadata.xml.
        maven { url = uri("$rootDir/libs") }
        google()
        mavenCentral()
        // No JitPack. It builds artifacts on demand out of repositories this project
        // does not control, so a tag moving — or an upstream author adding something
        // hostile — would land in a build here without anyone reviewing a diff.
        // Everything that came from it is now either vendored source (vendor/) or a
        // checksum-pinned local artifact (libs/).
    }
}

rootProject.name = "zamolxis"

// The Reticulum/LXMF/LXST stack is built from source in vendor/. The
// LOCAL_RETICULUM_KT / LOCAL_LXMF_KT / LOCAL_LXST_KT composite-build overrides that
// used to sit here are gone with it: they existed to point the build at an external
// checkout, which is exactly the door this repo closed. Edit vendor/ directly and
// record the change in vendor/PROVENANCE.md.

include(":app")
include(":data")
include(":micron")
include(":crypto-pq")
include(":rns-api")
include(":rns-ipc")
include(":rns-host")
include(":rns-backend-kt")
include(":rns-backend-py")
include(":rns-stats")
include(":detekt-rules")
include(":screenshot-tests")

// Vendored third-party sources — see vendor/PROVENANCE.md for upstream, version and
// the local patches carried on top. Kept under a `:vendor:` path prefix so every
// dependency edge onto them reads as what it is.
include(":vendor:reticulum-kt:rns-core")
include(":vendor:reticulum-kt:rns-interfaces")
include(":vendor:reticulum-kt:rns-android")
include(":vendor:lxmf-kt:lxmf-core")
include(":vendor:lxst-kt:lxst")
