package com.goodwy.messages.feature.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.messages.R
import com.goodwy.messages.common.util.ClipboardItem

class ClipboardItemsAdapter(
    private val context: Context,
    private val onCopy: (ClipboardItem) -> Unit
) : RecyclerView.Adapter<ClipboardItemsAdapter.ViewHolder>() {

    var items: List<ClipboardItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val clipboardText: TextView = view.findViewById(R.id.clipboardText)
        val clipboardExpiry: TextView = view.findViewById(R.id.clipboardExpiry)
        val btnCopyClipboard: TextView = view.findViewById(R.id.btnCopyClipboard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_clipboard, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.clipboardText.text = item.text

        val remainingMillis = item.expiresAt - System.currentTimeMillis()
        val remainingMinutes = (remainingMillis / 60000).coerceAtLeast(0)
        val remainingSeconds = ((remainingMillis % 60000) / 1000).coerceAtLeast(0)

        holder.clipboardExpiry.text = if (remainingMinutes > 0) {
            "⏱️ پاکسازی خودکار تا $remainingMinutes دقیقه دیگر"
        } else {
            "⏱️ پاکسازی خودکار تا $remainingSeconds ثانیه دیگر"
        }

        holder.btnCopyClipboard.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("5Messages", item.text))
            Toast.makeText(context, "در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
            onCopy(item)
        }
    }
}
