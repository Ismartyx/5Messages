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
            Preferences.AOD_STYLE_CAMERA_RING -> "رینگ نور دور دوربین"
            Preferences.AOD_STYLE_EDGE_LIGHTING -> "نورپردازی لبه‌ها (Edge Lighting)"
            Preferences.AOD_STYLE_MINIMAL -> "ساعت و کارت پیام (Always On Display)"
            else -> "نقطه چشمک‌زن LED (پیش‌فرض)"
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
            text = "فعال‌سازی سیستم اعلان و Always On Display"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            isChecked = prefs.aodEnabled.get()
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(enableCheck)

        // Current state variables
        var currentAlwaysOn = prefs.aodAlwaysOn.get()
        var currentPocketMode = prefs.aodPocketMode.get()
        var currentBrightness = prefs.aodBrightness.get()
        var currentClockAlwaysVisible = prefs.aodClockAlwaysVisible.get()
        var currentStyle = prefs.aodStyle.get()
        var currentPos = prefs.aodPosition.get()
        var currentColor = prefs.aodColor.get()
        var currentTimeout = prefs.aodTimeout.get()
        var currentOffsetY = prefs.aodOffsetY.get()
        var currentOffsetX = prefs.aodOffsetX.get()
        var currentDotSize = prefs.aodDotSize.get()
        var currentRingRadius = prefs.aodRingRadius.get()
        var currentClockStyle = prefs.aodClockStyle.get()
        var currentClockScale = prefs.aodClockScale.get()
        var currentTapReveal = prefs.aodTapRevealNames.get()
        var currentTapRevealClock = prefs.aodTapRevealClock.get()
        var currentNamesPos = prefs.aodNamesPosition.get()
        var currentNamesSize = prefs.aodNamesTextSize.get()
        var currentNamesOpacity = prefs.aodNamesOpacity.get()
        var currentNamesColor = prefs.aodNamesColor.get()

        // 1.1 Always-On Display Mode (Screen Off / Lock Screen Persistent Mode)
        val alwaysOnCheck = CheckBox(context).apply {
            text = "🌙 صفحه همیشه روشن دائمی (Always On Display)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            isChecked = currentAlwaysOn
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentAlwaysOn = isChecked
            }
        }
        container.addView(alwaysOnCheck)

        val alwaysOnDesc = TextView(context).apply {
            text = "در صورت وجود پیام خوانده‌نشده، با خاموش یا قفل شدن صفحه چراغ اعلان روشن می‌شود. اگر پیامی نباشد، برای جلوگیری از مصرف بیهوده باتری صفحه کاملاً خاموش می‌ماند."
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        container.addView(alwaysOnDesc)

        // 1.2 Continuous Clock Mode (Always visible vs Tap-to-wake)
        val clockAlwaysVisibleCheck = CheckBox(context).apply {
            text = "⏰ روشن ماندن یکسره ساعت و تاریخ روی صفحه (Always On)"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            isChecked = currentClockAlwaysVisible
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentClockAlwaysVisible = isChecked
            }
        }
        container.addView(clockAlwaysVisibleCheck)

        val clockAlwaysVisibleDesc = TextView(context).apply {
            text = "ساعت و تاریخ شمسی به صورت یکسره و مداوم روی صفحه قفل روشن می‌مانند. در صورت خاموش بودن، ساعت فقط با یک ضربه روی صفحه پدیدار می‌شود."
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        container.addView(clockAlwaysVisibleDesc)

        // 1.2 Pocket Mode (Battery Saver)
        val pocketCheck = CheckBox(context).apply {
            text = "🛡️ حالت محافظ جیب (کاهش ۱۰۰٪ مصرف باتری با حسگر مجاورت)"
            textSize = 13.5f
            isChecked = currentPocketMode
            setPadding(dp(8), dp(2), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentPocketMode = isChecked
            }
        }
        container.addView(pocketCheck)

        val pocketDesc = TextView(context).apply {
            text = "وقتی گوشی داخل جیب، کیف یا روی میز به پشت باشد، صفحه برای صرفه‌جویی کامل در باتری به طور خودکار خاموش می‌شود."
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        container.addView(pocketDesc)

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
            text = "⚙️ تنظیمات مجوزهای برنامه (مجوز شناور و قفل صفحه)"
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
            text = "💡 راهنما: در گوشی‌های شیائومی (MIUI/HyperOS)، وارد مسیر بالا و بخش «مجوزهای دیگر (Other permissions)» شده و گزینه‌های «نمایش در صفحه قفل» و «پنجره‌های شناور» را تیک سبز بگذارید."
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

        // 2. Style Selector
        val styleOptions = arrayOf(
            Pair(Preferences.AOD_STYLE_LED, "💡 نقطه چشمک‌زن LED (پیش‌فرض)"),
            Pair(Preferences.AOD_STYLE_CAMERA_RING, "⭕ حلقه نورانی دور حفره دوربین سلفی"),
            Pair(Preferences.AOD_STYLE_EDGE_LIGHTING, "✨ نورپردازی متحرک لبه‌ها (Edge Lighting)"),
            Pair(Preferences.AOD_STYLE_MINIMAL, "📱 ساعت و خلاصه پیام همیشگی (Always On Display)")
        )
        container.addView(createSectionTitle("نوع نمایش اعلان"))
        val styleRow = createOptionRow(
            "سبک نمایش",
            styleOptions.firstOrNull { it.first == currentStyle }?.second ?: styleOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب نوع نمایش اعلان")
                .setItems(styleOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentStyle = styleOptions[which].first
                    valTv.text = styleOptions[which].second
                }
                .show()
        }
        container.addView(styleRow)

        // 3. Position Selector
        val posOptions = arrayOf(
            Pair(Preferences.AOD_POS_TOP_RIGHT, "گوشه بالا راست (محل چراغ اعلان LED)"),
            Pair(Preferences.AOD_POS_TOP_CENTER, "وسط بالا (محل دوربین سلفی)"),
            Pair(Preferences.AOD_POS_TOP_LEFT, "گوشه بالا چپ")
        )
        container.addView(createSectionTitle("موقعیت نور (نقطه LED و رینگ دور دوربین)"))
        val posRow = createOptionRow(
            "موقعیت مکانی",
            posOptions.firstOrNull { it.first == currentPos }?.second ?: posOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب موقعیت نور")
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
            Pair(Color.parseColor("#0EA5E9"), "آبی فیروزه‌ای"),
            Pair(Color.parseColor("#10B981"), "سبز نئونی"),
            Pair(Color.parseColor("#8B5CF6"), "بنفش"),
            Pair(Color.parseColor("#EF4444"), "قرمز اسپرت"),
            Pair(Color.parseColor("#F59E0B"), "طلایی / نارنجی"),
            Pair(Color.parseColor("#FFFFFF"), "سفید براق")
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

        // 5. Timeout Selector (only for notification-only mode)
        val timeoutOptions = arrayOf(
            Pair(10, "۱۰ ثانیه"),
            Pair(30, "۳۰ ثانیه (پیش‌فرض)"),
            Pair(60, "۱ دقیقه"),
            Pair(180, "۳ دقیقه"),
            Pair(300, "۵ دقیقه"),
            Pair(0, "همیشگی (تا زمان لمس کاربر)")
        )
        container.addView(createSectionTitle("مدت زمان روشن ماندن اعلان"))
        val timeoutRow = createOptionRow(
            "مدت زمان",
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

        // 6. Clock Style Selector (Applies to both Always-On Display and Tap Reveal!)
        val clockStyleOptions = arrayOf(
            Pair(Preferences.AOD_CLOCK_CLASSIC, "🕒 دیجیتال خطی کلاسیک (پیش‌فرض)"),
            Pair(Preferences.AOD_CLOCK_STACKED, "📱 دیجیتال عمودی بزرگ (ساعت بالا دقیقه پایین - سامسونگ/پیکسل)"),
            Pair(Preferences.AOD_CLOCK_ANALOG, "⏱️ ساعت عقربه‌ای مینیمال شیک"),
            Pair(Preferences.AOD_CLOCK_NEON, "💡 ساعت دیجیتال نئونی درخشان"),
            Pair(Preferences.AOD_CLOCK_DIAL, "⭕ ساعت دایره‌ای متقارن با درصد باتری")
        )
        container.addView(createSectionTitle("شکل و سبک ساعت (AOD و ضربه روی صفحه)"))
        val clockRow = createOptionRow(
            "سبک ساعت",
            clockStyleOptions.firstOrNull { it.first == currentClockStyle }?.second ?: clockStyleOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب شکل ساعت")
                .setItems(clockStyleOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentClockStyle = clockStyleOptions[which].first
                    valTv.text = clockStyleOptions[which].second
                }
                .show()
        }
        container.addView(clockRow)

        // 6.1 Clock Size / Dimensions Selector
        val clockScaleOptions = arrayOf(
            Pair(60, "۶۰٪ (بسیار ریز و مینیاتوری)"),
            Pair(75, "۷۵٪ (کوچک و ظریف)"),
            Pair(85, "۸۵٪ (کمی کوچک‌تر از معمول)"),
            Pair(100, "۱۰۰٪ (اندازه استاندارد - پیش‌فرض)"),
            Pair(115, "۱۱۵٪ (کمی درشت‌تر و خواناتر)"),
            Pair(130, "۱۳۰٪ (بزرگ و نمایان)"),
            Pair(150, "۱۵۰٪ (خیلی بزرگ)"),
            Pair(175, "۱۷۵٪ (فوق‌العاده درشت)")
        )
        val clockScaleRow = createOptionRow(
            "اندازه و ابعاد ساعت",
            clockScaleOptions.firstOrNull { it.first == currentClockScale }?.second ?: "${currentClockScale}٪"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه و ابعاد ساعت")
                .setItems(clockScaleOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentClockScale = clockScaleOptions[which].first
                    valTv.text = clockScaleOptions[which].second
                }
                .show()
        }
        container.addView(clockScaleRow)

        // 6.2 Brightness Selector
        val brightnessOptions = arrayOf(
            Pair(15, "۱۵٪ (خیلی کم‌نور / مخصوص اتاق کاملاً تاریک)"),
            Pair(30, "۳۰٪ (ملایم)"),
            Pair(45, "۴۵٪ (متوسط و خوانا - پیش‌فرض)"),
            Pair(60, "۶۰٪ (روشن و واضح)"),
            Pair(80, "۸۰٪ (بسیار روشن)"),
            Pair(100, "۱۰۰٪ (حداکثر روشنایی صفحه)")
        )
        val brightnessRow = createOptionRow(
            "میزان روشنایی صفحه و ساعت",
            brightnessOptions.firstOrNull { it.first == currentBrightness }?.second ?: "${currentBrightness}٪"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("تنظیم میزان روشنایی صفحه و ساعت")
                .setItems(brightnessOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentBrightness = brightnessOptions[which].first
                    valTv.text = brightnessOptions[which].second
                }
                .show()
        }
        container.addView(brightnessRow)

        // 7. Fine-tuning Size and Position
        container.addView(createSectionTitle("تنظیم اندازه و موقعیت دقیق (هماهنگی با دوربین)"))

        val dotSizeOptions = arrayOf(
            Pair(5, "۵dp (ریز و ظریف - مشابه چراغ LED گوشی‌های سونی/سامسونگ)"),
            Pair(6, "۶dp (پیش‌فرض)"),
            Pair(7, "۷dp (متوسط)"),
            Pair(9, "۹dp (بزرگ و نمایان)")
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
            Pair(0, "خودکار (تشخیص خودکار بریدگی دوربین گوشی)"),
            Pair(9, "۹dp (بسیار ریز - دوربین‌های سلفی مدرن)"),
            Pair(11, "۱۱dp (استاندارد اکثر گوشی‌ها)"),
            Pair(13, "۱۳dp (متوسط)"),
            Pair(15, "۱۵dp (بزرگ)"),
            Pair(18, "۱۸dp (بسیار بزرگ)")
        )
        val ringRadiusRow = createOptionRow(
            "شعاع دایره دور دوربین",
            ringRadiusOptions.firstOrNull { it.first == currentRingRadius }?.second ?: (if (currentRingRadius == 0) "خودکار" else "${currentRingRadius}dp")
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه شعاع دایره دور دوربین")
                .setItems(ringRadiusOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentRingRadius = ringRadiusOptions[which].first
                    valTv.text = ringRadiusOptions[which].second
                }
                .show()
        }
        container.addView(ringRadiusRow)

        val offsetYOptions = arrayOf(
            Pair(-20, "۲۰- dp (خیلی بالاتر)"),
            Pair(-15, "۱۵- dp (بالاتر)"),
            Pair(-10, "۱۰- dp"),
            Pair(-5, "۵- dp"),
            Pair(0, "۰ dp (پیش‌فرض خودکار در راستای دوربین)"),
            Pair(5, "۵+ dp"),
            Pair(10, "۱۰+ dp"),
            Pair(15, "۱۵+ dp (پایین‌تر)"),
            Pair(20, "۲۰+ dp (خیلی پایین‌تر)")
        )
        val offsetYRow = createOptionRow(
            "تنظیم موقعیت عمودی (بالا / پایین)",
            offsetYOptions.firstOrNull { it.first == currentOffsetY }?.second ?: "${currentOffsetY}dp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("تنظیم موقعیت عمودی نسبت به دوربین")
                .setItems(offsetYOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentOffsetY = offsetYOptions[which].first
                    valTv.text = offsetYOptions[which].second
                }
                .show()
        }
        container.addView(offsetYRow)

        val offsetXOptions = arrayOf(
            Pair(-25, "۲۵- dp (سمت چپ)"),
            Pair(-15, "۱۵- dp (سمت چپ)"),
            Pair(0, "۰ dp (وسط / موقعیت پیش‌فرض)"),
            Pair(15, "۱۵+ dp (سمت راست)"),
            Pair(25, "۲۵+ dp (سمت راست)")
        )
        val offsetXRow = createOptionRow(
            "تنظیم موقعیت افقی (چپ / راست)",
            offsetXOptions.firstOrNull { it.first == currentOffsetX }?.second ?: "${currentOffsetX}dp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("تنظیم موقعیت افقی نسبت به دوربین")
                .setItems(offsetXOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentOffsetX = offsetXOptions[which].first
                    valTv.text = offsetXOptions[which].second
                }
                .show()
        }
        container.addView(offsetXRow)

        // Interactive Drag & Drop Calibration Button
        val calibrateBtn = Button(context).apply {
            text = "🖐️ تعیین دقیق موقعیت با انگشت (Drag & Drop)"
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
                prefs.aodAlwaysOn.set(currentAlwaysOn)
                prefs.aodPocketMode.set(currentPocketMode)
                prefs.aodBrightness.set(currentBrightness)
                prefs.aodClockAlwaysVisible.set(currentClockAlwaysVisible)
                prefs.aodStyle.set(currentStyle)
                prefs.aodColor.set(currentColor)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodClockScale.set(currentClockScale)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodTapRevealClock.set(currentTapRevealClock)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)
                AodCalibrationActivity.start(context)
            }
        }
        container.addView(calibrateBtn)

        // 8. Tap to Peek Settings
        container.addView(createSectionTitle("تنظیمات لمس صفحه همیشه روشن (تک ضربه)"))

        val tapCheck = CheckBox(context).apply {
            text = "نمایش نام فرستندگان با یک ضربه روی صفحه (حالت مات محو)"
            textSize = 13.5f
            isChecked = currentTapReveal
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentTapReveal = isChecked
            }
        }
        container.addView(tapCheck)

        val clockPeekCheck = CheckBox(context).apply {
            text = "نمایش ساعت و تاریخ شمسی در بالای صفحه با یک ضربه"
            textSize = 13.5f
            isChecked = currentTapRevealClock
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                currentTapRevealClock = isChecked
            }
        }
        container.addView(clockPeekCheck)

        val namesPosOptions = arrayOf(
            Pair(Preferences.AOD_NAMES_POS_RIGHT, "سمت راست صفحه (پیش‌فرض)"),
            Pair(Preferences.AOD_NAMES_POS_LEFT, "سمت چپ صفحه"),
            Pair(Preferences.AOD_NAMES_POS_CENTER, "وسط صفحه")
        )
        val namesPosRow = createOptionRow(
            "موقعیت نام فرستنده",
            namesPosOptions.firstOrNull { it.first == currentNamesPos }?.second ?: namesPosOptions[0].second
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب موقعیت نام فرستنده")
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
            Pair(16, "۱۶sp (خوانا و درشت‌تر)"),
            Pair(18, "۱۸sp (بزرگ)")
        )
        val namesSizeRow = createOptionRow(
            "اندازه متن نام",
            namesSizeOptions.firstOrNull { it.first == currentNamesSize }?.second ?: "${currentNamesSize}sp"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب اندازه متن نام")
                .setItems(namesSizeOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesSize = namesSizeOptions[which].first
                    valTv.text = namesSizeOptions[which].second
                }
                .show()
        }
        container.addView(namesSizeRow)

        val namesOpacityOptions = arrayOf(
            Pair(25, "۲۵٪ (بسیار مات و نامحسوس - محرمانه)"),
            Pair(40, "۴۰٪ (پیش‌فرض ملایم - شیک)"),
            Pair(60, "۶۰٪ (نیمه‌شفاف)"),
            Pair(80, "۸۰٪ (واضح)"),
            Pair(100, "۱۰۰٪ (کاملاً پررنگ و سفید)")
        )
        val namesOpacityRow = createOptionRow(
            "میزان شفافیت و ماتی نام",
            namesOpacityOptions.firstOrNull { it.first == currentNamesOpacity }?.second ?: "${currentNamesOpacity}٪"
        ) { valTv ->
            AlertDialog.Builder(context)
                .setTitle("انتخاب میزان شفافیت نام")
                .setItems(namesOpacityOptions.map { it.second }.toTypedArray()) { _, which ->
                    currentNamesOpacity = namesOpacityOptions[which].first
                    valTv.text = namesOpacityOptions[which].second
                }
                .show()
        }
        container.addView(namesOpacityRow)

        val namesColorOptions = arrayOf(
            Pair(0, "هماهنگ با فرستنده پیام"),
            Pair(Color.parseColor("#F1F5F9"), "سفید ملایم (پیش‌فرض)"),
            Pair(Color.parseColor("#0EA5E9"), "آبی فیروزه‌ای"),
            Pair(Color.parseColor("#10B981"), "سبز نئونی"),
            Pair(Color.parseColor("#8B5CF6"), "بنفش"),
            Pair(Color.parseColor("#F59E0B"), "نارنجی / طلایی")
        )
        val namesColorRow = createOptionRow(
            "رنگ متن فرستنده",
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

        // 9. Test Preview Button
        val previewBtn = Button(context).apply {
            text = "👁️ تست زنده و پیش‌نمایش آنی (Preview)"
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
                prefs.aodEnabled.set(true)
                prefs.aodAlwaysOn.set(currentAlwaysOn)
                prefs.aodPocketMode.set(currentPocketMode)
                prefs.aodBrightness.set(currentBrightness)
                prefs.aodClockAlwaysVisible.set(currentClockAlwaysVisible)
                prefs.aodStyle.set(currentStyle)
                prefs.aodPosition.set(currentPos)
                prefs.aodColor.set(currentColor)
                prefs.aodTimeout.set(currentTimeout)
                prefs.aodOffsetY.set(currentOffsetY)
                prefs.aodOffsetX.set(currentOffsetX)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodClockScale.set(currentClockScale)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodTapRevealClock.set(currentTapRevealClock)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)

                AodNotificationActivity.start(
                    context = context,
                    threadId = -1L,
                    sender = "علی محمدی",
                    snippet = "سلام! این پیش‌نمایش زنده صفحه همیشه روشن است.",
                    color = if (currentColor != -1) currentColor else Color.parseColor("#0EA5E9"),
                    isPreview = true
                )
            }
        }
        container.addView(previewBtn)

        AlertDialog.Builder(context)
            .setTitle("✨ تنظیمات اعلان و Always On Display")
            .setView(scrollView)
            .setPositiveButton("ذخیره") { _, _ ->
                val isEnabled = enableCheck.isChecked
                prefs.aodEnabled.set(isEnabled)
                prefs.aodAlwaysOn.set(currentAlwaysOn)
                prefs.aodPocketMode.set(currentPocketMode)
                prefs.aodBrightness.set(currentBrightness)
                prefs.aodClockAlwaysVisible.set(currentClockAlwaysVisible)
                prefs.aodStyle.set(currentStyle)
                prefs.aodPosition.set(currentPos)
                prefs.aodColor.set(currentColor)
                prefs.aodTimeout.set(currentTimeout)
                prefs.aodOffsetY.set(currentOffsetY)
                prefs.aodOffsetX.set(currentOffsetX)
                prefs.aodDotSize.set(currentDotSize)
                prefs.aodRingRadius.set(currentRingRadius)
                prefs.aodClockStyle.set(currentClockStyle)
                prefs.aodClockScale.set(currentClockScale)
                prefs.aodTapRevealNames.set(currentTapReveal)
                prefs.aodTapRevealClock.set(currentTapRevealClock)
                prefs.aodNamesPosition.set(currentNamesPos)
                prefs.aodNamesTextSize.set(currentNamesSize)
                prefs.aodNamesOpacity.set(currentNamesOpacity)
                prefs.aodNamesColor.set(currentNamesColor)

                if (isEnabled && currentAlwaysOn) {
                    AodService.start(context)
                } else {
                    AodService.stop(context)
                }

                onSaved?.invoke()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
