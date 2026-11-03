package com.goodwy.messages.feature.main

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class CategoryItem(
    val id: String,
    var name: String,
    val color: Int,
    val isCustom: Boolean = false
)

object CategoryManager {
    private const val PREFS_NAME = "manual_message_categories"
    private const val KEY_CATEGORIES_JSON = "custom_categories_v2"

    val DEFAULT_CATEGORIES = listOf(
        CategoryItem("CONTACTS", "مخاطبین", Color.parseColor("#0EA5E9"), false),
        CategoryItem("UNKNOWN", "شخصی ناشناس", Color.parseColor("#F59E0B"), false),
        CategoryItem("BANK", "بانکی", Color.parseColor("#22C55E"), false),
        CategoryItem("OTP", "رمز و کد", Color.parseColor("#A855F7"), false),
        CategoryItem("OTHER", "متفرقه", Color.parseColor("#EF4444"), false)
    )

    private val PALETTE = listOf(
        Color.parseColor("#06B6D4"), // Cyan
        Color.parseColor("#EC4899"), // Pink
        Color.parseColor("#8B5CF6"), // Purple
        Color.parseColor("#10B981"), // Emerald
        Color.parseColor("#F97316"), // Orange
        Color.parseColor("#6366F1"), // Indigo
        Color.parseColor("#14B8A6"), // Teal
        Color.parseColor("#EAB308"), // Yellow
        Color.parseColor("#3B82F6")  // Blue
    )

    fun getCategories(context: Context): List<CategoryItem> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_CATEGORIES_JSON, null)
        val list = mutableListOf<CategoryItem>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val color = obj.optInt("color", Color.parseColor("#0EA5E9"))
                    val isCustom = obj.optBoolean("isCustom", false)
                    list.add(CategoryItem(id, name, color, isCustom))
                }
            } catch (_: Exception) {}
        }

        // If empty or first run, initialize with defaults
        if (list.isEmpty()) {
            list.addAll(DEFAULT_CATEGORIES)
            saveCategories(context, list)
        } else {
            // Ensure all 5 standard categories exist in the list
            DEFAULT_CATEGORIES.forEach { def ->
                if (list.none { it.id == def.id }) {
                    list.add(def)
                }
            }
        }

        return list
    }

    fun saveCategories(context: Context, list: List<CategoryItem>) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("color", item.color)
                put("isCustom", item.isCustom)
            })
        }
        sp.edit().putString(KEY_CATEGORIES_JSON, array.toString()).apply()
    }

    fun renameCategory(context: Context, id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val list = getCategories(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(name = trimmed)
            saveCategories(context, list)
            return true
        }
        return false
    }

    fun addCategory(context: Context, name: String): CategoryItem? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val list = getCategories(context).toMutableList()
        val id = "CUSTOM_${System.currentTimeMillis()}"
        val color = PALETTE[list.size % PALETTE.size]
        val newItem = CategoryItem(id, trimmed, color, isCustom = true)
        list.add(newItem)
        saveCategories(context, list)
        return newItem
    }

    fun deleteCategory(context: Context, id: String): Boolean {
        val list = getCategories(context).toMutableList()
        val item = list.firstOrNull { it.id == id } ?: return false
        if (!item.isCustom) return false

        list.remove(item)
        saveCategories(context, list)

        // Clear manual assignments associated with this deleted category
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()
        sp.all.forEach { (k, v) ->
            if (v == id) {
                editor.remove(k)
            }
        }
        editor.apply()
        return true
    }

    fun moveCategoryPosition(context: Context, fromIndex: Int, toIndex: Int) {
        val list = getCategories(context).toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
            saveCategories(context, list)
        }
    }
}
