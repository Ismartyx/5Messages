package com.goodwy.messages.common.util

import android.content.Context
import com.goodwy.messages.repository.ConversationRepository
import org.json.JSONArray
import org.json.JSONObject

object TrashManager {
    private const val PREFS_NAME = "trash_preferences"
    private const val KEY_TRASHED_THREADS = "trashed_threads_json"

    data class TrashedThread(
        val threadId: Long,
        val trashedAt: Long
    )

    fun moveToTrash(context: Context, threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        val current = getTrashedItems(context).toMutableList()
        val now = System.currentTimeMillis()
        for (id in threadIds) {
            if (current.none { it.threadId == id }) {
                current.add(TrashedThread(id, now))
            }
        }
        saveTrashedItems(context, current)
    }

    fun restoreConversations(context: Context, threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        val current = getTrashedItems(context).toMutableList()
        current.removeAll { threadIds.contains(it.threadId) }
        saveTrashedItems(context, current)
    }

    fun removeTrashed(context: Context, threadIds: List<Long>) {
        restoreConversations(context, threadIds)
    }

    fun isTrashed(context: Context, threadId: Long): Boolean {
        return getTrashedThreadIds(context).contains(threadId)
    }

    fun getTrashedThreadIds(context: Context): Set<Long> {
        return getTrashedItems(context).map { it.threadId }.toSet()
    }

    fun getTrashedItems(context: Context): List<TrashedThread> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_TRASHED_THREADS, null) ?: return emptyList()
        val list = mutableListOf<TrashedThread>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(TrashedThread(obj.getLong("threadId"), obj.getLong("trashedAt")))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveTrashedItems(context: Context, list: List<TrashedThread>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().apply {
                put("threadId", item.threadId)
                put("trashedAt", item.trashedAt)
            })
        }
        sp.edit().putString(KEY_TRASHED_THREADS, arr.toString()).apply()
    }

    fun deletePermanently(context: Context, threadIds: List<Long>, conversationRepo: ConversationRepository) {
        if (threadIds.isEmpty()) return
        conversationRepo.deleteConversations(*threadIds.toLongArray())
        removeTrashed(context, threadIds)
    }

    fun emptyTrash(context: Context, conversationRepo: ConversationRepository) {
        val ids = getTrashedThreadIds(context)
        if (ids.isNotEmpty()) {
            conversationRepo.deleteConversations(*ids.toLongArray())
        }
        saveTrashedItems(context, emptyList())
    }

    fun cleanupExpired(context: Context, conversationRepo: ConversationRepository, retentionDays: Int) {
        if (retentionDays <= 0) return // 0 = never
        val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24L * 3600L * 1000L)
        val items = getTrashedItems(context)
        val expired = items.filter { it.trashedAt < cutoff }.map { it.threadId }
        if (expired.isNotEmpty()) {
            conversationRepo.deleteConversations(*expired.toLongArray())
            removeTrashed(context, expired)
        }
    }
}
