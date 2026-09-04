package network.zamolxis.app.rns.backend.py

import java.io.File

/**
 * Opt-in file sink for RNS logging, for diagnosing on-device problems (notably
 * BLE-only reachability) on OEMs that mute third-party logcat — Motorola drops
 * non-system tags, so the RNS path-resolution / announce trace never reaches
 * `adb logcat`.
 *
 * Off unless a marker file exists in the RNS config directory, so production
 * builds pay nothing. To capture on a device:
 *
 *   adb -s <serial> shell run-as <app> sh -c 'echo 6 > files/reticulum/reticulum/debug_logging.enable'
 *   # restart the :reticulum service, reproduce, then pull:
 *   adb -s <serial> exec-out run-as <app> cat files/reticulum/reticulum/rns_debug.log
 *
 * The marker's contents, if a bare integer 0..7, set the RNS loglevel; anything
 * else (including empty) falls back to [DEFAULT_LEVEL] (LOG_DEBUG).
 */
object RnsDebugLog {
    /** Presence of this file in the config dir turns file logging on. */
    const val MARKER_FILE = "debug_logging.enable"

    /** RNS writes its log here when enabled; pull it with `run-as`. */
    const val LOG_FILE = "rns_debug.log"

    /** LOG_DEBUG — path/announce tracing without the LOG_EXTREME firehose. */
    const val DEFAULT_LEVEL = 6

    private const val MIN_LEVEL = 0
    private const val MAX_LEVEL = 7

    fun isEnabled(configDir: File): Boolean = File(configDir, MARKER_FILE).exists()

    fun logFile(configDir: File): File = File(configDir, LOG_FILE)

    /**
     * The RNS loglevel to use, read from the marker file's text. A bare integer
     * in [MIN_LEVEL]..[MAX_LEVEL] wins; anything else yields [DEFAULT_LEVEL], so
     * a plain `touch` of the marker still enables a sensible level.
     */
    fun level(configDir: File): Int {
        val raw =
            runCatching { File(configDir, MARKER_FILE).readText().trim() }.getOrNull()
        val parsed = raw?.toIntOrNull() ?: return DEFAULT_LEVEL
        return if (parsed in MIN_LEVEL..MAX_LEVEL) parsed else DEFAULT_LEVEL
    }
}
