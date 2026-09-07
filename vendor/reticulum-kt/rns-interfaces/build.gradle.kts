// Vendored: reticulum-kt v0.0.22, module rns-interfaces. MPL-2.0 — see ../LICENSE.
// See vendor/PROVENANCE.md.
//
// Carries a local patch on top of v0.0.22 (BLE handshake cancellation) — the only
// test vendored alongside the sources is the one that guards it.
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
    api(project(":vendor:reticulum-kt:rns-core"))

    // api: Interface.online is a public StateFlow<Boolean>.
    api(libs.coroutines.core)

    // Upstream's test suite is not vendored — only BLEInterfaceIncomingHandshakeTest,
    // which fails without the local BLE patch (verified by reverting the patch).
    // Plain JUnit 5 assertions on purpose: upstream wrote this test against kotest
    // matchers, and pulling kotest in for four of them would add a dependency tree to
    // a build whose point is to have fewer of them.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
