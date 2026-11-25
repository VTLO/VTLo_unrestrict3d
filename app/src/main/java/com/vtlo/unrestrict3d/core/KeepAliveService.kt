package com.vtlo.unrestrict3d.core

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
import androidx.core.content.ContextCompat

/**
 * Foreground service + partial wake lock held while the model loads / generates / a pipeline runs,
 * so Android does not freeze or reap the process when the screen turns off.
 */
class KeepAliveService : Service() {

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Working…"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "VTLo work", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle("VTLo_unrestrict3d")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, n)
            }
        }.onFailure { EventLog.log("startForeground failed: ${it.message}") }
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vtlo:work")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wl?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object { private const val CH = "vtlo_work" }
}

/** Ref-counted so overlapping jobs (download + load) share one service. */
object KeepAlive {
    private var count = 0

    @Synchronized
    fun acquire(ctx: Context, text: String) {
        count++
        runCatching {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, KeepAliveService::class.java).putExtra("text", text)
            )
        }.onFailure { EventLog.log("keep-alive start failed: ${it.message}") }
    }

    @Synchronized
    fun release(ctx: Context) {
        if (count > 0) count--
        if (count == 0) runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
    }
}
