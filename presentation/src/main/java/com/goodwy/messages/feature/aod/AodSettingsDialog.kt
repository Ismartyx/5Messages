package com.goodwy.messages.feature.aod

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goodwy.messages.R
import com.goodwy.messages.util.Preferences

object AodSettingsDialog {

    fun getStyleTitle(context: Context, styleKey: String): String {
        return when (styleKey) {
            Preferences.AOD_STYLE_CAMERA_RING -> "حلقه نوری دور دوربین"
            Preferences.AOD_STYLE_EDGE_LIGHTING -> "نورپردازی لبه‌ها (Edge Lighting)"
            Preferences.AOD_STYLE_MINIMAL -> "ساعت و پیام (Always On Display)"
            else -> "نقطه چشمک‌زن LED (کلاسیک)"
        }
    }

    fun show(context: Context, prefs: Preferences, onSaved: (() -> Unit)? = null) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        scrollView.addView(container)

        // 1. Enable Switch / CheckBox
        val enableCheck = CheckBox(context).apply {
            text = "فعال‌سازی چراغ اعلان و Always On Display"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            isChecked = prefs.aodEnabled.get()
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(enableCheck)

        fun createSectionTitle(title: String): TextView {
            return TextView(context).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#0EA5E9"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(6))
            }
        }

