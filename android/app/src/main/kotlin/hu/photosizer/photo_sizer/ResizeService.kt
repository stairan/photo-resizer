package hu.photosizer.photo_sizer

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

/** Keeps the process alive (with a notification) while a long conversion is running. */
class ResizeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, "Képek átméretezése", "Indítás…", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "resize"
        private const val NOTIFICATION_ID = 1
        @Volatile private var appContext: Context? = null

        fun start(ctx: Context) {
            appContext = ctx.applicationContext
            ctx.startForegroundService(Intent(ctx, ResizeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ResizeService::class.java))
        }

        fun update(done: Long, total: Long, text: String) {
            val ctx = appContext ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val max = if (total > Int.MAX_VALUE) 0 else total.toInt()
            nm.notify(NOTIFICATION_ID, build(ctx, "Képek átméretezése", "$done / $total — $text", done.toInt(), max))
        }

        private fun build(ctx: Context, title: String, text: String, progress: Int, max: Int): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Átméretezés", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setProgress(max, progress.coerceAtMost(max), max == 0)
                .build()
        }
    }
}
