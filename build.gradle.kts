// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Keep in lockstep with the `hilt` version in gradle/libs.versions.toml — the
    // plugin and the runtime artifacts are released as a set.
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("io.sentry.android.gradle") version "6.1.0" apply false
    // Chaquopy — applied ONLY by :rns-backend-py (module-level, never at :app level)
    // so the kotlinBackend flavor's classpath is never polluted with the Python
    // runtime. Version matches release/v0.10.x's proven config.
    id("com.chaquo.python") version "17.0.0" apply false
    id("app.cash.paparazzi") version "1.3.5" apply false
    id("jacoco")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("de.aaschmid.cpd") version "3.5"
}

// Reproducible archives across all subprojects (including detekt-rules):
// strip per-file timestamps and sort entries by name so ZIP/JAR/APK outputs are
// byte-identical for the same inputs.
allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// Apply JaCoCo, ktlint, and detekt to all subprojects (except detekt-rules)
subprojects {
    // Skip detekt-rules module - it's a pure JVM module for detekt custom rules
    if (name == "detekt-rules") return@subprojects

    // Vendored third-party sources under vendor/ (see vendor/PROVENANCE.md) are held
    // out of the style gates. detekt runs at maxIssues 0 against this project's own
    // conventions, and ktlint's baseline is line-number-addressed — pointing either at
    // ~50k lines somebody else wrote would produce a wall of findings nobody can act on
    // without diverging from upstream, which is the one thing that keeps a future
    // re-sync readable. What that code IS reviewed against is the audit recorded in
    // vendor/PROVENANCE.md. The JDK 21 javac pin at the end of this block still applies
    // to them.
    val isVendored = path == ":vendor" || path.startsWith(":vendor:")

    apply(plugin = "jacoco")
    if (!isVendored) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")
    }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    if (!isVendored) {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            // 1.5.x relaxes `discouraged-comment-location` so end-of-line trailing comments
            // on value arguments no longer trip the rule (see issue #923). 1.0.1 flagged ~550
            // such lines; they were only ever hidden by android.set(true) below, so external
            // linters (Codacy) still surfaced them. Bumping the engine fixes both.
            version.set("1.5.0")
            android.set(true)
            outputColorName.set("RED")
            // This plugin only ever gets to lint `.kts` build scripts — see the comment on
            // `ktlintSourceCheck` below for why. Kept advisory because that is what it has
            // always been; the real gate on Kotlin sources is `ktlintSourceCheck`, which is
            // not advisory.
            ignoreFailures.set(true)
            filter {
                exclude("**/generated/**")
                exclude("**/build/**")
            }
        }

        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(files("${rootProject.projectDir}/detekt-config.yml"))
            // Baseline captures pre-existing issues. New code must pass all checks.
            // Run `./gradlew detektBaseline` to update after intentional changes.
            baseline = file("$projectDir/detekt-baseline.xml")
        }

        // Add custom Zamolxis detekt rules
        dependencies {
            "detektPlugins"(project(":detekt-rules"))
        }
    }

    // Pin the javac runner to JDK 21 for every module. The pythonBackend flavor's
    // Hilt-generated *Java* (e.g. HostBackendModule_ProvidePythonCallManagerFactory)
    // makes javac read LXST-kt's Java 21 bytecode; a JDK <21 javac can't load it
    // ("bad class file"). Only the compiler JDK is pinned — source/target
    // compatibility (17) and the Kotlin toolchain are left untouched, so output
    // bytecode is unchanged. Foojay (settings.gradle.kts) downloads JDK 21 if it
    // isn't installed. CI uses JDK 25, which also satisfies this.
    // Look up the toolchain service lazily inside configureEach: a JavaCompile
    // task only exists once a java/android plugin is applied, which also registers
    // JavaToolchainService — fetching it eagerly here (before the module's plugins
    // apply) would fail configuration.
    tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach {
        javaCompiler.set(
            project.extensions
                .getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
                .compilerFor {
                    languageVersion.set(
                        org.gradle.jvm.toolchain.JavaLanguageVersion
                            .of(21),
                    )
                },
        )
    }
}

// CPD (Copy-Paste Detector) for duplicate code detection
cpd {
    language = "kotlin"
    minimumTokenCount = 100 // ~10-15 lines minimum for duplicate detection
    toolVersion = "7.7.0" // PMD version with Kotlin support
}

