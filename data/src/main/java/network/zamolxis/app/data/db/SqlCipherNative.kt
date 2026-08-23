package network.zamolxis.app.data.db

/**
 * Loads SQLCipher's native library.
 *
 * `sqlcipher-android` does not load `libsqlcipher.so` from a static initialiser the
 * way its predecessor did — the caller has to ask for it, once per process, before
 * touching anything in `net.zetetic`. Both processes open the database, so both go
 * through here.
 */
internal object SqlCipherNative {
    private val loaded: Unit by lazy { System.loadLibrary("sqlcipher") }

    /** Load the native library if this process hasn't yet. Idempotent. */
    fun ensureLoaded() = loaded
}
