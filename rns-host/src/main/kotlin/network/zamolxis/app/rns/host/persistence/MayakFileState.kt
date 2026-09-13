package network.zamolxis.app.rns.host.persistence

/**
 * What the Mayak device file is, as the `:reticulum` service last reported it.
 *
 * Stored by name in the cross-process preferences, because the file is opened only in
 * the service and the setting that changes it lives in the UI process.
 */
enum class MayakFileState {
    /** Mayak is not running: the Kotlin backend, before the service started it, or it failed to. */
    NOT_RUNNING,

    /** Sealed under its passphrase alone. A copy opens anywhere the passphrase is known. */
    PORTABLE,

    /** Sealed under its passphrase and a key in this phone's hardware keystore. */
    BOUND,

    /** Being rewritten from one to the other. */
    CONVERTING,
}
