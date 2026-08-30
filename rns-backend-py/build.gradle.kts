// :rns-backend-py — Chaquopy (upstream Python RNS/LXMF) RnsBackend implementation.
//
// Owns the `ChaquopyRnsBackend` root impl + six sub-interface impls
// (`PythonRnsCore`, `PythonRnsLxmf`, `PythonRnsTelephony`, `PythonRnsTelemetry`,
// `PythonRnsNomadnet`, `PythonRnsTransportAdmin`). Each holds the shared
// `PythonRnsRuntime` and calls upstream RNS/LXMF methods directly via
// `PyObject.callAttr(...)` — Python is the protocol stack, not a wrapper layer.
//
// SLIM-PYTHON DISCIPLINE (see CLAUDE.md in this module):
//   - The Python tree contains ONLY upstream RNS/LXMF wheels + the
//     architecturally-forced interface adapters (BLE/RNode/USB `RNS.Interface`
//     subclasses) + Chaquopy env stubs + the ~50-line `event_bridge.py`
//     callback receiver.
//   - There is NO `rns_*.py` facade and no `reticulum_wrapper.py`. App-logic
//     helpers live in Kotlin in `:rns-host` and are shared by both backends.
//     Re-adding a Python facade is a regression caught by the
//     `NoRnsFacadeInPythonBackend` Detekt rule.
//
// The `com.chaquo.python` plugin is applied HERE, at module level — never at
// `:app` level — so the kotlinBackend flavor's compile/runtime classpath is
// never polluted with the Chaquopy runtime or Python wheels. This module only
// lands on the classpath through `:rns-host`'s `pythonBackendImplementation`
// edge.
//
// Dependency rule: `implementation(:rns-api)` for the contract + `api(lxst.kt)`
// for the telephony value types only. No `:rns-host` dep (the host wires this
// in via its pythonBackend-flavor Hilt module).

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
}

android {
    namespace = "network.zamolxis.app.rns.backend.py"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Mirror :rns-host / :rns-backend-kt: armeabi-v7a + arm64-v8a + x86_64.
            // Chaquopy ships its CPython runtime + native wheels (cryptography)
            // for exactly these ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        // BuildConfig surfaces the pinned upstream wheel versions to Kotlin so
        // PythonCapabilities can report them via RnsBackend.capabilities without
        // a runtime PyObject round-trip. Keep in sync with PINNED_VERSIONS.md.
        // Display strings for the About card — kept short (the pinning SHAs live
        // in PINNED_VERSIONS.md, not the user-facing version line). RNS and LXMF
        // ARE torlando-tech forks of markqvist's upstream; ble-reticulum is
        // torlando-tech's own project, so it carries no "fork" label.
        buildConfigField("String", "PY_RNS_VERSION", "\"1.4.2 (torlando-tech fork)\"")
        buildConfigField("String", "PY_LXMF_VERSION", "\"1.1.0 (torlando-tech fork)\"")
        buildConfigField("String", "PY_BLE_RETICULUM_VERSION", "\"0.2.2\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Keep desugaring in sync with the rest of the rns-* modules.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

// Chaquopy: upstream Python RNS/LXMF as the protocol stack for the python flavor.
//
// Wheel pins live in PINNED_VERSIONS.md (and are mirrored in the buildConfig
// fields above). Pin to commit SHA, not branch tip, wherever the upstream ref
// can be resolved — matches release/v0.10.x's reproducibility discipline.
chaquopy {
    defaultConfig {
        // Target CPython bundled into the APK. Build host needs a matching
        // python3.11 on PATH for pip resolution; CI provisions it.
        version = "3.11"

        pip {
            // RNS / LXMF / ble-reticulum are built from sources checked into
            // vendor/python/ instead of being fetched from GitHub on every build.
            //
            // They used to be three `git+https://github.com/torlando-tech/...@<sha>`
            // installs. The commit pins were sound — a git SHA cannot be moved — but the
            // build reached a repository this project does not control every time it ran,
            // pip is not covered by gradle/verification-metadata.xml, and nobody could
            // review a change without cloning. The forks were diffed against upstream
            // markqvist and audited before being vendored; see vendor/PROVENANCE.md for
            // what the deltas contain and what the audit found.
            //
            // Absolute paths: pip resolves relative paths against its own working
            // directory, which is not this module.
            install(rootProject.file("vendor/python/Reticulum").absolutePath)
            install(rootProject.file("vendor/python/LXMF").absolutePath)
            install(rootProject.file("vendor/python/ble-reticulum").absolutePath)

            install("cryptography>=42.0.0")

            // msgpack — Sideband-compatible telemetry + LXST signalling wire format.
            install("u-msgpack-python")
        }

        // .pyc precompilation requires an exact-minor buildPython on every build
        // host. Ship source form instead — keeps contributor environments simple
        // and is required anyway for RNS.Interface discovery via pkgutil.
        pyc {
            src = false
        }

        // Keep .py sources extractable at runtime: upstream RNS interface
        // discovery (Transport.find_interfaces) and pkgutil.get_data() both read
        // the bundled BLE adapter sources off disk.
        extractPackages("ble_reticulum", "ble_modules")
    }
}

dependencies {
    // Java 8+ core library desugaring runtime (java.time backport for API < 26).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Backend-seam contract (value types, capabilities, sub-interfaces).
    implementation(project(":rns-api"))

    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines — every PyObject call is wrapped in withContext(Dispatchers.IO)
    // to keep the GIL off the caller's thread.
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // LXST-kt — telephony value types only (CallState / CallCoordinator). The
    // python flavor runs voice on LXST-kt exactly like the kotlin flavor; the
    // CallCoordinator instance is constructed in :rns-host and passed in
    // (this module is NOT on the NoCallCoordinatorGetInstanceOutsideHost
    // allowlist — it must never call getInstance() itself).
    api(project(":vendor:lxst-kt:lxst"))

    // Serialization — flat-dict JSON marshalling for event-bridge payloads.
    implementation(libs.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.coroutines.test)
}

ksp {
    arg("correctErrorTypes", "true")
}
