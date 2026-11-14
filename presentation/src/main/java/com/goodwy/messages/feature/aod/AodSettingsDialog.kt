package com.goodwy.messages.feature.aod

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

        // Permission check & guidance for Xiaomi / Samsung / modern Android
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        if (!hasOverlay) {
            val permBtn = Button(context).apply {
                text = "⚠️ فعال‌سازی مجوز نمایش روی صفحه قفل و سایر برنامه‌ها"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#DC2626"))
                    cornerRadius = dp(10).toFloat()
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(42)
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                }
                layoutParams = lp
                setOnClickListener {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            container.addView(permBtn)
        }

        val appSettingsBtn = Button(context).apply {
            text = "⚙️ دسترسی‌های صفحه قفل (مخصوص شیائومی و سامسونگ)"
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#0284C7"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
        container.addView(appSettingsBtn)

        val noteTv = TextView(context).apply {
            text = "💡 نکته: در گوشی‌های شیائومی (MIUI/HyperOS)، دکمه بالا را بزنید و در بخش «دسترسی‌های دیگر (Other permissions)»، حتماً «نمایش در صفحه قفل» و «پنجره‌های بازشو در پس‌زمینه» را فعال کنید تا افکت همیشه نمایش داده شود."
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        container.addView(noteTv)

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
        var currentClockStyle = prefs.aodClockStyle.get()
        var currentTapReveal = prefs.aodTapRevealNames.get()
        var currentNamesPos = prefs.aodNamesPosition.get()
        var currentNamesSize = prefs.aodNamesTextSize.get()
        var currentNamesOpacity = prefs.aodNamesOpacity.get()
        var currentNamesColor = prefs.aodNamesColor.get()

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

        // Interactive Drag & Drop Calibration Button
        val calibrateBtn = Button(context).apply {
            text = "🎯 تعیین موقعیت با لمس و کشیدن (Drag & Drop)"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(4)
            }
            layoutParams = lp
            setOnClickListener {
                prefs.aodEnabled.set(true)
                prefs.aodStyle.set(currentStyle)
                prefs.aodColor.set(currentColor)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)
                prefs.aodClockStyle.set(currentClockStyle)
                AodCalibrationActivity.start(context)
            }
        }
        container.addView(calibrateBtn)

        // Clock Style Selector for Always On Display
        val clockStyleOptions = arrayOf(
            Pair(Preferences.AOD_CLOCK_CLASSIC, "🕒 دیجیتال افقی مینیمال (استاندارد)"),
            Pair(Preferences.AOD_CLOCK_STACKED, "📱 دیجیتال دوطبقه بزرگ (ساعت بالا، دقیقه پایین - طرح سامسونگ/پیکسل)"),
            Pair(Preferences.AOD_CLOCK_ANALOG, "⏱️ ساعت آنالوگ عقربه‌ای کلاسیک"),
            Pair(Preferences.AOD_CLOCK_NEON, "✨ ساعت دیجیتال نئونی درخشان"),
            Pair(Preferences.AOD_CLOCK_DIAL, "🔋 ساعت دایره‌ای مدرن با درصد شارژ باتری")
        )
        container.addView(createSectionTitle("مدل و سبک ساعت Always On Display"))
        val clockRow = createOptionRow(
            "مدل ساعت",
            clockStyleOptions.firstOrNull { it.first == currentClockStyle }?.second ?: clockStyleOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب مدل ساعت صفحه خاموش")
                .setItems(clockStyleOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentClockStyle = clockStyleOptions[which].first
                    valTv.text = clockStyleOptions[which].second
                }
                .show()
        }
        container.addView(clockRow)

        // 5. Tap to Peek Sender Names Settings
        container.addView(createSectionTitle("نمایش نام فرستنده با یک ضربه (حریم خصوصی)"))

        val tapCheck = CheckBox(context).apply {
            text = "نمایش نام فرستندگان با یک ضربه روی صفحه (بدون متن پیام)"
            textSize = 13.5f
            isChecked = currentTapReveal
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentTapReveal = isChecked
            }
        }
        container.addView(tapCheck)

        val namesPosOptions = arrayOf(
            Pair(Preferences.AOD_NAMES_POS_RIGHT, "سمت راست صفحه (پیش‌فرض)"),
            Pair(Preferences.AOD_NAMES_POS_LEFT, "سمت چپ صفحه"),
            Pair(Preferences.AOD_NAMES_POS_CENTER, "وسط صفحه")
        )
        val namesPosRow = createOptionRow(
            "موقعیت نمایش نام",
            namesPosOptions.firstOrNull { it.first == currentNamesPos }?.second ?: namesPosOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب موقعیت نام روی صفحه")
                .setItems(namesPosOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesPos = namesPosOptions[which].first
                    valTv.text = namesPosOptions[which].second
                }
                .show()
        }
        container.addView(namesPosRow)

        val namesSizeOptions = arrayOf(
            Pair(12, "۱۲sp (بسیار ریز و ظریف)"),
            Pair(14, "۱۴sp (استاندارد)"),
            Pair(16, "۱۶sp (متوسط و خوانا)"),
            Pair(18, "۱۸sp (درشت)")
        )
        val namesSizeRow = createOptionRow(
            "اندازه قلم نام",
            namesSizeOptions.firstOrNull { it.first == currentNamesSize }?.second ?: "${currentNamesSize}sp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه قلم نام")
                .setItems(namesSizeOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesSize = namesSizeOptions[which].first
                    valTv.text = namesSizeOptions[which].second
                }
                .show()
        }
        container.addView(namesSizeRow)

        val namesOpacityOptions = arrayOf(
            Pair(25, "۲۵٪ (بسیار محو و کم‌رنگ - محرمانه)"),
            Pair(40, "۴۰٪ (کم‌رنگ ملایم - پیش‌فرض)"),
            Pair(60, "۶۰٪ (نیمه‌روشن)"),
            Pair(80, "۸۰٪ (روشن)"),
            Pair(100, "۱۰۰٪ (کاملاً درخشان و واضح)")
        )
        val namesOpacityRow = createOptionRow(
            "شدت روشنایی و کم‌رنگی",
            namesOpacityOptions.firstOrNull { it.first == currentNamesOpacity }?.second ?: "${currentNamesOpacity}٪"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب شدت شفافیت نام")
                .setItems(namesOpacityOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesOpacity = namesOpacityOptions[which].first
                    valTv.text = namesOpacityOptions[which].second
                }
                .show()
        }
        container.addView(namesOpacityRow)

        val namesColorOptions = arrayOf(
            Pair(-1, "🎨 هماهنگ با فرستنده پیام"),
            Pair(Color.WHITE, "⚪ سفید ملایم (پیش‌فرض)"),
            Pair(Color.parseColor("#0EA5E9"), "🔵 آبی نئونی"),
            Pair(Color.parseColor("#10B981"), "🟢 سبز زمردی"),
            Pair(Color.parseColor("#8B5CF6"), "🟣 بنفش"),
            Pair(Color.parseColor("#F59E0B"), "🟡 کهربایی / طلایی")
        )
        val namesColorRow = createOptionRow(
            "رنگ نام فرستنده",
            namesColorOptions.firstOrNull { it.first == currentNamesColor }?.second ?: namesColorOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب رنگ نام فرستنده")
                .setItems(namesColorOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesColor = namesColorOptions[which].first
                    valTv.text = namesColorOptions[which].second
                }
                .show()
        }
        container.addView(namesColorRow)

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
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)

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
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)
                onSaved?.invoke()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
