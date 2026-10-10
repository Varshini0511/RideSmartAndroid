package com.ridesmart.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ridesmart.app.R

/**
 * A tiny foreground service that exists only to keep RideSmart's process alive while it
 * cycles through the ride apps reading prices. Aggressive OEMs (iQOO/Vivo, Xiaomi, Oppo…)
 * freeze or kill background processes; an ongoing foreground notification marks the work as
 * active so the system leaves it running. Started when a scrape begins, stopped when it ends.
 */
class ScrapeKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Price comparison",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Comparing live prices…")
            .setContentText("RideSmart is reading prices from your ride apps")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "ridesmart_scrape"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScrapeKeepAliveService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScrapeKeepAliveService::class.java)) }
        }
    }
}
