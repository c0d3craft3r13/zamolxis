// Vendored: LXMF-kt v0.0.14, module lxmf-core. MPL-2.0 — see ../LICENSE.
// See vendor/PROVENANCE.md.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api: LXMRouter/LXMessage expose rns-core types (Destination, Identity) in their
    // signatures, so consumers need rns-core on the compile classpath. Upstream wrote
    // this as the coordinate `com.github.torlando-tech.reticulum-kt:rns-core:v0.0.22`
    // — the same code this project now builds from vendor/.
    api(project(":vendor:reticulum-kt:rns-core"))

    implementation(libs.coroutines.core)
    implementation(libs.msgpack)
    implementation(libs.kotlin.logging)
}
