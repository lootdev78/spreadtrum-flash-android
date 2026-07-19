package io.github.lootdev78.spdflash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process in the foreground while the native USB engine owns a duplicated usbfs FD.
 * The operation itself is intentionally not restarted after process death: a USB flash session
 * cannot be resumed safely without re-handshaking and validating the target stage.
 */
class FlashKeepAliveService : Service() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SPRD Flash operations", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status and controlled cancellation for active USB flash operations"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            NativeBridge.cancel()
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification("Preparing controlled cancellation…", 0, false),
            )
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Do not disconnect USB", 0, false),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        NativeBridge.cancel()
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("Android timeout reached · cancelling operation", 0, false),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(label: String, percent: Int, determinate: Boolean) =
        notification(context = this, label = label, percent = percent, determinate = determinate)

    companion object {
        private const val CHANNEL_ID = "spd_flash_operations"
        private const val NOTIFICATION_ID = 1782
        private const val ACTION_CANCEL = "io.github.lootdev78.spdflash.CANCEL"
        private const val UPDATE_INTERVAL_MS = 750L

        @Volatile private var lastUpdateAt = 0L
        @Volatile private var lastPercent = -1
        @Volatile private var lastLabel = ""

        fun start(context: Context) {
            lastUpdateAt = 0L
            lastPercent = -1
            lastLabel = ""
            ContextCompat.startForegroundService(context, Intent(context, FlashKeepAliveService::class.java))
        }

        @Synchronized
        fun update(context: Context, label: String, percent: Int, determinate: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val normalizedPercent = percent.coerceIn(0, 100)
            val normalizedLabel = label.ifBlank { "Do not disconnect USB" }
            val important = normalizedPercent != lastPercent || normalizedLabel != lastLabel
            if (!important && now - lastUpdateAt < UPDATE_INTERVAL_MS) return
            if (important && now - lastUpdateAt < 250L) return

            lastUpdateAt = now
            lastPercent = normalizedPercent
            lastLabel = normalizedLabel
            context.getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(context, normalizedLabel, normalizedPercent, determinate),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashKeepAliveService::class.java))
        }

        private fun notification(context: Context, label: String, percent: Int, determinate: Boolean): android.app.Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, FlashKeepAliveService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle("SPRD Flash is running")
                .setContentText(label.ifBlank { "Do not disconnect USB" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(label.ifBlank { "Do not disconnect USB" }))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openIntent)
                .addAction(R.drawable.ic_stop, "Cancel safely", cancelIntent)
                .setProgress(100, percent.coerceIn(0, 100), !determinate)
                .build()
        }
    }
}
