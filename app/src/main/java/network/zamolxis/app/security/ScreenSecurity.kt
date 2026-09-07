package network.zamolxis.app.security

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * Whether this install lets the screen be captured, and the switch that decides.
 *
 * `FLAG_SECURE` is one flag with three effects, and all three matter here:
 *
 *  - the screenshot key and any screen recorder produce a black frame;
 *  - the window is not mirrored to a cast, an external display, or a remote
 *    assistance session;
 *  - Android stops storing a thumbnail of the window for the Recents switcher.
 *
 * That last one is the reason this is on by default. The app lock covers the UI
 * the moment the app leaves the foreground, but Android takes the Recents
 * snapshot of what was on screen *before* that — so without this flag a locked
 * app still shows a readable picture of the last open conversation to anyone who
 * swipes up, and that picture survives on disk in the system's snapshot cache.
 *
 * ## Why its own preference file, read synchronously
 *
 * The flag has to be on the window before the first frame is drawn, and it has
 * to be readable by [network.zamolxis.app.IncomingCallActivity], which is kept
 * out of Hilt so it can start while the phone is ringing. DataStore — which the
 * rest of the settings use — is a `Flow`: by the time it produced a value the
 * frame the flag exists to protect would already have been composed. A
 * SharedPreferences read is a synchronous map lookup, so it can happen in
 * `onCreate` before `setContent`.
 *
 * The file is separate from the app-lock PINs so that turning the app lock off
 * does not quietly re-enable screen capture.
 */
object ScreenSecurity {
    private const val PREFS_NAME = "zamolxis_screen_security"
    private const val KEY_BLOCKED = "screenshots_blocked"

    /**
     * Blocked unless the user says otherwise.
     *
     * The safe default for a messenger built around a duress PIN is the one
     * where a screenshot of a conversation cannot be taken by an app running
     * beside it, or left behind in a system cache the user never sees. Someone
     * who needs to capture the screen can turn it off and knows why they did.
     */
    private const val DEFAULT_BLOCKED = true

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether screen capture is currently blocked. */
    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, DEFAULT_BLOCKED)

    /** Turn screen capture blocking on or off. Takes effect on the next [apply]. */
    fun setBlocked(
        context: Context,
        blocked: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    /**
     * Put the current setting onto [window].
     *
     * Safe to call repeatedly and at any point in the activity's life: setting a
     * flag that is already set is a no-op, and clearing it takes effect on the
     * next frame. Call it from `onCreate` for the first frame, and again
     * whenever the switch is flipped so the change is visible without a restart.
     */
    fun apply(
        window: Window,
        blocked: Boolean,
    ) {
        if (blocked) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** [apply] the stored setting, for callers that have no reason to read it first. */
    fun apply(
        context: Context,
        window: Window,
    ) = apply(window, isBlocked(context))
}
