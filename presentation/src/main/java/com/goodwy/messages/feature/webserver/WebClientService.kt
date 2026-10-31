package com.goodwy.messages.feature.webserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.goodwy.messages.R
import com.goodwy.messages.feature.main.MainActivity
import com.goodwy.messages.repository.ConversationRepository
import com.goodwy.messages.repository.MessageRepository
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

class WebClientService : Service() {

    companion object {
        const val ACTION_START = "com.goodwy.messages.webclient.START"
        const val ACTION_STOP = "com.goodwy.messages.webclient.STOP"
        private const val NOTIFICATION_ID = 8881
        private const val CHANNEL_ID = "web_client_service_channel"
    }

    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var messageRepo: MessageRepository

    private var server: WebClientServer? = null

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return

        val port = WebClientHelper.getPort(this)
        val pin = WebClientHelper.getPin(this)

        server = WebClientServer(
            context = this,
            port = port,
            conversationRepo = conversationRepo,
            messageRepo = messageRepo,
            getPin = { WebClientHelper.getPin(this) }
        ).apply {
            start()
        }

        createNotificationChannel()

        val ip = WebClientHelper.getBestIpAddress()
        val url = "http://$ip:$port"

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, WebClientService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laptop_black_24dp)
            .setContentTitle("Web client is running")
            .setContentText("URL: $url")
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stop_black_24dp, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Timber.i("WebClientService running on $url")
    }

    private fun stopServer() {
        server?.stop()
        server = null
        WebClientHelper.setEnabled(this, false)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "وب‌کلاینت کامپیوتر",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش اتصال فعال مرورگر و وب‌سرور پیامک"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
