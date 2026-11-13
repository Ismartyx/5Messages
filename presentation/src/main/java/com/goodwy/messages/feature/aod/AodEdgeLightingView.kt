package com.goodwy.messages.feature.aod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class AodEdgeLightingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lightColor: Int = Color.parseColor("#0EA5E9")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 26f
    }
    private var pulseAlpha: Float = 1.0f
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
        animator = ValueAnimator.ofFloat(0.25f, 1.0f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseAlpha = it.animatedValue as Float
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
        val stroke = 12f
        val rect = RectF(stroke, stroke, width.toFloat() - stroke, height.toFloat() - stroke)
        val cornerRadius = 36f

        // Outer glow
        glowPaint.color = lightColor
        glowPaint.alpha = (pulseAlpha * 95).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)

        // Inner neon edge
        paint.color = lightColor
        paint.alpha = (pulseAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }
}
