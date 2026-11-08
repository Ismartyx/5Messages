package com.goodwy.messages.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

data class SavedMessage(
    val id: Long,
    val text: String,
    val timestamp: Long
)

data class ClipboardItem(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val expiresAt: Long
)

object SavedMessagesManager {
    private const val PREFS_NAME = "saved_messages_and_clipboard"
    private const val KEY_SAVED_MESSAGES = "saved_messages_list"
    private const val KEY_CLIPBOARD_ITEMS = "clipboard_items_list"
    const val AUTO_CLEAR_MILLIS = 5 * 60 * 1000L // 5 minutes

    private val handler = Handler(Looper.getMainLooper())
    private var scheduledClean: Runnable? = null

    init {
        // Schedule periodic check every 30 seconds
        val periodic = object : Runnable {
            override fun run() {
                // Background cleaning will be triggered when context is available
                handler.postDelayed(this, 30_000L)
            }
        }
        handler.postDelayed(periodic, 30_000L)
    }

    // ==========================================
    // SAVED MESSAGES (یادداشت‌ها / پیام‌های ذخیره شده)
    // ==========================================

    @Synchronized
    fun getSavedMessages(context: Context): List<SavedMessage> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_SAVED_MESSAGES, null) ?: return emptyList()
        val list = mutableListOf<SavedMessage>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SavedMessage(
                        id = obj.getLong("id"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun addSavedMessage(context: Context, text: String): SavedMessage? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val current = getSavedMessages(context).toMutableList()
        val item = SavedMessage(
            id = System.currentTimeMillis(),
            text = trimmed,
            timestamp = System.currentTimeMillis()
        )
        current.add(0, item)
        saveSavedMessages(context, current)
        return item
    }

    @Synchronized
    fun deleteSavedMessage(context: Context, id: Long): Boolean {
        val current = getSavedMessages(context).toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            saveSavedMessages(context, current)
        }
        return removed
    }

    @Synchronized
    private fun saveSavedMessages(context: Context, list: List<SavedMessage>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("timestamp", item.timestamp)
            })
        }
        sp.edit().putString(KEY_SAVED_MESSAGES, array.toString()).apply()
    }

    // ==========================================
    // CLIPBOARD (با پاکسازی خودکار ۵ دقیقه‌ای)
    // ==========================================

    @Synchronized
    fun getClipboardItems(context: Context): List<ClipboardItem> {
        cleanExpiredClipboard(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_CLIPBOARD_ITEMS, null) ?: return emptyList()
        val list = mutableListOf<ClipboardItem>()
        val now = System.currentTimeMillis()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val expiresAt = obj.getLong("expiresAt")
                if (expiresAt > now) {
                    list.add(
                        ClipboardItem(
                            id = obj.getLong("id"),
                            text = obj.getString("text"),
                            timestamp = obj.getLong("timestamp"),
                            expiresAt = expiresAt
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun addClipboardItem(context: Context, text: String, copyToSystem: Boolean = true): ClipboardItem? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val expiresAt = now + AUTO_CLEAR_MILLIS
        val item = ClipboardItem(
            id = now,
            text = trimmed,
            timestamp = now,
            expiresAt = expiresAt
        )

        val current = getClipboardItems(context).filter { it.text != trimmed }.toMutableList()
        current.add(0, item)
        saveClipboardItems(context, current)

        if (copyToSystem) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("5Messages", trimmed))
            } catch (_: Exception) {}
        }

        // Schedule wipe at expiry
        scheduleExpiry(context)
        return item
    }

    @Synchronized
    fun clearClipboard(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().remove(KEY_CLIPBOARD_ITEMS).apply()
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun cleanExpiredClipboard(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_CLIPBOARD_ITEMS, null) ?: return
        val now = System.currentTimeMillis()
        val valid = mutableListOf<ClipboardItem>()
        var hadExpired = false
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val expiresAt = obj.getLong("expiresAt")
                if (expiresAt > now) {
                    valid.add(
                        ClipboardItem(
                            id = obj.getLong("id"),
                            text = obj.getString("text"),
                            timestamp = obj.getLong("timestamp"),
                            expiresAt = expiresAt
                        )
                    )
                } else {
                    hadExpired = true
                }
            }
        } catch (_: Exception) {}

        if (hadExpired) {
            saveClipboardItems(context, valid)
            if (valid.isEmpty()) {
                // If all items expired, clear the system clipboard to preserve security
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("", ""))
                } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleExpiry(context: Context) {
        scheduledClean?.let { handler.removeCallbacks(it) }
        scheduledClean = Runnable {
            cleanExpiredClipboard(context.applicationContext)
        }
        handler.postDelayed(scheduledClean!!, AUTO_CLEAR_MILLIS + 1000L)
    }

    @Synchronized
    private fun saveClipboardItems(context: Context, list: List<ClipboardItem>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("timestamp", item.timestamp)
                put("expiresAt", item.expiresAt)
            })
        }
        sp.edit().putString(KEY_CLIPBOARD_ITEMS, array.toString()).apply()
    }
}
