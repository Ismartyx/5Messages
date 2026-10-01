package com.goodwy.messages.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.goodwy.messages.R
import com.goodwy.messages.common.Navigator
import com.goodwy.messages.common.androidxcompat.drawerOpen
import com.goodwy.messages.common.base.QkThemedActivity
import com.goodwy.messages.common.util.extensions.autoScrollToStart
import com.goodwy.messages.common.util.extensions.dismissKeyboard
import com.goodwy.messages.common.util.extensions.scrapViews
import com.goodwy.messages.common.util.extensions.setBackgroundTint
import com.goodwy.messages.common.util.extensions.setTint
import com.goodwy.messages.common.util.extensions.setVisible
import com.goodwy.messages.common.widget.QkTextView
import com.goodwy.messages.feature.blocking.BlockingDialog
import com.goodwy.messages.feature.changelog.ChangelogDialog
import com.goodwy.messages.feature.conversations.ConversationItemTouchCallback
import com.goodwy.messages.feature.conversations.ConversationsAdapter
import com.goodwy.messages.manager.ChangelogManager
import com.goodwy.messages.repository.BackupRepository
import com.goodwy.messages.repository.SyncRepository
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.drawer_view.*
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.main_activity.toolbar
import kotlinx.android.synthetic.main.main_activity.toolbarTitle
import kotlinx.android.synthetic.main.main_permission_hint.*
import kotlinx.android.synthetic.main.main_syncing.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    companion object {
        private const val MENU_MOVE_CATEGORY = 9991
        private const val MENU_NEXTCLOUD_CONFIG = 9992
    }

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var backupRepo: BackupRepository

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { toolbarSearch.textChanges() }
    override val composeIntent by lazy { compose.clicks() }
    override val drawerOpenIntent: Observable<Boolean> by lazy {
        drawerLayout
                .drawerOpen(Gravity.START)
                .doOnNext { dismissKeyboard() }
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                inbox.clicks().map { NavItem.INBOX },
                archived.clicks().map { NavItem.ARCHIVED },
                backup.clicks().doOnNext {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    showNextcloudSettingsDialog()
                }.filter { false }.map { NavItem.BACKUP },
                scheduled.clicks().map { NavItem.SCHEDULED },
                blocking.clicks().map { NavItem.BLOCKING },
                settings.clicks().map { NavItem.SETTINGS },
                plus.clicks().map { NavItem.PLUS },
                help.clicks().map { NavItem.HELP },
                invite.clicks().map { NavItem.INVITE }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val plusBannerIntent by lazy { plusBanner.clicks() }
    override val dismissRatingIntent by lazy { rateDismiss.clicks() }
    override val rateIntent by lazy { rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java] }
    private val toggle by lazy { ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.main_drawer_open_cd, 0) }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy { ObjectAnimator.ofInt(syncingProgress, "progress", 0, 0) }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val snackbar by lazy { findViewById<View>(R.id.snackbar) }
    private val syncing by lazy { findViewById<View>(R.id.syncing) }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()
    private var currentSelectedIds: List<Long> = emptyList()
    private var draggedTabIndex: Int = -1

    private val categoryColors = mapOf(
        MessageCategory.CONTACTS to Color.parseColor("#0EA5E9"), // Sky Blue
        MessageCategory.UNKNOWN to Color.parseColor("#F59E0B"),  // Amber Orange
        MessageCategory.BANK to Color.parseColor("#22C55E"),     // Emerald Green
        MessageCategory.OTP to Color.parseColor("#A855F7"),      // Purple
        MessageCategory.OTHER to Color.parseColor("#EF4444")     // Coral Red
    )

    private val categoryLabels = mapOf(
        MessageCategory.CONTACTS to "مخاطبین",
        MessageCategory.UNKNOWN to "شخصی ناشناس",
        MessageCategory.BANK to "بانکی",
        MessageCategory.OTP to "رمز و کد",
        MessageCategory.OTHER to "متفرقه"
    )

    private val tabViews: List<QkTextView> by lazy {
        listOf(tabContacts, tabUnknown, tabBank, tabOtp, tabOther)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        forceWhiteStatusBarIcons()
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        drawer?.setBackgroundColor(Color.parseColor("#111827"))

        conversationsSelectedIntent
                .autoDisposable(scope())
                .subscribe({ currentSelectedIds = it }, {})

        (snackbar as? ViewStub)?.setOnInflateListener { _, _ ->
            snackbarButton.clicks()
                    .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                    .subscribe(snackbarButtonIntent)
        }

        (syncing as? ViewStub)?.setOnInflateListener { _, _ ->
            syncingProgress?.progressTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            syncingProgress?.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
        }

        toggle.syncState()
        toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(recyclerView)

        setupDragAndDropForCategoryTabs()

        backup?.setOnLongClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNextcloudSettingsDialog()
            true
        }

        checkAndRunAutoBackup()

        drawer.clicks().autoDisposable(scope()).subscribe()

        theme
                .autoDisposable(scope())
                .subscribe({
                    forceWhiteStatusBarIcons()
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#9CA3AF")))
                            .let { tintList ->
                                inboxIcon?.imageTintList = tintList
                                archivedIcon?.imageTintList = tintList
                            }

                    listOf(plusBadge1, plusBadge2).forEach { badge ->
                        badge?.setBackgroundTint(Color.parseColor("#0EA5E9"))
                        badge?.setTextColor(Color.WHITE)
                    }
                    syncingProgress?.progressTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
                    syncingProgress?.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
                    plusIcon?.setTint(Color.parseColor("#0EA5E9"))
                    rateIcon?.setTint(Color.parseColor("#0EA5E9"))
                    compose?.setBackgroundTint(Color.parseColor("#0EA5E9"))
                    compose?.setTint(Color.WHITE)
                }, {})

        if (Build.VERSION.SDK_INT <= 22) {
            toolbarSearch?.setBackgroundTint(Color.parseColor("#111827"))
        }
    }

    private fun setupDragAndDropForCategoryTabs() {
        tabViews.forEachIndexed { index, tabView ->
            tabView.setOnLongClickListener { view ->
                draggedTabIndex = index
                val clipData = ClipData.newPlainText("tab_index", index.toString())
                val shadowBuilder = View.DragShadowBuilder(view)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.startDragAndDrop(clipData, shadowBuilder, view, 0)
                } else {
                    @Suppress("DEPRECATION")
                    view.startDrag(clipData, shadowBuilder, view, 0)
                }
                view.alpha = 0.5f
                true
            }

            tabView.setOnDragListener { targetView, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        val fromIdx = draggedTabIndex
                        val toIdx = tabViews.indexOf(targetView)
                        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                            val currentOrder = viewModel.getSavedCategoryOrder().toMutableList()
                            if (fromIdx in currentOrder.indices && toIdx in currentOrder.indices) {
                                val movedItem = currentOrder.removeAt(fromIdx)
                                currentOrder.add(toIdx, movedItem)
                                draggedTabIndex = toIdx
                                viewModel.saveCategoryOrder(currentOrder)
                                triggerAutoBackupIfEnabled()
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DROP -> true
                    DragEvent.ACTION_DRAG_ENDED -> {
                        tabViews.forEach { it.alpha = 1.0f }
                        draggedTabIndex = -1
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun forceWhiteStatusBarIcons() {
        try {
            window.statusBarColor = Color.parseColor("#0B1220")
            window.navigationBarColor = Color.parseColor("#0B1220")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
                window.decorView.systemUiVisibility = flags
            }
        } catch (_: Exception) {}
    }

    private fun showMoveConversationsCategoryDialog(selectedThreadIds: List<Long>) {
        if (selectedThreadIds.isEmpty()) return
        val categories = viewModel.getSavedCategoryOrder()
        val titles = categories.map { categoryLabels[it] ?: it.name }.toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("انتقال به کدام پوشه؟")
                .setItems(titles) { _, which ->
                    val targetCategory = categories[which]
                    viewModel.setManualCategoryForConversations(selectedThreadIds, targetCategory)
                    clearSelection()
                    triggerAutoBackupIfEnabled()
                    Toast.makeText(this, "به پوشه «${titles[which]}» منتقل شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("انصراف", null)
                .show()
    }

    private fun triggerAutoBackupIfEnabled() {
        try {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val autoBackupEnabled = sp.getBoolean("nc_auto_backup", true)
            val pass = sp.getString("nc_pass", "") ?: ""
            if (!autoBackupEnabled || pass.isBlank()) return

            sp.edit().putLong("nc_last_auto_backup", System.currentTimeMillis()).apply()
            Completable.fromAction { backupRepo.performBackup() }
                    .subscribeOn(Schedulers.io())
                    .subscribe({}, {})
        } catch (_: Exception) {}
    }

    private fun checkAndRunAutoBackup() {
        try {
            val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
            val autoBackupEnabled = sp.getBoolean("nc_auto_backup", true)
            val pass = sp.getString("nc_pass", "") ?: ""
            if (!autoBackupEnabled || pass.isBlank()) return

            val lastAutoBackup = sp.getLong("nc_last_auto_backup", 0L)
            val now = System.currentTimeMillis()
            if (now - lastAutoBackup >= TimeUnit.HOURS.toMillis(6)) {
                sp.edit().putLong("nc_last_auto_backup", now).apply()
                Completable.fromAction { backupRepo.performBackup() }
                        .subscribeOn(Schedulers.io())
                        .subscribe({}, {})
            }
        } catch (_: Exception) {}
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoundedBg(fillColor: String, strokeColor: String? = null, radiusDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), Color.parseColor(strokeColor))
            }
        }
    }

    private fun showNextcloudSettingsDialog() {
        val sp = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)
        val currentServer = sp.getString("nc_server", "https://nc.paranas.ir") ?: "https://nc.paranas.ir"
        val currentUser = sp.getString("nc_user", "saeed") ?: "saeed"
        val currentPass = sp.getString("nc_pass", "") ?: ""
        val currentPath = sp.getString("nc_path", "Backups/MessagesBackup/Messages_Backup.json")
                ?: "Backups/MessagesBackup/Messages_Backup.json"
        val currentAuto = sp.getBoolean("nc_auto_backup", true)

        val scrollView = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = makeRoundedBg("#0B1220", "#1E293B", 18)
        }
        scrollView.addView(card)

        // عنوان بالا: اتصال خودکار به سرور نکست‌کلاد
        val header = TextView(this).apply {
            text = "☁️ اتصال خودکار به سرور نکست‌کلاد"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(header)

        // ۱. آدرس سرور نکست‌کلاد
        val serverLabel = TextView(this).apply {
            text = "آدرس سرور نکست‌کلاد"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(6))
        }
        val serverInput = EditText(this).apply {
            setText(currentServer)
            hint = "https://nc.paranas.ir"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg("#090E1A", "#1E293B", 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        card.addView(serverLabel)
        card.addView(serverInput)

        // ۲. ردیف دو ستونه: نام کاربری (راست) و رمز / App Password (چپ)
        val credentialsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, dp(14), 0, 0)
        }

        val passCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val passLabel = TextView(this).apply {
            text = "رمز / App Password"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(6))
        }
        val passInput = EditText(this).apply {
            setText(currentPass)
            hint = "••••••••••••"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg("#090E1A", "#1E293B", 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passCol.addView(passLabel)
        passCol.addView(passInput)

        val userCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        val userLabel = TextView(this).apply {
            text = "نام کاربری"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(6))
        }
        val userInput = EditText(this).apply {
            setText(currentUser)
            hint = "saeed"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg("#090E1A", "#1E293B", 12)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        userCol.addView(userLabel)
        userCol.addView(userInput)

        credentialsRow.addView(passCol)
        credentialsRow.addView(userCol)
        card.addView(credentialsRow)

        // ۳. نام فایل در نکست‌کلاد
        val pathLabel = TextView(this).apply {
            text = "نام فایل در نکست‌کلاد"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.RIGHT
            setPadding(0, dp(14), 0, dp(6))
        }
        val pathInput = EditText(this).apply {
            setText(currentPath)
            hint = "Backups/MessagesBackup/Messages_Backup.json"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = makeRoundedBg("#090E1A", "#1E293B", 12)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        card.addView(pathLabel)
        card.addView(pathInput)

        // ۴. تیک بکاپ اتوماتیک پس از هر تغییر
        val autoCheck = CheckBox(this).apply {
            text = "بکاپ اتوماتیک پس از هر تغییر"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = currentAuto
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.RIGHT
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
            layoutParams = params
        }
        card.addView(autoCheck)

        // ۵. ردیف دکمه‌های سبز (ذخیره و بکاپ ابری) و نارنجی (بازیابی از سرور)
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val restoreBtn = Button(this).apply {
            text = "☁️ بازیابی از سرور"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = makeRoundedBg("#F59E0B", null, 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        }

        val saveBackupBtn = Button(this).apply {
            text = "💾 ذخیره و بکاپ ابری"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = makeRoundedBg("#10B981", null, 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        }

        buttonsRow.addView(restoreBtn)
        buttonsRow.addView(saveBackupBtn)
        card.addView(buttonsRow)

        // دکمه رفتن به لیست فایل‌های بکاپ محلی
        val localFilesBtn = Button(this).apply {
            text = "📂 مدیریت فایل‌های بکاپ محلی"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            isAllCaps = false
            background = makeRoundedBg("#111827", "#1E293B", 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(12)
            }
        }
        card.addView(localFilesBtn)

        val dialog = AlertDialog.Builder(this)
                .setView(scrollView)
                .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun saveInputsToPrefs() {
            val srv = serverInput.text.toString().trim().ifEmpty { "https://nc.paranas.ir" }
            val usr = userInput.text.toString().trim().ifEmpty { "saeed" }
            val pwd = passInput.text.toString().trim()
            val pth = pathInput.text.toString().trim().ifEmpty { "Backups/MessagesBackup/Messages_Backup.json" }
            val aut = autoCheck.isChecked

            sp.edit()
                    .putString("nc_server", srv)
                    .putString("nc_user", usr)
                    .putString("nc_pass", pwd)
                    .putString("nc_path", pth)
                    .putBoolean("nc_auto_backup", aut)
                    .putLong("nc_last_auto_backup", System.currentTimeMillis())
                    .apply()
        }

        saveBackupBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            Toast.makeText(this, "در حال ذخیره و ارسال بکاپ به سرور نکست‌‌کلاد...", Toast.LENGTH_SHORT).show()
            Completable.fromAction { backupRepo.performBackup() }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        Toast.makeText(this, "✅ بکاپ ابری در نکست‌کلاد ذخیره شد", Toast.LENGTH_LONG).show()
                    }, {
                        Toast.makeText(this, "خطا در ارسال به سرور", Toast.LENGTH_SHORT).show()
                    })
        }

        restoreBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            Toast.makeText(this, "در حال دریافت فایل بکاپ از سرور نکست‌‌کلاد...", Toast.LENGTH_SHORT).show()
            Completable.fromAction {
                val srv = (sp.getString("nc_server", "https://nc.paranas.ir") ?: "").trim().trimEnd('/')
                val usr = (sp.getString("nc_user", "saeed") ?: "").trim()
                val pwd = (sp.getString("nc_pass", "") ?: "").trim()
                val pth = (sp.getString("nc_path", "Backups/MessagesBackup/Messages_Backup.json") ?: "").trim().trimStart('/')

                if (srv.isEmpty() || usr.isEmpty() || pwd.isEmpty() || pth.isEmpty()) {
                    throw IllegalStateException("اطلاعات سرور کامل نیست")
                }

                val auth = "Basic " + Base64.encodeToString("$usr:$pwd".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val targetUrl = "$srv/remote.php/dav/files/$usr/$pth"

                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", auth)
                    connectTimeout = 15000
                    readTimeout = 30000
                }

                if (conn.responseCode !in 200..299) {
                    val code = conn.responseCode
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code")
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val dir = (getExternalFilesDir("Backups") ?: File(filesDir, "Backups")).apply { mkdirs() }
                val cloudFile = File(dir, "backup-nextcloud-restore.json")
                FileOutputStream(cloudFile, false).use { it.write(bytes) }

                backupRepo.performRestore(cloudFile.absolutePath)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Toast.makeText(this, "✅ بازیابی اطلاعات و تنظیمات از سرور انجام شد!", Toast.LENGTH_LONG).show()
            }, { err ->
                Toast.makeText(this, "❌ خطا در بازیابی از سرور: ${err.message}", Toast.LENGTH_LONG).show()
            })
        }

        localFilesBtn.setOnClickListener {
            saveInputsToPrefs()
            dialog.dismiss()
            navigator.showBackup()
        }

        dialog.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.run(onNewIntentIntent::onNext)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        forceWhiteStatusBarIcons()

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        toolbarSearch?.setVisible(state.page is Inbox && state.page.selected == 0 || state.page is Searching)
        toolbarTitle?.setVisible(toolbarSearch?.visibility != View.VISIBLE)
        categoryScroll?.setVisible(state.page is Inbox && state.page.selected == 0)

        toolbar?.menu?.findItem(R.id.archive)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unarchive)?.isVisible = state.page is Archived && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.delete)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.read)?.isVisible = markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.unread)?.isVisible = !markRead && selectedConversations != 0
        toolbar?.menu?.findItem(R.id.block)?.isVisible = selectedConversations != 0
        toolbar?.menu?.findItem(MENU_MOVE_CATEGORY)?.isVisible = state.page is Inbox && selectedConversations != 0
        toolbar?.menu?.findItem(MENU_NEXTCLOUD_CONFIG)?.isVisible = selectedConversations == 0

        listOf(plusBadge1, plusBadge2).forEach { badge ->
            badge?.isVisible = !state.upgraded
        }
        plus?.isVisible = state.upgraded
        plusBanner?.isVisible = !state.upgraded
        rateLayout?.setVisible(state.showRating)

        compose?.setVisible(state.page is Inbox || state.page is Archived)
        compose?.animate()?.rotation(if (state.drawerOpen) 90f else 0f)?.start()
        conversationsAdapter.emptyView = empty.takeIf { state.page is Inbox || state.page is Archived }
        searchAdapter.emptyView = empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_24dp)
                    toolbar?.setBackgroundTint(Color.parseColor("#111827"))
                    toolbar?.elevation = prefs.searchElevation.get().toFloat()
                    compose?.animate()?.rotation(0f)?.start()
                }
                title = getString(R.string.main_title_selected, state.page.selected)
                if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(recyclerView)
                empty?.setText(R.string.inbox_empty_text)

                val inactiveBg = Color.parseColor("#111827")
                val activeCategoryColor = categoryColors[state.page.category] ?: Color.parseColor("#0EA5E9")

                compose?.setBackgroundTint(activeCategoryColor)
                compose?.setTint(Color.WHITE)

                val orderedCategories = viewModel.getSavedCategoryOrder()
                tabViews.forEachIndexed { index, tabView ->
                    val category = orderedCategories.getOrNull(index) ?: return@forEachIndexed
                    val label = categoryLabels[category] ?: ""
                    val hasUnread = (state.page.unreadCounts[category] ?: 0) > 0
                    val isSelected = state.page.category == category
                    val catColor = categoryColors[category] ?: Color.parseColor("#0EA5E9")

                    tabView.text = if (hasUnread) "$label •" else label
                    tabView.setBackgroundTint(if (isSelected) catColor else inactiveBg)
                    tabView.setTextColor(if (isSelected) Color.WHITE else catColor)
                    tabView.setTypeface(null, Typeface.BOLD)

                    tabView.setOnClickListener {
                        clearSelection()
                        viewModel.setCategory(category)
                    }
                }
            }

            is Searching -> {
                showBackButton(true)
                toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                if (recyclerView?.adapter !== searchAdapter) recyclerView?.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                if (state.page.selected > 0) {
                    toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    compose?.animate()?.rotation(90f)?.start()
                } else {
                    toolbar?.setNavigationIcon(R.drawable.ic_menu_24dp)
                    toolbar?.navigationIcon?.setTint(Color.parseColor("#F9FAFB"))
                    compose?.animate()?.rotation(0f)?.start()
                }
                toolbar?.setBackgroundResource(R.drawable.rounded_rectangle_transparent_24dp)
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (recyclerView?.adapter !== conversationsAdapter) recyclerView?.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                empty?.setText(R.string.archived_empty_text)
            }
        }

        inbox?.isActivated = state.page is Inbox
        archived?.isActivated = state.page is Archived

        if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true && !state.drawerOpen) {
            drawerLayout?.closeDrawer(GravityCompat.START)
        } else if (drawerLayout?.isDrawerVisible(GravityCompat.START) == false && state.drawerOpen) {
            drawerLayout?.openDrawer(GravityCompat.START)
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncing?.isVisible = false
                snackbar?.isVisible = !state.defaultSms || !state.smsPermission || !state.contactPermission
            }

            is SyncRepository.SyncProgress.Running -> {
                syncing?.isVisible = true
                syncingProgress?.max = state.syncing.max
                if (syncingProgress != null) {
                    progressAnimator.apply { setIntValues(syncingProgress.progress, state.syncing.progress) }.start()
                }
                syncingProgress?.isIndeterminate = state.syncing.indeterminate
                snackbar?.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarTitle?.setText(R.string.main_default_sms_title)
                snackbarMessage?.setText(R.string.main_default_sms_message)
                snackbarButton?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_sms)
                snackbarButton?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                snackbarTitle?.setText(R.string.main_permission_required)
                snackbarMessage?.setText(R.string.main_permission_contacts)
                snackbarButton?.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        forceWhiteStatusBarIcons()
        activityResumedIntent.onNext(true)
        checkAndRunAutoBackup()
    }

    override fun onPause() {
        super.onPause()
        activityResumedIntent.onNext(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    override fun showBackButton(show: Boolean) {
        toggle.onDrawerSlide(drawer, if (show) 1f else 0f)
        toggle.drawerArrowDrawable.color = Color.parseColor("#F9FAFB")
    }

    override fun requestDefaultSms() {
        navigator.showDefaultSmsDialog(this)
    }

    override fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        toolbarSearch?.text = null
    }

    override fun clearSelection() {
        conversationsAdapter.clearSelection()
    }

    override fun themeChanged() {
        recyclerView?.scrapViews()
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources.getQuantityString(R.plurals.dialog_delete_message, count, count))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
                .setNegativeعلت آن پیامِ خطای قبلی، **فیلتر خودکار کپی‌رایت سیستم** بود؛ چون در مراحل قبل کلِ فایل‌های ۶۰۰ خطیِ سورس اصلی برنامه (`MainActivity.kt`) را با تمام کدهای دست‌نخورده‌اش کامل کپی می‌کردیم، سیستم به تکرار طولانیِ سورسِ اولیه برنامه گیر داد.

برای اینکه دیگر به این محدودیت نخوریم و کار تو هم خیلی راحت‌تر شود، به جای تکرار کلِ فایل ۶۰۰ خطی، فقط **بخش‌های جدید و اختصاصی خودمان** را می‌گذارم.

---

### ۱. طراحی آیکون به سبک تلگرام با تغییر اختصاصی (در `build.yml`)
در کد `build.yml` زیر، آیکون برنامه را دقیقاً با **آبیِ معروف تلگرام (`#24A1DE` و `#0EA5E9`)** و یک **موشک کاغذی سفیدِ مدرن که دنباله‌اش به شکل حباب پیامک است** طراحی کردم تا در کنار آیکون تلگرامِ پایین صفحه‌ات فوق‌العاده شیک و هماهنگ دیده شود.

وارد `.github/workflows/build.yml` شو و فقط بخش مربوط به آیکون (`bg_xml` و `fg_xml`) را با این الگو جایگزین کن (یا کل `build.yml` زیر را بگذار):

```yaml
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 8
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '8'

      - name: Apply Global Dark Theme, Telegram-Style Icon & App Name
        run: |
          python3 - << 'PYEOF'
          import os, glob, re

          # ۱. تغییر نام برنامه به Messages
          for s_file in glob.glob("**/src/**/res/values*/strings.xml", recursive=True):
              with open(s_file, "r") as f:
                  content = f.read()
              content = re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)', r'\g<1>Messages\g<2>', content)
              content = content.replace("4Messages", "Messages").replace("5Messages", "Messages").replace("QKSMS", "Messages")
              with open(s_file, "w") as f:
                  f.write(content)

          for g_file in glob.glob("**/*.gradle", recursive=True):
              with open(g_file, "r") as f:
                  g = f.read()
              g = g.replace("4Messages", "Messages").replace("5Messages", "Messages")
              with open(g_file, "w") as f:
                  f.write(g)

          # ۲. پیش‌فرض تم تاریک و سفید ماندن آیکون‌های ساعت و آنتن بالای صفحه
          prefs_file = "domain/src/main/java/com/goodwy/messages/util/Preferences.kt"
          if os.path.exists(prefs_file):
              with open(prefs_file, "r") as f:
                  p = f.read()
              p = p.replace('rxPrefs.getInteger("nightMode", NIGHT_MODE_SYSTEM)', 'rxPrefs.getInteger("nightMode", NIGHT_MODE_DARK)')
              p = p.replace('rxPrefs.getInteger("nightMode", NIGHT_MODE_OFF)', 'rxPrefs.getInteger("nightMode", NIGHT_MODE_DARK)')
              with open(prefs_file, "w") as f:
                  f.write(p)

          themed_act = "presentation/src/main/java/com/goodwy/messages/common/base/QkThemedActivity.kt"
          if os.path.exists(themed_act):
              with open(themed_act, "r") as f:
                  ta = f.read()
              ta = ta.replace("View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR", "0")
              ta = ta.replace("View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR", "0")
              with open(themed_act, "w") as f:
                  f.write(ta)

          colors_file = "presentation/src/main/res/values/colors.xml"
          if os.path.exists(colors_file):
              with open(colors_file, "r") as f:
                  c = f.read()
              c = re.sub(r'(<color name="backgroundDark">).*?(</color>)', r'\g<1>#0B1220\g<2>', c)
              c = re.sub(r'(<color name="bubbleDark">).*?(</color>)', r'\g<1>#111827\g<2>', c)
              with open(colors_file, "w") as f:
                  f.write(c)

          for style_file in glob.glob("presentation/src/main/res/values*/styles.xml"):
              with open(style_file, "r") as f:
                  s = f.read()
              s = s.replace('parent="Theme.AppCompat.Light.NoActionBar"', 'parent="Theme.AppCompat.NoActionBar"')
              s = s.replace('parent="Theme.AppCompat.Light.Dialog"', 'parent="Theme.AppCompat.Dialog"')
              s = s.replace('parent="Widget.AppCompat.Light.PopupMenu"', 'parent="Widget.AppCompat.PopupMenu"')
              s = s.replace('<item name="android:windowLightStatusBar">true</item>', '<item name="android:windowLightStatusBar">false</item>')
              s = s.replace('<item name="android:windowLightNavigationBar">true</item>', '<item name="android:windowLightNavigationBar">false</item>')
              s = s.replace('@color/backgroundLight', '@color/backgroundDark')
              s = s.replace('@color/bubbleLight', '@color/bubbleDark')
              s = s.replace('@color/textPrimary', '@color/textPrimaryDark')
              s = s.replace('@color/textSecondary', '@color/textSecondaryDark')
              s = s.replace('@color/textTertiary', '@color/textTertiaryDark')
              with open(style_file, "w") as f:
                  f.write(s)

          # ۳. طراحی آیکون به سبک تلگرام با تغییر اختصاصی (پس‌زمینه آبی تلگرامی + موشک/پیامک مدرن سفید)
          os.makedirs("presentation/src/main/res/drawable", exist_ok=True)
          os.makedirs("presentation/src/main/res/mipmap-anydpi-v26", exist_ok=True)

          bg_xml = """<?xml version="1.0" encoding="utf-8"?>
          <vector xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
              android:width="108dp"
              android:height="108dp"
              android:viewportWidth="108"
              android:viewportHeight="108">
              <path android:fillColor="#24A1DE" android:pathData="M0,0h108v108h-108z"/>
          </vector>"""

          fg_xml = """<?xml version="1.0" encoding="utf-8"?>
          <vector xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
              android:width="108dp"
              android:height="108dp"
              android:viewportWidth="108"
              android:viewportHeight="108">
              <!-- سایه داخلی بال موشک به سبک تلگرام -->
              <path
                  android:fillColor="#B3E5FC"
                  android:pathData="M44,62L42,76L53,66L72,42L44,62Z"/>
              <path
                  android:fillColor="#81D4FA"
                  android:pathData="M44,62L53,66L42,76L44,62Z"/>
              <!-- بدنه اصلی موشک تلگرامی با زاویه تیز و مدرن -->
              <path
                  android:fillColor="#FFFFFF"
                  android:pathData="M78,32L26,52L44,60L72,38L50,63L66,74L78,32Z"/>
              <!-- دو خط سرعت/پیام در دنباله موشک (تغییر اختصاصی نسبت به تلگرام) -->
              <path
                  android:fillColor="#FFFFFF"
                  android:pathData="M26,64h10v3.5h-10zM30,71h7v3.5h-7z"/>
          </vector>"""

          adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
          <adaptive-icon xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)">
              <background android:drawable="@drawable/ic_launcher_bg_custom"/>
              <foreground android:drawable="@drawable/ic_launcher_fg_custom"/>
          </adaptive-icon>"""

          with open("presentation/src/main/res/drawable/ic_launcher_bg_custom.xml", "w") as f:
              f.write(bg_xml)
          with open("presentation/src/main/res/drawable/ic_launcher_fg_custom.xml", "w") as f:
              f.write(fg_xml)

          for xml_file in glob.glob("presentation/src/main/res/mipmap-anydpi-v26/*.xml"):
              with open(xml_file, "w") as f:
                  f.write(adaptive_xml)
          PYEOF

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build APK
        run: |
          unset ANDROID_NDK_HOME
          unset ANDROID_NDK_ROOT
          unset ANDROID_NDK_LATEST_HOME
          sudo rm -rf "$ANDROID_HOME/ndk" "$ANDROID_HOME/ndk-bundle" "$ANDROID_SDK_ROOT/ndk" "$ANDROID_SDK_ROOT/ndk-bundle" || true
          cat << 'EOF' > init.gradle
          gradle.projectsLoaded {
              rootProject.allprojects {
                  afterEvaluate { project ->
                      if (project.hasProperty('android')) {
                          project.android.packagingOptions {
                              doNotStrip "**/*.so"
                              doNotStrip "*/*.so"
                              doNotStrip "*/*/*.so"
                          }
                      }
                  }
              }
          }
          EOF
          ./gradlew assembleNoAnalyticsDebug --no-daemon --init-script init.gradle > build_log.txt 2>&1

      - name: Show Exact Error (If Failed)
        if: failure()
        run: |
          echo "=== COMPILATION ERRORS ==="
          grep -E "^e: |FAILURE:|What went wrong:|Execution failed|> " build_log.txt | tail -n 40 || true
          echo "=== LAST 40 LINES ==="
          tail -n 40 build_log.txt

      - name: Upload APK
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: Messages-APK
          path: "**/*.apk"
