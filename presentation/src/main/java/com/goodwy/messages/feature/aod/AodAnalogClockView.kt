package com.goodwy.messages.feature.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

class AodAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var accentColor = Color.parseColor("#0EA5E9")

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#333333")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.FILL_AND_STROKE
        color = Color.parseColor("#888888")
    }

    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7f
        color = Color.WHITE
    }

    private val minutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4.5f
        color = Color.parseColor("#0EA5E9")
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0EA5E9")
    }

    fun setAccentColor(color: Int) {
        this.accentColor = color
        minutePaint.color = color
        centerPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) - 10f
        if (radius <= 0) return

        // Outer subtle border
        canvas.drawCircle(cx, cy, radius, dialPaint)

        // 12 hour tick marks
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30).toDouble())
            val isMain = (i % 3 == 0)
            val tickLen = if (isMain) 12f else 6f
            val rOuter = radius - 4f
            val rInner = rOuter - tickLen

            tickPaint.color = if (isMain) Color.WHITE else Color.parseColor("#666666")
            tickPaint.strokeWidth = if (isMain) 3f else 1.5f

            val startX = (cx + rInner * Math.sin(angle)).toFloat()
            val startY = (cy - rInner * Math.cos(angle)).toFloat()
            val stopX = (cx + rOuter * Math.sin(angle)).toFloat()
            val stopY = (cy - rOuter * Math.cos(angle)).toFloat()
            canvas.drawLine(startX, startY, stopX, stopY, tickPaint)
        }

        val cal = Calendar.getInstance()
        val hours = cal.get(Calendar.HOUR)
        val minutes = cal.get(Calendar.MINUTE)

        // Hour hand
        val hourAngle = Math.toRadians(((hours + minutes / 60f) * 30).toDouble())
        val hourLen = radius * 0.52f
        val hX = (cx + hourLen * Math.sin(hourAngle)).toFloat()
        val hY = (cy - hourLen * Math.cos(hourAngle)).toFloat()
        canvas.drawLine(cx, cy, hX, hY, hourPaint)

        // Minute hand
        val minAngle = Math.toRadians((minutes * 6).toDouble())
        val minLen = radius * 0.74f
        val mX = (cx + minLen * Math.sin(minAngle)).toFloat()
        val mY = (cy - minLen * Math.cos(minAngle)).toFloat()
        canvas.drawLine(cx, cy, mX, mY, minutePaint)

        // Center pivot dot
        canvas.drawCircle(cx, cy, 6f, centerPaint)
    }
}
