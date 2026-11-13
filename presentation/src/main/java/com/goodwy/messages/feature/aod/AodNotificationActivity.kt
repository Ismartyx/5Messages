package com.goodwy.messages.feature.aod

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.goodwy.messages.R
import com.goodwy.messages.common.util.JalaliDateHelper
import com.goodwy.messages.feature.compose.ComposeActivity
import com.goodwy.messages.util.Preferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AodNotificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_SNIPPET = "extra_snippet"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_IS_PREVIEW = "extra_is_preview"

        fun start(
            context: Context,
            threadId: Long,
            sender: String,
            snippet: String,
            color: Int = -1,
            isPreview: Boolean = false
        ) {
            val intent = Intent(context, AodNotificationActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SNIPPET, snippet)
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_IS_PREVIEW, isPreview)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }

    private var dotAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var gestureDetector: GestureDetector? = null

    private var currentThreadId: Long = -1L
    private var isPreview: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setupWindowFlags()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aod_notification)

        initViews(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { initViews(it) }
    }

    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive sticky fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews(intent: Intent) {
        currentThreadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
        val snippet = intent.getStringExtra(EXTRA_SNIPPET) ?: ""
        val incomingColor = intent.getIntExtra(EXTRA_COLOR, -1)
        isPreview = intent.getBooleanExtra(EXTRA_IS_PREVIEW, false)

        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val aodStyle = sp.getString("aod_style", Preferences.AOD_STYLE_LED) ?: Preferences.AOD_STYLE_LED
        val aodPosition = sp.getString("aod_position", Preferences.AOD_POS_TOP_RIGHT) ?: Preferences.AOD_POS_TOP_RIGHT
        val aodColorPref = sp.getInt("aod_color", -1)
        val timeoutSeconds = if (isPreview) 6 else sp.getInt("aod_timeout", 30)
        val shamsiEnabled = sp.getBoolean("shamsi_calendar", true)

        val resolvedColor = when {
            aodColorPref != -1 -> aodColorPref
            incomingColor != -1 -> incomingColor
            else -> Color.parseColor("#0EA5E9")
        }

        val aodRoot = findViewById<FrameLayout>(R.id.aodRoot)
        val edgeLighting = findViewById<AodEdgeLightingView>(R.id.aodEdgeLighting)
        val cameraRing = findViewById<AodCameraRingView>(R.id.aodCameraRing)
        val ledDot = findViewById<View>(R.id.aodLedDot)
        val minimalContainer = findViewById<LinearLayout>(R.id.aodMinimalContainer)
        val clockView = findViewById<TextView>(R.id.aodClock)
        val dateView = findViewById<TextView>(R.id.aodDate)
        val senderView = findViewById<TextView>(R.id.aodSender)
        val snippetView = findViewById<TextView>(R.id.aodSnippet)
        val iconView = findViewById<ImageView>(R.id.aodIcon)
        val cardView = findViewById<LinearLayout>(R.id.aodMessageCard)
        val hintView = findViewById<TextView>(R.id.aodDismissHint)

        if (isPreview) {
            hintView.text = "پیش‌نمایش افکت (جهت خروج دوبار ضربه بزنید)"
        }

        // Hide all initially
        edgeLighting.visibility = View.GONE
        cameraRing.visibility = View.GONE
        ledDot.visibility = View.GONE
        minimalContainer.visibility = View.GONE

        // Density for dp calculations
        val density = resources.displayMetrics.density

        when (aodStyle) {
            Preferences.AOD_STYLE_EDGE_LIGHTING -> {
                edgeLighting.visibility = View.VISIBLE
                edgeLighting.setColor(resolvedColor)
                edgeLighting.startAnimation()
            }

            Preferences.AOD_STYLE_CAMERA_RING -> {
                cameraRing.visibility = View.VISIBLE
                cameraRing.setColor(resolvedColor)
                val params = cameraRing.layoutParams as FrameLayout.LayoutParams
                when (aodPosition) {
                    Preferences.AOD_POS_TOP_LEFT -> {
                        params.gravity = Gravity.TOP or Gravity.START
                        params.marginStart = (20 * density).toInt()
                        params.marginEnd = 0
                    }
                    Preferences.AOD_POS_TOP_RIGHT -> {
                        params.gravity = Gravity.TOP or Gravity.END
                        params.marginStart = 0
                        params.marginEnd = (20 * density).toInt()
                    }
                    else -> {
                        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        params.marginStart = 0
                        params.marginEnd = 0
                    }
                }
                params.topMargin = (14 * density).toInt()
                cameraRing.layoutParams = params
                cameraRing.startAnimation()
            }

            Preferences.AOD_STYLE_MINIMAL -> {
                minimalContainer.visibility = View.VISIBLE

                // Set time
                val timePattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
                clockView.text = SimpleDateFormat(timePattern, Locale.US).format(Date())

                // Set date
                val now = System.currentTimeMillis()
                if (shamsiEnabled) {
                    val j = JalaliDateHelper.fromMillis(now)
                    val weekday = JalaliDateHelper.getWeekdayName(now)
                    val monthName = JalaliDateHelper.getMonthName(j.month)
                    dateView.text = "$weekday، ${j.day} $monthName ${j.year}"
                } else {
                    dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now))
                }

                senderView.text = if (sender.isNotBlank()) sender else "پیام جدید"
                snippetView.text = if (snippet.isNotBlank()) snippet else "..."
                iconView.imageTintList = ColorStateList.valueOf(resolvedColor)

                cardView.setOnClickListener {
                    openConversation()
                }
            }

            else -> { // Default: LED dot
                ledDot.visibility = View.VISIBLE
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolvedColor)
                    setSize((14 * density).toInt(), (14 * density).toInt())
                }
                ledDot.background = bg

                val params = ledDot.layoutParams as FrameLayout.LayoutParams
                when (aodPosition) {
                    Preferences.AOD_POS_TOP_LEFT -> {
                        params.gravity = Gravity.TOP or Gravity.START
                        params.marginStart = (24 * density).toInt()
                        params.marginEnd = 0
                    }
                    Preferences.AOD_POS_TOP_CENTER -> {
                        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        params.marginStart = 0
                        params.marginEnd = 0
                    }
                    else -> {
                        params.gravity = Gravity.TOP or Gravity.END
                        params.marginStart = 0
                        params.marginEnd = (24 * density).toInt()
                    }
                }
                params.topMargin = (20 * density).toInt()
                ledDot.layoutParams = params

                startDotAnimation(ledDot)
            }
        }

        // Double-tap detector to dismiss
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent?): Boolean {
                finish()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                if (isPreview) {
                    finish()
                    return true
                }
                return super.onSingleTapConfirmed(e)
            }
        })

        aodRoot.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event) ?: false
            true
        }

        // Schedule auto dismiss
        dismissRunnable?.let { handler.removeCallbacks(it) }
        if (timeoutSeconds > 0) {
            val r = Runnable {
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
            dismissRunnable = r
            handler.postDelayed(r, timeoutSeconds * 1000L)
        }
    }

    private fun startDotAnimation(dotView: View) {
        dotAnimator?.cancel()
        dotAnimator = ValueAnimator.ofFloat(0.12f, 1.0f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val alpha = it.animatedValue as Float
                dotView.alpha = alpha
                val scale = 0.85f + 0.3f * alpha
                dotView.scaleX = scale
                dotView.scaleY = scale
            }
            start()
        }
    }

    private fun openConversation() {
        if (!isPreview && currentThreadId > 0) {
            try {
                val openIntent = Intent(this, ComposeActivity::class.java).apply {
                    putExtra("threadId", currentThreadId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(openIntent)
            } catch (_: Exception) {}
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        dotAnimator?.cancel()
        dismissRunnable?.let { handler.removeCallbacks(it) }
    }
}