tasks.named<de.aaschmid.gradle.plugins.cpd.Cpd>("cpdCheck") {
    // Configure source files for all modules
    source =
        files(
            // These must track settings.gradle.kts. The list previously named
            // "domain/src/main/kotlin" and "reticulum/src/main/java" — one an empty
            // module, the other deleted long ago — so CPD silently scanned two
            // directories that did not exist and never looked at the rns-* modules.
            "app/src/main/java",
            "data/src/main/java",
            "micron/src/main/java",
            "crypto-pq/src/main/kotlin",
            "rns-api/src/main/java",
            "rns-ipc/src/main/java",
            "rns-host/src/main/kotlin",
            "rns-backend-kt/src/main/kotlin",
            "rns-backend-py/src/main/kotlin",
        ).asFileTree.matching {
            include("**/*.kt")
            exclude("**/generated/**")
            exclude("**/build/**")
            // Generated framebuffer data: 512 byte literals, one per line, produced
            // by scripts/convert_icon_to_framebuffer.py. CPD reported 67 "duplications"
            // inside it — rows of identical zeros matching each other — which is noise
            // nobody can act on and which buries the findings that are real.
            exclude("**/rnode/ZamolxisLogo.kt")
        }
    // Advisory mode initially - duplicates are reported but don't fail the build
    ignoreFailures = true
    // Enable text report for easier reading
    reports {
        text.required.set(true)
    }
}

// ktlint over Kotlin sources, run through the CLI rather than the Gradle plugin.
//
// The plugin registers its per-source-set tasks from inside a
// `withPlugin("org.jetbrains.kotlin.android")` callback. Since AGP 9 that plugin is
// not applied — Kotlin support is built into AGP, and applying it explicitly is a
// hard error — so the callback never fires and no Android module ever got a
// `ktlintMainSourceSetCheck` task. `ktlintCheck` was still green because the one
// task it could still find lints `.kts` build scripts. The pure-JVM modules
// (:micron, :crypto-pq) were the only Kotlin the linter ever saw: about 1,700 lines
// out of 185,000.
//
// The CLI does not care how the Kotlin plugin got applied, so this cannot drift back
// into silence the same way. Both read rule configuration from .editorconfig, which
// now pins `ktlint_code_style` — leaving it implicit is how `android.set(true)` above
// came to contradict the style the code is actually written in without anyone
// noticing.
val ktlintCli: Configuration by configurations.creating

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:1.5.0")
}

val ktlintSourceCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs ktlint over every module's Kotlin sources (the Gradle plugin only sees .kts)."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    // Pre-existing violations live in the baseline and do not fail the build; anything
    // new does. Regenerate deliberately by deleting the file and re-running, the same
    // contract as the detekt and hardcoded-string baselines.
    args(
        "**/src/**/*.kt",
        "!**/build/**",
        "!**/generated/**",
        // Vendored third-party sources — see the note in the `subprojects` block.
        "!vendor/**",
        "--baseline=config/ktlint-baseline.xml",
        "--reporter=plain",
    )
}

// Auto-fix counterpart of `ktlintSourceCheck`. Restrict the blast radius with
// -Pktlint.paths="glob1,glob2" (default: every Kotlin source, same as the check).
// The baseline is honored here too, so pre-existing violations are left alone.
val ktlintSourceFormat by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs ktlint --format over Kotlin sources (see ktlintSourceCheck)."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    val paths =
        (findProperty("ktlint.paths") as? String)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("**/src/**/*.kt")
    args(
        paths +
            listOf(
                "!**/build/**",
                "!**/generated/**",
                // --format REWRITES files, so the vendor exclusion matters more here than
                // in the check. Without it one run reformatted 137 vendored files and
                // broke the byte-for-byte match with upstream that vendor/PROVENANCE.md
                // documents and that makes a re-sync diffable at all.
                "!vendor/**",
                "--format",
                "--baseline=config/ktlint-baseline.xml",
                "--reporter=plain",
            ),
    )
}

