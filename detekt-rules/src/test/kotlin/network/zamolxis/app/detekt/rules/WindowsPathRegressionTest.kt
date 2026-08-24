package network.zamolxis.app.detekt.rules

import io.github.detekt.test.utils.compileContentForTest
import io.gitlab.arturbosch.detekt.api.BaseRule
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression guard for the Windows path-separator bug.
 *
 * `KtFile.virtualFilePath` keeps the OS separator — backslashes on Windows —
 * while every rule's path filter used forward slashes. On a Windows checkout the
 * filters silently matched nothing, so the rules reported zero findings locally,
 * tests were written blind, and the same rules then exploded on Linux CI. Every
 * rule now normalises via [normalizedPath]; these tests pin that behaviour by
 * linting content whose virtual path uses backslashes.
 */
class WindowsPathRegressionTest {
    private fun lintAt(
        rule: BaseRule,
        code: String,
        path: String,
    ): List<Finding> = rule.lint(compileContentForTest(code, path))

    @Test
    fun `NoRelaxedMocks fires on a backslash test path`() {
        val code =
            """
            package com.example

            class ExampleTest {
                val repository = mockk<Repository>(relaxed = true)
            }
            """.trimIndent()

        val findings =
            lintAt(
                NoRelaxedMocksRule(Config.empty),
                code,
                "app\\src\\test\\java\\com\\example\\ExampleTest.kt",
            )
        assertEquals(1, findings.size, "Relaxed mock in a Windows test path must be reported")
    }

    @Test
    fun `NoRelaxedMocks still ignores production paths with backslashes`() {
        val code =
            """
            package com.example

            class Factory {
                val repository = mockk<Repository>(relaxed = true)
            }
            """.trimIndent()

        val findings =
            lintAt(
                NoRelaxedMocksRule(Config.empty),
                code,
                "app\\src\\main\\java\\com\\example\\Factory.kt",
            )
        assertEquals(0, findings.size, "Production files must stay out of scope")
    }

    @Test
    fun `NoVerifyOnlyTests fires on a backslash test path`() {
        val code =
            """
            package com.example

            class ExampleTest {
                @Test
                fun `does the thing`() {
                    subject.run()
                    verify { dependency.call() }
                }
            }
            """.trimIndent()

        val findings =
            lintAt(
                NoVerifyOnlyTestsRule(Config.empty),
                code,
                "app\\src\\test\\java\\com\\example\\ExampleTest.kt",
            )
        assertEquals(1, findings.size, "Verify-only test in a Windows test path must be reported")
    }

    @Test
    fun `BleLoggingTag skips backslash test paths and checks backslash main paths`() {
        val code =
            """
            package network.zamolxis.app.rns.host.ble.client

            class BleScanner {
                companion object {
                    private const val TAG = "wrong-tag"
                }
            }
            """.trimIndent()

        val testFindings =
            lintAt(
                BleLoggingTagRule(Config.empty),
                code,
                "rns-host\\src\\test\\java\\network\\zamolxis\\app\\rns\\host\\ble\\client\\BleScannerTest.kt",
            )
        assertEquals(0, testFindings.size, "Test sources must be skipped even with backslashes")

        val mainFindings =
            lintAt(
                BleLoggingTagRule(Config.empty),
                code,
                "rns-host\\src\\main\\java\\network\\zamolxis\\app\\rns\\host\\ble\\client\\BleScanner.kt",
            )
        assertEquals(1, mainFindings.size, "Main sources must be checked even with backslashes")
    }

    @Test
    fun `CallCoordinator allowlist honours backslash host paths`() {
        val code =
            """
            package com.example

            class HostWiring {
                fun wire() {
                    val coordinator = CallCoordinator.getInstance()
                }
            }
            """.trimIndent()

        val allowedFindings =
            lintAt(
                NoCallCoordinatorGetInstanceOutsideHostRule(Config.empty),
                code,
                "rns-host\\src\\main\\java\\com\\example\\HostWiring.kt",
            )
        assertEquals(0, allowedFindings.size, ":rns-host is an allowed owner even with backslashes")

        val disallowedFindings =
            lintAt(
                NoCallCoordinatorGetInstanceOutsideHostRule(Config.empty),
                code,
                "app\\src\\main\\java\\com\\example\\HostWiring.kt",
            )
        assertEquals(1, disallowedFindings.size, "Direct getInstance() outside the host must be reported")
    }

    @Test
    fun `ReflectivelyKeptRequired fires on a backslash bridge-module path`() {
        val code =
            """
            package network.zamolxis.app.rns.host.bridge

            class KotlinEventBridge {
                fun onEvent(payload: String) = Unit
            }
            """.trimIndent()

        val findings =
            lintAt(
                ReflectivelyKeptRequiredRule(Config.empty),
                code,
                "rns-host\\src\\main\\java\\network\\zamolxis\\app\\rns\\host\\bridge\\KotlinEventBridge.kt",
            )
        assertEquals(1, findings.size, "Unannotated bridge class in a Windows path must be reported")
    }
}
