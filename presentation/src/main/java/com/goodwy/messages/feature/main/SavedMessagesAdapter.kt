package com.goodwy.messages.feature.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.messages.R
import com.goodwy.messages.common.util.DateFormatter
import com.goodwy.messages.common.util.SavedMessage

class SavedMessagesAdapter(
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val onDelete: (SavedMessage) -> Unit
) : RecyclerView.Adapter<SavedMessagesAdapter.ViewHolder>() {

    var items: List<SavedMessage> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageDate: TextView = view.findViewById(R.id.messageDate)
        val btnCopy: ImageView = view.findViewById(R.id.btnCopy)
        val btnShare: ImageView = view.findViewById(R.id.btnShare)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_saved_message, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.messageText.text = item.text
        holder.messageDate.text = dateFormatter.getDetailedTimestamp(item.timestamp)

        holder.btnCopy.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Saved Message", item.text))
            Toast.makeText(context, "در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
        }

        holder.btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.text)
            }
            context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
        }

        holder.btnDelete.setOnClickListener {
            onDelete(item)
        }
    }
}
