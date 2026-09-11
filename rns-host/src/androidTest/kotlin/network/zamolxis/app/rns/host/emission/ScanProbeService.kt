package network.zamolxis.app.rns.host.emission

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A foreground service that exists only to hold this process in the foreground
 * while a measurement runs.
 *
 * The app ships its Reticulum stack in a foreground service, and the question
 * this answers is whether that changes anything for a Bluetooth scan on a
 * sleeping device. An ordinary app's scan is suspended there and returns a zero
 * indistinguishable from an empty room; if a foreground service is exempt, the
 * cover rule can measure in the state a phone actually spends its life in, and
 * if it is not, that is worth knowing rather than guessing.
 *
 * It does nothing but exist. The scanning is done by the test, in this same
 * process, so the only variable is the process's importance to the system.
 */
class ScanProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification =
            Notification
                .Builder(this, CHANNEL)
                .setContentTitle("Measuring scan behaviour")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "scan-probe"
        const val ID = 4711
    }
}
