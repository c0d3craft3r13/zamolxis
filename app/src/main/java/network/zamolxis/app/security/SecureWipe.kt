package network.zamolxis.app.security

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import network.zamolxis.app.rns.host.ReticulumService
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Destroys everything this install knows, leaving a phone that looks like it
 * just had the app installed.
 *
 * Reached from the duress PIN. What it removes:
 *
 *  - both Room databases — messages, contacts, announces, interfaces;
 *  - the Reticulum working directory, which holds the identity file, the path
 *    table and the announce cache;
 *  - every Android Keystore alias the app owns, so the encrypted blobs left
 *    on disk by anything we miss cannot be opened later;
 *  - all shared preferences and DataStore files, including the PINs
 *    themselves;
 *  - caches, staged attachments, downloaded maps and firmware.
 *
 * ## Order matters
 *
 * The Reticulum stack runs in its own process and owns open handles to the
 * identity file and its storage directory. Deleting under a live stack gets
 * the files rewritten from memory moments later, so the service is stopped
 * and its process confirmed dead *before* anything is unlinked.
 *
 * ## What this cannot do
 *
 * Files the user deliberately exported — an attachment saved to Downloads, a
 * QR code shared to another app — are outside this app's sandbox and are not
 * ours to delete. Neither is the app itself: Android has no way for an app to
 * uninstall itself without a visible system prompt, and a prompt is precisely
 * the tell this whole feature exists to avoid.
 */
@Singleton
class SecureWipe
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "SecureWipe"

            /** Room databases, by the names their builders use. */
            private val DATABASE_NAMES = listOf("zamolxis_database", "interface_database")

            /**
             * Keystore aliases the app creates. Listed rather than discovered so
             * a wipe never reaches past this app into another's keys, which
             * `KeyStore.aliases()` on a shared store could otherwise do.
             */
            private val KEYSTORE_ALIASES = listOf("zamolxis_identity_master_key")

            private const val ANDROID_KEYSTORE = "AndroidKeyStore"

            private const val SERVICE_STOP_POLL_MS = 250L
            private const val SERVICE_STOP_MAX_POLLS = 12
        }

        /**
         * Wipe, then report whether every step succeeded.
         *
         * A false result still means most of the data is gone — the steps are
         * independent and one failure does not stop the rest. It is returned so
         * a caller can decide whether to retry, not so it can be shown: telling
         * the user the wipe was partial, on screen, in front of whoever demanded
         * the PIN, would give away that a wipe happened at all.
         */
        suspend fun wipeEverything(): Boolean =
            withContext(Dispatchers.IO) {
                stopReticulumService()

                var allSucceeded = true
                allSucceeded = deleteDatabases() && allSucceeded
                allSucceeded = deleteKeystoreEntries() && allSucceeded
                allSucceeded = deleteFilesDir() && allSucceeded
                allSucceeded = deleteSharedPreferences() && allSucceeded
                allSucceeded = deleteCaches() && allSucceeded
                allSucceeded
            }

        /**
         * Ask the Reticulum process to exit and wait for it to go.
         *
         * ACTION_STOP rather than a plain stopService: the service exits through
         * System.exit(0) so the Python VM tears down through its shutdown hooks,
         * and, more importantly here, so it stops writing before we start
         * deleting.
         */
        private suspend fun stopReticulumService() {
            val processName = "${context.packageName}:reticulum"
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val running =
                    activityManager.runningAppProcesses
                        .orEmpty()
                        .any { it.processName == processName }
                if (!running) return

                val stopIntent =
                    Intent(context, ReticulumService::class.java).apply {
                        action = ReticulumService.ACTION_STOP
                    }
                ContextCompat.startForegroundService(context, stopIntent)

                repeat(SERVICE_STOP_MAX_POLLS) {
                    delay(SERVICE_STOP_POLL_MS)
                    val stillRunning =
                        activityManager.runningAppProcesses
                            .orEmpty()
                            .any { p -> p.processName == processName }
                    if (!stillRunning) return
                }
                Log.w(TAG, "Reticulum process still running; wiping anyway")
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop Reticulum service: ${e.message}")
            }
        }

        private fun deleteDatabases(): Boolean =
            DATABASE_NAMES.fold(true) { ok, name ->
                // deleteDatabase takes the -wal and -shm sidecars with it; those
                // hold recently written rows that outlive the main file.
                runCatching { context.deleteDatabase(name) }

                // Its return value is not usable as a verdict: false means both
                // "could not delete" and "there was nothing there", and this
                // method's whole job is to report the difference. Ask the
                // filesystem instead — what matters is that nothing is left.
                val gone = runCatching { !context.getDatabasePath(name).exists() }.getOrDefault(false)
                if (!gone) Log.w(TAG, "Database $name is still on disk after the wipe")
                gone && ok
            }

        private fun deleteKeystoreEntries(): Boolean =
            runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                KEYSTORE_ALIASES.forEach { alias ->
                    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                }
                true
            }.getOrElse {
                Log.e(TAG, "Keystore wipe failed", it)
                false
            }

        /**
         * Everything under files/, including `reticulum/` (identity, paths,
         * announce cache), `attachments/`, `offline_maps/` and the Chaquopy
         * asset tree the Python backend extracts.
         */
        private fun deleteFilesDir(): Boolean = deleteContents(context.filesDir)

        private fun deleteCaches(): Boolean {
            var ok = deleteContents(context.cacheDir)
            context.externalCacheDir?.let { ok = deleteContents(it) && ok }
            // Attachments the app staged for sharing live here on some devices.
            // The plural form, because the singular returns only the primary
            // volume: on a phone with an SD card the app's directory on that card
            // is a second copy of the same files, and leaving it behind would
            // leave the duress wipe with a hole exactly the size of the removable
            // storage someone can pull out and read elsewhere.
            context.getExternalFilesDirs(null).orEmpty().filterNotNull().forEach {
                ok = deleteContents(it) && ok
            }
            return ok
        }

        /**
         * Shared preferences and DataStore, wiped last among the file steps
         * because this is where the PINs live: if an earlier step throws, the
         * lock is still in place on the next launch rather than the app opening
         * unprotected onto half-deleted data.
         */
        private fun deleteSharedPreferences(): Boolean {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val ok = deleteContents(prefsDir)
            // DataStore keeps its own directory outside shared_prefs.
            val dataStoreDir = File(context.filesDir, "datastore")
            return deleteContents(dataStoreDir) && ok
        }

        private fun deleteContents(dir: File): Boolean {
            if (!dir.exists()) return true
            return runCatching {
                dir.listFiles().orEmpty().all { it.deleteRecursively() }
            }.getOrElse {
                Log.e(TAG, "Failed clearing ${dir.name}", it)
                false
            }
        }
    }
