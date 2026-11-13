package com.goodwy.messages.feature.aod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class AodCameraRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lightColor: Int = Color.parseColor("#0EA5E9")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
    }
    private var pulseAlpha: Float = 1.0f
    private var rotationAngle: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        startAnimation()
    }

    fun setColor(color: Int) {
        this.lightColor = color
        invalidate()
    }

    fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                val fraction = it.animatedFraction
                pulseAlpha = 0.45f + 0.55f * kotlin.math.abs(kotlin.math.sin(fraction * Math.PI.toFloat()))
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) - 15f
        if (radius <= 0) return

        // Outer glow
        glowPaint.color = lightColor
        glowPaint.alpha = (pulseAlpha * 85).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, glowPaint)

        // Main glowing ring with sweep gradient
        val colors = intArrayOf(
            lightColor,
            Color.TRANSPARENT,
            lightColor,
            Color.WHITE,
            lightColor
        )
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        paint.shader = SweepGradient(cx, cy, colors, null)
        paint.alpha = (pulseAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.restore()
    }
}
