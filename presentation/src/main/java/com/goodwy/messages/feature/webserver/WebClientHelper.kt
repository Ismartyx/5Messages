package com.goodwy.messages.feature.webserver

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

object WebClientHelper {
    private const val PREFS_NAME = "web_client_prefs"
    private const val KEY_PIN = "pin"
    private const val KEY_PORT = "port"
    private const val KEY_ENABLED = "enabled"

    fun getPin(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_PIN, "1234") ?: "1234"
    }

    fun setPin(context: Context, pin: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_PIN, pin.trim()).apply()
    }

    fun getPort(context: Context): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_PORT, 8080)
    }

    fun isEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (WebClientService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    fun startService(context: Context) {
        setEnabled(context, true)
        val intent = Intent(context, WebClientService::class.java).apply {
            action = WebClientService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        setEnabled(context, false)
        val intent = Intent(context, WebClientService::class.java).apply {
            action = WebClientService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun getBestIpAddress(): String {
        val ips = getAllIps()
        // Prioritize Tailscale IP (starts with 100.)
        val tailscale = ips.firstOrNull { it.startsWith("100.") }
        if (tailscale != null) return tailscale
        // Fallback to local Wi-Fi IP
        return ips.firstOrNull { !it.startsWith("127.") } ?: "127.0.0.1"
    }

    fun getAllIps(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses ?: continue
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("100.")) {
                            list.add(0, host) // tailscale first
                        } else {
                            list.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error obtaining IP addresses")
        }
        return list
    }
}
