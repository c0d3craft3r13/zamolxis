plugins {
    kotlin("jvm")
    jacoco
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

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}

dependencies {
    // BouncyCastle provides the ML-KEM (FIPS 203) and X25519 implementations.
    // Deliberately a vetted library: nothing in this module implements a
    // cryptographic primitive itself, it only composes them.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}
