// Vendored: reticulum-kt v0.0.22, module rns-core. MPL-2.0 — see ../LICENSE.
// Sources are checked in; nothing here resolves from JitPack. See vendor/PROVENANCE.md.
//
// This build file is NOT upstream's. Upstream published to Maven and pinned its own
// dependency versions in gradle.properties; here the versions come from this repo's
// version catalog, which is what the app already resolved to anyway — Gradle picked
// the app's higher coroutines/BouncyCastle/MessagePack over the ones the published
// POM asked for.
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
    implementation(libs.coroutines.core)

    // Cryptography — Ed25519/X25519/AES/HMAC. rns-core composes primitives, it does
    // not implement any.
    implementation(libs.bouncycastle)

    // api: exposed through Transport/Identity persistence signatures.
    api(libs.msgpack)

    // BZ2 for Resource compression.
    implementation(libs.commons.compress)

    // SLF4J facade only; the consuming app supplies the binding.
    implementation(libs.kotlin.logging)
}
