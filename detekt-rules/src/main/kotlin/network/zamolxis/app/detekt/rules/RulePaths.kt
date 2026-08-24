package network.zamolxis.app.detekt.rules

import org.jetbrains.kotlin.psi.KtFile

/**
 * `KtFile.virtualFilePath` keeps the OS separator — backslashes on Windows —
 * so a filter like `path.contains("/test/")` silently matches nothing there
 * and the rule stays green while checking nothing (the same failure mode that
 * kept BleLoggingTag pinned to a pre-rename package for months).
 *
 * Normalize once here so every path-based filter is separator-agnostic.
 */
internal fun KtFile.normalizedPath(): String = virtualFilePath.replace('\\', '/')