        fun createOptionRow(label: String, currentValue: String, onClick: (TextView) -> Unit): LinearLayout {
            val valTv = TextView(context).apply {
                text = currentValue
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121212"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#262626"))
                }
                setOnClickListener { onClick(valTv) }
            }
            val titleTv = TextView(context).apply {
                text = label
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(titleTv)
            row.addView(valTv)
            return row
        }

        // Current state variables
        var currentStyle = prefs.aodStyle.get()
        var currentPos = prefs.aodPosition.get()
        var currentColor = prefs.aodColor.get()
        var currentTimeout = prefs.aodTimeout.get()
        var currentOffsetY = prefs.aodOffsetY.get()
        var currentOffsetX = prefs.aodOffsetX.get()
        var currentDotSize = prefs.aodDotSize.get()
        var currentRingRadius = prefs.aodRingRadius.get()

        // 2. Style Selector
        val styleOptions = arrayOf(
            Pair(Preferences.AOD_STYLE_LED, "🔴 نقطه چشمک‌زن LED (قدیمی)"),
            Pair(Preferences.AOD_STYLE_CAMERA_RING, "⭕ حلقه نوری دور دوربین پانچ‌هول"),
            Pair(Preferences.AOD_STYLE_EDGE_LIGHTING, "📱 نورپردازی لبه‌های صفحه (Edge Lighting)"),
            Pair(Preferences.AOD_STYLE_MINIMAL, "🕒 ساعت و پیام صفحه خاموش (Always On Display)")
        )
        container.addView(createSectionTitle("سبک افکت اعلان"))
        val styleRow = createOptionRow(
            "سبک افکت",
            styleOptions.firstOrNull { it.first == currentStyle }?.second ?: styleOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب سبک افکت اعلان")
                .setItems(styleOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentStyle = styleOptions[which].first
                    valTv.text = styleOptions[which].second
                }
                .show()
        }
        container.addView(styleRow)

        // 3. Position Selector
        val posOptions = arrayOf(
            Pair(Preferences.AOD_POS_TOP_RIGHT, "گوشه بالا راست (محل سنتی چراغ LED)"),
            Pair(Preferences.AOD_POS_TOP_CENTER, "وسط بالا (دور دوربین سلفی)"),
            Pair(Preferences.AOD_POS_TOP_LEFT, "گوشه بالا چپ")
        )
        container.addView(createSectionTitle("موقعیت نور (برای نقطه و حلقه دور دوربین)"))
        val posRow = createOptionRow(
            "موقعیت افکت",
            posOptions.firstOrNull { it.first == currentPos }?.second ?: posOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب موقعیت افکت")
                .setItems(posOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentPos = posOptions[which].first
                    valTv.text = posOptions[which].second
                }
                .show()
        }
        container.addView(posRow)

        // 4. Color Selector
        val colorOptions = arrayOf(
            Pair(-1, "🎨 هماهنگ با فرستنده پیام (پیش‌فرض)"),
            Pair(Color.parseColor("#0EA5E9"), "🔵 آبی نئونی"),
            Pair(Color.parseColor("#10B981"), "🟢 سبز زمردی"),
            Pair(Color.parseColor("#8B5CF6"), "🟣 بنفش"),
            Pair(Color.parseColor("#EF4444"), "🔴 قرمز یاقوتی"),
            Pair(Color.parseColor("#F59E0B"), "🟡 طلایی / کهربایی"),
            Pair(Color.parseColor("#FFFFFF"), "⚪ سفید یخی")
        )
        container.addView(createSectionTitle("رنگ نورپردازی"))
        val colorRow = createOptionRow(
            "رنگ نور",
            colorOptions.firstOrNull { it.first == currentColor }?.second ?: colorOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب رنگ نورپردازی")
                .setItems(colorOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentColor = colorOptions[which].first
                    valTv.text = colorOptions[which].second
                }
                .show()
        }
        container.addView(colorRow)

        // 5. Timeout Selector
        val timeoutOptions = arrayOf(
            Pair(10, "۱۰ ثانیه"),
            Pair(30, "۳۰ ثانیه (پیش‌فرض)"),
            Pair(60, "۱ دقیقه"),
            Pair(180, "۳ دقیقه"),
            Pair(300, "۵ دقیقه"),
            Pair(0, "دائم (تا زمان لمس صفحه)")
        )
        container.addView(createSectionTitle("مدت زمان روشن ماندن صفحه"))
        val timeoutRow = createOptionRow(
            "زمان خاموشی",
            timeoutOptions.firstOrNull { it.first == currentTimeout }?.second ?: timeoutOptions[1].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("مدت زمان روشن ماندن")
                .setItems(timeoutOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentTimeout = timeoutOptions[which].first
                    valTv.text = timeoutOptions[which].second
                }
                .show()
        }
        container.addView(timeoutRow)

        // 6. Fine-tuning Size and Position (Alignment with Camera Hole)
        container.addView(createSectionTitle("تنظیم اندازه و موقعیت دقیق (تراز با دوربین)"))

        val dotSizeOptions = arrayOf(
            Pair(5, "۵dp (ریز و ظریف - مانند چراغ LED گوشی‌های کلاسیک)"),
            Pair(6, "۶dp (پیش‌فرض)"),
            Pair(7, "۷dp (متوسط)"),
            Pair(9, "۹dp (درشت و برجسته)")
        )
        val dotSizeRow = createOptionRow(
            "اندازه نقطه LED",
            dotSizeOptions.firstOrNull { it.first == currentDotSize }?.second ?: "${currentDotSize}dp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه نقطه چشمک‌زن")
                .setItems(dotSizeOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentDotSize = dotSizeOptions[which].first
                    valTv.text = dotSizeOptions[which].second
                }
                .show()
        }
        container.addView(dotSizeRow)

        val ringRadiusOptions = arrayOf(
            Pair(0, "خودکار (تشخیص خودکار پانچ‌هول دوربین)"),
            Pair(9, "۹dp (خیلی ریز - پانچ‌هول بسیار کوچک)"),
            Pair(11, "۱۱dp (استاندارد دور لنز دوربین)"),
            Pair(13, "۱۳dp (متوسط)"),
            Pair(15, "۱۵dp (بزرگ)"),
            Pair(18, "۱۸dp (خیلی بزرگ)")
        )
        val ringRadiusRow = createOptionRow(
            "شعاع حلقه دور دوربین",
            ringRadiusOptions.firstOrNull { it.first == currentRingRadius }?.second ?: (if (currentRingRadius == 0) "خودکار" else "${currentRingRadius}dp")
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه حلقه نوری دور دوربین")
                .setItems(ringRadiusOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentRingRadius = ringRadiusOptions[which].first
                    valTv.text = ringRadiusOptions[which].second
                }
                .show()
        }
        container.addView(ringRadiusRow)

        val offsetYOptions = arrayOf(
            Pair(-20, "۲۰- dp (خیلی بالا)"),
            Pair(-15, "۱۵- dp (بالاتر)"),
            Pair(-10, "۱۰- dp"),
            Pair(-5, "۵- dp"),
            Pair(0, "۰ dp (هم‌تراز پیش‌فرض با دوربین)"),
            Pair(5, "۵+ dp"),
            Pair(10, "۱۰+ dp"),
            Pair(15, "۱۵+ dp (پایین‌تر)"),
            Pair(20, "۲۰+ dp (خیلی پایین)")
        )
        val offsetYRow = createOptionRow(
            "تنظیم ارتفاع عمودی (بالا / پایین)",
            offsetYOptions.firstOrNull { it.first == currentOffsetY }?.second ?: "${currentOffsetY}dp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("تنظیم ارتفاع افکت نور و حلقه")
                .setItems(offsetYOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentOffsetY = offsetYOptions[which].first
                    valTv.text = offsetYOptions[which].second
                }
                .show()
        }
        container.addView(offsetYRow)

        val offsetXOptions = arrayOf(
            Pair(-25, "۲۵- dp (سمت چپ)"),
            Pair(-15, "۱۵- dp (کمی چپ)"),
            Pair(0, "۰ dp (وسط / بدون تغییر)"),
            Pair(15, "۱۵+ dp (کمی راست)"),
            Pair(25, "۲۵+ dp (سمت راست)")
        )
        val offsetXRow = createOptionRow(
            "تنظیم جابجایی افقی (چپ / راست)",
            offsetXOptions.firstOrNull { it.first == currentOffsetX }?.second ?: "${currentOffsetX}dp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("تنظیم جابجایی افقی افکت نور")
                .setItems(offsetXOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentOffsetX = offsetXOptions[which].first
                    valTv.text = offsetXOptions[which].second
                }
                .show()
        }
        container.addView(offsetXRow)

        // 6. Test Preview Button
        val previewBtn = Button(context).apply {
            text = "👁️ تست و پیش‌نمایش افکت (Preview)"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener {
                // Temporarily save to test with selected settings
                prefs.aodEnabled.set(true)
                prefs.aodStyle.set(currentStyle)
                prefs.aodPosition.set(currentPos)
                prefs.aodColor.set(currentColor)
                prefs.aodTimeout.set(currentTimeout)
                prefs.aodOffsetY.set(currentOffsetY)
                prefs.aodOffsetX.set(currentOffsetX)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)

                AodNotificationActivity.start(
                    context = context,
                    threadId = -1L,
                    sender = "علی محمدی",
                    snippet = "سلام! این پیش‌نمایش افکت اعلان در صفحه خاموش است.",
                    color = if (currentColor != -1) currentColor else Color.parseColor("#0EA5E9"),
                    isPreview = true
                )
            }
        }
        container.addView(previewBtn)

        AlertDialog.Builder(context)
            .setTitle("✨ چراغ اعلان و Always On Display")
            .setView(scrollView)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.aodEnabled.set(enableCheck.isChecked)
                prefs.aodStyle.set(currentStyle)
                prefs.aodPosition.set(currentPos)
                prefs.aodColor.set(currentColor)
                prefs.aodTimeout.set(currentTimeout)
                prefs.aodOffsetY.set(currentOffsetY)
                prefs.aodOffsetX.set(currentOffsetX)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)
                onSaved?.invoke()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
