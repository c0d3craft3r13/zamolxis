package network.zamolxis.app.util

internal fun isPyxisUpdateFilename(filename: String): Boolean {
    val normalized = filename.trim().lowercase()
    return normalized.endsWith(".pyxis.zip") || normalized.endsWith(".pyxis")
}
