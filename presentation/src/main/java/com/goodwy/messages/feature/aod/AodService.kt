package com.goodwy.messages.feature.aod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.goodwy.messages.R
import com.goodwy.messages.feature.main.MainActivity

class AodService : Service() {

    companion object {
        const val CHANNEL_ID = "aod_service_channel"
        const val NOTIF_ID = 8844
        const val ACTION_DISMISS_AOD = "com.goodwy.messages.action.DISMISS_AOD"

        fun start(context: Context) {
            try {
                val intent = Intent(context, AodService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AodService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    private var screenReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification())
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                        val alwaysOn = sp.getBoolean("aod_always_on", false)
                        val aodEnabled = sp.getBoolean("aod_enabled", false)
                        if (alwaysOn && aodEnabled) {
                            // Small delay so system screen-off lock transitions finish smoothly
                            handler.postDelayed({
                                AodNotificationActivity.startAlwaysOn(context)
                            }, 250L)
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // User unlocked the phone - dismiss AOD if showing
                        try {
                            val dismissIntent = Intent(ACTION_DISMISS_AOD).apply {
                                setPackage(context.packageName)
                            }
                            context.sendBroadcast(dismissIntent)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        try {
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس صفحه همیشه روشن (AOD)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش ساعت، تاریخ شمسی و اعلان‌ها روی صفحه خاموش"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("صفحه همیشه روشن (AOD)")
            .setContentText("فعال برای نمایش هوشمند ساعت و اعلانات پیام")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            screenReceiver = null
        }
    }
}
