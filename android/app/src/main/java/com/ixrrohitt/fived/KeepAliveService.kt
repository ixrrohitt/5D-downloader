package com.ixrrohitt.fived

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service: keeps downloads alive when the app is in the background or the screen is off. */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, build(this, "Starting…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, build(this, "Starting…", 0))
        if (wl == null) {
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "5d:download").apply { acquire(3 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "dl"
        private const val CH_DONE = "done"

        // notification channels (and Builder(context, channel)) only exist on Android 8+
        @Suppress("DEPRECATION")
        private fun builder(c: Context, ch: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else Notification.Builder(c).setPriority(Notification.PRIORITY_LOW)

        private fun channels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun open(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun build(c: Context, text: String, pct: Int): Notification =
            builder(c, CH).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("5D Downloader").setContentText(text).setOngoing(true)
                .setProgress(100, pct, pct == 0).setContentIntent(open(c)).build()

        fun start(c: Context) {
            val i = Intent(c, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun update(c: Context, text: String, pct: Int) {
            try { c.getSystemService(NotificationManager::class.java).notify(1, build(c, text, pct)) } catch (e: Exception) {}
        }

        fun done(c: Context, text: String) {
            try {
                channels(c)
                val n = builder(c, CH_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("5D Downloader").setContentText(text).setAutoCancel(true).setContentIntent(open(c)).build()
                c.getSystemService(NotificationManager::class.java).notify(2, n)
            } catch (e: Exception) {}
        }

        fun stopIfIdle(c: Context) {
            val running = Engine.jobs.values.any { it.kind == "download" && (it.status == "running" || it.status == "queued") }
            if (!running) c.stopService(Intent(c, KeepAliveService::class.java))
        }
    }
}
