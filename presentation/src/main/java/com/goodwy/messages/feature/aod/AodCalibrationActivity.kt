package com.goodwy.messages.feature.aod

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.goodwy.messages.R
import com.goodwy.messages.util.Preferences

class AodCalibrationActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, AodCalibrationActivity::class.java)
            context.startActivity(intent)
        }
    }

    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var ringRadius: Float = 0f
    private var dotSizePx: Int = 0
    private var isCameraRingStyle: Boolean = false

    private lateinit var cameraRingView: AodCameraRingView
    private lateinit var ledDotView: View
    private lateinit var crosshairView: View
    private lateinit var coordsTextView: TextView
    private lateinit var controlCard: LinearLayout

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive sticky fullscreen
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        setContentView(R.layout.activity_aod_calibration)

        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val aodStyle = sp.getString("aod_style", Preferences.AOD_STYLE_LED) ?: Preferences.AOD_STYLE_LED
        val aodColorPref = sp.getInt("aod_color", -1)
        val resolvedColor = if (aodColorPref != -1) aodColorPref else Color.parseColor("#0EA5E9")
        val userDotSize = sp.getInt("aod_dot_size", 6)
        val userRingRadius = sp.getInt("aod_ring_radius", 0)

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()

        dotSizePx = (userDotSize * density).toInt()
        ringRadius = if (userRingRadius > 0) (userRingRadius * density) else (12f * density)
        isCameraRingStyle = (aodStyle == Preferences.AOD_STYLE_CAMERA_RING)

        val root = findViewById<FrameLayout>(R.id.calibrationRoot)
        cameraRingView = findViewById(R.id.calibrationCameraRing)
        ledDotView = findViewById(R.id.calibrationLedDot)
        crosshairView = findViewById(R.id.calibrationCrosshair)
        coordsTextView = findViewById(R.id.calibrationCoords)
        controlCard = findViewById(R.id.calibrationControlCard)

        // Initial positions
        val savedX = sp.getInt("aod_custom_x", -1)
        val savedY = sp.getInt("aod_custom_y", -1)

        val defaultCenterY = (24f * density)
        currentX = if (savedX >= 0) savedX.toFloat() else (screenWidth / 2f)
        currentY = if (savedY >= 0) savedY.toFloat() else defaultCenterY

        if (isCameraRingStyle) {
            cameraRingView.visibility = View.VISIBLE
            cameraRingView.setColor(resolvedColor)
            ledDotView.visibility = View.GONE
        } else {
            cameraRingView.visibility = View.GONE
            ledDotView.visibility = View.VISIBLE
            ledDotView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resolvedColor)
            }
        }

        updateViews()

        // Touch handling
        root.setOnTouchListener { _, event ->
            val cardRect = IntArray(2)
            controlCard.getLocationOnScreen(cardRect)
            val cardTop = cardRect[1]

            // If user touches above the bottom control card, move the dot/ring
            if (event.rawY < cardTop - 10) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        currentX = event.rawX.coerceIn(0f, screenWidth)
                        currentY = event.rawY.coerceIn(0f, screenHeight)
                        crosshairView.visibility = View.VISIBLE
                        updateViews()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        crosshairView.visibility = View.GONE
                        updateViews()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // D-pad micro-adjustments
        val stepPx = (2 * density)
        findViewById<Button>(R.id.btnMoveLeft).setOnClickListener {
            currentX = (currentX - stepPx).coerceAtLeast(0f)
            updateViews()
        }
        findViewById<Button>(R.id.btnMoveRight).setOnClickListener {
            currentX = (currentX + stepPx).coerceAtMost(screenWidth)
            updateViews()
        }
        findViewById<Button>(R.id.btnMoveUp).setOnClickListener {
            currentY = (currentY - stepPx).coerceAtLeast(0f)
            updateViews()
        }
        findViewById<Button>(R.id.btnMoveDown).setOnClickListener {
            currentY = (currentY + stepPx).coerceAtMost(screenHeight)
            updateViews()
        }

        // Save position button
        findViewById<Button>(R.id.btnSavePosition).setOnClickListener {
            sp.edit()
                .putInt("aod_custom_x", currentX.toInt())
                .putInt("aod_custom_y", currentY.toInt())
                .apply()
            Toast.makeText(this, "✓ موقعیت نور با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Reset to auto button
        findViewById<Button>(R.id.btnResetDefault).setOnClickListener {
            sp.edit()
                .putInt("aod_custom_x", -1)
                .putInt("aod_custom_y", -1)
                .apply()
            Toast.makeText(this, "موقعیت به حالت خودکار دوربین بازنشانی شد", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateViews() {
        val density = resources.displayMetrics.density
        coordsTextView.text = "مختصات: X = ${currentX.toInt()} px | Y = ${currentY.toInt()} px"

        if (isCameraRingStyle) {
            cameraRingView.setTarget(currentX, currentY, ringRadius)
        } else {
            val params = ledDotView.layoutParams as FrameLayout.LayoutParams
            params.width = dotSizePx
            params.height = dotSizePx
            params.gravity = Gravity.TOP or Gravity.START
            params.marginStart = (currentX - dotSizePx / 2f).toInt().coerceAtLeast(0)
            params.topMargin = (currentY - dotSizePx / 2f).toInt().coerceAtLeast(0)
            ledDotView.layoutParams = params
        }

        // Crosshair positioning
        val chSize = (44 * density).toInt()
        val chParams = crosshairView.layoutParams as FrameLayout.LayoutParams
        chParams.width = chSize
        chParams.height = chSize
        chParams.gravity = Gravity.TOP or Gravity.START
        chParams.marginStart = (currentX - chSize / 2f).toInt().coerceAtLeast(0)
        chParams.topMargin = (currentY - chSize / 2f).toInt().coerceAtLeast(0)
        crosshairView.layoutParams = chParams
    }
}
