package com.goodwy.messages.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.goodwy.messages.util.NumberExtractor

object ClipboardUtils {

    fun copy(context: Context, string: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val asciiString = NumberExtractor.toAsciiDigits(string)
        val clip = ClipData.newPlainText("SMS", asciiString)
        clipboard.setPrimaryClip(clip)
    }

}
