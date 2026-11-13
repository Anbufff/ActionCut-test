package com.actioncut.feature.export.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/**
 * Keeps the CPU and Media3 Transformer alive when the user minimizes the app
 * or switches to another app during video export.
 */
class ExportForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(progress))
            }
            else -> {
                ensureChannel()
                acquireWakeLock()
                val notif = buildNotification(0f)
                if (Build.VERSION.SDK_INT >= 35) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActionCut::ExportWakeLock").apply {
            setReferenceCounted(false)
            acquire(30 * 60_000L) // 30-min safety timeout
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while ActionCut exports your video"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Float): Notification {
        val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
        val text = if (pct >= 98) "Saving video to Gallery…" else "Exporting video… $pct%"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ActionCut")
            .setContentText(text)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "actioncut_export"
        private const val NOTIF_ID = 7001
        private const val ACTION_START = "com.actioncut.export.START"
        private const val ACTION_UPDATE = "com.actioncut.export.UPDATE"
        private const val ACTION_STOP = "com.actioncut.export.STOP"
        private const val EXTRA_PROGRESS = "progress"

        fun start(context: Context) {
            val i = Intent(context, ExportForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun update(context: Context, progress: Float) {
            val i = Intent(context, ExportForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_PROGRESS, progress)
            runCatching { context.startService(i) }
        }

        fun stop(context: Context) {
            val i = Intent(context, ExportForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}