// Android Lint — progressive enforcement, NO baseline by design.
//
// Round 1 enforces ONLY `NewApi` — the check that catches API-level/minSdk
// mismatches like the readParcelable(ClassLoader, Class) crash (API 33 method on
// minSdk 24). Each subsequent round widens `checkOnly` by one or a few checks,
// fixing that set inline BEFORE enforcing it. Because un-enforced checks aren't
// run, the backlog is faced check-by-check and there is never anything to baseline
// (a baseline would just permanently hide the very issues we're trying to surface).
subprojects {
    if (name == "detekt-rules") return@subprojects
    val lintConfig: com.android.build.api.dsl.Lint.() -> Unit = {
        checkOnly += "NewApi"
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false // enforced via the dedicated `lint` task in CI, not release assembly
    }
    plugins.withType<com.android.build.gradle.AppPlugin> {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> { lint(lintConfig) }
    }
    plugins.withType<com.android.build.gradle.LibraryPlugin> {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> { lint(lintConfig) }
    }
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// Convenience task: `./gradlew installDebug` installs the app (noSentry debug variant)
tasks.register("installDebug") {
    dependsOn(":app:installNoSentryDebug")
    description = "Installs the app noSentry debug APK"
    group = "Install"
}

// Create unified coverage report task
tasks.register("jacocoTestReport", JacocoReport::class) {
    group = "verification"
    description = "Generate unified Jacoco coverage report for all modules"

    // Depend on unit tests from all modules:
    // - app: noSentryDebug variant (sentry/noSentry share the same code)
    // - reticulum, data: debug variant (no product flavors)
    subprojects.forEach { subproject ->
        // Try noSentryDebugUnitTest first (for app module), fall back to debugUnitTest (for other modules)
        val testTask =
            subproject.tasks.findByName("testNoSentryDebugUnitTest")
                ?: subproject.tasks.findByName("testDebugUnitTest")
        if (testTask != null) {
            dependsOn(testTask)
        }
    }

    // Use lazy configuration - fileTree is resolved at execution time, not registration time
    val sourceDirectoriesList = mutableListOf<File>()
    val classDirectoriesList = mutableListOf<File>()
    val execDataPatterns = mutableListOf<String>()

    subprojects.forEach { subproject ->
        // Add source directories (these exist at registration time)
        val sourceDir = subproject.file("src/main/java")
        if (sourceDir.exists()) {
            sourceDirectoriesList.add(sourceDir)
        }
        val kotlinSourceDir = subproject.file("src/main/kotlin")
        if (kotlinSourceDir.exists()) {
            sourceDirectoriesList.add(kotlinSourceDir)
        }

        // Add patterns for class directories and exec data (resolved at execution time)
        val buildDir =
            subproject.layout.buildDirectory
                .get()
                .asFile
        // Use ASM-transformed classes which contain all classes including UI/Compose
        // Try both variant paths - noSentryDebug for app, debug for other modules
        classDirectoriesList.add(File("$buildDir/intermediates/classes/noSentryDebug/transformNoSentryDebugClassesWithAsm/dirs"))
        classDirectoriesList.add(File("$buildDir/intermediates/classes/debug/transformDebugClassesWithAsm/dirs"))
        // Android puts coverage data in outputs/unit_test_code_coverage/
        execDataPatterns.add("$buildDir/outputs/unit_test_code_coverage/noSentryDebugUnitTest")
        execDataPatterns.add("$buildDir/outputs/unit_test_code_coverage/debugUnitTest")
    }

    sourceDirectories.setFrom(sourceDirectoriesList)

    // Use provider for lazy evaluation - resolved at execution time
    classDirectories.setFrom(
        provider {
            classDirectoriesList.filter { it.exists() }.map { dir ->
                fileTree(dir) {
                    exclude(
                        "**/R.class",
                        "**/R\$*.class",
                        "**/BuildConfig.*",
                        "**/Manifest*.*",
                        "**/*Test*.*",
                        "**/Hilt_*.*",
                        "**/*_Factory.*",
                        "**/*_MembersInjector.*",
                    )
                }
            }
        },
    )

    // Use provider for lazy evaluation - resolved at execution time
    executionData.setFrom(
        provider {
            execDataPatterns.mapNotNull { dirPath ->
                val execDir = File(dirPath)
                if (execDir.exists()) {
                    fileTree(execDir) { include("*.exec") }
                } else {
                    null
                }
            }
        },
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
