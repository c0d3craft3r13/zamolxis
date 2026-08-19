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
        // Vendored Reticulum stack (reticulum-kt / LXMF-kt / LXST-kt), checked into
        // libs/ as a plain Maven layout. Listed FIRST so a fresh clone builds with no
        // network and never depends on JitPack still being willing to serve those
        // repos. Refresh with scripts/vendor-libs.sh after bumping a version.
        maven { url = uri("$rootDir/libs") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // usb-serial-for-android; fallback for the vendored stack
    }
}

rootProject.name = "zamolxis"

// Opt-in composite-build override: point reticulum-kt/LXMF-kt/LXST-kt
// at a local checkout for a tight edit-build-install loop. Enable with
// e.g. `LOCAL_RETICULUM_KT=../reticulum-kt ./gradlew :app:installDebug`.
// Not committed as always-on to avoid masking the published artifact from
// CI / other developers.
System.getenv("LOCAL_RETICULUM_KT")?.let { includeBuild(it) }
// LXMF-kt + LXST-kt are published on JitPack with its single-module
// root-coord collapse: the Maven coord is `<user>:<repo>`, but the
// actual Gradle module is a subproject (lxmf-core / lxst-core). A
// plain `includeBuild` won't auto-substitute because the project
// group (`com.github.torlando-tech.LXMF-kt`) and artifact
// (`lxmf-core`) don't match the consumer-side coord
// (`com.github.torlando-tech:LXMF-kt`). We add an explicit
// dependencySubstitution to map the collapsed coord to the real
// subproject.
System.getenv("LOCAL_LXMF_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXMF-kt"))
                .using(project(":lxmf-core"))
        }
    }
}
System.getenv("LOCAL_LXST_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXST-kt"))
                .using(project(":lxst"))
        }
    }
}

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
