package com.goodwy.messages.feature.aod

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        const val EXTRA_ALWAYS_ON = "extra_always_on"

        fun start(
            context: Context,
            threadId: Long,
            sender: String,
            snippet: String,
            color: Int = -1,
            isPreview: Boolean = false,
            isAlwaysOn: Boolean = false
        ) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "5Messages:AodWakeLock"
                )
                wakeLock?.acquire(4000)
            } catch (_: Exception) {}

            val intent = Intent(context, AodNotificationActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SNIPPET, snippet)
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_IS_PREVIEW, isPreview)
                putExtra(EXTRA_ALWAYS_ON, isAlwaysOn)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        fun startAlwaysOn(context: Context) {
            val hasUnread = hasUnreadSms(context)
            val latest = if (hasUnread) getLatestUnreadSms(context) else null
            val threadId = latest?.first ?: -1L
            val sender = latest?.second ?: ""
            val snippet = latest?.third ?: ""

            start(
                context = context,
                threadId = threadId,
                sender = sender,
                snippet = snippet,
                color = -1,
                isPreview = false,
                isAlwaysOn = true
            )
        }

        fun hasUnreadSms(context: Context): Boolean {
            return try {
                val cursor = context.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("_id"),
                    "read = 0",
                    null,
                    null
                )
                val count = cursor?.count ?: 0
                cursor?.close()
                count > 0
            } catch (_: Exception) {
                false
            }
        }

        fun getLatestUnreadSms(context: Context): Triple<Long, String, String>? {
            return try {
                val cursor = context.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("thread_id", "address", "body"),
                    "read = 0",
                    null,
                    "date DESC LIMIT 1"
                )
                var result: Triple<Long, String, String>? = null
                if (cursor != null && cursor.moveToFirst()) {
                    val threadId = cursor.getLong(0)
                    val address = cursor.getString(1) ?: ""
                    val body = cursor.getString(2) ?: ""
                    result = Triple(threadId, address, body)
                }
                cursor?.close()
                result
            } catch (_: Exception) {
                null
            }
        }
    }

    private var dotAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var gestureDetector: GestureDetector? = null

    private var currentThreadId: Long = -1L
    private var isPreview: Boolean = false
    private var isAlwaysOn: Boolean = false
    private var hasNewMessage: Boolean = false

    // Proximity Sensor for Pocket Mode (Battery Saver)
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListener: SensorEventListener? = null
    private var isPocketed: Boolean = false

    // Broadcast receiver to dismiss AOD when user unlocks phone
    private var unlockReceiver: BroadcastReceiver? = null

    private var cachedColor: Int = -1

    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val style = sp.getString("aod_clock_style", Preferences.AOD_CLOCK_CLASSIC) ?: Preferences.AOD_CLOCK_CLASSIC
            val scale = sp.getInt("aod_clock_scale", 100)
            val shamsi = sp.getBoolean("shamsi_calendar", true)
            val color = if (cachedColor != -1) cachedColor else Color.parseColor("#0EA5E9")
            renderPeekClock(color, style, scale, shamsi)

            val now = System.currentTimeMillis()
            val delay = 60000L - (now % 60000L)
            handler.postDelayed(this, delay.coerceAtLeast(1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupWindowFlags()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aod_notification)

        setupProximitySensor()
        setupUnlockReceiver()
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
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Configurable screen brightness for AOD (defaults to 45% for clear visibility)
        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val aodBrightness = sp.getInt("aod_brightness", 45)
        val targetBrightness = (aodBrightness.coerceIn(5, 100) / 100f)
        val lp = window.attributes
        lp.screenBrightness = targetBrightness
        window.attributes = lp

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

    private fun setupProximitySensor() {
        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val pocketMode = sp.getBoolean("aod_pocket_mode", true)
        if (!pocketMode) return

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (proximitySensor != null) {
            proximityListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val distance = event?.values?.getOrNull(0) ?: return
                    val maxRange = proximitySensor?.maximumRange ?: 5f
                    val near = distance < Math.min(maxRange, 4.5f)
                    if (near != isPocketed) {
                        isPocketed = near
                        onPocketStateChanged(isPocketed)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            try {
                sensorManager?.registerListener(
                    proximityListener,
                    proximitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
            } catch (_: Exception) {}
        }
    }

    private fun onPocketStateChanged(inPocket: Boolean) {
        val root = findViewById<View>(R.id.aodRoot)
        val lp = window.attributes
        if (inPocket) {
            // Turn off display brightness to 0 and hide views (100% battery saving in pocket)
            lp.screenBrightness = 0.0f
            window.attributes = lp
            root?.visibility = View.INVISIBLE
            handler.removeCallbacks(timeUpdateRunnable)
        } else {
            // Restore configured display brightness
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val aodBrightness = sp.getInt("aod_brightness", 45)
            val targetBrightness = (aodBrightness.coerceIn(5, 100) / 100f)
            lp.screenBrightness = targetBrightness
            window.attributes = lp
            root?.visibility = View.VISIBLE

            val alwaysVis = sp.getBoolean("aod_clock_always_visible", true)
            if (alwaysVis) {
                handler.removeCallbacks(timeUpdateRunnable)
                handler.post(timeUpdateRunnable)
            }
        }
    }

    private fun setupUnlockReceiver() {
        if (unlockReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AodService.ACTION_DISMISS_AOD)
        }
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
        }
        try {
            registerReceiver(unlockReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else (24 * resources.displayMetrics.density).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews(intent: Intent) {
        currentThreadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
        val snippet = intent.getStringExtra(EXTRA_SNIPPET) ?: ""
        val incomingColor = intent.getIntExtra(EXTRA_COLOR, -1)
        isPreview = intent.getBooleanExtra(EXTRA_IS_PREVIEW, false)
        isAlwaysOn = intent.getBooleanExtra(EXTRA_ALWAYS_ON, false)

        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val aodStyle = sp.getString("aod_style", Preferences.AOD_STYLE_LED) ?: Preferences.AOD_STYLE_LED
        val aodPosition = sp.getString("aod_position", Preferences.AOD_POS_TOP_RIGHT) ?: Preferences.AOD_POS_TOP_RIGHT
        val aodClockStyle = sp.getString("aod_clock_style", Preferences.AOD_CLOCK_CLASSIC) ?: Preferences.AOD_CLOCK_CLASSIC
        val aodClockScale = sp.getInt("aod_clock_scale", 100)
        val aodClockAlwaysVisible = sp.getBoolean("aod_clock_always_visible", true)
        val aodColorPref = sp.getInt("aod_color", -1)
        val timeoutSeconds = if (isPreview) 6 else sp.getInt("aod_timeout", 30)
        val shamsiEnabled = sp.getBoolean("shamsi_calendar", true)

        val userOffsetY = sp.getInt("aod_offset_y", 0)
        val userOffsetX = sp.getInt("aod_offset_x", 0)
        val userDotSize = sp.getInt("aod_dot_size", 6)
        val userRingRadius = sp.getInt("aod_ring_radius", 0)
        val customX = sp.getInt("aod_custom_x", -1)
        val customY = sp.getInt("aod_custom_y", -1)

        val resolvedColor = when {
            aodColorPref != -1 -> aodColorPref
            incomingColor != -1 -> incomingColor
            else -> Color.parseColor("#0EA5E9")
        }
        cachedColor = resolvedColor

        // Determine if an unread / new message exists
        hasNewMessage = isPreview || sender.isNotBlank() || currentThreadId > 0 || hasUnreadSms(this)

        val aodRoot = findViewById<FrameLayout>(R.id.aodRoot)
        val edgeLighting = findViewById<AodEdgeLightingView>(R.id.aodEdgeLighting)
        val cameraRing = findViewById<AodCameraRingView>(R.id.aodCameraRing)
        val ledDot = findViewById<View>(R.id.aodLedDot)
        val minimalContainer = findViewById<LinearLayout>(R.id.aodMinimalContainer)
        val clockView = findViewById<TextView>(R.id.aodClock)
        val stackedContainer = findViewById<LinearLayout>(R.id.aodClockStackedContainer)
        val hoursView = findViewById<TextView>(R.id.aodClockHours)
        val minutesView = findViewById<TextView>(R.id.aodClockMinutes)
        val analogClock = findViewById<AodAnalogClockView>(R.id.aodAnalogClock)
        val dialContainer = findViewById<LinearLayout>(R.id.aodClockDialContainer)
        val dialText = findViewById<TextView>(R.id.aodClockDialText)
        val batteryBadge = findViewById<TextView>(R.id.aodBatteryBadge)
        val dateView = findViewById<TextView>(R.id.aodDate)
        val senderView = findViewById<TextView>(R.id.aodSender)
        val snippetView = findViewById<TextView>(R.id.aodSnippet)
        val iconView = findViewById<ImageView>(R.id.aodIcon)
        val cardView = findViewById<LinearLayout>(R.id.aodMessageCard)
        val peekContainer = findViewById<LinearLayout>(R.id.aodPeekNamesContainer)
        val peekSenderName = findViewById<TextView>(R.id.aodPeekSenderName)

        val tapRevealNames = sp.getBoolean("aod_tap_reveal_names", true)
        val namesPos = sp.getString("aod_names_position", Preferences.AOD_NAMES_POS_RIGHT) ?: Preferences.AOD_NAMES_POS_RIGHT
        val namesTextSize = sp.getInt("aod_names_text_size", 14)
        val namesOpacity = sp.getInt("aod_names_opacity", 40)
        val namesColorPref = sp.getInt("aod_names_color", 0)

        val resolvedNamesColor = when {
            namesColorPref == 0 -> if (incomingColor != -1) incomingColor else Color.WHITE
            else -> namesColorPref
        }

        peekSenderName?.text = if (sender.isNotBlank()) sender else (if (hasNewMessage) "پیام جدید" else "")
        peekSenderName?.textSize = namesTextSize.toFloat()
        peekSenderName?.setTextColor(resolvedNamesColor)
        val targetAlpha = (namesOpacity.coerceIn(10, 100) / 100f)
        peekSenderName?.alpha = targetAlpha

        val peekLp = peekContainer?.layoutParams as? FrameLayout.LayoutParams
        if (peekLp != null) {
            when (namesPos) {
                Preferences.AOD_NAMES_POS_LEFT -> {
                    peekLp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    peekSenderName?.gravity = Gravity.START
                }
                Preferences.AOD_NAMES_POS_CENTER -> {
                    peekLp.gravity = Gravity.CENTER
                    peekSenderName?.gravity = Gravity.CENTER
                }
                else -> {
                    peekLp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    peekSenderName?.gravity = Gravity.END
                }
            }
            peekContainer.layoutParams = peekLp
        }

        // Hide all indicators initially
        edgeLighting.visibility = View.GONE
        cameraRing.visibility = View.GONE
        ledDot.visibility = View.GONE
        minimalContainer.visibility = View.GONE
        dotAnimator?.cancel()

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val statusBarH = getStatusBarHeight().toFloat()
        val defaultCenterY = if (statusBarH > 0) statusBarH / 2f else (22f * density)
        val defaultRadius = 11f * density

        fun applyCameraPlacement(detectedX: Float, detectedY: Float, detectedR: Float) {
            val isCustomPos = (customX >= 0 && customY >= 0)
            val finalX = if (isCustomPos) customX.toFloat() else {
                val baseX = when (aodPosition) {
                    Preferences.AOD_POS_TOP_LEFT -> if (detectedX < screenWidth / 3f) detectedX else (28f * density)
                    Preferences.AOD_POS_TOP_RIGHT -> if (detectedX > screenWidth * 2f / 3f) detectedX else screenWidth - (28f * density)
                    else -> if (detectedX >= screenWidth / 3f && detectedX <= screenWidth * 2f / 3f) detectedX else (screenWidth / 2f)
                }
                baseX + (userOffsetX * density)
            }
            val finalY = if (isCustomPos) customY.toFloat() else (detectedY + (userOffsetY * density))
            val finalRadius = if (userRingRadius > 0) (userRingRadius * density) else detectedR

            // Only show alert/blinking lights if there is an unread / new message!
            if (!hasNewMessage && aodStyle != Preferences.AOD_STYLE_MINIMAL) {
                // Keep screen completely black for maximum OLED battery saving
                return
            }

            when (aodStyle) {
                Preferences.AOD_STYLE_CAMERA_RING -> {
                    if (hasNewMessage) {
                        cameraRing.visibility = View.VISIBLE
                        cameraRing.setColor(resolvedColor)
                        cameraRing.setTarget(finalX, finalY, finalRadius)
                        cameraRing.startAnimation()
                    }
                }

                Preferences.AOD_STYLE_EDGE_LIGHTING -> {
                    if (hasNewMessage) {
                        edgeLighting.visibility = View.VISIBLE
                        edgeLighting.setColor(resolvedColor)
                        edgeLighting.startAnimation()
                    }
                }

                Preferences.AOD_STYLE_MINIMAL -> {
                    minimalContainer.visibility = View.VISIBLE
                    val scale = (aodClockScale.coerceIn(50, 250) / 100f)

                    val is24 = DateFormat.is24HourFormat(this@AodNotificationActivity)
                    val timePattern = if (is24) "HH:mm" else "h:mm a"
                    val timeString = SimpleDateFormat(timePattern, Locale.US).format(Date())

                    val hoursPattern = if (is24) "HH" else "hh"
                    val hoursString = SimpleDateFormat(hoursPattern, Locale.US).format(Date())
                    val minutesString = SimpleDateFormat("mm", Locale.US).format(Date())

                    clockView.visibility = View.GONE
                    stackedContainer.visibility = View.GONE
                    analogClock.visibility = View.GONE
                    dialContainer.visibility = View.GONE

                    when (aodClockStyle) {
                        Preferences.AOD_CLOCK_STACKED -> {
                            stackedContainer.visibility = View.VISIBLE
                            hoursView.text = hoursString
                            hoursView.textSize = 60f * scale
                            minutesView.text = minutesString
                            minutesView.textSize = 60f * scale
                            minutesView.setTextColor(resolvedColor)
                        }
                        Preferences.AOD_CLOCK_ANALOG -> {
                            analogClock.visibility = View.VISIBLE
                            val analogParams = analogClock.layoutParams
                            if (analogParams != null) {
                                analogParams.width = (150f * density * scale).toInt()
                                analogParams.height = (150f * density * scale).toInt()
                                analogClock.layoutParams = analogParams
                            }
                            analogClock.setAccentColor(resolvedColor)
                        }
                        Preferences.AOD_CLOCK_NEON -> {
                            clockView.visibility = View.VISIBLE
                            clockView.text = timeString
                            clockView.textSize = 56f * scale
                            clockView.setShadowLayer(25f, 0f, 0f, resolvedColor)
                            clockView.setTextColor(Color.WHITE)
                        }
                        Preferences.AOD_CLOCK_DIAL -> {
                            dialContainer.visibility = View.VISIBLE
                            dialText.text = timeString
                            dialText.textSize = 44f * scale
                            val pct = getBatteryPercentage(this@AodNotificationActivity)
                            if (pct >= 0) {
                                batteryBadge.text = "⚡ $pct% باتری"
                                batteryBadge.textSize = (12f * Math.sqrt(scale.toDouble())).toFloat()
                                batteryBadge.visibility = View.VISIBLE
                            } else {
                                batteryBadge.visibility = View.GONE
                            }
                        }
                        else -> { // Classic
                            clockView.visibility = View.VISIBLE
                            clockView.text = timeString
                            clockView.textSize = 56f * scale
                            clockView.setShadowLayer(0f, 0f, 0f, 0)
                            clockView.setTextColor(Color.WHITE)
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (shamsiEnabled) {
                        val j = JalaliDateHelper.fromMillis(now)
                        val weekday = JalaliDateHelper.getWeekdayName(now)
                        val monthName = JalaliDateHelper.getMonthName(j.month)
                        dateView.text = "$weekday، ${j.day} $monthName ${j.year}"
                    } else {
                        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now))
                    }
                    dateView.textSize = (14f * Math.sqrt(scale.toDouble())).toFloat()

                    if (hasNewMessage && sender.isNotBlank()) {
                        cardView.visibility = View.VISIBLE
                        senderView.text = sender
                        snippetView.text = if (snippet.isNotBlank()) snippet else "..."
                        iconView.imageTintList = ColorStateList.valueOf(resolvedColor)
                        cardView.setOnClickListener { openConversation() }
                    } else {
                        cardView.visibility = View.GONE
                    }
                }

                else -> { // LED Dot
                    if (hasNewMessage) {
                        ledDot.visibility = View.VISIBLE
                        val dotSizePx = (userDotSize * density).toInt()

                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(resolvedColor)
                        }
                        ledDot.background = bg

                        val params = ledDot.layoutParams as FrameLayout.LayoutParams
                        params.width = dotSizePx
                        params.height = dotSizePx
                        params.topMargin = (finalY - dotSizePx / 2f).toInt().coerceAtLeast(0)

                        if (isCustomPos) {
                            params.gravity = Gravity.TOP or Gravity.START
                            params.marginStart = (finalX - dotSizePx / 2f).toInt().coerceAtLeast(0)
                            params.marginEnd = 0
                        } else {
                            when (aodPosition) {
                                Preferences.AOD_POS_TOP_LEFT -> {
                                    params.gravity = Gravity.TOP or Gravity.START
                                    params.marginStart = (finalX - dotSizePx / 2f).toInt().coerceAtLeast(10)
                                    params.marginEnd = 0
                                }
                                Preferences.AOD_POS_TOP_RIGHT -> {
                                    params.gravity = Gravity.TOP or Gravity.END
                                    params.marginStart = 0
                                    params.marginEnd = ((screenWidth - finalX) - dotSizePx / 2f).toInt().coerceAtLeast(10)
                                }
                                else -> {
                                    params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                                    params.marginStart = (userOffsetX * density).toInt()
                                    params.marginEnd = 0
                                }
                            }
                        }
                        ledDot.layoutParams = params

                        startDotAnimation(ledDot)
                    }
                }
            }
        }

        // Detect cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.decorView.post {
                val cutout = window.decorView.rootWindowInsets?.displayCutout
                val rect = cutout?.boundingRects?.firstOrNull()
                if (rect != null) {
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    val r = (Math.max(rect.width(), rect.height()) / 2f) + (2f * density)
                    applyCameraPlacement(cx, cy, r)
                } else {
                    applyCameraPlacement(screenWidth / 2f, defaultCenterY, defaultRadius)
                }
            }
        } else {
            applyCameraPlacement(screenWidth / 2f, defaultCenterY, defaultRadius)
        }

        // Tap and Double-tap detector
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent?): Boolean {
                if (!isPreview) {
                    if (hasNewMessage && currentThreadId > 0) {
                        openConversation()
                    } else {
                        finish()
                    }
                } else {
                    finish()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                if (aodClockAlwaysVisible && aodStyle != Preferences.AOD_STYLE_MINIMAL) {
                    renderPeekClock(resolvedColor, aodClockStyle, aodClockScale, shamsiEnabled)
                    if (hasNewMessage && tapRevealNames) {
                        togglePeekNamesOnly()
                    }
                    return true
                }
                togglePeekClockAndNames(resolvedColor, aodClockStyle, aodClockScale, shamsiEnabled, tapRevealNames)
                return true
            }
        })

        aodRoot.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event) ?: false
            true
        }

        // Show continuous clock immediately if enabled
        if (aodClockAlwaysVisible && aodStyle != Preferences.AOD_STYLE_MINIMAL) {
            val peekClock = findViewById<LinearLayout>(R.id.aodPeekClockContainer)
            peekClock?.alpha = 1f
            peekClock?.visibility = View.VISIBLE
            renderPeekClock(resolvedColor, aodClockStyle, aodClockScale, shamsiEnabled)

            handler.removeCallbacks(timeUpdateRunnable)
            val now = System.currentTimeMillis()
            val delay = 60000L - (now % 60000L)
            handler.postDelayed(timeUpdateRunnable, delay.coerceAtLeast(1000L))
        }

        // Schedule auto dismiss only if NOT in Always-On mode
        dismissRunnable?.let { handler.removeCallbacks(it) }
        val alwaysOnPref = sp.getBoolean("aod_always_on", false)
        if (!isPreview && (isAlwaysOn || alwaysOnPref)) {
            // Keep running continuously on lock screen
        } else if (timeoutSeconds > 0) {
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
        dotAnimator = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val alpha = it.animatedValue as Float
                dotView.alpha = alpha
                val scale = 0.95f + 0.1f * alpha
                dotView.scaleX = scale
                dotView.scaleY = scale
            }
            start()
        }
    }

    private val hidePeekRunnable = Runnable {
        val peekContainer = findViewById<LinearLayout>(R.id.aodPeekNamesContainer)
        val peekClock = findViewById<LinearLayout>(R.id.aodPeekClockContainer)
        peekContainer?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            peekContainer.visibility = View.GONE
        }?.start()
        peekClock?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            peekClock.visibility = View.GONE
        }?.start()
    }

    private fun renderPeekClock(
        accentColor: Int,
        clockStyle: String,
        clockScale: Int,
        shamsiEnabled: Boolean
    ) {
        val scale = (clockScale.coerceIn(50, 250) / 100f)
        val density = resources.displayMetrics.density

        val peekTime = findViewById<TextView>(R.id.aodPeekClockTime)
        val peekStacked = findViewById<LinearLayout>(R.id.aodPeekClockStackedContainer)
        val peekHours = findViewById<TextView>(R.id.aodPeekClockHours)
        val peekMinutes = findViewById<TextView>(R.id.aodPeekClockMinutes)
        val peekAnalog = findViewById<AodAnalogClockView>(R.id.aodPeekAnalogClock)
        val peekDial = findViewById<LinearLayout>(R.id.aodPeekClockDialContainer)
        val peekBattery = findViewById<TextView>(R.id.aodPeekBatteryBadge)
        val peekDialText = findViewById<TextView>(R.id.aodPeekClockDialText)
        val peekDate = findViewById<TextView>(R.id.aodPeekClockDate)

        val is24 = DateFormat.is24HourFormat(this)
        val timePattern = if (is24) "HH:mm" else "h:mm a"
        val timeString = SimpleDateFormat(timePattern, Locale.US).format(Date())

        peekTime?.visibility = View.GONE
        peekStacked?.visibility = View.GONE
        peekAnalog?.visibility = View.GONE
        peekDial?.visibility = View.GONE

        when (clockStyle) {
            Preferences.AOD_CLOCK_STACKED -> {
                peekStacked?.visibility = View.VISIBLE
                val hoursPattern = if (is24) "HH" else "hh"
                peekHours?.text = SimpleDateFormat(hoursPattern, Locale.US).format(Date())
                peekHours?.textSize = 48f * scale
                peekMinutes?.text = SimpleDateFormat("mm", Locale.US).format(Date())
                peekMinutes?.textSize = 48f * scale
                peekMinutes?.setTextColor(accentColor)
            }
            Preferences.AOD_CLOCK_ANALOG -> {
                peekAnalog?.visibility = View.VISIBLE
                val analogParams = peekAnalog?.layoutParams
                if (analogParams != null) {
                    analogParams.width = (140f * density * scale).toInt()
                    analogParams.height = (140f * density * scale).toInt()
                    peekAnalog.layoutParams = analogParams
                }
                peekAnalog?.setAccentColor(accentColor)
                peekAnalog?.invalidate()
            }
            Preferences.AOD_CLOCK_NEON -> {
                peekTime?.visibility = View.VISIBLE
                peekTime?.text = timeString
                peekTime?.textSize = 44f * scale
                peekTime?.setShadowLayer(25f, 0f, 0f, accentColor)
                peekTime?.setTextColor(Color.WHITE)
            }
            Preferences.AOD_CLOCK_DIAL -> {
                peekDial?.visibility = View.VISIBLE
                peekDialText?.text = timeString
                peekDialText?.textSize = 36f * scale
                val pct = getBatteryPercentage(this)
                if (pct >= 0) {
                    peekBattery?.text = "⚡ $pct% باتری"
                    peekBattery?.textSize = (11f * Math.sqrt(scale.toDouble())).toFloat()
                    peekBattery?.visibility = View.VISIBLE
                } else {
                    peekBattery?.visibility = View.GONE
                }
            }
            else -> { // Classic
                peekTime?.visibility = View.VISIBLE
                peekTime?.text = timeString
                peekTime?.textSize = 44f * scale
                peekTime?.setShadowLayer(0f, 0f, 0f, 0)
                peekTime?.setTextColor(Color.WHITE)
            }
        }

        // Shamsi or Gregorian Date
        val now = System.currentTimeMillis()
        if (shamsiEnabled) {
            val j = JalaliDateHelper.fromMillis(now)
            val weekday = JalaliDateHelper.getWeekdayName(now)
            val monthName = JalaliDateHelper.getMonthName(j.month)
            peekDate?.text = "$weekday، ${j.day} $monthName ${j.year}"
        } else {
            peekDate?.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now))
        }
        peekDate?.textSize = (13f * Math.sqrt(scale.toDouble())).toFloat()
    }

    private val hidePeekNamesOnlyRunnable = Runnable {
        val peekNames = findViewById<LinearLayout>(R.id.aodPeekNamesContainer)
        peekNames?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            peekNames.visibility = View.GONE
        }?.start()
    }

    private fun togglePeekNamesOnly() {
        val peekNames = findViewById<LinearLayout>(R.id.aodPeekNamesContainer) ?: return
        handler.removeCallbacks(hidePeekNamesOnlyRunnable)
        if (peekNames.visibility == View.VISIBLE && peekNames.alpha > 0.05f) {
            peekNames.animate().alpha(0f).setDuration(250).withEndAction {
                peekNames.visibility = View.GONE
            }.start()
        } else {
            peekNames.alpha = 0f
            peekNames.visibility = View.VISIBLE
            peekNames.animate().alpha(1f).setDuration(300).start()
            handler.postDelayed(hidePeekNamesOnlyRunnable, 6000L)
        }
    }

    private fun togglePeekClockAndNames(
        accentColor: Int,
        clockStyle: String,
        clockScale: Int,
        shamsiEnabled: Boolean,
        tapRevealNames: Boolean
    ) {
        val peekClock = findViewById<LinearLayout>(R.id.aodPeekClockContainer) ?: return
        val peekNames = findViewById<LinearLayout>(R.id.aodPeekNamesContainer)

        handler.removeCallbacks(hidePeekRunnable)
        if (peekClock.visibility == View.VISIBLE && peekClock.alpha > 0.05f) {
            // Dismiss immediately
            peekClock.animate().alpha(0f).setDuration(250).withEndAction {
                peekClock.visibility = View.GONE
            }.start()
            peekNames?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
                peekNames.visibility = View.GONE
            }?.start()
        } else {
            renderPeekClock(accentColor, clockStyle, clockScale, shamsiEnabled)

            peekClock.alpha = 0f
            peekClock.visibility = View.VISIBLE
            peekClock.animate().alpha(1f).setDuration(300).start()

            // Peek sender names if unread message exists
            if (hasNewMessage && tapRevealNames && peekNames != null) {
                peekNames.alpha = 0f
                peekNames.visibility = View.VISIBLE
                peekNames.animate().alpha(1f).setDuration(300).start()
            }

            // Auto fade out back to OLED black after 6 seconds to preserve battery
            handler.postDelayed(hidePeekRunnable, 6000L)
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

    private fun getBatteryPercentage(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (_: Exception) {
            -1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dotAnimator?.cancel()
        dismissRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(hidePeekRunnable)
        handler.removeCallbacks(timeUpdateRunnable)
        handler.removeCallbacks(hidePeekNamesOnlyRunnable)

        proximityListener?.let {
            try {
                sensorManager?.unregisterListener(it)
            } catch (_: Exception) {}
            proximityListener = null
        }

        unlockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            unlockReceiver = null
        }
    }
}
