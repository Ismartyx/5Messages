package com.goodwy.messages.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.goodwy.messages.R
import com.goodwy.messages.interactor.MarkRead
import dagger.android.AndroidInjection
import javax.inject.Inject

class CopyCodeReceiver : BroadcastReceiver() {

    @Inject lateinit var markRead: MarkRead

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)

        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val isOtp = intent.getBooleanExtra(EXTRA_IS_OTP, true)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("code", code)
        clipboard?.setPrimaryClip(clip)

        Handler(Looper.getMainLooper()).post {
            val message = if (isOtp) {
                context.getString(R.string.code_copied_toast, code)
            } else {
                context.getString(R.string.number_copied_toast, code)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        if (threadId > 0) {
            val pendingResult = goAsync()
            markRead.execute(listOf(threadId)) { pendingResult.finish() }
        }
    }

    companion object {
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_IS_OTP = "extra_is_otp"
        const val EXTRA_THREAD_ID = "threadId"
    }
}
